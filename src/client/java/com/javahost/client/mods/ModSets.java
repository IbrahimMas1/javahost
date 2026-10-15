package com.javahost.client.mods;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.google.gson.JsonParser;
import com.javahost.JavaHost;
import com.javahost.client.Toasts;
import com.javahost.mods.ModScanner;
import com.javahost.mods.ModSetsState;
import com.javahost.net.Payloads;
import com.javahost.util.ModManifest;
import com.javahost.util.ModSwap;
import com.javahost.util.Modrinth;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

/**
 * Mod sets: the mods a friend's world needs (downloaded from Modrinth), or a modpack, kept in their own folders under
 * {@code javahost/modsets/} and switched in when the game closes (by {@code ModSwap}). The player's own mods are put
 * aside, never deleted, and "Back to my mods" brings them back.
 */
public final class ModSets {
	/** A world we couldn't join because we lack some of its mods. */
	public record Needed(ModManifest manifest, List<ModManifest.Entry> missing, String address,
			com.javahost.client.friends.InviteFlow.Target target) {
		public String hostName() {
			return manifest.host().isEmpty() ? "The host" : manifest.host();
		}
	}

	/** A downloaded set: its folder, what it's called, and its jars. */
	public record Saved(String folder, String label, String kind, long size, int mods, long created) {
	}

	private static volatile Needed needed;

	private ModSets() {
	}

	public static Path dir() {
		return ModSetsState.dir();
	}

	private static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	private static Path planFile() {
		return dir().resolve("plan.properties");
	}

	private static Path resultFile() {
		return dir().resolve("result.properties");
	}

	// ================================================================ the check when joining

	/** Listens for the host's mod list while connecting (before Minecraft compares registries). */
	public static void register() {
		ClientConfigurationNetworking.registerGlobalReceiver(Payloads.ModList.TYPE, (payload, context) -> {
			int missingCount = 0;
			try {
				ModManifest manifest = ModManifest.fromJson(payload.json());
				List<ModManifest.Entry> missing = manifest.missing(ModManifest.of(installed()));
				missingCount = missing.size();
				if (!missing.isEmpty()) {
					var server = serverOf(context.packetListener());
					needed = new Needed(manifest, missing, server == null ? "" : server.ip,
							com.javahost.client.friends.InviteFlow.joiningTarget());
					JavaHost.LOGGER.info("[Mods] this world needs {} more mod(s): {}", missing.size(),
							missing.stream().map(m -> m.id() + " " + m.version()).toList());
				}
			} catch (IllegalArgumentException e) {
				JavaHost.LOGGER.warn("[Mods] the host's mod list was unreadable; joining as usual", e);
			}
			context.responseSender().sendPacket(new Payloads.ModCheckReply(missingCount));
		});
	}

	/** The server being joined (Minecraft keeps it on the connection until we're in the world). */
	private static net.minecraft.client.multiplayer.ServerData serverOf(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl listener) {
		var server = ((com.javahost.client.mixin.ClientCommonListenerAccess) listener).javahost$serverData();
		return server != null ? server : Minecraft.getInstance().getCurrentServer();
	}

	/** The mods we have: id to version (including mods bundled inside other jars). */
	public static Map<String, String> installed() {
		Map<String, String> mods = new HashMap<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			mods.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
		}
		return mods;
	}

	public static Needed needed() {
		return needed;
	}

	public static void clearNeeded() {
		needed = null;
	}

	/** After the host turned us away for missing mods: show what's needed instead of the bare message. */
	public static void tick(Minecraft minecraft) {
		Needed waiting = needed;
		if (waiting != null && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
			minecraft.gui.setScreen(new com.javahost.client.gui.ModsNeededScreen(waiting));
		}
	}

	// ================================================================ downloading

	/** Folder names are made by us: "host-<id>" or "pack-<id>". */
	static Path setDir(String folder) {
		if (!folder.matches("(host|pack)-[A-Za-z0-9]{1,32}")) {
			throw new IllegalArgumentException("bad set folder " + folder);
		}
		return dir().resolve(folder);
	}

	/**
	 * Downloads the missing mods (exact versions, checked against the host's hashes) into a set folder. Blocking;
	 * call on a background thread. Returns the folder name.
	 */
	public static String download(Needed what, Consumer<String> progress) throws IOException, InterruptedException {
		String folder = "host-" + what.manifest().id();
		Path set = setDir(folder);
		Path jars = set.resolve("jars");
		Files.createDirectories(jars);
		int done = 0;
		List<String> names = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		for (ModManifest.Entry mod : what.missing()) {
			if (!mod.onModrinth()) {
				continue;
			}
			progress.accept("Downloading " + mod.name() + " (" + (++done) + " of " + what.missing().stream().filter(ModManifest.Entry::onModrinth).count() + ")");
			Modrinth.ModFile file = Modrinth.fileInVersion(mod.modrinthVersion(), mod.sha512());
			if (file == null) {
				throw new IOException(mod.name() + " " + mod.version() + " isn't on Modrinth any more");
			}
			Modrinth.download(file, ModSwap.inside(jars, mod.fileName()));
			names.add(mod.fileName());
			ids.add(mod.id());
		}
		Properties info = new Properties();
		info.setProperty("label", what.hostName() + "'s world");
		info.setProperty("kind", "host");
		info.setProperty("created", String.valueOf(System.currentTimeMillis()));
		ModSwap.store(info, "jar", names);
		ModSwap.store(info, "id", ids);
		write(set.resolve("set.properties"), info);
		return folder;
	}

	// ================================================================ switching (when the game closes)

	/** Stages a switch to a downloaded set; it happens when Minecraft closes. */
	public static void stageSwitch(String folder) throws IOException {
		Path set = setDir(folder);
		Properties info = read(set.resolve("set.properties"));
		boolean pack = "pack".equals(info.getProperty("kind"));
		List<String> setJars = ModSwap.list(info, "jar");
		Set<String> setIds = new HashSet<>(ModSwap.list(info, "id"));

		Properties current = ModSetsState.active();
		List<String> currentAdded = ModSwap.list(current, "added");
		List<String> currentOut = ModSwap.list(current, "movedOut");
		Path store = dir().resolve("my-mods");

		List<String> out = new ArrayList<>();
		List<String> back = new ArrayList<>();
		List<String> keepOut = new ArrayList<>();
		// The mods folder as it would be after undoing the current set: ours, plus what's waiting in the store.
		Set<String> view = new LinkedHashSet<>(jarNames(modsDir()));
		view.removeAll(currentAdded);
		view.addAll(currentOut);
		for (String name : view) {
			boolean stored = currentOut.contains(name);
			String id = modIdOf((stored ? store.resolve("mods") : modsDir()).resolve(name));
			boolean clash;
			if ("javahost".equals(id)) {
				clash = false;
			} else if (setJars.contains(name)) {
				clash = false; // the very same file: it just stays
			} else if (pack) {
				// A modpack replaces the mods, except what JavaHost itself needs if the pack doesn't bring it.
				clash = !("fabric-api".equals(id) && !setIds.contains("fabric-api"));
			} else {
				clash = id != null && setIds.contains(id);
			}
			if (clash) {
				(stored ? keepOut : out).add(name);
			} else if (stored) {
				back.add(name);
			}
		}
		Properties plan = new Properties();
		plan.setProperty("kind", "switch");
		plan.setProperty("set", folder);
		plan.setProperty("label", info.getProperty("label", folder));
		ModSwap.store(plan, "remove", currentAdded);
		ModSwap.store(plan, "out", out);
		ModSwap.store(plan, "back", back);
		ModSwap.store(plan, "keepOut", keepOut);
		ModSwap.store(plan, "in", setJars.stream().map(name -> set.resolve("jars").resolve(name).toString()).toList());
		ModSwap.store(plan, "configRemove", ModSwap.list(current, "configCreated"));
		ModSwap.store(plan, "configRestore", ModSwap.list(current, "configBacked"));
		Path overrides = set.resolve("overrides");
		if (Files.isDirectory(overrides)) {
			plan.setProperty("configFrom", overrides.toString());
			try (Stream<Path> files = Files.walk(overrides)) {
				ModSwap.store(plan, "config", files.filter(Files::isRegularFile)
						.map(p -> overrides.relativize(p).toString().replace('\\', '/')).filter(p -> !p.startsWith("mods/")).sorted().toList());
			}
		}
		stage(plan);
		JavaHost.LOGGER.info("[Mods] switching to {} when the game closes: {} in, {} put aside, {} back", folder, setJars.size(), out.size(), back.size());
	}

	/** Stages going back to the player's own mods. */
	public static void stageRestore() throws IOException {
		Properties current = ModSetsState.active();
		Properties plan = new Properties();
		plan.setProperty("kind", "restore");
		plan.setProperty("label", "Your own mods");
		ModSwap.store(plan, "remove", ModSwap.list(current, "added"));
		ModSwap.store(plan, "back", ModSwap.list(current, "movedOut"));
		ModSwap.store(plan, "configRemove", ModSwap.list(current, "configCreated"));
		ModSwap.store(plan, "configRestore", ModSwap.list(current, "configBacked"));
		stage(plan);
	}

	private static void stage(Properties plan) throws IOException {
		Path game = FabricLoader.getInstance().getGameDir();
		plan.setProperty("mods", modsDir().toString());
		plan.setProperty("game", game.toString());
		plan.setProperty("store", dir().resolve("my-mods").toString());
		plan.setProperty("state", ModSetsState.stateFile().toString());
		plan.setProperty("result", resultFile().toString());
		Files.createDirectories(dir());
		write(planFile(), plan);
	}

	public static boolean staged() {
		return Files.exists(planFile());
	}

	/** What the waiting switch is to ("AlexPlays's world", "Your own mods"), or null. */
	public static String stagedLabel() {
		try {
			return staged() ? read(planFile()).getProperty("label") : null;
		} catch (IOException e) {
			return null;
		}
	}

	public static void cancelStaged() {
		try {
			Files.deleteIfExists(planFile());
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't cancel the switch", e);
		}
	}

	/** The set in use ("AlexPlays's world"), or null when it's the player's own mods. */
	public static String activeLabel() {
		Properties state = ModSetsState.active();
		return state.isEmpty() ? null : state.getProperty("label");
	}

	public static String activeFolder() {
		return ModSetsState.active().getProperty("set");
	}

	/** Called as the game closes: starts the helper that switches mods once the game has exited. */
	public static void launchIfStaged() {
		if (!staged()) {
			return;
		}
		try {
			Properties plan = read(planFile());
			plan.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
			Path running = dir().resolve("running.properties");
			write(running, plan);
			Files.delete(planFile());
			// Run from a copy of JavaHost, so an update swapping our jar at the same time can't pull it away.
			List<Path> classpath = new ArrayList<>(FabricLoader.getInstance().getModContainer(JavaHost.MOD_ID).orElseThrow().getOrigin().getPaths());
			if (classpath.size() == 1 && classpath.getFirst().toString().endsWith(".jar")) {
				Path helper = dir().resolve("helper.jar");
				Files.copy(classpath.getFirst(), helper, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				classpath = List.of(helper);
			}
			String javaCommand = ProcessHandle.current().info().command().orElse("java");
			new ProcessBuilder(javaCommand, "-cp", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()),
					"com.javahost.util.ModSwap", running.toString())
					.redirectErrorStream(true).redirectOutput(dir().resolve("swap.log").toFile()).start();
			JavaHost.LOGGER.info("[Mods] switching mods as the game closes: {}", plan.getProperty("label"));
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't start the mod switch", e);
		}
	}

	/** Called at startup: says how the last switch went. */
	public static void reportLastResult() {
		if (!Files.exists(resultFile())) {
			return;
		}
		try {
			Properties result = read(resultFile());
			boolean ok = Boolean.parseBoolean(result.getProperty("ok"));
			if (ok && "restore".equals(result.getProperty("kind"))) {
				Toasts.notify("Back to your own mods", "Everything is just as you had it.");
			} else if (ok) {
				Toasts.notify("Mods ready: " + result.getProperty("label"), "Your own mods are kept safe. Go back any time from the Mods tab.");
			} else {
				Toasts.notify("Couldn't switch mods", result.getProperty("reason") + " Nothing was changed.");
			}
			Files.deleteIfExists(resultFile());
			Files.deleteIfExists(dir().resolve("running.properties"));
		} catch (IOException ignored) {
			// Nothing to report.
		}
	}

	// ================================================================ saved sets

	public static List<Saved> saved() {
		List<Saved> sets = new ArrayList<>();
		if (!Files.isDirectory(dir())) {
			return sets;
		}
		try (Stream<Path> folders = Files.list(dir())) {
			for (Path folder : folders.filter(Files::isDirectory).toList()) {
				Path infoFile = folder.resolve("set.properties");
				if (!Files.exists(infoFile)) {
					continue;
				}
				Properties info = read(infoFile);
				long size;
				try (Stream<Path> walk = Files.walk(folder)) {
					size = walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
				}
				sets.add(new Saved(folder.getFileName().toString(), info.getProperty("label", "?"), info.getProperty("kind", "host"), size,
						ModSwap.list(info, "jar").size(), Long.parseLong(info.getProperty("created", "0"))));
			}
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't list mod sets", e);
		}
		sets.sort(Comparator.comparingLong(Saved::created).reversed());
		return sets;
	}

	public static void delete(Saved set) throws IOException {
		if (set.folder().equals(activeFolder())) {
			throw new IOException("That set is in use");
		}
		try (Stream<Path> walk = Files.walk(setDir(set.folder()))) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	// ================================================================ helpers

	static List<String> jarNames(Path folder) {
		try (Stream<Path> files = Files.list(folder)) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** The Fabric mod id inside a jar, or null if it isn't a Fabric mod. */
	static String modIdOf(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			try (InputStream in = zip.getInputStream(entry)) {
				return JsonParser.parseString(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static Properties read(Path file) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		}
		return properties;
	}

	static void write(Path file, Properties properties) throws IOException {
		Files.createDirectories(file.getParent());
		try (OutputStream out = Files.newOutputStream(file)) {
			properties.store(out, "JavaHost mod set");
		}
	}

	/** This game's mods, for the Mods tab (worked out in the background). */
	public static ModManifest mine() {
		return ModScanner.ready();
	}
}

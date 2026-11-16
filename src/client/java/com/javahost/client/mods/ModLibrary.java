package com.javahost.client.mods;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.javahost.JavaHost;
import com.javahost.client.Toasts;
import com.javahost.mods.ModScanner;
import com.javahost.mods.ModSetsState;
import com.javahost.util.IoExecutor;
import com.javahost.util.ModChanges;
import com.javahost.util.ModJar;
import com.javahost.util.ModStates;
import com.javahost.util.ModSwap;
import com.javahost.util.ModToggle;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * The player's mods, for My mods: every jar in mods/ and in the off store ({@code javahost/mods-off/mods}), each with
 * what it's doing ({@link ModStates}). Read off the render thread and remembered; {@link #version()} goes up when
 * anything shown changes. Switching a mod on or off is a wanted change ({@link ModChanges}), kept in
 * {@code javahost/mods-off/wanted.properties} and made when the game closes, by the one helper that runs after exit
 * ({@link AfterExitHelper}, plan kind {@code toggle}).
 */
public final class ModLibrary {
	/** How old the list may get while My mods is open before it's read again. */
	public static final long REFRESH_MS = 3000;
	/** Mods that always stay on, and why. */
	private static final Map<String, String> PROTECTED = Map.of(JavaHost.MOD_ID, "This is JavaHost", "fabric-api", "JavaHost needs it");
	private static final int MAX_MARKERS = 500;
	private static final int MAX_NESTED_IDS = 64;

	/** What was read from disk. */
	private record Disk(List<ModJar> on, List<ModJar> off, Set<String> removing, Map<String, String> slugs) {
	}

	/** What the game loaded: files in mods/, ids from elsewhere, and the ids bundled inside each loaded jar. */
	private record Loaded(Set<String> files, Set<String> ids, Map<String, Set<String>> nested) {
	}

	/** The answer to switching one mod: which jars go with it (names for the confirm), or why not. */
	public record Ask(String file, boolean on, List<String> files, List<String> names, String refusal, List<String> missing) {
		public boolean ok() {
			return refusal == null;
		}

		/** More than the clicked mod changes (or something it needs isn't installed): ask first. */
		public boolean needsConfirm() {
			return ok() && (files.size() > 1 || !missing.isEmpty());
		}

		/** The other mods' names. */
		public List<String> others() {
			List<String> others = new ArrayList<>();
			for (int i = 0; i < files.size(); i++) {
				if (!files.get(i).equals(file)) {
					others.add(names.get(i));
				}
			}
			return others;
		}
	}

	private static final Object LOCK = new Object();
	private static ModChanges changes = new ModChanges();
	private static volatile Set<String> atStart;
	private static volatile Loaded loaded;
	private static volatile Disk disk;
	private static volatile List<ModStates.Entry> entries = List.of();
	private static volatile int version;
	private static volatile String signature = "";
	private static volatile long scannedAt;
	private static volatile int setChanges = -1;
	private static final AtomicBoolean SCANNING = new AtomicBoolean();
	/** Jars already read: path, size and time to what's inside (jars rarely change, and reading them is the slow part). */
	private static final Map<String, ModJar> JARS = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ModJar> eldest) {
			return size() > 4 * ModJar.MAX_JARS;
		}
	};

	// Test hooks.
	private static volatile Set<String> pretendLoaded;
	private static volatile String pretendSet;

	private ModLibrary() {
	}

	// ================================================================ folders

	public static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	/** The off store: {@code mods/} holds the jars that are off. */
	public static Path storeDir() {
		return FabricLoader.getInstance().getGameDir().resolve("javahost").resolve("mods-off");
	}

	public static Path storeMods() {
		return storeDir().resolve("mods");
	}

	public static Path stateFile() {
		return storeDir().resolve("state.properties");
	}

	public static Path resultFile() {
		return storeDir().resolve("result.properties");
	}

	public static Path wantedFile() {
		return storeDir().resolve("wanted.properties");
	}

	/** The plan handed to the helper as the game closed (read again at the next start, for its counts). */
	public static Path runningFile() {
		return storeDir().resolve("running.properties");
	}

	private static Path droppedFile() {
		return storeDir().resolve("dropped.properties");
	}

	// ================================================================ startup

	/** Called once at startup: which jars were in mods/ as the game started, and the changes wanted last time. */
	public static void init() {
		atStart = Set.copyOf(jarNames(modsDir()));
		synchronized (LOCK) {
			changes = ModChanges.read(wantedFile());
		}
	}

	private static Set<String> atStart() {
		Set<String> names = atStart;
		return names == null ? Set.of() : names;
	}

	// ================================================================ reading

	/** Every mod, on ones first. Empty until the first read has finished ({@link #ready()}). */
	public static List<ModStates.Entry> entries() {
		checkSets();
		return entries;
	}

	public static boolean ready() {
		return disk != null;
	}

	/** Goes up whenever what {@link #entries()} shows changes. */
	public static int version() {
		checkSets();
		return version;
	}

	/** Reads the folders again in the background (unless a read is already running). */
	public static void refresh() {
		if (!SCANNING.compareAndSet(false, true)) {
			return;
		}
		try {
			IoExecutor.POOL.execute(() -> {
				try {
					scan();
				} finally {
					SCANNING.set(false);
				}
			});
		} catch (RuntimeException e) {
			SCANNING.set(false);
		}
	}

	/** Reads again if the list is older than {@link #REFRESH_MS} (cheap to call every tick). */
	public static void refreshIfStale() {
		if (System.currentTimeMillis() - scannedAt > REFRESH_MS) {
			refresh();
		}
	}

	/** Reads the folders now, on this thread (tests). */
	public static void refreshNow() {
		scan();
	}

	private static void scan() {
		try {
			Disk read = readDisk();
			disk = read;
			boolean pruned;
			synchronized (LOCK) {
				pruned = changes.prune(read.on().stream().map(ModJar::file).toList(), read.off().stream().map(ModJar::file).toList());
				if (pruned) {
					save(); // a jar deleted or moved by hand: its change is gone too
				}
			}
			derive();
		} catch (RuntimeException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't read the mods folder", e);
		} finally {
			scannedAt = System.currentTimeMillis();
		}
	}

	private static Disk readDisk() {
		Loaded game = loaded();
		List<ModJar> on = new ArrayList<>();
		for (ModJar jar : readJars(modsDir())) {
			Set<String> nested = game.nested().get(jar.file());
			if (nested != null && !nested.isEmpty()) {
				// Mods bundled inside this jar: turning it off takes them too, so what needs them must go with it.
				List<String> provides = new ArrayList<>(jar.provides());
				for (String id : nested) {
					if (!id.equals(jar.id()) && !provides.contains(id) && provides.size() < MAX_NESTED_IDS) {
						provides.add(id);
					}
				}
				jar = new ModJar(jar.file(), jar.id(), jar.name(), jar.version(), jar.description(), jar.depends(), provides, jar.environment(),
						jar.parsed());
			}
			on.add(jar);
		}
		List<ModJar> off = readJars(storeMods());
		return new Disk(List.copyOf(on), off, Set.copyOf(Removals.pendingJars()), markers());
	}

	/** {@code *.jar} files directly in a folder (not links: nothing outside the folder is read), read once each. */
	private static List<ModJar> readJars(Path dir) {
		List<Path> files;
		try (Stream<Path> list = Files.list(dir)) {
			files = list.filter(p -> {
				String name = p.getFileName().toString();
				return ModChanges.safeName(name) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
			}).limit(ModJar.MAX_JARS).toList();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
		List<ModJar> jars = new ArrayList<>();
		for (Path file : files) {
			String key;
			try {
				BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				if (!attributes.isRegularFile()) {
					continue;
				}
				key = file + "|" + attributes.size() + "|" + attributes.lastModifiedTime().toMillis();
			} catch (IOException e) {
				continue; // gone meanwhile
			}
			ModJar jar;
			synchronized (JARS) {
				jar = JARS.get(key);
			}
			if (jar == null) {
				jar = ModJar.read(file);
				synchronized (JARS) {
					JARS.put(key, jar);
				}
			}
			jars.add(jar);
		}
		return jars;
	}

	/** Jars JavaHost installed: jar file name to its Modrinth name, from the {@code .javahost-<slug>} markers. */
	private static Map<String, String> markers() {
		Map<String, String> slugs = new HashMap<>();
		try (Stream<Path> list = Files.list(modsDir())) {
			for (Path marker : list.filter(p -> p.getFileName().toString().startsWith(".javahost-")).limit(MAX_MARKERS).toList()) {
				String slug = marker.getFileName().toString().substring(".javahost-".length());
				if (!slug.matches("[A-Za-z0-9_.-]{1,64}") || Files.size(marker) > 512) {
					continue;
				}
				String jar = Files.readString(marker).trim();
				if (ModChanges.safeName(jar)) {
					slugs.put(jar, slug);
				}
			}
		} catch (IOException | RuntimeException e) {
			// No markers.
		}
		return slugs;
	}

	/** What Fabric loaded (fixed for the session, so worked out once). */
	private static Loaded loaded() {
		Loaded known = loaded;
		if (known != null) {
			return known;
		}
		Path mods = real(modsDir());
		Set<String> files = new HashSet<>();
		Set<String> ids = new HashSet<>();
		Map<String, Set<String>> nested = new HashMap<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModContainer top = mod;
			while (top.getContainingMod().isPresent()) {
				top = top.getContainingMod().get();
			}
			Path jar = ModScanner.jarOf(top);
			boolean fromMods = jar != null && mods.equals(real(jar.toAbsolutePath().getParent()));
			Set<String> modIds = new LinkedHashSet<>();
			modIds.add(mod.getMetadata().getId());
			modIds.addAll(mod.getMetadata().getProvides());
			if (!fromMods) {
				ids.addAll(modIds);
			} else if (top == mod) {
				files.add(jar.getFileName().toString());
			} else {
				nested.computeIfAbsent(jar.getFileName().toString(), k -> new LinkedHashSet<>()).addAll(modIds);
			}
		}
		known = new Loaded(Set.copyOf(files), Set.copyOf(ids), Map.copyOf(nested));
		loaded = known;
		return known;
	}

	private static Path real(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	private static Set<String> loadedIds() {
		Set<String> ids = new HashSet<>(loaded().ids());
		Set<String> pretend = pretendLoaded;
		if (pretend != null) {
			ids.addAll(pretend);
		}
		return ids;
	}

	/** The mod set in use added these jars. */
	private static Set<String> setFiles() {
		return pretendSet != null ? Set.of() : new HashSet<>(ModSwap.list(ModSetsState.active(), "added"));
	}

	/** Re-derives when a mod set switch is staged or cancelled (wanted changes then show or hide). */
	private static void checkSets() {
		int now = ModSets.changes();
		if (now != setChanges) {
			setChanges = now;
			if (disk != null) {
				derive();
			}
		}
	}

	private static synchronized void derive() {
		Disk read = disk;
		if (read == null) {
			return;
		}
		ModChanges wanted;
		synchronized (LOCK) {
			// Read-only (a mod set in use, or a switch to one waiting): the list shows what is, not what was wanted.
			wanted = readOnlyReason() != null ? new ModChanges() : new ModChanges(changes);
		}
		Loaded game = loaded();
		List<ModStates.Entry> derived = ModStates.derive(new ModStates.Inputs(read.on(), read.off(), game.files(), loadedIds(), atStart(), wanted,
				read.removing(), read.slugs(), PROTECTED, setFiles()));
		StringBuilder key = new StringBuilder();
		for (ModStates.Entry entry : derived) {
			key.append(entry.file()).append('|').append(entry.inMods()).append('|').append(entry.state()).append('|').append(entry.slug())
					.append('|').append(entry.jar().name()).append('|').append(entry.jar().version()).append('\n');
		}
		entries = List.copyOf(derived);
		if (!key.toString().equals(signature)) {
			signature = key.toString();
			version++;
		}
	}

	// ================================================================ read-only

	/** Why the switches can't be used right now, or null. */
	public static String readOnlyReason() {
		String set = pretendSet != null ? pretendSet : ModSets.activeLabel();
		if (set != null && !set.isEmpty()) {
			return "You're using " + set + ". Go back to your own mods to switch them on and off.";
		}
		String staged = ModSets.staged() ? ModSets.stagedLabel() : null;
		if (staged != null) {
			return "Switching to " + staged + " when you close the game. Cancel that to switch mods on and off.";
		}
		return null;
	}

	// ================================================================ switching

	/** Works out what switching {@code file} on or off takes with it. Nothing changes until {@link #apply}. */
	public static Ask ask(String file, boolean on) {
		Disk read = disk;
		if (read == null) {
			return new Ask(file, on, List.of(), List.of(), "Still reading your mods.", List.of());
		}
		String readOnly = readOnlyReason();
		if (readOnly != null) {
			return new Ask(file, on, List.of(), List.of(), readOnly, List.of());
		}
		ModChanges wanted;
		synchronized (LOCK) {
			wanted = new ModChanges(changes);
		}
		// The folder as it will be once the game has closed: what the switches point at.
		List<ModJar> viewOn = new ArrayList<>();
		List<ModJar> viewOff = new ArrayList<>();
		Map<String, String> names = new HashMap<>();
		Set<String> inMods = new HashSet<>();
		for (ModJar jar : read.on()) {
			inMods.add(jar.file());
			names.put(jar.file(), jar.name());
			if (read.removing().contains(jar.file())) {
				continue; // deleted when the game closes
			}
			(wanted.wantsOn(jar.file(), true) ? viewOn : viewOff).add(jar);
		}
		for (ModJar jar : read.off()) {
			if (inMods.contains(jar.file())) {
				continue;
			}
			names.put(jar.file(), jar.name());
			(wanted.wantsOn(jar.file(), false) ? viewOn : viewOff).add(jar);
		}
		// Mods that aren't jars in mods/ (built in, or loaded from elsewhere) are always there.
		List<String> builtIn = new ArrayList<>(loaded().ids());
		builtIn.sort(null);
		if (!builtIn.isEmpty()) {
			viewOn.add(new ModJar("\0loaded", null, "", "", "", List.of(), builtIn, "*", true));
		}
		ModToggle toggle = new ModToggle(viewOn, viewOff, PROTECTED.keySet(), setFiles());
		ModToggle.Result result = on ? toggle.turnOn(file) : toggle.turnOff(file);
		if (!result.ok()) {
			return new Ask(file, on, List.of(), List.of(), result.refusal(), List.of());
		}
		List<String> files = new ArrayList<>(result.files());
		files.remove(file);
		files.addFirst(file); // the clicked one first
		return new Ask(file, on, files, files.stream().map(f -> names.getOrDefault(f, f)).toList(), null, result.missing());
	}

	/** Makes the wanted change an {@link #ask} worked out (it happens when the game closes). */
	public static void apply(Ask ask) {
		Disk read = disk;
		if (read == null || !ask.ok()) {
			return;
		}
		Set<String> inMods = new HashSet<>();
		read.on().forEach(jar -> inMods.add(jar.file()));
		synchronized (LOCK) {
			for (String file : ask.files()) {
				changes.want(file, ask.on(), inMods.contains(file));
			}
			save();
		}
		derive();
	}

	/** Switches one mod straight away if nothing else goes with it; returns the ask either way (the page confirms the rest). */
	public static Ask setWanted(String file, boolean on) {
		Ask ask = ask(file, on);
		if (ask.ok() && !ask.needsConfirm()) {
			apply(ask);
		}
		return ask;
	}

	/** Forgets every wanted on/off change. */
	public static void undoAll() {
		synchronized (LOCK) {
			changes.clear();
			save();
		}
		derive();
	}

	/** How many on/off changes are wanted. */
	public static int changeCount() {
		synchronized (LOCK) {
			return changes.size();
		}
	}

	/** Marks a jar in mods/ (and its JavaHost marker) to be deleted when the game closes; its wanted change goes. */
	public static void remove(ModStates.Entry entry) throws IOException {
		Removals.remove(entry.file(), entry.slug());
		synchronized (LOCK) {
			changes.want(entry.file(), true, true);
			save();
		}
		refresh();
	}

	/** Changed your mind: it stays. */
	public static void keep(ModStates.Entry entry) throws IOException {
		Removals.keep(entry.file(), entry.slug());
		refresh();
	}

	/** Where a mod's jar is. */
	public static Path pathOf(ModStates.Entry entry) {
		return (entry.inMods() ? modsDir() : storeMods()).resolve(entry.file());
	}

	private static void save() {
		try {
			changes.write(wantedFile());
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't save the mods to switch on and off", e);
		}
	}

	// ================================================================ when the game closes

	/**
	 * Writes the swap for the wanted changes ({@code kind=toggle}) with this game's process id, for the helper; returns
	 * it, or null when nothing needs to move. Nothing is made while a mod set is in use (the changes stay wanted).
	 */
	public static Path writePlan(long pid) {
		ModChanges wanted;
		synchronized (LOCK) {
			if (changes.isEmpty()) {
				return null;
			}
			wanted = new ModChanges(changes);
		}
		if (readOnlyReason() != null) {
			return null;
		}
		List<String> onNow = jarNames(modsDir());
		List<String> offNow = jarNames(storeMods());
		wanted.prune(onNow, offNow);
		ModToggle.Plan plan = wanted.plan(onNow, offNow, Removals.pendingJars());
		if (plan.isEmpty()) {
			return null;
		}
		Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Properties properties = ModToggle.toProperties(game.resolve("mods"), game, game.resolve("javahost").resolve("mods-off"),
				game.resolve("javahost").resolve("mods-off").resolve("state.properties"),
				game.resolve("javahost").resolve("mods-off").resolve("result.properties"), pid, "Your mods", "toggle", plan);
		try {
			Files.createDirectories(storeDir());
			try (OutputStream out = Files.newOutputStream(runningFile())) {
				properties.store(out, "JavaHost: mods turned on and off as the game closes");
			}
			JavaHost.LOGGER.info("[Mods] turning {} off and {} on as the game closes", plan.out().size(), plan.back().size());
			return runningFile();
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't write the on/off plan", e);
			return null;
		}
	}

	/** The helper has the plan: the wanted changes are done with (the next start reads how it went). */
	static void handedOver() {
		synchronized (LOCK) {
			changes.clear();
			save();
		}
	}

	/** A mod set switch goes first: the wanted on/off changes are dropped, and the next start says so. */
	static void dropForSetSwitch(String label) {
		int count;
		synchronized (LOCK) {
			count = changes.size();
			if (count == 0) {
				return;
			}
			changes.clear();
			save();
		}
		Properties dropped = new Properties();
		dropped.setProperty("count", String.valueOf(count));
		dropped.setProperty("label", label == null ? "" : label);
		try {
			Files.createDirectories(storeDir());
			try (OutputStream out = Files.newOutputStream(droppedFile())) {
				dropped.store(out, "JavaHost: on/off changes not made because a mod set was switched to");
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't note the dropped changes", e);
		}
	}

	/** Called at startup: says how the last on/off swap went, once. */
	public static void reportLastResult() {
		try {
			if (Files.exists(droppedFile())) {
				Properties dropped = read(droppedFile());
				String label = dropped.getProperty("label", "");
				Toasts.quiet("Your mod changes weren't made", (label.isEmpty() ? "You switched mod sets" : "You switched to " + label)
						+ " instead, so your mods stayed as they were.");
				Files.deleteIfExists(droppedFile());
			}
			if (!Files.exists(resultFile())) {
				return;
			}
			Properties result = read(resultFile());
			Properties plan = Files.exists(runningFile()) ? read(runningFile()) : new Properties();
			if (Boolean.parseBoolean(result.getProperty("ok"))) {
				int out = ModSwap.list(plan, "out").size();
				int back = ModSwap.list(plan, "back").size();
				if (out + back > 0) {
					Toasts.quiet(summary(out, back), out > 0 ? "Switch them back on from Mods any time." : "They're running now.");
				}
				List<String> conflicts = ModSwap.list(result, "conflicts");
				if (!conflicts.isEmpty()) {
					String first = conflicts.getFirst();
					String name = first.toLowerCase(Locale.ROOT).endsWith(".jar") ? first.substring(0, first.length() - 4) : first;
					Toasts.quiet(conflicts.size() == 1 ? "An older copy of " + name + " was kept" : "Older copies of " + conflicts.size() + " mods were kept",
							"Two files had the same name, so one was put aside in javahost/mods-off/replaced.");
				}
			} else {
				String reason = result.getProperty("reason", "").strip();
				Toasts.notify("Couldn't switch mods on and off", (reason.isEmpty() ? "" : reason + (reason.endsWith(".") ? " " : ". "))
						+ "Nothing was changed.");
			}
			Files.deleteIfExists(resultFile());
			Files.deleteIfExists(runningFile());
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.debug("[Mods] nothing to report about switching mods", e);
		}
	}

	/** "2 mods turned off", "1 mod turned on", "2 mods turned off, 1 on". */
	static String summary(int out, int back) {
		if (out > 0 && back > 0) {
			return com.javahost.client.gui.ui.Draw.count(out, "mod") + " turned off, " + back + " on";
		}
		return out > 0 ? com.javahost.client.gui.ui.Draw.count(out, "mod") + " turned off" : com.javahost.client.gui.ui.Draw.count(back, "mod") + " turned on";
	}

	// ================================================================ helpers

	static List<String> jarNames(Path folder) {
		try (Stream<Path> files = Files.list(folder)) {
			return files.map(p -> p.getFileName().toString()).filter(ModChanges::safeName)
					.filter(name -> !Files.isDirectory(folder.resolve(name), LinkOption.NOFOLLOW_LINKS)).sorted().limit(ModJar.MAX_JARS).toList();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	private static Properties read(Path file) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		}
		return properties;
	}

	// ================================================================ test hooks

	/** For tests: mod ids to count as loaded (the test game loads its mods from the classpath); null for the real list. */
	public static void pretendLoadedForTests(Collection<String> ids) {
		pretendLoaded = ids == null ? null : Set.copyOf(ids);
		if (disk != null) {
			derive();
		}
	}

	/** For tests: pretend a mod set is in use (its label), or null. */
	public static void pretendSetForTests(String label) {
		pretendSet = label;
		if (disk != null) {
			derive();
		}
	}

	/** For tests: forget the jars seen at startup (a fresh look, as if the game had just started with what's there now). */
	public static void restartForTests() {
		atStart = Set.copyOf(jarNames(modsDir()));
		synchronized (LOCK) {
			changes = ModChanges.read(wantedFile());
		}
		refreshNow();
	}

	/** For tests: as if the helper had taken the plan (the game closing). */
	public static void handedOverForTests() {
		handedOver();
	}

	/** For tests: the wanted changes. */
	public static ModChanges changesForTests() {
		synchronized (LOCK) {
			return new ModChanges(changes);
		}
	}
}

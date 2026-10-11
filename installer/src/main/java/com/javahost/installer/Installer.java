package com.javahost.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import com.javahost.util.ModCatalog;
import com.javahost.util.Modrinth;
import com.javahost.util.Modrinth.ModFile;

/** Does the actual installing and uninstalling. The window and the command line both drive this. */
public final class Installer {
	/** What to install. */
	public record Options(Path minecraftDir, boolean performanceMods, boolean relay, boolean shaders,
			boolean importSettings, boolean importPacks, boolean importWorlds) {
	}

	/** Receives progress as a fraction (0..1) and a line describing the current step. */
	public interface Progress {
		void update(double fraction, String step);
	}

	public static final String MOD_VERSION;
	public static final String MINECRAFT_VERSION;

	static {
		Properties props = new Properties();
		try (InputStream in = Installer.class.getResourceAsStream("installer.properties")) {
			if (in != null) {
				props.load(in);
			}
		} catch (IOException ignored) {
			// Defaults below.
		}
		MOD_VERSION = props.getProperty("mod_version", "dev");
		MINECRAFT_VERSION = props.getProperty("minecraft_version", "26.3");
	}

	private Installer() {
	}

	public static boolean isInstalled(Path minecraftDir) {
		return LauncherProfiles.hasProfile(minecraftDir);
	}

	public static void install(Options options, Progress progress) throws IOException, InterruptedException {
		Path mc = options.minecraftDir();
		if (!MinecraftDirs.hasLauncherProfiles(mc)) {
			throw new IOException("Couldn't find the Minecraft Launcher's files in " + mc
					+ ". Open the Minecraft Launcher once, then try again.");
		}
		Path gameDir = MinecraftDirs.gameDir(mc);
		Path mods = gameDir.resolve("mods");
		Files.createDirectories(mods);

		progress.update(0.05, "Finding the latest Fabric loader for Minecraft " + MINECRAFT_VERSION + "...");
		String loader = FabricInstaller.latestLoader(MINECRAFT_VERSION);
		progress.update(0.12, "Installing Fabric loader " + loader + "...");
		String versionId = FabricInstaller.install(mc, MINECRAFT_VERSION, loader);

		progress.update(0.2, "Installing JavaHost " + MOD_VERSION + "...");
		installJavaHostJar(mods);

		List<String> projects = new ArrayList<>();
		projects.add("fabric-api");
		if (options.performanceMods()) {
			ModCatalog.group(ModCatalog.Group.PERFORMANCE).forEach(e -> projects.add(e.slug()));
		}
		if (options.shaders()) {
			ModCatalog.group(ModCatalog.Group.SHADERS).forEach(e -> projects.add(e.slug()));
		}
		if (options.relay()) {
			ModCatalog.group(ModCatalog.Group.RELAY).forEach(e -> projects.add(e.slug()));
		}
		progress.update(0.25, "Finding mods for Minecraft " + MINECRAFT_VERSION + "...");
		List<ModFile> files = Modrinth.resolve(projects, MINECRAFT_VERSION, id -> false, step -> progress.update(0.3, step));

		for (int i = 0; i < files.size(); i++) {
			ModFile file = files.get(i);
			progress.update(0.35 + 0.45 * i / Math.max(1, files.size()), "Downloading " + file.fileName() + "...");
			removeOldVersion(mods, file);
			Modrinth.download(file, mods.resolve(file.fileName()));
			Files.writeString(mods.resolve(".javahost-" + file.project()), file.fileName());
		}

		if (options.importSettings()) {
			progress.update(0.82, "Copying your settings and keybinds...");
			DataImporter.importSettings(mc, gameDir);
		}
		if (options.importPacks()) {
			progress.update(0.86, "Copying your resource packs and shaders...");
			DataImporter.importPacks(mc, gameDir);
		}
		if (options.importWorlds()) {
			progress.update(0.9, "Copying your worlds (this can take a moment)...");
			DataImporter.importWorlds(mc, gameDir);
		}

		progress.update(0.96, "Adding the JavaHost profile to the Minecraft Launcher...");
		LauncherProfiles.addProfile(mc, versionId, gameDir, iconDataUri());
		progress.update(1.0, "Done!");
	}

	/** Removes the launcher profile and the Fabric version; JavaHost's folder is only deleted if asked. */
	public static void uninstall(Path minecraftDir, boolean deleteGameDir, Progress progress) throws IOException {
		progress.update(0.2, "Removing the JavaHost profile...");
		String versionId = LauncherProfiles.removeProfile(minecraftDir);
		if (versionId != null && versionId.startsWith("fabric-loader-") && !LauncherProfiles.versionUsedByOthers(minecraftDir, versionId)) {
			progress.update(0.5, "Removing " + versionId + "...");
			deleteTree(minecraftDir.resolve("versions").resolve(versionId));
		}
		if (deleteGameDir) {
			progress.update(0.7, "Deleting JavaHost's folder...");
			deleteTree(MinecraftDirs.gameDir(minecraftDir));
		}
		progress.update(1.0, "JavaHost was removed.");
	}

	private static void installJavaHostJar(Path mods) throws IOException {
		try (Stream<Path> list = Files.list(mods)) {
			for (Path old : list.filter(p -> p.getFileName().toString().matches("javahost-.*\\.jar")).toList()) {
				Files.delete(old);
			}
		}
		Path target = mods.resolve("javahost-" + MOD_VERSION + ".jar");
		String override = System.getProperty("javahost.installer.embeddedMod");
		if (override != null) {
			Files.copy(Path.of(override), target, StandardCopyOption.REPLACE_EXISTING);
			return;
		}
		try (InputStream in = Installer.class.getResourceAsStream("javahost.jar")) {
			if (in == null) {
				throw new IOException("This installer is missing the JavaHost mod file");
			}
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** When updating, delete the file we installed last time for this project if the name changed. */
	private static void removeOldVersion(Path mods, ModFile file) throws IOException {
		Path marker = mods.resolve(".javahost-" + file.project());
		if (Files.exists(marker)) {
			String previous = Files.readString(marker).trim();
			if (!previous.equals(file.fileName()) && previous.endsWith(".jar") && !previous.contains("/") && !previous.contains("\\")) {
				Files.deleteIfExists(mods.resolve(previous));
			}
		}
	}

	static String iconDataUri() throws IOException {
		try (InputStream in = Installer.class.getResourceAsStream("icon-128.png")) {
			if (in == null) {
				return "Furnace";
			}
			return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}
}

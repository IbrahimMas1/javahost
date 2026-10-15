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
import com.javahost.util.UpdateManifest;
import com.javahost.util.Modrinth;
import com.javahost.util.Modrinth.ModFile;

/** Does the actual installing and uninstalling. The window and the command line both drive this. */
public final class Installer {
	/**
	 * What to install. {@code build}: the release's build for the chosen Minecraft version (from
	 * {@link UpdateManifest#forMinecraft}), with JavaHost and the Fabric API it was made against.
	 */
	public record Options(Path minecraftDir, UpdateManifest build, boolean performanceMods, boolean relay, boolean shaders,
			boolean importSettings, boolean importPacks, boolean importWorlds) {
		public Edition edition() {
			return new Edition(build.minecraft());
		}
	}

	/** Receives progress as a fraction (0..1) and a line describing the current step. */
	public interface Progress {
		void update(double fraction, String step);
	}

	/** The installer's own version (the release it came from). */
	public static final String MOD_VERSION;

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
	}

	/** Fabric API's Modrinth project id: it comes with JavaHost's build, so mods that need it don't fetch another. */
	private static final String FABRIC_API_ID = "P7dR8mSH";

	private Installer() {
	}

	public static boolean isInstalled(Path minecraftDir, Edition edition) {
		return LauncherProfiles.hasProfile(minecraftDir, edition);
	}

	public static void install(Options options, Progress progress) throws IOException, InterruptedException {
		Path mc = options.minecraftDir();
		if (!MinecraftDirs.hasLauncherProfiles(mc)) {
			throw new IOException("Couldn't find the Minecraft Launcher's files in " + mc
					+ ". Open the Minecraft Launcher once, then try again.");
		}
		UpdateManifest build = options.build();
		String minecraft = build.minecraft();
		Edition edition = options.edition();
		Path gameDir = edition.gameDir(mc);
		Path mods = gameDir.resolve("mods");
		Files.createDirectories(mods);

		progress.update(0.05, "Finding the latest Fabric loader for Minecraft " + minecraft + "...");
		String loader = FabricInstaller.latestLoader(minecraft);
		progress.update(0.12, "Installing Fabric loader " + loader + "...");
		String versionId = FabricInstaller.install(mc, minecraft, loader);

		// JavaHost and Fabric API, exactly as listed in the signed release.
		List<UpdateManifest.FileEntry> own = build.files();
		for (int i = 0; i < own.size(); i++) {
			UpdateManifest.FileEntry file = own.get(i);
			progress.update(0.15 + 0.1 * i / Math.max(1, own.size()), "Downloading " + file.name() + "...");
			Path downloaded = gameDir.resolve(".javahost-download").resolve(file.name());
			Releases.download(file, downloaded);
			replace(mods, file, downloaded);
		}
		deleteTree(gameDir.resolve(".javahost-download"));

		List<String> projects = new ArrayList<>();
		if (options.performanceMods()) {
			ModCatalog.group(ModCatalog.Group.PERFORMANCE).forEach(e -> projects.add(e.slug()));
		}
		if (options.shaders()) {
			ModCatalog.group(ModCatalog.Group.SHADERS).forEach(e -> projects.add(e.slug()));
		}
		if (options.relay()) {
			ModCatalog.group(ModCatalog.Group.RELAY).forEach(e -> projects.add(e.slug()));
		}
		progress.update(0.25, "Finding mods for Minecraft " + minecraft + "...");
		List<ModFile> files = Modrinth.resolve(projects, minecraft, FABRIC_API_ID::equals, step -> progress.update(0.3, step));

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

		progress.update(0.96, "Adding the " + edition.profileName() + " profile to the Minecraft Launcher...");
		LauncherProfiles.addProfile(mc, edition, versionId, iconDataUri());
		progress.update(1.0, "Done!");
	}

	/** Removes a version's launcher profile and its Fabric version; its folder is only deleted if asked. */
	public static void uninstall(Path minecraftDir, Edition edition, boolean deleteGameDir, Progress progress) throws IOException {
		progress.update(0.2, "Removing the " + edition.profileName() + " profile...");
		String versionId = LauncherProfiles.removeProfile(minecraftDir, edition);
		if (versionId != null && versionId.startsWith("fabric-loader-") && !LauncherProfiles.versionUsedByOthers(minecraftDir, edition, versionId)) {
			progress.update(0.5, "Removing " + versionId + "...");
			deleteTree(minecraftDir.resolve("versions").resolve(versionId));
		}
		if (deleteGameDir) {
			progress.update(0.7, "Deleting JavaHost's folder...");
			deleteTree(edition.gameDir(minecraftDir));
		}
		progress.update(1.0, "JavaHost was removed.");
	}

	/** Puts a downloaded release file into mods/, removing the files it replaces (older JavaHost or Fabric API). */
	private static void replace(Path mods, UpdateManifest.FileEntry file, Path downloaded) throws IOException {
		try (Stream<Path> list = Files.list(mods)) {
			for (Path old : list.filter(p -> {
				String name = p.getFileName().toString();
				return !name.equals(file.name()) && name.matches(file.replaces());
			}).toList()) {
				Files.delete(old);
			}
		}
		Files.move(downloaded, mods.resolve(file.name()), StandardCopyOption.REPLACE_EXISTING);
		// Fabric API used to come from Modrinth with a marker; it's part of JavaHost's build now.
		Files.deleteIfExists(mods.resolve(".javahost-fabric-api"));
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

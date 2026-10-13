package com.javahost.client.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.javahost.JavaHost;
import com.javahost.util.ModCatalog;
import com.javahost.util.Modrinth;
import com.javahost.util.Modrinth.ModFile;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Installs optional mods (FPS mods, Iris, the relay) for this Minecraft version from Modrinth, from inside
 * the game. The big FPS gains on Fabric come from these well-proven mods, so JavaHost installs them rather
 * than re-implementing them.
 */
public final class BoostMods {
	/** A catalog entry as shown on the Performance tab. */
	public record BoostMod(String slug, String modId, String name, String benefit, boolean recommended) {
	}

	public static final List<BoostMod> MODS = ModCatalog.ENTRIES.stream()
			.map(e -> new BoostMod(e.slug(), e.modId(), e.name(), e.benefit(), e.group() == ModCatalog.Group.PERFORMANCE))
			.toList();

	private BoostMods() {
	}

	/** Remembered answers (menus ask every frame); cleared after an install. */
	private static final java.util.Map<String, Boolean> INSTALLED = new java.util.concurrent.ConcurrentHashMap<>();

	public static boolean isInstalled(BoostMod mod) {
		return INSTALLED.computeIfAbsent(mod.slug(),
				slug -> FabricLoader.getInstance().isModLoaded(mod.modId()) || pendingJar(slug) != null);
	}

	/** A jar downloaded earlier, waiting for a restart to load. Marker files are written by us and the installer. */
	private static Path pendingJar(String slug) {
		Path marker = modsDir().resolve(".javahost-" + slug);
		try {
			if (Files.exists(marker)) {
				Path jar = modsDir().resolve(Files.readString(marker).trim());
				return Files.exists(jar) ? jar : null;
			}
		} catch (IOException ignored) {
			// Treat as not installed.
		}
		return null;
	}

	private static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	private static String gameVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElseThrow();
	}

	/** Downloads the mods and their dependencies. Blocking; call from a background thread. Returns how many jars were installed. */
	public static int install(List<BoostMod> mods, Consumer<String> progress) {
		try {
			List<ModFile> files = Modrinth.resolve(mods.stream().map(BoostMod::slug).toList(), gameVersion(),
					projectId -> projectId.equals(Modrinth.FABRIC_API), progress);
			int installed = 0;
			for (ModFile file : files) {
				// Skip dependencies that are already loaded (e.g. Sodium when installing Iris).
				if (MODS.stream().anyMatch(m -> m.slug().equals(file.project()) && FabricLoader.getInstance().isModLoaded(m.modId()))) {
					continue;
				}
				progress.accept("Downloading " + file.fileName() + "...");
				Modrinth.download(file, modsDir().resolve(file.fileName()));
				Files.writeString(modsDir().resolve(".javahost-" + file.project()), file.fileName());
				installed++;
			}
			return installed;
		} catch (IOException | InterruptedException | RuntimeException e) {
			JavaHost.LOGGER.warn("Could not install mods", e);
			progress.accept("Download failed: " + e.getMessage());
			return 0;
		} finally {
			INSTALLED.clear();
		}
	}
}

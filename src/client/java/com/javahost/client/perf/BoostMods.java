package com.javahost.client.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

	/**
	 * Whether a mod from Modrinth (by its Modrinth name) is loaded or downloaded and waiting for a restart. Mods are
	 * usually loaded under the same id as their Modrinth name; ones JavaHost downloaded leave a marker either way.
	 */
	public static boolean installedSlug(String slug) {
		return INSTALLED.computeIfAbsent(slug, s -> FabricLoader.getInstance().isModLoaded(s) || pendingJar(s) != null);
	}

	/** Downloaded for this mod (by Modrinth name) but not loaded yet: it starts working after a restart. */
	public static boolean waitingForRestart(String slug) {
		return !FabricLoader.getInstance().isModLoaded(slug) && pendingJar(slug) != null && !LOADED_JARS.contains(pendingJar(slug).getFileName().toString());
	}

	/** Jar file names Fabric loaded this session (a marker's jar in here is already running under another id). */
	private static final java.util.Set<String> LOADED_JARS = FabricLoader.getInstance().getAllMods().stream()
			.flatMap(m -> m.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH ? m.getOrigin().getPaths().stream() : java.util.stream.Stream.empty())
			.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toUnmodifiableSet());

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

	/** A mod JavaHost downloaded (it left a marker): its Modrinth name, a display name, and whether it's running. */
	public record Installed(String slug, String name, boolean active) {
	}

	/** Mods JavaHost installed, running or waiting for a restart (at most 30, by name). */
	public static List<Installed> installedByJavaHost() {
		List<Installed> found = new java.util.ArrayList<>();
		try (var markers = Files.list(modsDir())) {
			for (Path marker : markers.filter(p -> p.getFileName().toString().startsWith(".javahost-")).limit(30).toList()) {
				String slug = marker.getFileName().toString().substring(".javahost-".length());
				Path jar = pendingJar(slug);
				if (jar == null) {
					continue;
				}
				String jarName = jar.getFileName().toString();
				var loaded = FabricLoader.getInstance().getAllMods().stream()
						.filter(m -> m.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH
								&& m.getOrigin().getPaths().stream().anyMatch(p -> p.getFileName().toString().equals(jarName)))
						.findFirst();
				String name = loaded.map(m -> m.getMetadata().getName()).orElseGet(() -> nameInJar(jar, slug));
				found.add(new Installed(slug, com.javahost.util.Text.clean(name, 48), loaded.isPresent() || FabricLoader.getInstance().isModLoaded(slug)));
			}
		} catch (IOException e) {
			JavaHost.LOGGER.debug("[Mods] couldn't list installed mods", e);
		}
		found.sort(java.util.Comparator.comparing(i -> i.name().toLowerCase(java.util.Locale.ROOT)));
		return found;
	}

	/** Jar file name to the mod's name inside it (read once per jar). */
	private static final java.util.Map<String, String> JAR_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

	/** The name in a jar's fabric.mod.json, for a mod that hasn't loaded yet; its Modrinth name if there's none. */
	private static String nameInJar(Path jar, String fallback) {
		return JAR_NAMES.computeIfAbsent(jar.getFileName().toString(), key -> {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
				var entry = zip.getEntry("fabric.mod.json");
				if (entry != null) {
					try (var in = zip.getInputStream(entry)) {
						var json = com.google.gson.JsonParser.parseString(new String(in.readNBytes(1 << 20), java.nio.charset.StandardCharsets.UTF_8))
								.getAsJsonObject();
						if (json.has("name")) {
							return json.get("name").getAsString();
						}
					}
				}
			} catch (IOException | RuntimeException e) {
				// fall back to the Modrinth name
			}
			return fallback;
		});
	}

	private static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	private static String gameVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElseThrow();
	}

	/**
	 * Downloads the mods and their dependencies. Blocking; call from a background thread. Returns how many jars were
	 * installed. A cancelled install removes the jars it already saved, so nothing half-installed loads next time.
	 */
	public static int install(List<BoostMod> mods, com.javahost.client.mods.Downloads.Reporter progress) {
		List<Path> saved = new java.util.ArrayList<>();
		try {
			List<ModFile> files = Modrinth.resolve(mods.stream().map(BoostMod::slug).toList(), gameVersion(),
					projectId -> projectId.equals(Modrinth.FABRIC_API), progress::status);
			progress.fraction(0.05);
			List<ModFile> needed = files.stream()
					// Skip dependencies that are already loaded (e.g. Sodium when installing Iris).
					.filter(file -> MODS.stream().noneMatch(m -> m.slug().equals(file.project()) && FabricLoader.getInstance().isModLoaded(m.modId())))
					.toList();
			int installed = 0;
			for (ModFile file : needed) {
				int index = installed;
				progress.status(needed.size() == 1 ? "Downloading " + file.fileName() + "..."
						: "Downloading " + (index + 1) + " of " + needed.size() + "...");
				Path target = modsDir().resolve(file.fileName());
				Modrinth.download(file, target, List.of("cdn.modrinth.com"), (read, total) -> {
					double share = total > 0 ? (double) read / total : 0;
					progress.fraction(0.05 + 0.95 * (index + share) / needed.size());
				}, progress::cancelled);
				saved.add(target);
				Path marker = modsDir().resolve(".javahost-" + file.project());
				Files.writeString(marker, file.fileName());
				saved.add(marker);
				installed++;
			}
			return installed;
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (progress.cancelled()) {
				for (Path path : saved) {
					try {
						Files.deleteIfExists(path);
					} catch (IOException ignored) {
						// best effort
					}
				}
			} else {
				JavaHost.LOGGER.warn("Could not install mods", e);
				progress.status("Download failed: " + e.getMessage());
			}
			return 0;
		} finally {
			INSTALLED.clear();
		}
	}
}

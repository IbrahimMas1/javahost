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
	/**
	 * A mod JavaHost downloaded (it left a marker): its Modrinth name, a display name, whether it's running, its jar's
	 * file name, its mod id, and the mod ids it needs.
	 */
	public record Installed(String slug, String name, boolean active, String jarName, String modId, java.util.Set<String> needs) {
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
				JarInfo info = jarInfo(jar, slug);
				String name = loaded.map(m -> m.getMetadata().getName()).orElse(info.name());
				String modId = loaded.map(m -> m.getMetadata().getId()).orElse(info.id());
				found.add(new Installed(slug, com.javahost.util.Text.clean(name, 48), loaded.isPresent() || FabricLoader.getInstance().isModLoaded(slug),
						jarName, modId, info.needs()));
			}
		} catch (IOException e) {
			JavaHost.LOGGER.debug("[Mods] couldn't list installed mods", e);
		}
		found.sort(java.util.Comparator.comparing(i -> i.name().toLowerCase(java.util.Locale.ROOT)));
		return found;
	}

	/** Mods that must never be removed from the menu: JavaHost itself and what it runs on. */
	private static final java.util.Set<String> ESSENTIAL = java.util.Set.of("fabric-api", "fabricloader", "minecraft", "java", JavaHost.MOD_ID);

	/**
	 * Why {@code mod} can't be removed right now, or null if it can: something else needs it (a loaded mod, or another
	 * mod JavaHost installed that isn't being removed too), or JavaHost itself runs on it.
	 */
	public static String cannotRemove(Installed mod, List<Installed> all, java.util.Set<String> beingRemoved) {
		if (ESSENTIAL.contains(mod.modId()) || ESSENTIAL.contains(mod.slug())) {
			return "JavaHost needs it";
		}
		for (var other : FabricLoader.getInstance().getAllMods()) {
			String otherJar = other.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH
					? other.getOrigin().getPaths().stream().map(p -> p.getFileName().toString()).findFirst().orElse("") : "";
			if (otherJar.equals(mod.jarName()) || beingRemoved.contains(otherJar) || other.getContainingMod().isPresent()) {
				continue;
			}
			boolean needs = other.getMetadata().getDependencies().stream()
					.anyMatch(d -> d.getKind() == net.fabricmc.loader.api.metadata.ModDependency.Kind.DEPENDS && d.getModId().equals(mod.modId()));
			if (needs) {
				return other.getMetadata().getName() + " needs it";
			}
		}
		for (Installed other : all) {
			if (!other.jarName().equals(mod.jarName()) && !other.active() && !beingRemoved.contains(other.jarName()) && other.needs().contains(mod.modId())) {
				return other.name() + " needs it";
			}
		}
		return null;
	}

	/** What a jar's fabric.mod.json says: its name, id and the mod ids it depends on. */
	private record JarInfo(String name, String id, java.util.Set<String> needs) {
	}

	/** Jar file name to what's inside it (read once per jar). */
	private static final java.util.Map<String, JarInfo> JAR_INFO = new java.util.concurrent.ConcurrentHashMap<>();

	/** Reads a jar's fabric.mod.json (for a mod that hasn't loaded yet); falls back to its Modrinth name. */
	private static JarInfo jarInfo(Path jar, String fallback) {
		return JAR_INFO.computeIfAbsent(jar.getFileName().toString(), key -> {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
				var entry = zip.getEntry("fabric.mod.json");
				if (entry != null) {
					try (var in = zip.getInputStream(entry)) {
						var json = com.google.gson.JsonParser.parseString(new String(in.readNBytes(1 << 20), java.nio.charset.StandardCharsets.UTF_8))
								.getAsJsonObject();
						java.util.Set<String> needs = json.has("depends") && json.get("depends").isJsonObject()
								? java.util.Set.copyOf(json.getAsJsonObject("depends").keySet()) : java.util.Set.of();
						return new JarInfo(json.has("name") ? json.get("name").getAsString() : fallback,
								json.has("id") ? json.get("id").getAsString() : fallback, needs);
					}
				}
			} catch (IOException | RuntimeException e) {
				// fall back to the Modrinth name
			}
			return new JarInfo(fallback, fallback, java.util.Set.of());
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
				com.javahost.client.mods.Removals.unmark(file.fileName(), marker.getFileName().toString()); // installed again: keep it
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

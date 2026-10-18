package com.javahost.client.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import com.javahost.util.ModSwap;
import com.javahost.util.Modrinth;
import com.javahost.util.MrPack;
import net.fabricmc.loader.api.FabricLoader;

/** Installs a Modrinth modpack (Fabric, this Minecraft version) as a mod set the host can switch to. */
public final class Modpacks {
	private Modpacks() {
	}

	public static String gameVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElseThrow();
	}

	public static List<Modrinth.Pack> search(String query) throws IOException, InterruptedException {
		return Modrinth.searchModpacks(query, gameVersion());
	}

	/** Downloads the pack and everything it lists (hash-checked). Blocking; returns the set folder name. */
	public static String install(Modrinth.Pack pack, Downloads.Reporter progress) throws IOException, InterruptedException {
		progress.status("Finding " + pack.title() + " for Minecraft " + gameVersion() + "...");
		Modrinth.ModFile file = Modrinth.latestPack(pack.projectId(), gameVersion());
		if (file == null) {
			throw new IOException(pack.title() + " has no Fabric version for Minecraft " + gameVersion());
		}
		String folder = "pack-" + file.versionId();
		Path set = ModSets.setDir(folder);
		Path jars = set.resolve("jars");
		Path overrides = set.resolve("overrides");
		boolean existed = Files.exists(set.resolve("set.properties")); // already installed: never removed by a cancel
		Files.createDirectories(jars);
		Path mrpack = set.resolve("pack.mrpack");
		progress.status("Downloading " + pack.title() + "...");
		Modrinth.download(file, mrpack, List.of("cdn.modrinth.com"), (read, total) -> progress.fraction(total > 0 ? 0.05 * read / total : 0),
				progress::cancelled);
		MrPack.Index index = MrPack.readIndex(mrpack);
		if (!index.minecraft().equals(gameVersion())) {
			throw new IOException("This pack is for Minecraft " + index.minecraft());
		}
		int done = 0;
		// Progress by bytes when the pack says how big its files are, by file count otherwise.
		long totalBytes = index.files().stream().mapToLong(MrPack.PackFile::size).sum();
		long[] doneBytes = {0};
		for (MrPack.PackFile entry : index.files()) {
			progress.status("Downloading " + (++done) + " of " + index.files().size() + "...");
			int fileNumber = done;
			long before = doneBytes[0];
			Modrinth.Progress fileProgress = (read, total) -> progress.fraction(0.05 + 0.9 * (totalBytes > 0
					? (double) (before + read) / totalBytes
					: (fileNumber - 1 + (total > 0 ? (double) read / total : 0)) / index.files().size()));
			String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
			Path target = entry.path().startsWith("mods/") && name.endsWith(".jar")
					? ModSwap.inside(jars, name)
					: ModSwap.inside(overrides, entry.path());
			IOException last = null;
			for (String url : entry.urls()) {
				try {
					Modrinth.download(new Modrinth.ModFile("", "", "", name, url, entry.sha512(), List.of(), ""), target, Modrinth.PACK_HOSTS,
							fileProgress, progress::cancelled);
					last = null;
					break;
				} catch (Modrinth.Cancelled e) {
					if (!existed) {
						deleteSet(set);
					}
					throw e;
				} catch (IOException e) {
					last = e; // try the next mirror
				}
			}
			if (last != null) {
				throw last;
			}
			doneBytes[0] += Math.max(0, entry.size());
		}
		progress.status("Unpacking settings...");
		MrPack.extractOverrides(mrpack, overrides);
		// Jars shipped inside the pack itself count as its mods.
		Path packMods = overrides.resolve("mods");
		if (Files.isDirectory(packMods)) {
			try (Stream<Path> files = Files.list(packMods)) {
				for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
					Files.move(jar, ModSwap.inside(jars, jar.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		List<String> names = ModSets.jarNames(jars);
		List<String> ids = new ArrayList<>();
		for (String name : names) {
			String id = ModSets.modIdOf(jars.resolve(name));
			ids.add(id == null ? "" : id);
		}
		Properties info = new Properties();
		info.setProperty("label", index.name() + (index.version().isEmpty() ? "" : " " + index.version()));
		info.setProperty("kind", "pack");
		info.setProperty("created", String.valueOf(System.currentTimeMillis()));
		ModSwap.store(info, "jar", names);
		ModSwap.store(info, "id", ids);
		ModSets.write(set.resolve("set.properties"), info);
		Files.deleteIfExists(mrpack);
		return folder;
	}

	/** Removes a half-downloaded pack. */
	private static void deleteSet(Path set) {
		try (Stream<Path> files = Files.walk(set)) {
			for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			com.javahost.JavaHost.LOGGER.debug("[Mods] couldn't remove {}", set, e);
		}
	}
}

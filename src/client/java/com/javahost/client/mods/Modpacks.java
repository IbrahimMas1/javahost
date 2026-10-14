package com.javahost.client.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
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
	public static String install(Modrinth.Pack pack, Consumer<String> progress) throws IOException, InterruptedException {
		progress.accept("Finding " + pack.title() + " for Minecraft " + gameVersion() + "...");
		Modrinth.ModFile file = Modrinth.latestPack(pack.projectId(), gameVersion());
		if (file == null) {
			throw new IOException(pack.title() + " has no Fabric version for Minecraft " + gameVersion());
		}
		String folder = "pack-" + file.versionId();
		Path set = ModSets.setDir(folder);
		Path jars = set.resolve("jars");
		Path overrides = set.resolve("overrides");
		Files.createDirectories(jars);
		Path mrpack = set.resolve("pack.mrpack");
		progress.accept("Downloading " + pack.title() + "...");
		Modrinth.download(file, mrpack);
		MrPack.Index index = MrPack.readIndex(mrpack);
		if (!index.minecraft().equals(gameVersion())) {
			throw new IOException("This pack is for Minecraft " + index.minecraft());
		}
		int done = 0;
		for (MrPack.PackFile entry : index.files()) {
			progress.accept("Downloading " + (++done) + " of " + index.files().size() + "...");
			String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
			Path target = entry.path().startsWith("mods/") && name.endsWith(".jar")
					? ModSwap.inside(jars, name)
					: ModSwap.inside(overrides, entry.path());
			IOException last = null;
			for (String url : entry.urls()) {
				try {
					Modrinth.download(new Modrinth.ModFile("", "", "", name, url, entry.sha512(), List.of(), ""), target, Modrinth.PACK_HOSTS);
					last = null;
					break;
				} catch (IOException e) {
					last = e; // try the next mirror
				}
			}
			if (last != null) {
				throw last;
			}
		}
		progress.accept("Unpacking settings...");
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
}

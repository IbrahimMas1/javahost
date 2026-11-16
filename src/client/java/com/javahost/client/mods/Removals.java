package com.javahost.client.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.javahost.JavaHost;
import com.javahost.client.perf.BoostMods;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mods the player removed from Mods, My mods. A running game holds its jars open (Windows won't delete them), so
 * they're deleted by {@code util/RemoveAfterExit} once Minecraft has closed; until then they keep working, and Keep
 * takes them off the list again. The deleting is done by the one helper that runs after exit ({@link AfterExitHelper}).
 */
public final class Removals {
	private Removals() {
	}

	private static Path listFile() {
		return FabricLoader.getInstance().getGameDir().resolve("javahost").resolve("remove-on-exit.txt");
	}

	/** File names in mods/ to delete when the game closes. */
	private static Set<String> names() {
		try {
			return Files.exists(listFile()) ? new LinkedHashSet<>(Files.readAllLines(listFile())) : new LinkedHashSet<>();
		} catch (IOException e) {
			return new LinkedHashSet<>();
		}
	}

	private static void save(Set<String> names) throws IOException {
		if (names.isEmpty()) {
			Files.deleteIfExists(listFile());
			return;
		}
		Files.createDirectories(listFile().getParent());
		Files.write(listFile(), names);
	}

	/** Jar names that will be deleted when the game closes. */
	public static Set<String> pendingJars() {
		Set<String> jars = names();
		jars.removeIf(name -> !name.endsWith(".jar"));
		return jars;
	}

	public static boolean pending(BoostMods.Installed mod) {
		return names().contains(mod.jarName());
	}

	/** Marks a mod to be deleted (jar and marker) when the game closes. */
	public static void remove(BoostMods.Installed mod) throws IOException {
		remove(mod.jarName(), mod.slug());
	}

	/** Changed your mind: the mod stays. */
	public static void keep(BoostMods.Installed mod) throws IOException {
		keep(mod.jarName(), mod.slug());
	}

	/** Takes files off the list, e.g. a mod installed again after it was removed. */
	public static void unmark(String... fileNames) {
		Set<String> names = names();
		if (names.removeAll(Set.of(fileNames))) {
			try {
				save(names);
			} catch (IOException e) {
				JavaHost.LOGGER.warn("[Mods] couldn't update the removal list", e);
			}
		}
	}

	/** The list for {@code util/AfterExit}, or null when nothing is waiting to be removed. */
	public static Path listIfAny() {
		return names().isEmpty() ? null : listFile();
	}

	/** Marks a jar (and its JavaHost marker, if any) to be deleted when the game closes. */
	public static void remove(String jarName, String slug) throws IOException {
		Set<String> names = names();
		names.add(jarName);
		if (slug != null) {
			names.add(".javahost-" + slug);
		}
		save(names);
	}

	/** Takes a jar (and its marker) off the list again. */
	public static void keep(String jarName, String slug) throws IOException {
		Set<String> names = names();
		names.remove(jarName);
		if (slug != null) {
			names.remove(".javahost-" + slug);
		}
		save(names);
	}
}

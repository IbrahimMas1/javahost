package com.javahost.client.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.javahost.JavaHost;
import com.javahost.client.perf.BoostMods;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mods the player removed from Mods, Installed. A running game holds its jars open (Windows won't delete them), so
 * they're deleted by {@code util/RemoveAfterExit} once Minecraft has closed; until then they keep working, and Keep
 * takes them off the list again.
 */
public final class Removals {
	private Removals() {
	}

	private static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
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
		Set<String> names = names();
		names.add(mod.jarName());
		names.add(".javahost-" + mod.slug());
		save(names);
	}

	/** Changed your mind: the mod stays. */
	public static void keep(BoostMods.Installed mod) throws IOException {
		Set<String> names = names();
		names.remove(mod.jarName());
		names.remove(".javahost-" + mod.slug());
		save(names);
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

	/** Called as the game closes: starts the helper that deletes the removed mods once the game has exited. */
	public static void launchIfAny() {
		if (names().isEmpty()) {
			return;
		}
		try {
			// Run from a copy of JavaHost, so an update swapping our jar at the same time can't pull it away.
			List<Path> classpath = new ArrayList<>(FabricLoader.getInstance().getModContainer(JavaHost.MOD_ID).orElseThrow().getOrigin().getPaths());
			if (classpath.size() == 1 && classpath.getFirst().toString().endsWith(".jar")) {
				Path helper = listFile().resolveSibling("remove-helper.jar");
				Files.copy(classpath.getFirst(), helper, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				classpath = List.of(helper);
			}
			String javaCommand = ProcessHandle.current().info().command().orElse("java");
			new ProcessBuilder(javaCommand, "-cp", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()),
					"com.javahost.util.RemoveAfterExit", String.valueOf(ProcessHandle.current().pid()), modsDir().toString(), listFile().toString())
					.redirectErrorStream(true).redirectOutput(listFile().resolveSibling("remove.log").toFile()).start();
			JavaHost.LOGGER.info("[Mods] removing {} file(s) as the game closes", names().size());
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't start removing mods", e);
		}
	}
}

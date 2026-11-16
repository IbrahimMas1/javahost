package com.javahost.client.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.javahost.JavaHost;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Everything about the player's mods that has to wait for the game to close, started once as it closes: one small
 * Java process ({@code util/AfterExit}) deletes the mods marked for removal ({@link Removals}) and then does at most one
 * swap: a mod set switch ({@link ModSets}), else Vanilla mode going in or out ({@link VanillaMode}), else the on/off
 * changes ({@link ModLibrary}). A set switch drops wanted on/off changes (the next start says so); staging Vanilla mode
 * clears them, and the two can't be staged together with a set switch.
 */
public final class AfterExitHelper {
	private AfterExitHelper() {
	}

	private static Path javahostDir() {
		return FabricLoader.getInstance().getGameDir().resolve("javahost");
	}

	/** Called on {@code CLIENT_STOPPING}. */
	public static void launch() {
		Path removals = Removals.listIfAny();
		long pid = ProcessHandle.current().pid();
		// One swap per exit: a mod set switch, else Vanilla mode going in or out, else the per-mod changes.
		Path plan = ModSets.takeStaged();
		boolean toggles = false;
		boolean vanilla = false;
		if (plan != null) {
			ModLibrary.dropForSetSwitch(ModSets.lastTakenLabel());
		} else {
			plan = VanillaMode.writePlan(pid);
			vanilla = plan != null;
			if (plan == null) {
				plan = ModLibrary.writePlan(pid);
				toggles = plan != null;
			}
		}
		if (removals == null && plan == null) {
			return;
		}
		try {
			List<Path> classpath = helperClasspath();
			String javaCommand = ProcessHandle.current().info().command().orElse("java");
			Files.createDirectories(javahostDir());
			new ProcessBuilder(javaCommand, "-cp", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()),
					"com.javahost.util.AfterExit", String.valueOf(pid), FabricLoader.getInstance().getGameDir().resolve("mods").toString(),
					removals == null ? "-" : removals.toString(), plan == null ? "-" : plan.toString())
					.redirectErrorStream(true).redirectOutput(javahostDir().resolve("after-exit.log").toFile()).start();
			if (toggles) {
				ModLibrary.handedOver(); // the helper has the plan now: the wanted changes are done with
			}
			if (vanilla) {
				VanillaMode.handedOver();
			}
			JavaHost.LOGGER.info("[Mods] after exit: {}{}", removals == null ? "" : "removing mods, ", plan == null ? "no swap" : plan.getFileName());
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't start the after-exit helper", e);
		}
	}

	/**
	 * Where the helper's classes come from: a copy of the JavaHost jar, so an update swapping our jar at the same time
	 * can't pull it away (in a development game, the class folders themselves).
	 */
	private static List<Path> helperClasspath() {
		List<Path> classpath = new ArrayList<>(FabricLoader.getInstance().getModContainer(JavaHost.MOD_ID).orElseThrow().getOrigin().getPaths());
		if (classpath.size() == 1 && classpath.getFirst().toString().endsWith(".jar")) {
			Path helper = javahostDir().resolve("after-exit.jar");
			try {
				Files.createDirectories(javahostDir());
				Files.copy(classpath.getFirst(), helper, StandardCopyOption.REPLACE_EXISTING);
				return List.of(helper);
			} catch (IOException e) {
				// An earlier helper may still hold the copy (Windows): run from the jar itself this once.
				JavaHost.LOGGER.debug("[Mods] couldn't copy the helper; using the mod jar", e);
			}
		}
		return classpath;
	}
}

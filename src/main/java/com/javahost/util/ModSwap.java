package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Switches mod sets once the game has closed: to the mods a friend's world needs, to a modpack, or back to the
 * player's own mods. Runs as its own small Java process ({@code java -cp <helper jar> com.javahost.util.ModSwap
 * <plan>}) using only the JDK, like {@link UpdateSwap}.
 *
 * <p>The plan lists files to move out of mods/ (kept in the store), to move back in from the store, to remove (jars
 * a mod set added; they stay cached in its folder), and to copy in, plus config files to apply. Every step is undone
 * if any step fails, so mods/ is never left half switched. On success the new state (what to undo later to get back)
 * is written to {@code state}.
 */
public final class ModSwap {
	private ModSwap() {
	}

	public static void main(String[] args) throws IOException {
		Properties plan = new Properties();
		try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
			plan.load(in);
		}
		long pid = Long.parseLong(plan.getProperty("pid", "-1"));
		Path result = Path.of(plan.getProperty("result"));
		if (pid > 0 && !waitForExit(pid, 180)) {
			writeResult(result, false, plan, "The game didn't close in time.");
			return;
		}
		run(plan, result);
	}

	private static boolean waitForExit(long pid, int seconds) {
		var handle = ProcessHandle.of(pid);
		if (handle.isEmpty()) {
			return true;
		}
		try {
			handle.get().onExit().get(seconds, TimeUnit.SECONDS);
			Thread.sleep(500); // let the OS release file handles
			return true;
		} catch (Exception e) {
			return !handle.get().isAlive();
		}
	}

	private interface Undo {
		void run() throws IOException;
	}

	/** Does the switch described by {@code plan}; returns true on success. Public for tests. */
	public static boolean run(Properties plan, Path result) throws IOException {
		Path mods = Path.of(plan.getProperty("mods"));
		Path game = Path.of(plan.getProperty("game"));
		Path store = Path.of(plan.getProperty("store"));
		Path keptMods = store.resolve("mods");
		Path trash = store.resolve("removing");
		Path configBackup = store.resolve("config-backup");
		Deque<Undo> undo = new ArrayDeque<>();
		Properties state = new Properties();
		state.setProperty("label", plan.getProperty("label", ""));
		state.setProperty("set", plan.getProperty("set", ""));
		try {
			Files.createDirectories(keptMods);
			deleteTree(trash);
			Files.createDirectories(trash);
			// 1. Jars a mod set added: out of the way (deleted only once everything worked).
			for (String name : list(plan, "remove")) {
				Path jar = inside(mods, name);
				if (Files.exists(jar)) {
					Path parked = trash.resolve(name);
					moveWithRetry(jar, parked);
					undo.push(() -> moveWithRetry(parked, jar));
				}
			}
			// 2. The player's own jars that clash: kept in the store.
			List<String> movedOut = new ArrayList<>();
			for (String name : list(plan, "out")) {
				Path jar = inside(mods, name);
				if (Files.exists(jar)) {
					Path kept = inside(keptMods, name);
					moveWithRetry(jar, kept);
					undo.push(() -> moveWithRetry(kept, jar));
					movedOut.add(name);
				}
			}
			// 3. Jars coming home from the store.
			for (String name : list(plan, "back")) {
				Path kept = inside(keptMods, name);
				Path jar = inside(mods, name);
				if (Files.exists(kept) && !Files.exists(jar)) {
					moveWithRetry(kept, jar);
					undo.push(() -> moveWithRetry(jar, kept));
				}
			}
			// 4. The mod set's jars.
			List<String> added = new ArrayList<>();
			for (String source : list(plan, "in")) {
				Path from = Path.of(source);
				if (!Files.isRegularFile(from)) {
					throw new IOException("Missing downloaded file " + from.getFileName());
				}
				Path jar = inside(mods, from.getFileName().toString());
				if (Files.exists(jar)) {
					continue; // already there (the same file name means the same jar)
				}
				Files.copy(from, jar);
				undo.push(() -> Files.deleteIfExists(jar));
				added.add(from.getFileName().toString());
			}
			// 5. Config files: going back puts the player's originals back first; then a pack's are applied (originals kept).
			for (String relative : list(plan, "configRemove")) {
				Path to = inside(game, relative);
				if (Files.exists(to)) {
					Path parked = trash.resolve("config").resolve(relative);
					Files.createDirectories(parked.getParent());
					moveWithRetry(to, parked);
					undo.push(() -> moveWithRetry(parked, to));
				}
			}
			for (String relative : list(plan, "configRestore")) {
				Path backup = inside(configBackup, relative);
				Path to = inside(game, relative);
				if (Files.exists(backup)) {
					Path previous = trash.resolve("config-current").resolve(relative);
					if (Files.exists(to)) {
						Files.createDirectories(previous.getParent());
						Files.copy(to, previous, StandardCopyOption.REPLACE_EXISTING);
						undo.push(() -> Files.copy(previous, to, StandardCopyOption.REPLACE_EXISTING));
					}
					Files.createDirectories(to.getParent());
					Files.copy(backup, to, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			List<String> configCreated = new ArrayList<>();
			List<String> configBacked = new ArrayList<>();
			Path configSource = plan.containsKey("configFrom") ? Path.of(plan.getProperty("configFrom")) : null;
			for (String relative : list(plan, "config")) {
				Path from = inside(configSource, relative);
				Path to = inside(game, relative);
				Path backup = inside(configBackup, relative);
				Files.createDirectories(to.getParent());
				if (Files.exists(to)) {
					Files.createDirectories(backup.getParent());
					Files.copy(to, backup, StandardCopyOption.REPLACE_EXISTING);
					configBacked.add(relative);
					undo.push(() -> Files.copy(backup, to, StandardCopyOption.REPLACE_EXISTING));
				} else {
					configCreated.add(relative);
					undo.push(() -> Files.deleteIfExists(to));
				}
				Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
			}
			// Done: the removed jars and replaced files can go.
			deleteTree(trash);
			List<String> stillBackedUp = list(plan, "config");
			for (String relative : list(plan, "configRestore")) {
				if (!stillBackedUp.contains(relative)) {
					Files.deleteIfExists(inside(configBackup, relative));
				}
			}
			if ("restore".equals(plan.getProperty("kind"))) {
				Files.deleteIfExists(Path.of(plan.getProperty("state")));
			} else {
				store(state, "added", added);
				movedOut.addAll(list(plan, "keepOut")); // still in the store from the set before
				store(state, "movedOut", movedOut);
				store(state, "configCreated", configCreated);
				store(state, "configBacked", configBacked);
				Path stateFile = Path.of(plan.getProperty("state"));
				Files.createDirectories(stateFile.getParent());
				try (OutputStream out = Files.newOutputStream(stateFile)) {
					state.store(out, "JavaHost: the mod set in use, and how to go back");
				}
			}
			writeResult(result, true, plan, "");
			return true;
		} catch (IOException | RuntimeException e) {
			while (!undo.isEmpty()) {
				try {
					undo.pop().run();
				} catch (IOException ignored) {
					// Best effort; keep going so as much as possible is restored.
				}
			}
			writeResult(result, false, plan, e.getMessage() == null ? e.toString() : e.getMessage());
			return false;
		}
	}

	/** {@code name} inside {@code dir}, refusing anything that would land outside it. */
	public static Path inside(Path dir, String name) throws IOException {
		Path path = dir.resolve(name).normalize();
		if (!path.startsWith(dir.normalize()) || path.equals(dir.normalize())) {
			throw new IOException("Unsafe file name " + name);
		}
		return path;
	}

	public static List<String> list(Properties properties, String key) {
		List<String> values = new ArrayList<>();
		for (int i = 0; properties.containsKey(key + "." + i); i++) {
			values.add(properties.getProperty(key + "." + i));
		}
		return values;
	}

	public static void store(Properties properties, String key, List<String> values) {
		for (int i = 0; i < values.size(); i++) {
			properties.setProperty(key + "." + i, values.get(i));
		}
	}

	private static void moveWithRetry(Path from, Path to) throws IOException {
		IOException last = null;
		Files.createDirectories(to.getParent());
		for (int attempt = 0; attempt < 10; attempt++) {
			try {
				Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException e) {
				last = e; // e.g. antivirus briefly holding the file on Windows
				try {
					Thread.sleep(300);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw last;
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static void writeResult(Path result, boolean ok, Properties plan, String reason) throws IOException {
		Properties out = new Properties();
		out.setProperty("ok", String.valueOf(ok));
		out.setProperty("kind", plan.getProperty("kind", "switch"));
		out.setProperty("label", plan.getProperty("label", ""));
		out.setProperty("reason", reason == null ? "" : reason);
		out.setProperty("time", String.valueOf(System.currentTimeMillis()));
		Files.createDirectories(result.getParent());
		try (OutputStream stream = Files.newOutputStream(result)) {
			out.store(stream, "JavaHost mod set switch result");
		}
	}
}

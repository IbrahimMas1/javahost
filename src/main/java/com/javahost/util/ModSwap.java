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

	/** Waits for the game to exit; true once it has (or was never running). */
	static boolean waitForExit(long pid, int seconds) {
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

	/**
	 * Does the switch described by {@code plan}; returns true on success. Public for tests.
	 *
	 * <p>Nothing is ever deleted before the whole switch has worked: files on their way out are parked in
	 * {@code store/removing} and only cleared after the new state is written (the commit point). A failure before that
	 * undoes every step.
	 *
	 * <p>{@code kind}: "switch" (the default) is a mod set; its state records what to undo ({@code added},
	 * {@code movedOut}, config lists). "restore" always deletes the state file on success, whatever is left in the
	 * store. Any other kind (a plain on/off plan such as "toggle"/"vanilla") records in {@code movedOut} exactly what
	 * is in {@code store/mods} afterwards, and deletes the state file when that is empty.
	 *
	 * <p>A name in {@code out} or {@code back} that exists on both sides is never overwritten. Identical files are
	 * merged (the extra copy goes after the commit). Different ones: the copy that loses (the stored one, in both
	 * directions) is moved to {@code store/replaced/<millis>-<name>} and no longer tracked; it is never cleaned up
	 * automatically. The name is listed under {@code conflicts} and the moved file under {@code replaced} (relative to
	 * the store) in the result file.
	 */
	public static boolean run(Properties plan, Path result) throws IOException {
		Path mods = Path.of(plan.getProperty("mods"));
		Path game = Path.of(plan.getProperty("game"));
		Path store = Path.of(plan.getProperty("store"));
		Path keptMods = store.resolve("mods");
		Path trash = store.resolve("removing");
		Path configBackup = store.resolve("config-backup");
		String kind = plan.getProperty("kind", "switch");
		boolean modSet = "switch".equals(kind);
		Deque<Undo> undo = new ArrayDeque<>();
		Properties state = new Properties();
		state.setProperty("label", plan.getProperty("label", ""));
		state.setProperty("set", plan.getProperty("set", ""));
		List<String> conflicts = new ArrayList<>();
		List<String> replaced = new ArrayList<>();
		Path replacedDir = store.resolve("replaced");
		long now = System.currentTimeMillis();
		List<String> staleBackups = new ArrayList<>();
		try {
			Files.createDirectories(keptMods);
			deleteTree(trash); // left by an earlier run that was killed: only removed or duplicate files
			Files.createDirectories(trash);
			// 1. Jars a mod set added: out of the way (deleted only once everything worked).
			for (String name : list(plan, "remove")) {
				Path jar = inside(mods, name);
				if (Files.exists(jar)) {
					Path parked = inside(trash.resolve("jars"), name);
					moveWithRetry(jar, parked);
					undo.push(() -> moveWithRetry(parked, jar));
				}
			}
			// 2. The player's own jars going out: kept in the store.
			List<String> movedOut = new ArrayList<>();
			for (String name : list(plan, "out")) {
				Path jar = inside(mods, name);
				if (!Files.exists(jar)) {
					continue; // e.g. deleted just before (a removal)
				}
				Path kept = inside(keptMods, name);
				if (Files.exists(kept)) {
					if (sameBytes(jar, kept)) {
						// The store already has this very file: the copy in mods/ goes once everything worked.
						Path parked = inside(trash.resolve("same-in-mods"), name);
						moveWithRetry(jar, parked);
						undo.push(() -> moveWithRetry(parked, jar));
						addOnce(movedOut, name);
						continue;
					}
					// A different file of the same name is stored: it makes way (kept in replaced/), this one goes in.
					Path aside = replace(kept, replacedDir, now, name, undo);
					addOnce(conflicts, name);
					replaced.add(store.relativize(aside).toString().replace('\\', '/'));
				}
				moveWithRetry(jar, kept);
				undo.push(() -> moveWithRetry(kept, jar));
				addOnce(movedOut, name);
			}
			// 3. Jars coming home from the store.
			for (String name : list(plan, "back")) {
				Path kept = inside(keptMods, name);
				Path jar = inside(mods, name);
				if (!Files.exists(kept)) {
					continue;
				}
				if (Files.exists(jar)) {
					// Already in mods/ (reinstalled while it was off, say).
					if (sameBytes(jar, kept)) {
						Path parked = inside(trash.resolve("same-in-store"), name);
						moveWithRetry(kept, parked);
						undo.push(() -> moveWithRetry(parked, kept));
					} else {
						// The one in mods/ stays; the stored one makes way (kept in replaced/, no longer tracked).
						Path aside = replace(kept, replacedDir, now, name, undo);
						addOnce(conflicts, name);
						replaced.add(store.relativize(aside).toString().replace('\\', '/'));
					}
					movedOut.remove(name);
					continue;
				}
				moveWithRetry(kept, jar);
				undo.push(() -> moveWithRetry(jar, kept));
				movedOut.remove(name);
			}
			// 4. The mod set's jars (copied next to the target first, so a failed copy never leaves half a jar in mods/).
			List<String> added = new ArrayList<>();
			for (String source : list(plan, "in")) {
				Path from = Path.of(source);
				if (!Files.isRegularFile(from)) {
					throw new IOException("Missing downloaded file " + from.getFileName());
				}
				String name = from.getFileName().toString();
				Path jar = inside(mods, name);
				if (Files.exists(jar)) {
					continue; // already there (the same file name means the same jar)
				}
				Path incoming = inside(trash.resolve("incoming"), name);
				Files.createDirectories(incoming.getParent());
				Files.copy(from, incoming, StandardCopyOption.REPLACE_EXISTING);
				moveWithRetry(incoming, jar);
				undo.push(() -> Files.deleteIfExists(jar));
				added.add(name);
			}
			// 5. Config files: going back puts the player's originals back first; then a pack's are applied (originals kept).
			for (String relative : list(plan, "configRemove")) {
				Path to = inside(game, relative);
				if (Files.exists(to)) {
					Path parked = inside(trash.resolve("config"), relative);
					moveWithRetry(to, parked);
					undo.push(() -> moveWithRetry(parked, to));
				}
			}
			for (String relative : list(plan, "configRestore")) {
				Path backup = inside(configBackup, relative);
				Path to = inside(game, relative);
				if (Files.exists(backup)) {
					if (Files.exists(to)) {
						Path previous = inside(trash.resolve("config-current"), relative);
						Files.createDirectories(previous.getParent());
						Files.copy(to, previous, StandardCopyOption.REPLACE_EXISTING);
						undo.push(() -> Files.copy(previous, to, StandardCopyOption.REPLACE_EXISTING));
					} else {
						undo.push(() -> Files.deleteIfExists(to));
					}
					Files.createDirectories(to.getParent());
					Files.copy(backup, to, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			List<String> configCreated = new ArrayList<>();
			List<String> configBacked = new ArrayList<>();
			Path configSource = plan.containsKey("configFrom") ? Path.of(plan.getProperty("configFrom")) : null;
			for (String relative : list(plan, "config")) {
				if (configSource == null) {
					throw new IOException("The plan has config files but no configFrom");
				}
				Path from = inside(configSource, relative);
				Path to = inside(game, relative);
				Path backup = inside(configBackup, relative);
				Files.createDirectories(to.getParent());
				if (Files.exists(to)) {
					Files.createDirectories(backup.getParent());
					if (Files.exists(backup)) {
						// Keep the backup that was there, so a rollback can put it back.
						Path oldBackup = inside(trash.resolve("config-backup-old"), relative);
						Files.createDirectories(oldBackup.getParent());
						Files.copy(backup, oldBackup, StandardCopyOption.REPLACE_EXISTING);
						undo.push(() -> Files.copy(oldBackup, backup, StandardCopyOption.REPLACE_EXISTING));
					} else {
						undo.push(() -> Files.deleteIfExists(backup));
					}
					Files.copy(to, backup, StandardCopyOption.REPLACE_EXISTING);
					configBacked.add(relative);
					undo.push(() -> Files.copy(backup, to, StandardCopyOption.REPLACE_EXISTING));
				} else {
					configCreated.add(relative);
					undo.push(() -> Files.deleteIfExists(to));
				}
				Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
			}
			List<String> stillBackedUp = list(plan, "config");
			for (String relative : list(plan, "configRestore")) {
				if (!stillBackedUp.contains(relative)) {
					staleBackups.add(relative);
				}
			}
			// 6. The commit point: the new state. Everything before can still be undone; nothing after can fail the switch.
			Path stateFile = Path.of(plan.getProperty("state"));
			if (modSet) {
				store(state, "added", added);
				for (String name : list(plan, "keepOut")) { // still in the store from the set before
					addOnce(movedOut, name);
				}
				store(state, "movedOut", movedOut);
				store(state, "configCreated", configCreated);
				store(state, "configBacked", configBacked);
				writeState(stateFile, state);
			} else if ("restore".equals(kind)) {
				Files.deleteIfExists(stateFile);
			} else {
				List<String> inStore = storeContents(keptMods);
				if (inStore.isEmpty()) {
					Files.deleteIfExists(stateFile);
				} else {
					state.setProperty("kind", kind);
					store(state, "movedOut", inStore);
					writeState(stateFile, state);
				}
			}
		} catch (IOException | RuntimeException e) {
			List<String> notRestored = new ArrayList<>();
			while (!undo.isEmpty()) {
				try {
					undo.pop().run();
				} catch (IOException | RuntimeException failed) {
					// Best effort; keep going so as much as possible is restored.
					notRestored.add(failed.getMessage() == null ? failed.toString() : failed.getMessage());
				}
			}
			String reason = e.getMessage() == null ? e.toString() : e.getMessage();
			if (!notRestored.isEmpty()) {
				reason += " Some files couldn't be put back: " + String.join("; ", notRestored);
			}
			writeResult(result, false, plan, reason, List.of(), List.of());
			return false;
		}
		// Done: the removed jars, duplicates and replaced files can go. Whatever can't is cleared next time.
		try {
			deleteTree(trash);
		} catch (IOException ignored) {
			// Harmless leftovers.
		}
		for (String relative : staleBackups) {
			try {
				Files.deleteIfExists(inside(configBackup, relative));
			} catch (IOException ignored) {
				// Harmless leftover.
			}
		}
		writeResult(result, true, plan, "", conflicts, replaced);
		return true;
	}

	/** What the store holds: file names relative to it, sorted (hidden files such as .DS_Store left out). */
	static List<String> storeContents(Path keptMods) throws IOException {
		if (!Files.isDirectory(keptMods)) {
			return List.of();
		}
		try (var walk = Files.walk(keptMods)) {
			return walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith("."))
					.map(p -> keptMods.relativize(p).toString().replace('\\', '/')).sorted().toList();
		}
	}

	/** Moves a stored copy that lost a clash to {@code replaced/<millis>-<name>} (undoable); returns where it went. */
	private static Path replace(Path kept, Path replacedDir, long now, String name, Deque<Undo> undo) throws IOException {
		String flat = name.replace('/', '_').replace('\\', '_');
		Path aside = inside(replacedDir, now + "-" + flat);
		for (int i = 2; Files.exists(aside); i++) {
			aside = inside(replacedDir, now + "-" + i + "-" + flat);
		}
		Path target = aside;
		moveWithRetry(kept, target);
		undo.push(() -> moveWithRetry(target, kept));
		return target;
	}

	private static boolean sameBytes(Path a, Path b) throws IOException {
		return Files.isRegularFile(a) && Files.isRegularFile(b) && Files.size(a) == Files.size(b) && Files.mismatch(a, b) == -1L;
	}

	private static void addOnce(List<String> list, String name) {
		if (!list.contains(name)) {
			list.add(name);
		}
	}

	/** Written next to the target and moved over it, so a half-written state file can't happen. */
	private static void writeState(Path stateFile, Properties state) throws IOException {
		Files.createDirectories(stateFile.getParent());
		Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
		try (OutputStream out = Files.newOutputStream(temp)) {
			state.store(out, "JavaHost: the mods put aside, and how to go back");
		}
		try {
			Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
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
				Files.move(from, to); // never over an existing file: that file would be lost
				return;
			} catch (java.nio.file.FileAlreadyExistsException e) {
				throw e;
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

	static void writeResult(Path result, boolean ok, Properties plan, String reason) throws IOException {
		writeResult(result, ok, plan, reason, List.of(), List.of());
	}

	private static void writeResult(Path result, boolean ok, Properties plan, String reason, List<String> conflicts,
			List<String> replaced) throws IOException {
		Properties out = new Properties();
		out.setProperty("ok", String.valueOf(ok));
		out.setProperty("kind", plan.getProperty("kind", "switch"));
		out.setProperty("label", plan.getProperty("label", ""));
		out.setProperty("reason", reason == null ? "" : reason);
		out.setProperty("time", String.valueOf(System.currentTimeMillis()));
		store(out, "conflicts", conflicts);
		store(out, "replaced", replaced);
		Files.createDirectories(result.getParent());
		try (OutputStream stream = Files.newOutputStream(result)) {
			out.store(stream, "JavaHost mod set switch result");
		}
	}
}

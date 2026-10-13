package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Swaps jars in mods/ once the game has closed (a running game holds its jars open, on Windows especially).
 * Runs as its own small Java process: {@code java -cp <new javahost jar> com.javahost.util.UpdateSwap <plan>}.
 * Uses only the JDK, so it doesn't need anything from Minecraft.
 *
 * <p>The plan (a .properties file) says which game to wait for, where mods/ is, where to keep the old jars, where to
 * write the result, and each new jar with a pattern for the jars it replaces. If any step fails, everything is put
 * back exactly as it was.
 */
public final class UpdateSwap {
	private UpdateSwap() {
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

	/** Does the swap described by {@code plan}; returns true on success. Public for tests. */
	public static boolean run(Properties plan, Path result) throws IOException {
		Path mods = Path.of(plan.getProperty("mods"));
		Path backup = Path.of(plan.getProperty("backup"));
		String version = plan.getProperty("version");
		List<Path[]> movedOut = new ArrayList<>(); // {original location, backup location}
		List<Path> copiedIn = new ArrayList<>();
		try {
			// Fresh backup folder (the previous one is replaced only once this swap succeeds).
			Path staging = backup.resolveSibling(backup.getFileName() + ".new");
			deleteTree(staging);
			Files.createDirectories(staging);
			for (int i = 0; plan.containsKey("file." + i + ".new"); i++) {
				Path newJar = Path.of(plan.getProperty("file." + i + ".new"));
				Pattern replaces = Pattern.compile(plan.getProperty("file." + i + ".replaces"));
				if (!Files.isRegularFile(newJar)) {
					throw new IOException("Missing downloaded file " + newJar.getFileName());
				}
				try (Stream<Path> list = Files.list(mods)) {
					for (Path old : list.filter(p -> replaces.matcher(p.getFileName().toString()).matches()).toList()) {
						Path kept = staging.resolve(old.getFileName());
						moveWithRetry(old, kept);
						movedOut.add(new Path[] {old, kept});
					}
				}
				Path target = mods.resolve(newJar.getFileName());
				Files.copy(newJar, target, StandardCopyOption.REPLACE_EXISTING);
				copiedIn.add(target);
			}
			deleteTree(backup);
			Files.move(staging, backup);
			// The jars we kept are now in backup/ (not staging/) - nothing more to undo.
			writeResult(result, true, plan, "");
			return true;
		} catch (IOException | RuntimeException e) {
			// Undo: remove what we added, put back what we moved.
			for (Path added : copiedIn) {
				Files.deleteIfExists(added);
			}
			for (Path[] move : movedOut) {
				try {
					moveWithRetry(move[1], move[0]);
				} catch (IOException ignored) {
					// Best effort; keep going so as much as possible is restored.
				}
			}
			writeResult(result, false, plan, e.getMessage() == null ? e.toString() : e.getMessage());
			return false;
		}
	}

	private static void moveWithRetry(Path from, Path to) throws IOException {
		IOException last = null;
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
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static void writeResult(Path result, boolean ok, Properties plan, String reason) throws IOException {
		Properties out = new Properties();
		out.setProperty("ok", String.valueOf(ok));
		out.setProperty("version", plan.getProperty("version", ""));
		out.setProperty("kind", plan.getProperty("kind", "update"));
		out.setProperty("reason", reason == null ? "" : reason);
		out.setProperty("time", String.valueOf(System.currentTimeMillis()));
		Files.createDirectories(result.getParent());
		try (OutputStream stream = Files.newOutputStream(result)) {
			out.store(stream, "JavaHost update result");
		}
	}
}

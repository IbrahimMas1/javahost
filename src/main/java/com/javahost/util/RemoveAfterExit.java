package com.javahost.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Deletes mods the player removed, once the game has closed (a running game holds its jars open, on Windows
 * especially). Runs as its own small Java process: {@code java -cp <javahost> com.javahost.util.RemoveAfterExit
 * <game pid> <mods folder> <list file>}. Uses only the JDK.
 *
 * <p>The list holds file names in the mods folder, one per line. Anything that isn't a plain file name is ignored, so
 * nothing outside the mods folder can be touched. Names that couldn't be deleted stay in the list for next time.
 */
public final class RemoveAfterExit {
	private RemoveAfterExit() {
	}

	public static void main(String[] args) throws IOException {
		long pid = Long.parseLong(args[0]);
		Path mods = Path.of(args[1]);
		Path list = Path.of(args[2]);
		waitForExit(pid, 180);
		removeAll(mods, list);
	}

	private static void waitForExit(long pid, int seconds) {
		var handle = ProcessHandle.of(pid);
		if (handle.isEmpty()) {
			return;
		}
		try {
			handle.get().onExit().get(seconds, TimeUnit.SECONDS);
			Thread.sleep(500); // let the OS release file handles
		} catch (Exception e) {
			// Try anyway; whatever is still locked stays in the list.
		}
	}

	/** A plain file name: no folders, nothing hidden behind "..". */
	static boolean safeName(String name) {
		return !name.isBlank() && name.length() <= 200 && !name.contains("/") && !name.contains("\\") && !name.contains(":")
				&& !name.contains("\0") && !name.equals(".") && !name.equals("..");
	}

	/** Deletes the listed files from {@code mods}. Returns the names it couldn't delete (kept in the list). Public for tests. */
	public static List<String> removeAll(Path mods, Path list) throws IOException {
		if (!Files.exists(list)) {
			return List.of();
		}
		List<String> left = new ArrayList<>();
		for (String line : Files.readAllLines(list)) {
			String name = line.strip();
			if (!safeName(name)) {
				continue;
			}
			if (!delete(mods.resolve(name))) {
				left.add(name);
			}
		}
		if (left.isEmpty()) {
			Files.deleteIfExists(list);
		} else {
			Files.write(list, left);
		}
		return left;
	}

	private static boolean delete(Path file) {
		for (int attempt = 0; attempt < 5; attempt++) {
			try {
				Files.deleteIfExists(file);
				return true;
			} catch (IOException e) {
				try {
					Thread.sleep(400);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
		return false;
	}
}

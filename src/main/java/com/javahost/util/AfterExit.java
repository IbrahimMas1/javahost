package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Everything that has to wait for the game to close, in one small Java process (JDK only, like {@link ModSwap}):
 * {@code java -cp <javahost> com.javahost.util.AfterExit <game pid> <mods folder> <removal list or -> <swap plan or ->}.
 *
 * <p>Waits for the game once, deletes the mods the player removed ({@link RemoveAfterExit}), then does at most one
 * swap ({@link ModSwap}). A jar both removed and named in the plan is already gone when the swap looks for it, which
 * the swap skips. {@code ModSwap.main} and {@code RemoveAfterExit.main} still work on their own for helpers staged by
 * older versions.
 */
public final class AfterExit {
	private AfterExit() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 4) {
			System.err.println("Usage: AfterExit <pid> <mods folder> <removal list or -> <swap plan or ->");
			return;
		}
		run(Long.parseLong(args[0]), Path.of(args[1]), "-".equals(args[2]) ? null : Path.of(args[2]),
				"-".equals(args[3]) ? null : Path.of(args[3]), 180);
	}

	/** Does it all; returns false if the swap failed (or the game didn't close). Public for tests. */
	public static boolean run(long pid, Path mods, Path removeList, Path planFile, int waitSeconds) throws IOException {
		boolean exited = pid <= 0 || ModSwap.waitForExit(pid, waitSeconds);
		if (removeList != null) {
			try {
				RemoveAfterExit.removeAll(mods, removeList); // what can't go stays listed for next time
			} catch (IOException | RuntimeException e) {
				System.err.println("Couldn't remove mods: " + e);
			}
		}
		if (planFile == null || !Files.exists(planFile)) {
			return true;
		}
		Properties plan = new Properties();
		try (InputStream in = Files.newInputStream(planFile)) {
			plan.load(in);
		}
		Path result = Path.of(plan.getProperty("result"));
		if (!exited) {
			ModSwap.writeResult(result, false, plan, "The game didn't close in time.");
			return false;
		}
		return ModSwap.run(plan, result);
	}
}

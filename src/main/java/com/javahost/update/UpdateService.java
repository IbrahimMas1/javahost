package com.javahost.update;

import java.io.IOException;

/**
 * What the rest of JavaHost may ask about updates. The real implementation (com.javahost.client.update) checks GitHub
 * and replaces JavaHost's jar after the game closes; it is only in the GitHub/installer jars. Every method here has a
 * do-nothing default, which is what copies from CurseForge and Modrinth use: those sites and the player's launcher
 * keep them up to date. See {@link Updates}.
 */
public interface UpdateService {
	/** At game start: report how the last update went and start the timer for the first check. */
	default void start() {
	}

	/** Every client tick. */
	default void tick() {
	}

	/** As the game closes: hand a downloaded update (or a rollback) to the swap helper. */
	default void launchOnExit() {
	}

	/** Whether this copy may replace its own jar (only in a game folder the JavaHost installer made). */
	default boolean selfUpdates() {
		return false;
	}

	/** The newer version that's out, or null. */
	default String availableVersion() {
		return null;
	}

	/** The available update can't be skipped. */
	default boolean important() {
		return false;
	}

	default boolean checking() {
		return false;
	}

	/** Checks now; {@code whenDone} runs on the game thread afterwards (straight away when there's nothing to check). */
	default void checkNow(Runnable whenDone) {
		if (whenDone != null) {
			whenDone.run();
		}
	}

	/** Opens the "JavaHost x is out" screen for the available update, over the current screen. */
	default void openUpdateScreen() {
	}

	/** An update or a rollback waits for the game to close. */
	default boolean staged() {
		return false;
	}

	/** "update" or "rollback", or null. */
	default String stagedKind() {
		return null;
	}

	default String stagedVersion() {
		return null;
	}

	default void cancelStaged() {
	}

	/** The JavaHost version kept from the last update, or null. */
	default String previousVersion() {
		return null;
	}

	/** Why the previous version can't be restored, or null if it can. */
	default String rollBackBlockedReason() {
		return null;
	}

	default void stageRollBack() throws IOException {
		throw new IOException("This copy of JavaHost doesn't update itself.");
	}
}

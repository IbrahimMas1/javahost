package com.javahost.client.update;

import com.javahost.client.Toasts;
import com.javahost.client.friends.Notifications;
import com.javahost.util.UpdateManifest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * When to check for updates and when to ask: a few seconds after the game starts, then every 6 hours. The question
 * only ever appears on the title screen or pause menu, never in the middle of play; if the player says "not now",
 * it waits until the next launch.
 */
public final class UpdateChecker {
	private static final long FIRST_CHECK_MS = 5_000;
	private static final long EVERY_MS = 6 * 60 * 60 * 1000L;

	private static volatile UpdateManifest available;
	/** The newest release we know of, newer than us or not (for the minimum safe version). */
	private static volatile UpdateManifest latest;
	/** An important update that was put off: ask again after this time. */
	private static long remindAt;
	private static final long IMPORTANT_REMIND_MS = 10 * 60 * 1000L;
	private static volatile boolean checking;
	private static long startedAt;
	private static long lastCheck;
	/** Asked already this launch (and the player said not now), or the popup is showing. */
	private static boolean asked;
	private static boolean notified;

	private UpdateChecker() {
	}

	public static void start() {
		startedAt = System.currentTimeMillis();
		Updater.reportLastResult();
	}

	/** The newer release, if there is one (and it isn't already downloaded and waiting to install). */
	public static UpdateManifest available() {
		return available;
	}

	public static UpdateManifest latest() {
		return latest;
	}

	/** The available update can't be skipped: we're older than the minimum safe version. */
	public static boolean important() {
		UpdateManifest update = available;
		return update != null && update.requires(com.javahost.JavaHost.VERSION);
	}

	public static boolean checking() {
		return checking;
	}

	/** Checks now (Settings button); the popup opens straight away if something is found. */
	public static void checkNow(Runnable whenDone) {
		asked = false;
		remindAt = 0; // asking now, so an update found asks straight away
		check(whenDone);
	}

	private static void check(Runnable whenDone) {
		if (checking) {
			return;
		}
		checking = true;
		lastCheck = System.currentTimeMillis();
		Updater.fetchLatest().whenComplete((manifest, error) -> Minecraft.getInstance().execute(() -> {
			checking = false;
			if (manifest != null) {
				latest = manifest;
				// A rollback waiting to happen that an important release now rules out is called off.
				String blocked = Updater.rollBackBlockedReason(manifest);
				if (blocked != null && "rollback".equals(Updater.stagedKind())) {
					Updater.cancelStaged();
					Toasts.notify("Going back was cancelled", blocked);
				}
			}
			boolean newer = manifest != null && com.javahost.util.Versions.compare(manifest.version(), com.javahost.JavaHost.VERSION) > 0;
			if (newer && !manifest.version().equals(Updater.stagedVersion())) {
				boolean isNew = available == null || !available.version().equals(manifest.version());
				available = manifest;
				if (isNew) {
					notified = false;
				}
			}
			if (whenDone != null) {
				whenDone.run();
			}
		}));
	}

	/** Called every client tick. */
	public static void tick(Minecraft minecraft) {
		long now = System.currentTimeMillis();
		if (startedAt != 0 && !checking && ((lastCheck == 0 && now - startedAt > FIRST_CHECK_MS) || (lastCheck != 0 && now - lastCheck > EVERY_MS))) {
			check(null);
		}
		UpdateManifest update = available;
		if (update == null || Updater.staged()) {
			return;
		}
		// Ordinary updates are asked about once per launch; important ones again a little later if put off.
		boolean due = important() ? now >= remindAt : !asked;
		if (!due) {
			return;
		}
		Screen screen = minecraft.gui.screen();
		if (screen instanceof TitleScreen || (screen instanceof PauseScreen pause && pause.showsPauseMenu())) {
			asked = true;
			remindAt = now + IMPORTANT_REMIND_MS;
			minecraft.gui.setScreen(new UpdateScreen(screen, update));
		} else if (screen == null && minecraft.level != null && !notified) {
			// Mid-game: just a heads-up; we ask properly at the next menu.
			notified = true;
			Toasts.show("update", (important() ? "Important update: JavaHost " : "JavaHost ") + update.version() + " is ready",
					"You'll be asked next time you open the menu (Esc).");
			Notifications.add(Notifications.Kind.INFO, null, "JavaHost " + update.version() + " is out",
					"Open the pause menu or Settings to update. " + update.notes(), null);
		}
	}

	/** The player chose to keep their version: don't ask again until the next launch. */
	static void keepForNow() {
		asked = true;
		remindAt = System.currentTimeMillis() + IMPORTANT_REMIND_MS;
	}
}

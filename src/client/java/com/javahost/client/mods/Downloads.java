package com.javahost.client.mods;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javahost.JavaHost;
import com.javahost.client.Toasts;
import com.javahost.client.friends.Notifications;
import com.javahost.client.perf.BoostMods;
import com.javahost.util.IoExecutor;
import com.javahost.util.Modrinth;
import net.minecraft.client.Minecraft;

/**
 * Every mod and modpack install goes through here: one at a time, in the order they were asked for, each with its
 * progress for the menu's download indicator, and a notification when it's done.
 */
public final class Downloads {
	public enum Stage { QUEUED, DOWNLOADING, DONE, FAILED, CANCELLED }

	public enum Kind { MOD, PACK }

	/** What an install reports back as it runs, and how it learns it should stop. */
	public interface Reporter {
		void status(String text);

		/** 0 to 1. */
		void fraction(double fraction);

		boolean cancelled();
	}

	/** One install: what it is, and how far it got. */
	public static final class Job implements Reporter {
		public final String id;
		public final String title;
		public final String iconUrl;
		public final Kind kind;
		private final Task task;
		private volatile Stage stage = Stage.QUEUED;
		private volatile double progress;
		private volatile String status = "Waiting...";
		private volatile boolean cancel;
		private volatile boolean needsRestart;

		private Job(String id, String title, String iconUrl, Kind kind, Task task) {
			this.id = id;
			this.title = title;
			this.iconUrl = iconUrl;
			this.kind = kind;
			this.task = task;
		}

		public Stage stage() {
			return stage;
		}

		public double progress() {
			return progress;
		}

		public String statusText() {
			return status;
		}

		/** Downloaded mods only load when Minecraft starts. */
		public boolean needsRestart() {
			return needsRestart;
		}

		public boolean active() {
			return stage == Stage.QUEUED || stage == Stage.DOWNLOADING;
		}

		@Override
		public void status(String text) {
			status = text;
			version++;
		}

		@Override
		public void fraction(double fraction) {
			progress = Math.max(0, Math.min(1, fraction));
		}

		@Override
		public boolean cancelled() {
			return cancel;
		}
	}

	/** The install itself. Returns true when it worked; throws or returns false when it didn't. */
	@FunctionalInterface
	private interface Task {
		boolean run(Job job) throws Exception;
	}

	private static final int MAX = 20;
	private static final List<Job> JOBS = new ArrayList<>();
	/** Jobs run one after another on this chain. */
	private static CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
	private static volatile int version;
	private static int unseen;

	private Downloads() {
	}

	/** A mod from Modrinth (by its Modrinth name), with whatever it needs. */
	public static Job installMod(String id, String slug, String title, String iconUrl) {
		return installMods(id, title, iconUrl, List.of(new BoostMods.BoostMod(slug, slug, title, "", false)));
	}

	/** Several mods as one download, e.g. the Performance tab's "Install all". */
	public static Job installMods(String id, String title, String iconUrl, List<BoostMods.BoostMod> mods) {
		return enqueue(new Job(id, title, iconUrl, Kind.MOD, job -> {
			int count = BoostMods.install(mods, job);
			job.needsRestart = count > 0;
			return count > 0;
		}));
	}

	/** A modpack, saved as a mod set to switch to from Mods, Installed. */
	public static Job installPack(Modrinth.Project project) {
		return enqueue(new Job(project.id(), project.title(), project.iconUrl(), Kind.PACK, job -> {
			Modpacks.install(new Modrinth.Pack(project.id(), project.slug(), project.title(), project.description(), project.downloads(),
					project.author()), job);
			return true;
		}));
	}

	private static synchronized Job enqueue(Job job) {
		Job existing = job(job.id);
		if (existing != null && existing.active()) {
			return existing; // already on its way
		}
		JOBS.removeIf(j -> j.id.equals(job.id));
		JOBS.addFirst(job);
		while (JOBS.size() > MAX) {
			JOBS.removeLast();
		}
		version++;
		tail = tail.thenRunAsync(() -> run(job), IoExecutor.NET).exceptionally(e -> null);
		return job;
	}

	private static void run(Job job) {
		if (job.cancel) {
			finish(job, Stage.CANCELLED);
			return;
		}
		job.stage = Stage.DOWNLOADING;
		job.status("Getting " + job.title + "...");
		boolean worked;
		try {
			worked = job.task.run(job);
		} catch (Exception e) {
			if (!job.cancel) {
				JavaHost.LOGGER.warn("[Downloads] {} failed", job.title, e);
				job.status("Couldn't install: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
			}
			worked = false;
		}
		if (job.cancel) {
			finish(job, Stage.CANCELLED);
		} else if (worked) {
			job.fraction(1);
			job.status(job.kind == Kind.PACK ? "Ready: use it from Mods, Installed" : "Restart Minecraft to use it");
			finish(job, Stage.DONE);
			if (job.kind == Kind.PACK) {
				Toasts.notify(job.title + " is ready", "Use it from Mods, Installed.");
			} else {
				Toasts.notify(job.title + " is ready", "Restart Minecraft to use it.",
						new Notifications.Action("Close game", () -> Minecraft.getInstance().stop()));
			}
		} else {
			if (job.status.startsWith("Getting") || job.status.startsWith("Downloading")) {
				job.status("Couldn't install it");
			}
			finish(job, Stage.FAILED);
			Toasts.notify("Couldn't install " + job.title, job.status);
		}
	}

	private static synchronized void finish(Job job, Stage stage) {
		job.stage = stage;
		if (stage != Stage.CANCELLED) {
			unseen++;
		}
		version++;
	}

	/** Stops a download (or takes it out of the queue). Files it already saved are removed. */
	public static void cancel(Job job) {
		job.cancel = true;
		job.status("Cancelling...");
	}

	/** Takes a finished download off the list. */
	public static synchronized void dismiss(Job job) {
		if (!job.active()) {
			JOBS.remove(job);
			version++;
		}
	}

	public static synchronized void clearFinished() {
		JOBS.removeIf(job -> !job.active());
		version++;
	}

	public static synchronized List<Job> jobs() {
		return List.copyOf(JOBS);
	}

	/** The newest download of this project, or null. */
	public static synchronized Job job(String id) {
		return JOBS.stream().filter(j -> j.id.equals(id)).findFirst().orElse(null);
	}

	public static synchronized boolean active() {
		return JOBS.stream().anyMatch(Job::active);
	}

	/** How far all running and waiting downloads are, together (0 to 1). */
	public static synchronized double overall() {
		List<Job> running = JOBS.stream().filter(Job::active).toList();
		return running.isEmpty() ? 1 : running.stream().mapToDouble(Job::progress).sum() / running.size();
	}

	/** Some finished download needs Minecraft to restart before it works. */
	public static synchronized boolean restartNeeded() {
		return JOBS.stream().anyMatch(j -> j.stage == Stage.DONE && j.needsRestart);
	}

	/** Downloads that finished since the player last opened the list. */
	public static synchronized int unseen() {
		return unseen;
	}

	public static synchronized void markSeen() {
		unseen = 0;
		version++;
	}

	/** Changes whenever anything about the downloads does, for menus that redraw when it changes. */
	public static int version() {
		return version;
	}
}

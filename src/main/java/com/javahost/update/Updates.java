package com.javahost.update;

import java.io.IOException;

import com.javahost.util.Distribution;

/**
 * The one way into JavaHost's self-updater. The updater (com.javahost.client.update, plus util/UpdateSwap and
 * util/UpdateManifest) is only in the jars on GitHub and from the installer: scripts/PublishMod.java deletes it from
 * the copies uploaded to CurseForge and Modrinth. So nothing outside it may name its classes; it's found here by name,
 * only when this copy isn't from a mod site, and when it isn't there every question gets the do-nothing answer.
 */
public final class Updates {
	/** The implementation, looked up by name so the JVM never needs it in a copy that doesn't have it. */
	static final String IMPLEMENTATION = "com.javahost.client.update.SelfUpdater";
	/** The no-updater answers (a mod-site copy, or the updater isn't in this jar). */
	static final UpdateService OFF = new UpdateService() {
	};

	private static volatile UpdateService loaded;

	private Updates() {
	}

	static UpdateService service() {
		// The marker alone is enough: a CurseForge/Modrinth copy never updates itself, even if the updater were there.
		if (Distribution.fromModSite() || simulatedMissing()) {
			return OFF;
		}
		UpdateService service = loaded;
		if (service == null) {
			service = load(IMPLEMENTATION, Updates.class.getClassLoader());
			loaded = service;
		}
		return service;
	}

	/** Tests: use this updater (null: look it up again). */
	static void use(UpdateService service) {
		loaded = service;
	}

	/** Development only: behave as if the updater weren't in the jar (tests). */
	private static boolean simulatedMissing() {
		return Boolean.getBoolean("fabric.development") && Boolean.getBoolean("javahost.test.noSelfUpdate");
	}

	/** The updater from {@code className}, or {@link #OFF} if it isn't there or isn't one. */
	static UpdateService load(String className, ClassLoader loader) {
		try {
			Class<?> type = Class.forName(className, true, loader);
			if (!UpdateService.class.isAssignableFrom(type)) {
				return OFF;
			}
			return (UpdateService) type.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return OFF;
		}
	}

	/** Whether this copy has the self-updater (false for CurseForge and Modrinth copies). */
	public static boolean present() {
		return service() != OFF;
	}

	public static void start() {
		service().start();
	}

	public static void tick() {
		service().tick();
	}

	public static void launchOnExit() {
		service().launchOnExit();
	}

	public static boolean selfUpdates() {
		return service().selfUpdates();
	}

	public static String availableVersion() {
		return service().availableVersion();
	}

	public static boolean important() {
		return service().important();
	}

	public static boolean checking() {
		return service().checking();
	}

	public static void checkNow(Runnable whenDone) {
		service().checkNow(whenDone);
	}

	public static void openUpdateScreen() {
		service().openUpdateScreen();
	}

	public static boolean staged() {
		return service().staged();
	}

	public static String stagedKind() {
		return service().stagedKind();
	}

	public static String stagedVersion() {
		return service().stagedVersion();
	}

	public static void cancelStaged() {
		service().cancelStaged();
	}

	public static String previousVersion() {
		return service().previousVersion();
	}

	public static String rollBackBlockedReason() {
		return service().rollBackBlockedReason();
	}

	public static void stageRollBack() throws IOException {
		service().stageRollBack();
	}
}

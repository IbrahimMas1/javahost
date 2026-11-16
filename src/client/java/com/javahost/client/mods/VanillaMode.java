package com.javahost.client.mods;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.Toasts;
import com.javahost.client.perf.BoostMods;
import com.javahost.util.ModJar;
import com.javahost.util.ModSwap;
import com.javahost.util.ModToggle;
import com.javahost.util.VanillaPlan;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Vanilla mode: every mod but JavaHost, Fabric API and e4mc (and what they need; optionally the performance mods) is
 * put aside in {@code javahost/vanilla/mods} when the game closes, and JavaHost hides itself in the game until the
 * player brings their mods back. Nothing is deleted. "On" means the swap worked: its state file exists.
 *
 * <p>Entering and leaving are staged here and made by the one after-exit helper ({@link AfterExitHelper}): plan kind
 * {@code vanilla} going in, {@code restore} coming back. Jars switched off one by one ({@code javahost/mods-off}) are
 * never touched: they stay off either way.
 */
public final class VanillaMode {
	/** What's waiting for the game to close. */
	public enum Staged { NONE, ENTER, LEAVE }

	private static volatile Boolean active;
	private static volatile Staged staged;
	private static volatile int version;

	private VanillaMode() {
	}

	// ================================================================ files

	public static Path storeDir() {
		return FabricLoader.getInstance().getGameDir().resolve("javahost").resolve("vanilla");
	}

	public static Path storeMods() {
		return storeDir().resolve("mods");
	}

	public static Path stateFile() {
		return storeDir().resolve("state.properties");
	}

	public static Path resultFile() {
		return storeDir().resolve("result.properties");
	}

	public static Path runningFile() {
		return storeDir().resolve("running.properties");
	}

	private static Path stagedFile() {
		return storeDir().resolve("staged.properties");
	}

	// ================================================================ state

	/**
	 * Whether Vanilla mode is on: its state file exists (written by the swap; read once per session, as it only changes
	 * while the game is closed, or when an emptied store is cleared). JavaHost hides itself in the game while it is.
	 */
	public static boolean active() {
		Boolean on = active;
		if (on == null) {
			on = Files.exists(stateFile());
			active = on;
		}
		return on;
	}

	/** What happens when the game closes. */
	public static Staged staged() {
		Staged now = staged;
		if (now == null) {
			now = Staged.NONE;
			try {
				if (Files.exists(stagedFile())) {
					now = "leave".equals(read(stagedFile()).getProperty("action")) ? Staged.LEAVE : Staged.ENTER;
				}
			} catch (IOException e) {
				now = Staged.NONE;
			}
			staged = now;
		}
		return now;
	}

	/** Goes up when Vanilla mode is staged, cancelled, or turned off (for pages that rebuild on change). */
	public static int version() {
		return version;
	}

	private static void changed() {
		staged = null;
		active = null;
		version++;
	}

	/** Why Vanilla mode can't be switched on right now, or null. */
	public static String blockedReason() {
		String set = ModLibrary.setInUse();
		if (set != null) {
			return "You're using " + set + ". Go back to your own mods first.";
		}
		if (ModSets.staged()) {
			String label = ModSets.stagedLabel();
			return "Switching to " + (label == null ? "a mod set" : label) + " when you close the game. Cancel that first.";
		}
		return null;
	}

	/** Mod ids the Performance page installs (Sodium, Lithium and the like). */
	public static List<String> performanceIds() {
		return BoostMods.MODS.stream().filter(BoostMods.BoostMod::recommended).map(BoostMods.BoostMod::modId).toList();
	}

	private static Set<String> keepIds(boolean keepPerformance) {
		return VanillaPlan.keepIds(keepPerformance, performanceIds());
	}

	/** How many jars Vanilla mode would put aside now (from the last read of mods/). */
	public static int toPutAside(boolean keepPerformance) {
		List<ModJar> on = ModLibrary.jarsInMods();
		return VanillaPlan.enter(on, keepIds(keepPerformance), Removals.pendingJars()).out().size();
	}

	/** The jars put aside (from the last read of the store), for the banner and the read-only list. */
	public static List<ModJar> putAside() {
		return ModLibrary.jarsPutAside();
	}

	// ================================================================ switching

	/**
	 * Switches Vanilla mode on when the game closes. Wanted per-mod changes are cleared (one swap per exit). Remembers
	 * the "Keep performance mods" choice.
	 */
	public static void stageEnter(boolean keepPerformance) throws IOException {
		String blocked = blockedReason();
		if (blocked != null) {
			throw new IOException(blocked);
		}
		Config config = Config.get();
		config.vanillaKeepPerformance = keepPerformance;
		config.save();
		ModLibrary.undoAll();
		Properties properties = new Properties();
		properties.setProperty("action", "enter");
		properties.setProperty("keepPerformance", String.valueOf(keepPerformance));
		write(stagedFile(), properties, "JavaHost: Vanilla mode starts when the game closes");
		changed();
	}

	/**
	 * Brings the player's mods back when the game closes. If the store is empty (emptied by hand), there's nothing to
	 * bring back: Vanilla mode simply ends now.
	 */
	public static void stageLeave() throws IOException {
		if (jarNames(storeMods()).isEmpty()) {
			Files.deleteIfExists(stateFile());
			Files.deleteIfExists(stagedFile());
			changed();
			ModLibrary.refresh();
			return;
		}
		Properties properties = new Properties();
		properties.setProperty("action", "leave");
		write(stagedFile(), properties, "JavaHost: your mods come back when the game closes");
		changed();
	}

	/** Changed your mind. */
	public static void cancelStaged() {
		try {
			Files.deleteIfExists(stagedFile());
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't cancel Vanilla mode", e);
		}
		changed();
	}

	// ================================================================ when the game closes

	/** The swap for what's staged, with this game's process id, for the helper; or null when nothing needs to move. */
	static Path writePlan(long pid) {
		Staged now = staged();
		if (now == Staged.NONE) {
			return null;
		}
		try {
			Properties stagedProperties = read(stagedFile());
			Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
			Path store = game.resolve("javahost").resolve("vanilla");
			ModToggle.Plan plan;
			String kind;
			String label;
			if (now == Staged.ENTER) {
				if (blockedReason() != null) {
					return null;
				}
				ModLibrary.refreshNow(); // what's in mods/ right now
				boolean keepPerformance = Boolean.parseBoolean(stagedProperties.getProperty("keepPerformance"));
				plan = VanillaPlan.enter(ModLibrary.jarsInMods(), keepIds(keepPerformance), Removals.pendingJars());
				kind = "vanilla";
				label = "Vanilla mode";
			} else {
				plan = VanillaPlan.leave(jarNames(store.resolve("mods")));
				kind = "restore";
				label = "Your mods";
			}
			if (plan.isEmpty() && now == Staged.ENTER) {
				Files.deleteIfExists(stagedFile());
				return null;
			}
			Properties properties = ModToggle.toProperties(game.resolve("mods"), game, store, store.resolve("state.properties"),
					store.resolve("result.properties"), pid, label, kind, plan);
			write(store.resolve("running.properties"), properties, "JavaHost: Vanilla mode, as the game closes");
			JavaHost.LOGGER.info("[Mods] {} as the game closes: {} put aside, {} back", label, plan.out().size(), plan.back().size());
			return store.resolve("running.properties");
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't write the Vanilla mode plan", e);
			return null;
		}
	}

	/** The helper has the plan. */
	static void handedOver() {
		try {
			Files.deleteIfExists(stagedFile());
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't clear Vanilla mode's staged file", e);
		}
		changed();
	}

	/** Called at startup: says how the last switch went, once. */
	public static void reportLastResult() {
		try {
			if (!Files.exists(resultFile())) {
				return;
			}
			Properties result = read(resultFile());
			Properties plan = Files.exists(runningFile()) ? read(runningFile()) : new Properties();
			boolean entering = "vanilla".equals(result.getProperty("kind"));
			if (Boolean.parseBoolean(result.getProperty("ok"))) {
				if (entering) {
					int count = ModSwap.list(plan, "out").size();
					Toasts.quiet("Vanilla mode is on", com.javahost.client.gui.ui.Draw.count(count, "mod") + " put aside. Bring them back from Mods.");
				} else {
					Toasts.quiet("Your mods are back", "Everything is as you had it.");
				}
				List<String> conflicts = ModSwap.list(result, "conflicts");
				if (!conflicts.isEmpty()) {
					String first = conflicts.getFirst();
					String name = first.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") ? first.substring(0, first.length() - 4) : first;
					Toasts.quiet(conflicts.size() == 1 ? "An older copy of " + name + " was kept" : "Older copies of " + conflicts.size() + " mods were kept",
							"Two files had the same name, so one was put aside in javahost/vanilla/replaced.");
				}
			} else {
				String reason = result.getProperty("reason", "").strip();
				Toasts.notify(entering ? "Couldn't switch to Vanilla mode" : "Couldn't bring your mods back",
						(reason.isEmpty() ? "" : reason + (reason.endsWith(".") ? " " : ". ")) + "Nothing was changed.");
			}
			Files.deleteIfExists(resultFile());
			Files.deleteIfExists(runningFile());
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.debug("[Mods] nothing to report about Vanilla mode", e);
		}
	}

	// ================================================================ helpers

	static List<String> jarNames(Path folder) {
		return ModLibrary.jarNames(folder);
	}

	private static Properties read(Path file) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		}
		return properties;
	}

	private static void write(Path file, Properties properties, String comment) throws IOException {
		Files.createDirectories(file.getParent());
		try (OutputStream out = Files.newOutputStream(file)) {
			properties.store(out, comment);
		}
	}

	// ================================================================ test hooks

	/** For tests: read the files again (after a swap run inside the test). */
	public static void recheckForTests() {
		changed();
	}

	/** For tests: as if the helper had taken the plan. */
	public static void handedOverForTests() {
		handedOver();
	}

	/** For tests: the plan the game would hand the helper now. */
	public static Path writePlanForTests() {
		return writePlan(-1);
	}
}

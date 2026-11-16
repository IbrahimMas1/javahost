package com.javahost.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.Toasts;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.VanillaConfirm;
import com.javahost.client.gui.pages.ModsPage;
import com.javahost.client.mods.ModLibrary;
import com.javahost.client.mods.VanillaMode;
import com.javahost.util.ModChanges;
import com.javahost.util.ModSwap;
import com.javahost.util.ModToggle;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Vanilla mode: made-up mods (all {@code jhtest-*}) in the test game's mods/, the confirm and its count, the pending
 * bar and Undo, refused while a mod set is in use, the swap in and out through the real engine (no exit needed), and
 * everything JavaHost hides while it's on (title screen, pause screen, HUD), the Home and My mods cards and the
 * Settings row. The player's own settings are never changed. Everything written is removed again.
 *
 * <p>{@code JAVAHOST_TEST_ONLY=vanilla1} / {@code vanilla2}: the two halves of {@code scripts/vanilla-test.sh}.
 */
final class VanillaTest {
	private static final int[][] SIZES = {{1710, 1070, 4}, {1280, 800, 3}};
	private static final String PREFIX = ModsToggleTest.PREFIX;

	private VanillaTest() {
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("[JavaHost test] FAILED: " + what);
		}
		JavaHost.LOGGER.info("[JavaHost test] ok: {}", what);
	}

	private record Fake(String file, String id, String name, String json) {
		String jar() {
			return PREFIX + file + ".jar";
		}
	}

	private static Fake fake(String file, String id, String name, String depends, String provides) {
		return new Fake(file, id, name, "{\"schemaVersion\": 1, \"id\": \"" + id + "\", \"version\": \"1.0.0\", \"name\": \"" + name
				+ "\", \"depends\": {" + depends + "}, \"provides\": [" + provides + "]}");
	}

	private static final Fake COZYLIB = fake("cozylib", "cozylib", "Cozylib", "", "");
	private static final Fake GLOWBERRY = fake("glowberry", "glowberry", "Glowberry", "\"cozylib\": \"*\"", "");
	private static final Fake LANTERNS = fake("lanterns-plus", "lanterns_plus", "Lanterns Plus", "", "");
	/** A pretend performance mod (Lithium's id) and the library it needs. */
	private static final Fake LITHIUM = fake("lithium", "lithium", "Lithium", "\"jhtest_speedlib\": \"*\"", "");
	private static final Fake SPEEDLIB = fake("speedlib", "jhtest_speedlib", "Speedlib", "", "");
	/** Answers to e4mc: it stays. */
	private static final Fake RELAY = fake("e4mc", "e4mc", "e4mc", "\"fabric-api\": \"*\"", "\"e4mc_minecraft\"");
	private static final List<Fake> ALL = List.of(COZYLIB, GLOWBERRY, LANTERNS, LITHIUM, SPEEDLIB, RELAY);

	private static void write(Path file, Fake mod) throws IOException {
		Files.createDirectories(file.getParent());
		try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
			zip.write(mod.json().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	private static Path mods() {
		return ModLibrary.modsDir();
	}

	private static List<String> jhtestIn(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(PREFIX) && ModChanges.safeName(n)).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	static void cleanUp(boolean storeExisted) {
		try {
			for (Path dir : List.of(mods(), VanillaMode.storeMods(), VanillaMode.storeDir().resolve("replaced"))) {
				try (Stream<Path> files = Files.list(dir)) {
					for (Path file : files.filter(p -> p.getFileName().toString().contains(PREFIX)).toList()) {
						Files.deleteIfExists(file);
					}
				} catch (IOException ignored) {
					// Not there.
				}
			}
			for (Path file : List.of(VanillaMode.stateFile(), VanillaMode.resultFile(), VanillaMode.runningFile(),
					VanillaMode.storeDir().resolve("staged.properties"))) {
				Files.deleteIfExists(file);
			}
			if (!storeExisted && Files.exists(VanillaMode.storeDir())) {
				try (Stream<Path> walk = Files.walk(VanillaMode.storeDir())) {
					for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
						Files.deleteIfExists(p);
					}
				}
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[JavaHost test] couldn't clean up the Vanilla mode test", e);
		}
	}

	private static String shot(int[] size, String what) {
		return "vanilla-" + size[0] + "-" + what;
	}

	private static void open(ClientGameTestContext context, Tab tab) {
		context.setScreen(() -> new JavaHostScreen(null, tab));
		context.waitTicks(8);
	}

	/** Scrolls the page until a widget with this label is fully on screen. */
	private static void scrollTo(ClientGameTestContext context, String label) {
		for (int i = 0; i < 30; i++) {
			boolean shown = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.anyMatch(c -> c instanceof AbstractWidget w && w.visible && w.getMessage().getString().equals(label)
							&& w.getY() >= 40 && w.getBottom() <= mc.getWindow().getGuiScaledHeight() - 20));
			if (shown) {
				return;
			}
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -1));
			context.waitTicks(6);
		}
	}

	private static boolean javahostWidgetsOn(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.anyMatch(c -> c.getClass().getName().startsWith("com.javahost.")));
	}

	private static Properties read(Path file) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		}
		return properties;
	}

	/** Runs the plan the game would hand the helper, through the real engine; only made-up jars are ever moved. */
	private static Properties swap(ClientGameTestContext context, String kind) throws IOException {
		Path planFile = context.computeOnClient(mc -> VanillaMode.writePlanForTests());
		check(planFile != null, kind + ": a plan is written");
		Properties plan = read(planFile);
		check(kind.equals(plan.getProperty("kind")) && plan.getProperty("store").endsWith("vanilla")
				&& plan.getProperty("state").endsWith(Path.of("vanilla", "state.properties").toString()), kind + ": the plan has its own store and state");
		List<String> out = ModSwap.list(plan, "out");
		List<String> back = ModSwap.list(plan, "back");
		check(out.stream().allMatch(n -> n.startsWith(PREFIX)) && back.stream().allMatch(n -> n.startsWith(PREFIX)),
				kind + ": the test game's mods/ only holds made-up jars, so only those move: " + out + back);
		check(ModSwap.run(plan, Path.of(plan.getProperty("result"))), kind + ": the swap ran");
		context.runOnClient(mc -> {
			VanillaMode.handedOverForTests();
			VanillaMode.recheckForTests();
			ModLibrary.restartForTests();
		});
		return plan;
	}

	// ---------------------------------------------------------------- the test

	static void run(ClientGameTestContext context) {
		boolean storeExisted = Files.exists(VanillaMode.storeDir());
		Config config = Config.get();
		String titleScreen = config.titleScreen;
		boolean pauseDock = config.pauseDock;
		boolean quiet = config.quiet;
		boolean fps = config.fpsOverlay;
		boolean keepPerformance = config.vanillaKeepPerformance;
		int previous = context.computeOnClient(mc -> mc.options.guiScale().get());
		cleanUp(true);
		try {
			// Settings that would show JavaHost everywhere: Vanilla mode must hide it anyway, without changing them.
			config.titleScreen = "FULL";
			config.pauseDock = true;
			config.quiet = false;
			config.fpsOverlay = true;
			config.vanillaKeepPerformance = false;
			for (Fake mod : ALL) {
				write(mods().resolve(mod.jar()), mod);
			}
			context.runOnClient(mc -> {
				ModLibrary.pretendLoadedForTests(Set.of("cozylib", "glowberry", "lanterns_plus", "lithium", "jhtest_speedlib", "e4mc"));
				ModLibrary.restartForTests();
				VanillaMode.recheckForTests();
				com.javahost.client.friends.Notifications.clear();
			});
			Map<String, String> before = ModsToggleTest.fingerprint(mods());
			off(context);
			on(context);
			check(ModsToggleTest.fingerprint(mods()).equals(before), "after Vanilla mode, mods/ is byte-identical to before");
			check(!Files.exists(VanillaMode.stateFile()), "and the state file is gone");
			check(config.titleScreen.equals("FULL") && config.pauseDock && !config.quiet && config.fpsOverlay,
					"the player's own title screen, pause menu, notification and overlay settings were never changed");
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			config.titleScreen = titleScreen;
			config.pauseDock = pauseDock;
			config.quiet = quiet;
			config.fpsOverlay = fps;
			config.vanillaKeepPerformance = keepPerformance;
			context.runOnClient(mc -> {
				ModLibrary.pretendLoadedForTests(null);
				ModLibrary.pretendSetForTests(null);
				VanillaMode.cancelStaged();
				com.javahost.client.friends.Notifications.clear();
			});
			cleanUp(storeExisted);
			context.runOnClient(mc -> {
				VanillaMode.recheckForTests();
				ModLibrary.restartForTests();
			});
			ModsPage.showMine(false);
			context.setScreen(() -> null);
			context.getInput().resizeWindow(854, 480);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(previous);
				TestCompat.resizeGui(mc);
			});
		}
	}

	/** Before: the entry points, the confirm and its count, the pending bar, Undo, refused while a set is in use. */
	private static void off(ClientGameTestContext context) {
		for (int[] size : SIZES) {
			boolean checks = size == SIZES[0];
			JavaHostClientTest.size(context, size[0], size[1], size[2]);
			ModsPage.showMine(true);
			open(context, Tab.MODS);
			scrollTo(context, "Vanilla mode");
			check(JavaHostClientTest.widgetsFit(context), "My mods with the Vanilla mode card fits at " + size[0] + "x" + size[1]);
			context.takeScreenshot(shot(size, "entry"));

			JavaHostClientTest.clickMenu(context, "Vanilla mode");
			VanillaConfirm confirm = context.computeOnClient(mc -> VanillaConfirm.openForTests());
			check(confirm != null, "Vanilla mode asks first");
			String text = confirm.text();
			check(text.contains("5 mods are put aside when you close the game. Nothing is deleted.")
					&& text.contains("can lose them if you open them in Vanilla mode") && text.contains("title screen still says Modded"),
					"the confirm is honest about what happens: " + text);
			if (checks) {
				int[] row = confirm.keepRowOffset();
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(row[0], row[1]));
				check(confirm.keepPerformance() && confirm.text().contains("3 mods are put aside"),
						"keeping performance mods keeps Lithium and the library it needs: " + confirm.text());
				context.waitTicks(2);
				context.takeScreenshot(shot(size, "confirm-keep"));
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(row[0], row[1]));
			}
			context.takeScreenshot(shot(size, "confirm"));
			int[] at = confirm.switchOffset();
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(at[0], at[1]));
			context.waitTicks(4);
			check(context.computeOnClient(mc -> VanillaMode.staged()) == VanillaMode.Staged.ENTER, "Switch stages Vanilla mode for the next exit");
			check(!Config.get().vanillaKeepPerformance, "the choice is remembered (off)");
			open(context, Tab.MODS);
			check(ModsPage.pendingText() != null, "the pending bar shows it");
			check(JavaHostClientTest.hasButton(context, "Undo") && JavaHostClientTest.hasButton(context, "Close game"), "with Undo and Close game");
			check(context.computeOnClient(mc -> ModLibrary.readOnlyReason()) != null, "the per-mod switches wait meanwhile");
			context.takeScreenshot(shot(size, "pending"));
			JavaHostClientTest.clickMenu(context, "Undo");
			check(context.computeOnClient(mc -> VanillaMode.staged()) == VanillaMode.Staged.NONE, "Undo cancels it");

			// Settings, off.
			open(context, Tab.SETTINGS);
			scrollTo(context, "Turn on");
			check(JavaHostClientTest.hasButton(context, "Turn on"), "Settings has a Vanilla mode row");
			context.takeScreenshot(shot(size, "settings-off"));

			if (checks) {
				// A mod set in use: Vanilla mode waits, with the reason.
				context.runOnClient(mc -> ModLibrary.pretendSetForTests("AlexPlays's world"));
				open(context, Tab.MODS);
				scrollTo(context, "Vanilla mode");
				AbstractWidget button = context.computeOnClient(mc -> mc.gui.screen().children().stream()
						.filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals("Vanilla mode"))
						.map(c -> (AbstractWidget) c).findFirst().orElse(null));
				check(button != null && !button.active, "Vanilla mode can't be chosen while a mod set is in use");
				check(String.valueOf(VanillaMode.blockedReason()).startsWith("You're using AlexPlays's world"), "and says why");
				context.takeScreenshot(shot(size, "blocked"));
				boolean refused;
				try {
					VanillaMode.stageEnter(false);
					refused = false;
				} catch (IOException e) {
					refused = true;
				}
				check(refused, "staging it is refused too");
				context.runOnClient(mc -> ModLibrary.pretendSetForTests(null));
			}
		}
	}

	/** On: the swap, everything hidden, the cards, and bringing the mods back. */
	private static void on(ClientGameTestContext context) throws IOException {
		JavaHostClientTest.size(context, 1710, 1070, 4);
		context.runOnClient(mc -> {
			// A waiting per-mod change is cleared by staging Vanilla mode (one swap per exit).
			ModLibrary.apply(ModLibrary.ask(LANTERNS.jar(), false));
		});
		check(context.computeOnClient(mc -> ModLibrary.changeCount()) == 1, "a per-mod change is waiting");
		try {
			VanillaMode.stageEnter(false);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		check(context.computeOnClient(mc -> ModLibrary.changeCount()) == 0, "staging Vanilla mode clears it");
		Properties plan = swap(context, "vanilla");
		check(Set.copyOf(ModSwap.list(plan, "out")).equals(Set.of(COZYLIB.jar(), GLOWBERRY.jar(), LANTERNS.jar(), LITHIUM.jar(), SPEEDLIB.jar())),
				"everything but e4mc is put aside: " + ModSwap.list(plan, "out"));
		check(jhtestIn(mods()).equals(List.of(RELAY.jar())), "only e4mc is left in mods/ (JavaHost and Fabric API are built into the test game)");
		check(Files.exists(VanillaMode.stateFile()) && "vanilla".equals(read(VanillaMode.stateFile()).getProperty("kind")),
				"the state file says what's put aside");
		check(context.computeOnClient(mc -> VanillaMode.active()), "Vanilla mode is on");
		context.runOnClient(mc -> VanillaMode.reportLastResult());
		context.waitTicks(2);
		check(JavaHostClientTest.bellHas(context, "Vanilla mode is on"), "a quiet notice says so");
		check(JavaHostClientTest.toastCount(context, "Vanilla mode is on") == 0, "without a toast, though Quiet notifications is off");
		check(Toasts.isQuiet(), "routine notices stay under the bell while it's on");

		for (int[] size : SIZES) {
			JavaHostClientTest.size(context, size[0], size[1], size[2]);
			// The title screen: just the small button.
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			context.waitTicks(10);
			check(!javahostWidgetsOn(context), "the title screen has no dock, model, name plate or friends panel");
			check(JavaHostClientTest.hasButton(context, "JavaHost"), "only the small JavaHost button");
			context.takeScreenshot(shot(size, "title"));
			// Home and My mods.
			open(context, Tab.HOME);
			check(JavaHostClientTest.hasButton(context, "Bring my mods back"), "Home shows the Vanilla mode card");
			context.takeScreenshot(shot(size, "home"));
			ModsPage.showMine(true);
			open(context, Tab.MODS);
			check(ModsPage.asideShown() == 5, "My mods lists the 5 mods put aside: " + ModsPage.asideShown());
			check(JavaHostClientTest.widgetsFit(context), "My mods in Vanilla mode fits at " + size[0] + "x" + size[1]);
			context.takeScreenshot(shot(size, "mine"));
			open(context, Tab.SETTINGS);
			scrollTo(context, "Bring my mods back");
			context.takeScreenshot(shot(size, "settings-on"));
		}

		// In a world: no pause dock, no HUD; hosting still works.
		JavaHostClientTest.size(context, 1710, 1070, 4);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestCompat.waitForChunks(world);
			context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
			context.waitTicks(8);
			check(!javahostWidgetsOn(context), "the pause screen has nothing of JavaHost, though its setting is on");
			context.takeScreenshot(shot(SIZES[0], "pause"));
			context.setScreen(() -> null);
			context.runOnClient(mc -> check(com.javahost.client.host.HostController.start(mc.getSingleplayerServer()) == null,
					"hosting still works in Vanilla mode"));
			context.waitTicks(20);
			check(com.javahost.client.JavaHostClient.hudStatusPills == 0, "no JavaHost HUD");
			context.takeScreenshot(shot(SIZES[0], "world"));
			context.runOnClient(mc -> com.javahost.client.host.HostController.stop());
		}

		// Bring my mods back, from Home.
		open(context, Tab.HOME);
		JavaHostClientTest.clickMenu(context, "Bring my mods back");
		check(context.computeOnClient(mc -> VanillaMode.staged()) == VanillaMode.Staged.LEAVE, "Bring my mods back waits for the game to close");
		ModsPage.showMine(true);
		open(context, Tab.MODS);
		check(ModsPage.pendingText() != null, "the pending bar shows it");
		context.takeScreenshot(shot(SIZES[0], "leaving"));
		Properties back = swap(context, "restore");
		check(Set.copyOf(ModSwap.list(back, "back")).size() == 5, "every stored jar comes back");
		check(!context.computeOnClient(mc -> VanillaMode.active()), "Vanilla mode is off");
		context.runOnClient(mc -> VanillaMode.reportLastResult());
		context.waitTicks(2);
		check(JavaHostClientTest.bellHas(context, "Your mods are back"), "a quiet notice says the mods are back");
		check(context.computeOnClient(mc -> ModLibrary.changeCount()) == 0, "and nothing else changed");
		check(ModToggle.VANILLA_KEEP.contains("e4mc"), "e4mc is in the keep set");
	}

	// ================================================================ scripts/vanilla-test.sh

	private static Path results() {
		return Path.of(System.getProperty("user.dir")).resolve("vanilla-results.txt");
	}

	private static void scriptCheck(boolean condition, String what) {
		try {
			Files.writeString(results(), (condition ? "ok: " : "FAILED: ") + what + "\n", StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Also in the log.
		}
		check(condition, what);
	}

	/** Launch 1: the made-up mods are loaded; stage Vanilla mode and close. */
	static void firstRun(ClientGameTestContext context) {
		Config.get().tourDone = true;
		context.runOnClient(mc -> ModLibrary.refreshNow());
		scriptCheck(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("glowberry"), "the game loaded Glowberry");
		scriptCheck(!context.computeOnClient(mc -> VanillaMode.active()), "Vanilla mode is off");
		scriptCheck(context.computeOnClient(mc -> VanillaMode.toPutAside(false)) == 2, "two mods would be put aside");
		try {
			VanillaMode.stageEnter(false);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		ModsPage.showMine(true);
		open(context, Tab.MODS);
		scriptCheck(ModsPage.pendingText() != null, "the pending bar shows Vanilla mode");
		context.setScreen(() -> null);
	}

	/** Launch 2: Vanilla mode is on and JavaHost hidden; bring the mods back and close. */
	static void secondRun(ClientGameTestContext context) {
		Config.get().tourDone = true;
		context.waitTicks(20);
		context.runOnClient(mc -> ModLibrary.refreshNow());
		scriptCheck(context.computeOnClient(mc -> VanillaMode.active()), "VanillaMode.active()");
		scriptCheck(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("glowberry"), "Glowberry didn't load");
		scriptCheck(JavaHostClientTest.bellHas(context, "Vanilla mode is on"), "the quiet notice says so");
		context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		context.waitTicks(10);
		scriptCheck(!javahostWidgetsOn(context) && JavaHostClientTest.hasButton(context, "JavaHost"), "the title screen has only the small button");
		context.takeScreenshot("vanilla-script-title");
		open(context, Tab.HOME);
		scriptCheck(JavaHostClientTest.hasButton(context, "Bring my mods back"), "Home shows the Vanilla mode card");
		context.takeScreenshot("vanilla-script-home");
		JavaHostClientTest.clickMenu(context, "Bring my mods back");
		scriptCheck(context.computeOnClient(mc -> VanillaMode.staged()) == VanillaMode.Staged.LEAVE, "bringing the mods back waits for the exit");
		context.setScreen(() -> null);
	}
}

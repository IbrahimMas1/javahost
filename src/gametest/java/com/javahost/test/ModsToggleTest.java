package com.javahost.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.pages.ModsPage;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.mods.ModLibrary;
import com.javahost.client.mods.Removals;
import com.javahost.util.ModChanges;
import com.javahost.util.ModStates;
import com.javahost.util.ModSwap;
import com.javahost.util.ModToggle;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * My mods: a switch per mod. Made-up mod jars (all named {@code jhtest-*}) are written into the test game's mods/ and
 * off store, the game is told which of them count as loaded (the test game loads its own mods from the classpath),
 * and every state, the pending bar, the confirm for a library, read-only while a mod set is in use, search, the swap
 * plan (run through the real engine here, without closing the game) and the notices at the next start are checked.
 * Everything the test writes is removed again; real jars in mods/ are never touched.
 *
 * <p>{@code JAVAHOST_TEST_ONLY=toggle1} / {@code toggle2} are the two halves of {@code scripts/mods-toggle-test.sh},
 * where the game really closes in between and the real after-exit helper moves the jar.
 */
final class ModsToggleTest {
	private static final int[][] SIZES = {{1710, 1070, 4}, {1280, 800, 3}};
	static final String PREFIX = "jhtest-";

	private ModsToggleTest() {
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("[JavaHost test] FAILED: " + what);
		}
		JavaHost.LOGGER.info("[JavaHost test] ok: {}", what);
	}

	// ---------------------------------------------------------------- made-up mods

	/** One made-up mod: file (without the prefix), id, name, version, and the ids it needs. */
	private record Fake(String file, String id, String name, String version, String... depends) {
		String jar() {
			return PREFIX + file + ".jar";
		}
	}

	private static final Fake COZYLIB = new Fake("cozylib", "cozylib", "Cozylib", "2.1.0");
	private static final Fake GLOWBERRY = new Fake("glowberry", "glowberry", "Glowberry", "1.4.2", "cozylib");
	private static final Fake MOSSY = new Fake("mossy-paths", "mossy_paths", "Mossy Paths", "0.9.1", "cozylib");
	private static final Fake CLOUDS = new Fake("cloud-shapes", "cloud_shapes", "Cloud Shapes", "3.0.0");
	private static final Fake LANTERNS = new Fake("lanterns-plus", "lanterns_plus", "Lanterns Plus", "1.2.0");
	private static final Fake POCKETMAP = new Fake("pocket-map", "pocket_map", "Pocket Map", "5.3.1");
	private static final Fake QUIETRAIN = new Fake("quiet-rain", "quiet_rain", "Quiet Rain", "1.0.4");
	private static final Fake SORTER = new Fake("stack-sorter", "stack_sorter", "Stack Sorter", "2.2.0");
	private static final Fake FRESH = new Fake("fresh-breeze", "fresh_breeze", "Fresh Breeze", "0.3.0");
	private static final Fake COMPASS = new Fake("old-compass", "old_compass", "Old Compass", "1.1.0");
	/** Answers to "fabric-api": always on. A low version, so a copy left behind by a killed run is never picked over the real one. */
	private static final Fake FABRIC = new Fake("fabric-api", "fabric-api", "Fabric API", "0.0.0+jhtest");
	private static final Fake NIGHTOWL = new Fake("night-owl", "night_owl", "Night Owl", "1.0.0");
	private static final Fake BERRYPIE = new Fake("berry-pie", "berry_pie", "Berry Pie", "1.0.0", "glowberry");
	/** Not a Fabric mod (no fabric.mod.json). */
	private static final String PLAIN = PREFIX + "shaderfix.jar";

	private static final List<Fake> ON = List.of(COZYLIB, GLOWBERRY, MOSSY, CLOUDS, LANTERNS, POCKETMAP, QUIETRAIN, SORTER, FRESH, COMPASS, FABRIC);
	private static final List<Fake> OFF = List.of(NIGHTOWL, BERRYPIE);
	/** What counts as loaded (Fresh Breeze is new: "installed" after the start). */
	private static final Set<String> LOADED = Set.of("cozylib", "glowberry", "mossy_paths", "cloud_shapes", "lanterns_plus", "pocket_map",
			"quiet_rain", "stack_sorter", "old_compass");

	static void writeJar(Path file, Fake mod, String extra) throws IOException {
		Files.createDirectories(file.getParent());
		StringBuilder json = new StringBuilder("{\"schemaVersion\": 1, \"id\": \"" + mod.id() + "\", \"version\": \"" + mod.version()
				+ "\", \"name\": \"" + mod.name() + "\", \"description\": \"A made-up mod for JavaHost's tests.\", \"environment\": \"*\", \"depends\": {");
		for (int i = 0; i < mod.depends().length; i++) {
			json.append(i == 0 ? "" : ", ").append('"').append(mod.depends()[i]).append("\": \"*\"");
		}
		json.append("}}");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(json.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("README.txt"));
			zip.write(("Made up for a test. " + extra).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	private static void writePlain(Path file) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("shaders/readme.txt"));
			zip.write("Not a Fabric mod.".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	private static Path mods() {
		return ModLibrary.modsDir();
	}

	private static Path store() {
		return ModLibrary.storeMods();
	}

	/** Removes everything a test run wrote (also what a killed run left): only jhtest files and the store's own files. */
	static void cleanUp(boolean storeExisted) {
		for (Path dir : List.of(mods(), store(), ModLibrary.storeDir().resolve("replaced"))) {
			try (Stream<Path> files = Files.list(dir)) {
				for (Path file : files.filter(p -> p.getFileName().toString().contains(PREFIX)).toList()) {
					Files.deleteIfExists(file);
				}
			} catch (IOException ignored) {
				// Not there.
			}
		}
		try {
			Files.deleteIfExists(mods().resolve(".javahost-jhtest-glowberry"));
			for (Path file : List.of(ModLibrary.wantedFile(), ModLibrary.stateFile(), ModLibrary.resultFile(), ModLibrary.runningFile())) {
				Files.deleteIfExists(file);
			}
			Path removeList = mods().getParent().resolve("javahost").resolve("remove-on-exit.txt");
			if (Files.exists(removeList)) {
				List<String> left = Files.readAllLines(removeList).stream().filter(n -> !n.contains(PREFIX)).toList();
				if (left.isEmpty()) {
					Files.delete(removeList);
				} else {
					Files.write(removeList, left);
				}
			}
			if (!storeExisted) {
				deleteTree(ModLibrary.storeDir());
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[JavaHost test] couldn't clean up the mods test", e);
		}
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

	/** Every file directly in a folder: name to SHA-256 (what "byte-identical" is checked with). */
	static Map<String, String> fingerprint(Path dir) {
		Map<String, String> out = new TreeMap<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				out.put(file.getFileName().toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
			}
		} catch (IOException | java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
		return out;
	}

	// ---------------------------------------------------------------- the page

	private static ModStates.Entry entry(String file) {
		return ModLibrary.entries().stream().filter(e -> e.file().equals(file)).findFirst()
				.orElseThrow(() -> new AssertionError("not listed: " + file));
	}

	private static ModStates.State state(Fake mod) {
		return entry(mod.jar()).state();
	}

	private static void open(ClientGameTestContext context) {
		ModsPage.showMine(true);
		context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
		context.waitTicks(8);
	}

	private static AbstractWidget widget(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals(label)).map(c -> (AbstractWidget) c).findFirst()
				.orElse(null));
	}

	/** Clicks a widget with the real mouse, scrolling it into view first. */
	private static void click(ClientGameTestContext context, String label) {
		for (int i = 0; i < 20; i++) {
			boolean shown = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.anyMatch(c -> c instanceof AbstractWidget w && w.visible && w.getMessage().getString().equals(label)
							&& w.getY() >= 40 && w.getBottom() <= mc.getWindow().getGuiScaledHeight() - 20));
			if (shown) {
				break;
			}
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -1));
			context.waitTicks(6);
		}
		JavaHostClientTest.clickMenu(context, label);
	}

	private static List<ToggleSwitch> switches(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream().filter(c -> c instanceof ToggleSwitch).map(c -> (ToggleSwitch) c)
				.toList());
	}

	// ---------------------------------------------------------------- the test

	static void run(ClientGameTestContext context, boolean quick) {
		boolean storeExisted = Files.exists(ModLibrary.storeDir());
		cleanUp(true);
		boolean quiet = Config.get().quiet;
		int previous = context.computeOnClient(mc -> mc.options.guiScale().get());
		try {
			Config.get().quiet = true;
			setUp();
			states(context);
			for (int[] size : SIZES) {
				JavaHostClientTest.size(context, size[0], size[1], size[2]);
				screens(context, size, size == SIZES[0]);
			}
			JavaHostClientTest.size(context, 1710, 1070, 4);
			planAndSwap(context);
			notices(context);
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			Config.get().quiet = quiet;
			context.runOnClient(mc -> {
				ModLibrary.pretendLoadedForTests(null);
				ModLibrary.pretendSetForTests(null);
				ModLibrary.undoAll();
				com.javahost.client.friends.Notifications.clear();
			});
			cleanUp(storeExisted);
			context.runOnClient(mc -> ModLibrary.restartForTests());
			ModsPage.showMineSearch("");
			ModsPage.showMine(false);
			context.setScreen(() -> null);
			context.getInput().resizeWindow(854, 480);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(previous);
				TestCompat.resizeGui(mc);
			});
		}
	}

	private static void setUp() throws IOException {
		for (Fake mod : ON) {
			writeJar(mods().resolve(mod.jar()), mod, "");
		}
		writePlain(mods().resolve(PLAIN));
		for (Fake mod : OFF) {
			writeJar(store().resolve(mod.jar()), mod, "");
		}
		Files.writeString(mods().resolve(".javahost-jhtest-glowberry"), GLOWBERRY.jar()); // JavaHost installed it
		Removals.remove(COMPASS.jar(), null);
		// Everything but Fresh Breeze was there when the game "started".
		Files.move(mods().resolve(FRESH.jar()), mods().getParent().resolve(FRESH.jar()));
		ModLibrary.pretendLoadedForTests(LOADED);
		ModLibrary.restartForTests();
		Files.move(mods().getParent().resolve(FRESH.jar()), mods().resolve(FRESH.jar()));
		ModLibrary.refreshNow();
	}

	private static void states(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			check(state(COZYLIB) == ModStates.State.ON && state(GLOWBERRY) == ModStates.State.ON, "loaded mods are on");
			check(state(FRESH) == ModStates.State.NEW, "a mod put in since the start is new");
			check(state(COMPASS) == ModStates.State.REMOVING, "a mod marked for removal says so");
			check(state(NIGHTOWL) == ModStates.State.OFF && !entry(NIGHTOWL.jar()).inMods(), "a jar in the off store is off");
			check(entry(FABRIC.jar()).isProtected() && entry(FABRIC.jar()).protectedReason().equals("JavaHost needs it"), "Fabric API always stays on");
			check(entry(GLOWBERRY.jar()).installedByJavaHost() && !entry(COZYLIB.jar()).installedByJavaHost(), "mods JavaHost installed are known by their marker");
			check(entry(PLAIN).name().equals(PLAIN) && entry(PLAIN).state() == ModStates.State.ON, "a jar that isn't a Fabric mod is listed by its file name");
			List<ModStates.Entry> all = ModLibrary.entries();
			int firstOff = 0;
			while (firstOff < all.size() && all.get(firstOff).inMods()) {
				firstOff++;
			}
			check(all.subList(firstOff, all.size()).stream().noneMatch(ModStates.Entry::inMods), "on mods come first, then off ones");
		});
	}

	private static String shot(int[] size, String what) {
		return "mods-" + size[0] + "-" + what;
	}

	private static void screens(ClientGameTestContext context, int[] size, boolean checks) {
		ModsPage.showMineSearch("");
		open(context);
		check(JavaHostClientTest.widgetsFit(context), "My mods fits at " + size[0] + "x" + size[1]);
		if (checks) {
			check("2 changes waiting".equals(ModsPage.pendingText()), "the pending bar counts the new mod and the removal (" + ModsPage.pendingText() + ")");
			check("New: loads next start".equals(ModsPage.captionOf(FRESH.jar()).replace("0.3.0 · ", "")), "a new mod's caption");
			check(ModsPage.captionOf(NIGHTOWL.jar()).endsWith("Off") && ModsPage.captionOf(FABRIC.jar()).endsWith("Always on"), "off and always-on captions");
			check(widget(context, "Keep") != null, "a mod being removed has Keep");
			AbstractWidget fabric = widget(context, FABRIC.name());
			check(fabric != null && !fabric.active, "Fabric API's switch can't be used");
		}
		context.takeScreenshot(shot(size, "list"));

		// A plain switch: off when the game closes; the bar counts it; Undo clears it.
		click(context, CLOUDS.name());
		context.waitTicks(4);
		if (checks) {
			check(context.computeOnClient(mc -> state(CLOUDS) == ModStates.State.TURNING_OFF), "switching a mod off waits for the game to close");
			check(ModsPage.captionOf(CLOUDS.jar()).endsWith("Turns off when you close the game"), "its caption says when");
			check("3 changes waiting".equals(ModsPage.pendingText()), "the pending bar counts it");
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot(shot(size, "pending"));
		click(context, "Undo");
		context.waitTicks(4);
		if (checks) {
			check(context.computeOnClient(mc -> ModLibrary.changeCount() == 0 && state(CLOUDS) == ModStates.State.ON), "Undo forgets the change");
			check(context.computeOnClient(mc -> state(COMPASS) == ModStates.State.ON), "Undo also keeps a mod marked for removal");
			check("1 change waiting".equals(ModsPage.pendingText()) && !JavaHostClientTest.hasButton(context, "Undo"),
					"only the new mod waits, and there's nothing to undo");
		}
		context.runOnClient(mc -> {
			try {
				Removals.remove(COMPASS.jar(), null);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			ModLibrary.refreshNow();
		});

		// A library: asks first, naming what goes with it.
		open(context);
		click(context, COZYLIB.name());
		context.waitTicks(4);
		String confirm = ModsPage.confirmText();
		check(confirm != null && confirm.contains("Glowberry") && confirm.contains("Mossy Paths"), "turning off a library asks first: " + confirm);
		check(context.computeOnClient(mc -> state(COZYLIB) == ModStates.State.ON), "nothing changes before Confirm");
		context.takeScreenshot(shot(size, "confirm"));
		int[] at = ModsPage.confirmButtonOffset();
		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(at[0], at[1]));
		context.waitTicks(4);
		if (checks) {
			check(context.computeOnClient(mc -> ModLibrary.changesForTests().off().equals(Set.of(COZYLIB.jar(), GLOWBERRY.jar(), MOSSY.jar()))),
					"Confirm turns off the library and the mods that need it");
			// Turning on a mod whose library is off brings the library back too.
			ModLibrary.Ask ask = context.computeOnClient(mc -> ModLibrary.ask(BERRYPIE.jar(), true));
			check(ask.needsConfirm() && ask.files().containsAll(List.of(BERRYPIE.jar(), GLOWBERRY.jar(), COZYLIB.jar())),
					"turning on a mod whose library is off asks to bring it back: " + ask.files());
			ModLibrary.Ask fabric = context.computeOnClient(mc -> ModLibrary.ask(FABRIC.jar(), false));
			check(!fabric.ok(), "a protected mod can't be switched off: " + fabric.refusal());
		}
		context.runOnClient(mc -> ModLibrary.undoAll());

		// A row's menu.
		open(context);
		click(context, "More for " + GLOWBERRY.name());
		context.waitTicks(4);
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()), "a row's more menu opens");
		context.takeScreenshot(shot(size, "more"));
		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());

		// While a mod set is in use, the switches are read-only.
		context.runOnClient(mc -> ModLibrary.pretendSetForTests("AlexPlays's world"));
		open(context);
		if (checks) {
			check(ModsPage.readOnlyNote() != null && ModsPage.readOnlyNote().startsWith("You're using AlexPlays's world"), "read-only says why");
			check(switches(context).stream().noneMatch(t -> t.active), "every switch is read-only while a mod set is in use");
			check(!context.computeOnClient(mc -> ModLibrary.ask(CLOUDS.jar(), false).ok()), "and no change can be asked for");
		}
		check(JavaHostClientTest.widgetsFit(context), "read-only fits at " + size[0] + "x" + size[1]);
		context.takeScreenshot(shot(size, "readonly"));
		context.runOnClient(mc -> ModLibrary.pretendSetForTests(null));

		// Search (more than 12 mods).
		ModsPage.showMineSearch("berry");
		open(context);
		if (checks) {
			check(ModsPage.shownFiles().equals(List.of(GLOWBERRY.jar(), BERRYPIE.jar())), "search finds Glowberry and Berry Pie: " + ModsPage.shownFiles());
		}
		check(JavaHostClientTest.widgetsFit(context), "search fits at " + size[0] + "x" + size[1]);
		context.takeScreenshot(shot(size, "search"));
		ModsPage.showMineSearch("");
	}

	private static Properties read(Path file) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		}
		return properties;
	}

	private static List<String> jarNames(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).filter(ModChanges::safeName).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Runs the plan the game would hand the helper, through the real engine (no exit needed). */
	private static boolean swap(ClientGameTestContext context, String what) throws IOException {
		Path planFile = context.computeOnClient(mc -> ModLibrary.writePlan(-1));
		check(planFile != null, what + ": a plan is written");
		Properties plan = read(planFile);
		check("toggle".equals(plan.getProperty("kind")) && plan.getProperty("store").endsWith("mods-off")
				&& plan.getProperty("state").endsWith("state.properties") && plan.getProperty("result").endsWith("result.properties"),
				what + ": the plan is a toggle with its own store, state and result");
		// Exactly what ModToggle.plan gives for the folders as they are and the wanted changes.
		ModChanges wanted = context.computeOnClient(mc -> ModLibrary.changesForTests());
		List<String> on = new ArrayList<>(jarNames(mods()));
		on.removeAll(Removals.pendingJars());
		List<String> off = jarNames(store());
		ModToggle.Plan expected = ModToggle.plan(on, off, wanted.wantOff(on, off));
		check(ModSwap.list(plan, "out").equals(expected.out()) && ModSwap.list(plan, "back").equals(expected.back()),
				what + ": the plan is exactly ModToggle.plan's (" + expected + ")");
		boolean ok = ModSwap.run(plan, Path.of(plan.getProperty("result")));
		context.runOnClient(mc -> {
			ModLibrary.handedOverForTests();
			ModLibrary.restartForTests();
		});
		return ok;
	}

	private static void planAndSwap(ClientGameTestContext context) throws IOException {
		context.runOnClient(mc -> {
			try {
				Removals.keep(COMPASS.jar(), null);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			ModLibrary.refreshNow();
		});
		Map<String, String> before = fingerprint(mods());
		Map<String, String> storeBefore = fingerprint(store());
		// Off: the library and what needs it; on: Night Owl.
		context.runOnClient(mc -> {
			ModLibrary.apply(ModLibrary.ask(COZYLIB.jar(), false));
			ModLibrary.apply(ModLibrary.ask(NIGHTOWL.jar(), true));
		});
		check(swap(context, "first swap"), "the swap ran");
		check(Files.exists(store().resolve(COZYLIB.jar())) && Files.exists(store().resolve(GLOWBERRY.jar())) && !Files.exists(mods().resolve(MOSSY.jar())),
				"the jars moved to the off store");
		check(Files.exists(mods().resolve(NIGHTOWL.jar())), "Night Owl came back to mods/");
		Properties state = read(ModLibrary.stateFile());
		check(new java.util.TreeSet<>(ModSwap.list(state, "movedOut")).equals(new java.util.TreeSet<>(jarNames(store()))),
				"the state file lists what the store holds");
		context.runOnClient(mc -> {
			ModLibrary.reportLastResult();
			ModLibrary.refreshNow();
		});
		context.waitTicks(4);
		check(context.computeOnClient(mc -> state(COZYLIB) == ModStates.State.OFF && state(GLOWBERRY) == ModStates.State.OFF),
				"the list shows them off afterwards");
		check(JavaHostClientTest.bellHas(context, "3 mods turned off, 1 on"), "a quiet notice says what changed");
		check(JavaHostClientTest.toastCount(context, "3 mods turned off, 1 on") == 0, "and it's quiet (no toast)");
		check(!Files.exists(ModLibrary.resultFile()), "the result is read once");
		for (int[] size : SIZES) {
			JavaHostClientTest.size(context, size[0], size[1], size[2]);
			context.setScreen(() -> new JavaHostScreen(null, Tab.NOTIFICATIONS));
			context.waitTicks(8);
			context.takeScreenshot(shot(size, "notice"));
			open(context);
			context.takeScreenshot(shot(size, "after"));
		}
		JavaHostClientTest.size(context, 1710, 1070, 4);

		// Back on: mods/ is exactly as it started.
		context.runOnClient(mc -> {
			ModLibrary.apply(ModLibrary.ask(GLOWBERRY.jar(), true)); // brings Cozylib with it
			ModLibrary.apply(ModLibrary.ask(MOSSY.jar(), true));
			ModLibrary.apply(ModLibrary.ask(NIGHTOWL.jar(), false));
		});
		check(swap(context, "second swap"), "the swap back ran");
		check(fingerprint(mods()).equals(before), "mods/ is byte-identical to how it started");
		check(fingerprint(store()).equals(storeBefore), "and the store holds just what it did");
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.clear());
		context.runOnClient(mc -> ModLibrary.reportLastResult());
	}

	private static void notices(ClientGameTestContext context) throws IOException {
		// A clash: a different file of the same name is already in the store.
		writeJar(store().resolve(LANTERNS.jar()), LANTERNS, "an older copy");
		context.runOnClient(mc -> {
			ModLibrary.refreshNow();
			com.javahost.client.friends.Notifications.clear();
		});
		Map<String, String> lanternsInMods = Map.of(LANTERNS.jar(), fingerprint(mods()).get(LANTERNS.jar()));
		context.runOnClient(mc -> ModLibrary.apply(ModLibrary.ask(LANTERNS.jar(), false)));
		check(swap(context, "clash"), "a swap with a clash still works");
		check(fingerprint(store()).get(LANTERNS.jar()).equals(lanternsInMods.get(LANTERNS.jar())), "the jar from mods/ is the one kept in the store");
		check(Files.list(ModLibrary.storeDir().resolve("replaced")).anyMatch(p -> p.getFileName().toString().endsWith(LANTERNS.jar())),
				"the older copy was put aside in replaced/");
		context.runOnClient(mc -> ModLibrary.reportLastResult());
		context.waitTicks(2);
		check(JavaHostClientTest.bellHas(context, "An older copy of jhtest-lanterns-plus was kept"), "the clash is one quiet notice");

		// A failure: a real toast with the plain reason.
		Properties failed = new Properties();
		failed.setProperty("ok", "false");
		failed.setProperty("kind", "toggle");
		failed.setProperty("reason", "A file was in use.");
		try (OutputStream out = Files.newOutputStream(ModLibrary.resultFile())) {
			failed.store(out, null);
		}
		context.setScreen(() -> null); // toasts wait while the menu is open
		context.runOnClient(mc -> ModLibrary.reportLastResult());
		context.waitTicks(2);
		check(JavaHostClientTest.bellHas(context, "Couldn't switch mods on and off"), "a failure is kept under the bell");
		check(context.computeOnClient(mc -> com.javahost.client.friends.Notifications.items().stream()
				.anyMatch(n -> n.text().equals("A file was in use. Nothing was changed."))), "with the plain reason");
		check(JavaHostClientTest.toastCount(context, "Couldn't switch mods on and off") == 1, "and a toast");
		context.waitTicks(4);
	}

	// ================================================================ the two-run check (scripts/mods-toggle-test.sh)

	/** Shared with the script: the folder it watches. */
	private static Path shared() {
		return Path.of(System.getProperty("user.dir")).resolve("mods-toggle-results.txt");
	}

	private static void result(String line) {
		try {
			Files.writeString(shared(), line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Also in the log.
		}
	}

	private static void scriptCheck(boolean condition, String what) {
		result((condition ? "ok: " : "FAILED: ") + what);
		check(condition, what);
	}

	/** First run: Glowberry and Cozylib really loaded; turn Glowberry off and close normally. */
	static void firstRun(ClientGameTestContext context) {
		Config.get().tourDone = true;
		context.runOnClient(mc -> ModLibrary.refreshNow());
		scriptCheck(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("glowberry"), "the game loaded Glowberry from mods/");
		scriptCheck(context.computeOnClient(mc -> state(GLOWBERRY) == ModStates.State.ON && state(COZYLIB) == ModStates.State.ON),
				"My mods shows Glowberry and Cozylib on (matched by the jar Fabric loaded)");
		ModLibrary.Ask ask = context.computeOnClient(mc -> ModLibrary.setWanted(GLOWBERRY.jar(), false));
		scriptCheck(ask.ok() && !ask.needsConfirm(), "nothing needs Glowberry, so it's switched straight away");
		open(context);
		scriptCheck("1 change waiting".equals(ModsPage.pendingText()), "the pending bar shows it");
		context.takeScreenshot("mods-toggle-run1");
		context.setScreen(() -> null);
		// The test ends; the game closes normally and the helper moves the jar.
	}

	/** Second run: Glowberry is off and said so; turn it back on and close. */
	static void secondRun(ClientGameTestContext context) {
		Config.get().tourDone = true;
		Config.get().quiet = true;
		context.waitTicks(20);
		context.runOnClient(mc -> ModLibrary.refreshNow());
		scriptCheck(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("glowberry"), "Glowberry didn't load this time");
		scriptCheck(context.computeOnClient(mc -> state(GLOWBERRY) == ModStates.State.OFF), "My mods shows Glowberry off");
		scriptCheck(JavaHostClientTest.bellHas(context, "1 mod turned off"), "the quiet notice says so");
		ModLibrary.Ask ask = context.computeOnClient(mc -> ModLibrary.setWanted(GLOWBERRY.jar(), true));
		scriptCheck(ask.ok() && !ask.needsConfirm(), "Cozylib is on, so Glowberry comes back on its own");
		open(context);
		context.takeScreenshot("mods-toggle-run2");
		context.setScreen(() -> null);
	}
}

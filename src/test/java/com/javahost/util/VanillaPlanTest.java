package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaPlanTest {
	@TempDir
	Path dir;

	private final ModJar javahost = ModToggleTest.mod("javahost-2.0.0.jar", "javahost", "fabric-api");
	private final ModJar fabricApi = ModToggleTest.mod("fabric-api.jar", "fabric-api", List.of(), List.of("fabric"));
	private final ModJar e4mc = ModToggleTest.mod("e4mc-6.2.3.jar", "e4mc", List.of("fabric-api"), List.of("e4mc_minecraft"));
	private final ModJar sodium = ModToggleTest.mod("sodium.jar", "sodium", "fabric");
	private final ModJar lithium = ModToggleTest.mod("lithium.jar", "lithium");
	private final ModJar speedlib = ModToggleTest.mod("speedlib.jar", "speedlib", List.of(), List.of("speed_api"));
	private final ModJar ferritecore = ModToggleTest.mod("ferritecore.jar", "ferritecore", "speed_api");
	private final ModJar glowberry = ModToggleTest.mod("glowberry.jar", "glowberry", "cozylib");
	private final ModJar cozylib = ModToggleTest.mod("cozylib.jar", "cozylib");
	private final ModJar plain = ModJar.parse("shaderfix.jar", "not json");

	private List<ModJar> all() {
		return List.of(javahost, fabricApi, e4mc, sodium, lithium, speedlib, ferritecore, glowberry, cozylib, plain);
	}

	private static final List<String> PERFORMANCE = List.of("sodium", "lithium", "ferritecore", "immediatelyfast", "entityculling");

	@Test
	void onlyJavaHostAndWhatItNeedsStay() {
		Set<String> keep = VanillaPlan.keepIds(false, PERFORMANCE);
		assertEquals(ModToggle.VANILLA_KEEP, keep);
		assertEquals(Set.of("javahost-2.0.0.jar", "fabric-api.jar", "e4mc-6.2.3.jar"), VanillaPlan.keepFiles(all(), keep));
		assertEquals(List.of("sodium.jar", "lithium.jar", "speedlib.jar", "ferritecore.jar", "glowberry.jar", "cozylib.jar", "shaderfix.jar"),
				VanillaPlan.putAside(all(), keep), "every other jar goes, including ones that aren't Fabric mods");
	}

	@Test
	void keepingPerformanceModsKeepsWhatTheyNeedToo() {
		Set<String> keep = VanillaPlan.keepIds(true, PERFORMANCE);
		assertEquals(List.of("glowberry.jar", "cozylib.jar", "shaderfix.jar"), VanillaPlan.putAside(all(), keep),
				"FerriteCore needs speed_api, which SpeedLib provides: it stays too");
	}

	@Test
	void aKeptModsDependencyIsFoundByWhatItProvides() {
		ModJar helper = ModToggleTest.mod("relay-helper.jar", "relay_helper", List.of(), List.of("e4mc_net"));
		ModJar relay = ModToggleTest.mod("e4mc.jar", "e4mc", "e4mc_net");
		Set<String> kept = VanillaPlan.keepFiles(List.of(relay, helper, cozylib), ModToggle.VANILLA_KEEP);
		assertEquals(Set.of("e4mc.jar", "relay-helper.jar"), kept);
	}

	@Test
	void matchedByIdNotFileName() {
		// JavaHost updated itself: the new jar has another name; both copies are JavaHost and stay.
		ModJar updated = ModToggleTest.mod("javahost-2.0.1+26.3.jar", "javahost", "fabric-api");
		ModJar renamed = ModToggleTest.mod("my-api-copy.jar", "fabric-api");
		assertEquals(List.of(), VanillaPlan.putAside(List.of(javahost, updated, renamed), ModToggle.VANILLA_KEEP));
		// And a jar merely named like JavaHost is put aside.
		ModJar impostor = ModToggleTest.mod("javahost-addon.jar", "javahost_addon");
		assertEquals(List.of("javahost-addon.jar"), VanillaPlan.putAside(List.of(javahost, impostor), ModToggle.VANILLA_KEEP));
	}

	@Test
	void enterSkipsJarsBeingRemovedAndBringsNothingBack() {
		ModToggle.Plan plan = VanillaPlan.enter(all(), ModToggle.VANILLA_KEEP, Set.of("cozylib.jar"));
		assertFalse(plan.out().contains("cozylib.jar"));
		assertTrue(plan.out().contains("glowberry.jar"));
		assertEquals(List.of(), plan.back());
	}

	@Test
	void leaveBringsBackEveryStoredJar() {
		ModToggle.Plan plan = VanillaPlan.leave(List.of("glowberry.jar", "cozylib.jar", "../escape.jar", "glowberry.jar"));
		assertEquals(List.of(), plan.out());
		assertEquals(List.of("glowberry.jar", "cozylib.jar"), plan.back());
		assertTrue(VanillaPlan.leave(List.of()).isEmpty(), "an emptied store: nothing to bring back");
	}

	/** In and out through the real engine: mods/ ends byte-identical and the state file is gone. */
	@Test
	void aRoundTripLeavesModsAsTheyWere() throws IOException {
		Path game = dir.resolve("game");
		Path mods = game.resolve("mods");
		Path store = game.resolve("javahost").resolve("vanilla");
		Files.createDirectories(mods);
		for (ModJar jar : all()) {
			Files.writeString(mods.resolve(jar.file()), "bytes of " + jar.file());
		}
		var before = ModSwapTest.fingerprint(mods);
		Path state = store.resolve("state.properties");
		Path result = store.resolve("result.properties");
		Properties enter = ModToggle.toProperties(mods, game, store, state, result, -1, "Vanilla mode", "vanilla",
				VanillaPlan.enter(all(), ModToggle.VANILLA_KEEP, Set.of()));
		assertTrue(ModSwap.run(enter, result));
		assertTrue(Files.exists(state), "on: the state file exists");
		assertEquals(Set.of("javahost-2.0.0.jar", "fabric-api.jar", "e4mc-6.2.3.jar"), ModSwapTest.fingerprint(mods).keySet());
		List<String> stored = ModSwap.storeContents(store.resolve("mods"));
		Properties leave = ModToggle.toProperties(mods, game, store, state, result, -1, "Your mods", "restore", VanillaPlan.leave(stored));
		assertTrue(ModSwap.run(leave, result));
		assertEquals(before, ModSwapTest.fingerprint(mods));
		assertFalse(Files.exists(state), "off: the state file is gone");
	}
}

package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ModToggleTest {
	static ModJar mod(String file, String id, List<String> depends, List<String> provides) {
		StringBuilder json = new StringBuilder("{\"id\": \"" + id + "\", \"name\": \"" + id.substring(0, 1).toUpperCase() + id.substring(1) + "\", \"depends\": {");
		for (int i = 0; i < depends.size(); i++) {
			json.append(i == 0 ? "" : ", ").append('"').append(depends.get(i)).append("\": \"*\"");
		}
		json.append("}, \"provides\": [");
		for (int i = 0; i < provides.size(); i++) {
			json.append(i == 0 ? "" : ", ").append('"').append(provides.get(i)).append('"');
		}
		return ModJar.parse(file, json.append("]}").toString());
	}

	static ModJar mod(String file, String id, String... depends) {
		return mod(file, id, List.of(depends), List.of());
	}

	private final ModJar javahost = mod("javahost.jar", "javahost", "fabric-api");
	private final ModJar fabricApi = mod("fabric-api.jar", "fabric-api", List.of(), List.of("fabric"));
	private final ModJar cozylib = mod("cozylib.jar", "cozylib", List.of(), List.of("cozy_api"));
	private final ModJar glowberry = mod("glowberry.jar", "glowberry", "cozy_api", "fabric-api");
	private final ModJar berrypie = mod("berrypie.jar", "berrypie", "glowberry");
	private final ModJar minimap = mod("minimap.jar", "minimap");
	private final ModJar unknown = ModJar.parse("plain.jar", "not json");

	private List<ModJar> all() {
		return List.of(javahost, fabricApi, cozylib, glowberry, berrypie, minimap, unknown);
	}

	@Test
	void aLibraryTakesEverythingThatNeedsItOff() {
		ModToggle toggle = new ModToggle(all(), List.of(), Set.of());
		ModToggle.Result result = toggle.turnOff("cozylib.jar");
		assertTrue(result.ok());
		assertEquals(List.of("berrypie.jar", "cozylib.jar", "glowberry.jar"), result.files(), "dependants of dependants go too");
		assertEquals(List.of("minimap.jar"), toggle.turnOff("minimap.jar").files());
		assertEquals(List.of("plain.jar"), toggle.turnOff("plain.jar").files());
	}

	@Test
	void anotherProviderKeepsDependantsOn() {
		ModJar otherLib = mod("othercozy.jar", "othercozy", List.of(), List.of("cozy_api"));
		List<ModJar> on = new ArrayList<>(all());
		on.add(otherLib);
		assertEquals(List.of("cozylib.jar"), new ModToggle(on, List.of(), Set.of()).turnOff("cozylib.jar").files());
	}

	@Test
	void protectedModsStayOnDirectlyOrThroughWhatNeedsThem() {
		ModToggle toggle = new ModToggle(all(), List.of(), Set.of(ModToggle.E4MC), Set.of("berrypie.jar"));
		ModToggle.Result direct = toggle.turnOff("fabric-api.jar");
		assertFalse(direct.ok());
		assertEquals("Fabric-api always stays on.", direct.refusal());
		assertFalse(toggle.turnOff("javahost.jar").ok());

		ModToggle.Result via = toggle.turnOff("cozylib.jar");
		assertFalse(via.ok(), "the mod set's berrypie needs glowberry, which needs cozylib");
		assertEquals("Berrypie needs Glowberry (which needs Cozylib), and Berrypie always stays on.", via.refusal());
		assertEquals(List.of(), via.files());
		assertEquals("Berrypie needs Glowberry, and Berrypie always stays on.", toggle.turnOff("glowberry.jar").refusal());
		assertFalse(toggle.turnOff("nothing.jar").ok());

		ModJar e4mc = mod("e4mc.jar", "e4mc");
		assertFalse(new ModToggle(List.of(e4mc), List.of(), Set.of(ModToggle.E4MC)).turnOff("e4mc.jar").ok());
	}

	@Test
	void turningOnBringsBackWhatItNeeds() {
		ModToggle toggle = new ModToggle(List.of(javahost, fabricApi, minimap), List.of(cozylib, glowberry, berrypie,
				mod("needy.jar", "needy", "nowhere")), Set.of());
		ModToggle.Result result = toggle.turnOn("berrypie.jar");
		assertTrue(result.ok());
		assertEquals(List.of("berrypie.jar", "cozylib.jar", "glowberry.jar"), result.files());
		assertEquals(List.of(), result.missing());
		assertEquals(List.of("cozylib.jar"), toggle.turnOn("cozylib.jar").files(), "a library needs nothing");
		ModToggle.Result needy = toggle.turnOn("needy.jar");
		assertEquals(List.of("needy.jar"), needy.files());
		assertEquals(List.of("nowhere"), needy.missing());
		assertFalse(toggle.turnOn("minimap.jar").ok(), "already on");
	}

	@Test
	void aPlanTakesTheFolderToTheWantedStateAndThenNothingIsLeft() {
		List<String> on = List.of("a.jar", "b.jar", "c.jar");
		List<String> off = List.of("d.jar", "e.jar");
		Set<String> wantOff = Set.of("b.jar", "e.jar");
		ModToggle.Plan plan = ModToggle.plan(on, off, wantOff);
		assertEquals(List.of("b.jar"), plan.out());
		assertEquals(List.of("d.jar"), plan.back());

		Set<String> nowOn = new LinkedHashSet<>(on);
		Set<String> nowOff = new LinkedHashSet<>(off);
		nowOn.removeAll(plan.out());
		nowOff.addAll(plan.out());
		nowOff.removeAll(plan.back());
		nowOn.addAll(plan.back());
		assertTrue(ModToggle.plan(nowOn, nowOff, wantOff).isEmpty(), "applied once, there's nothing more to do");
		assertEquals(wantOff, nowOff);

		ModToggle.Plan undo = ModToggle.plan(nowOn, nowOff, Set.of());
		assertEquals(Set.of("b.jar", "e.jar"), Set.copyOf(undo.back()));
		assertEquals(List.of(), undo.out());
	}

	@Test
	void vanillaKeepsOnlyTheKeepIds() {
		ModJar e4mc = mod("e4mc-5.jar", "e4mc");
		List<ModJar> on = new ArrayList<>(all());
		on.add(e4mc);
		assertEquals(List.of("cozylib.jar", "glowberry.jar", "berrypie.jar", "minimap.jar", "plain.jar"),
				ModToggle.vanilla(on, ModToggle.VANILLA_KEEP));
		assertNull(unknown.id());
	}

	@Test
	void thePlanFileHasEverythingModSwapNeeds() {
		Path base = Path.of("game");
		Properties p = ModToggle.toProperties(base.resolve("mods"), base, base.resolve("off"), base.resolve("off/state.properties"),
				base.resolve("off/result.properties"), 42, "Vanilla", "vanilla", new ModToggle.Plan(List.of("a.jar", "b.jar"), List.of("c.jar")));
		assertEquals("vanilla", p.getProperty("kind"));
		assertEquals("42", p.getProperty("pid"));
		assertEquals("Vanilla", p.getProperty("label"));
		assertEquals(List.of("a.jar", "b.jar"), ModSwap.list(p, "out"));
		assertEquals(List.of("c.jar"), ModSwap.list(p, "back"));
		for (String key : List.of("mods", "game", "store", "state", "result")) {
			assertTrue(p.containsKey(key), key);
		}
		assertThrows(IllegalArgumentException.class, () -> ModToggle.toProperties(base, base, base, base, base, 1, "", "switch",
				new ModToggle.Plan(List.of(), List.of())));
	}
}

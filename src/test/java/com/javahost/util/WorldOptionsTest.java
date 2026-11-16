package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javahost.util.WorldOptionsData.Operator;
import com.javahost.util.WorldOptionsData.Role;
import com.javahost.util.WorldOptionsData.Rule;
import com.javahost.util.WorldOptionsData.State;

class WorldOptionsTest {
	private static final UUID STEVE = UUID.fromString("00000000-0000-4000-8000-000000000001");

	// ---------------------------------------------------------------- who may use commands

	@Test
	void onlyOperatorsWithCheatsGetCommands() {
		assertEquals(0, OperatorPolicy.level(false, false, false, 0));
		assertEquals(0, OperatorPolicy.level(false, false, true, 2), "cheats on isn't enough without being an operator");
		assertEquals(0, OperatorPolicy.level(false, true, false, 0), "an operator gets nothing while cheats are off");
		assertEquals(2, OperatorPolicy.level(false, true, true, 0), "an operator gets the gamemasters level with cheats on");
		assertEquals(0, OperatorPolicy.level(false, false, false, 4), "the game's ops list doesn't count");
		assertEquals(2, OperatorPolicy.level(false, true, true, 4), "never more than gamemasters, even if the ops list says so");
	}

	@Test
	void theHostKeepsWhatTheGameGives() {
		assertEquals(4, OperatorPolicy.level(true, false, false, 4));
		assertEquals(0, OperatorPolicy.level(true, true, true, 0));
	}

	// ---------------------------------------------------------------- rule values

	@Test
	void booleanValues() {
		assertEquals("true", WorldOptionsData.validate(true, 0, 1, "true"));
		assertEquals("false", WorldOptionsData.validate(true, 0, 1, "false"));
		assertNull(WorldOptionsData.validate(true, 0, 1, "TRUE"));
		assertNull(WorldOptionsData.validate(true, 0, 1, "1"));
		assertNull(WorldOptionsData.validate(true, 0, 1, ""));
		assertNull(WorldOptionsData.validate(true, 0, 1, null));
	}

	@Test
	void integerValues() {
		assertEquals("50", WorldOptionsData.validate(false, 0, 100, "50"));
		assertEquals("7", WorldOptionsData.validate(false, 0, 100, "007"));
		assertEquals("-1", WorldOptionsData.validate(false, -1, Integer.MAX_VALUE, "-1"));
		assertEquals(String.valueOf(Integer.MAX_VALUE), WorldOptionsData.validate(false, 0, Integer.MAX_VALUE, String.valueOf(Integer.MAX_VALUE)));
		assertNull(WorldOptionsData.validate(false, 0, 100, "101"), "above the maximum");
		assertNull(WorldOptionsData.validate(false, 0, 100, "-1"), "below the minimum");
		assertNull(WorldOptionsData.validate(false, 0, Integer.MAX_VALUE, "2147483648"), "past what an int holds");
		assertNull(WorldOptionsData.validate(false, 0, 100, "99999999999999999999"));
		assertNull(WorldOptionsData.validate(false, 0, 100, "1.5"));
		assertNull(WorldOptionsData.validate(false, 0, 100, " 5"));
		assertNull(WorldOptionsData.validate(false, 0, 100, "true"));
		assertNull(WorldOptionsData.validate(false, 0, 100, "-"));
	}

	// ---------------------------------------------------------------- the world_state JSON

	private static Rule keepInventory(String value) {
		return new Rule("keep_inventory", "gamerule.minecraft.keep_inventory", "gamerule.minecraft.keep_inventory.description", "player",
				"gamerule.category.player", true, 0, 1, "false", value, true);
	}

	private static State sample(List<Rule> rules) {
		return new State(Role.OPERATOR, false, false, true, true, "This world was created with cheats on", "hard", "creative", "party",
				List.of(new Operator(STEVE, "Steve123")), rules);
	}

	@Test
	void roundTrip() {
		Rule sleep = new Rule("players_sleeping_percentage", "gamerule.minecraft.players_sleeping_percentage", "", "player",
				"gamerule.category.player", false, 0, Integer.MAX_VALUE, "100", "50", true);
		State parsed = WorldOptionsData.parse(WorldOptionsData.toJson(sample(List.of(keepInventory("true"), sleep))));
		assertNotNull(parsed);
		assertEquals(Role.OPERATOR, parsed.role());
		assertTrue(parsed.cheats());
		assertTrue(parsed.cheatsLocked());
		assertEquals("This world was created with cheats on", parsed.cheatsReason());
		assertEquals("hard", parsed.difficulty());
		assertEquals("creative", parsed.gameMode());
		assertEquals("party", parsed.voice());
		assertEquals(List.of(new Operator(STEVE, "Steve123")), parsed.operators());
		assertEquals(keepInventory("true"), parsed.rules().get(0));
		assertEquals(sleep, parsed.rules().get(1));
	}

	@Test
	void unreadableInputIsIgnored() {
		assertNull(WorldOptionsData.parse(null));
		assertNull(WorldOptionsData.parse(""));
		assertNull(WorldOptionsData.parse("not json {"));
		assertNull(WorldOptionsData.parse("[1,2,3]"));
		assertNull(WorldOptionsData.parse("\"text\""));
		assertNull(WorldOptionsData.parse("{\"role\":\"host\",\"pad\":\"" + "x".repeat(WorldOptionsData.MAX_JSON) + "\"}"), "oversized");
		assertNull(WorldOptionsData.parse("[".repeat(100_000)), "deeply nested");
	}

	@Test
	void wrongTypesFallBackSafely() {
		State state = WorldOptionsData.parse("{\"role\":5,\"cheats\":\"yes\",\"locked\":[true],\"difficulty\":\"impossible\","
				+ "\"gameMode\":{},\"voice\":\"everyone\",\"operators\":{\"id\":1},\"rules\":\"many\"}");
		assertNotNull(state);
		assertEquals(Role.NONE, state.role(), "an unknown role means no rights");
		assertFalse(state.cheats());
		assertFalse(state.locked());
		assertEquals("", state.difficulty());
		assertEquals("", state.gameMode());
		assertEquals("nearby", state.voice());
		assertTrue(state.operators().isEmpty());
		assertTrue(state.rules().isEmpty());
	}

	@Test
	void badRulesAndOperatorsAreDropped() {
		String json = "{\"role\":\"host\",\"operators\":[{\"id\":\"not-a-uuid\",\"name\":\"x\"},{\"id\":\"" + STEVE + "\",\"name\":\"§cSteve\\u0007123\"},"
				+ "{\"id\":\"" + STEVE + "\",\"name\":\"again\"},5],"
				+ "\"rules\":["
				+ "{\"id\":\"ok_rule\",\"type\":\"int\",\"min\":0,\"max\":10,\"def\":\"3\",\"value\":\"4\",\"name\":\"gamerule.ok\",\"cat\":\"mobs\"},"
				+ "{\"id\":\"ok_rule\",\"type\":\"int\",\"min\":0,\"max\":10,\"value\":\"5\"},"
				+ "{\"id\":\"out_of_range\",\"type\":\"int\",\"min\":0,\"max\":10,\"value\":\"11\"},"
				+ "{\"id\":\"inverted\",\"type\":\"int\",\"min\":10,\"max\":0,\"value\":\"5\"},"
				+ "{\"id\":\"no type\",\"value\":\"true\"},"
				+ "{\"id\":\"weird type\",\"type\":\"string\",\"value\":\"true\"},"
				+ "{\"id\":\"" + "a".repeat(60) + "\",\"type\":\"bool\",\"value\":\"true\"},"
				+ "{\"id\":\"bad_key\",\"type\":\"bool\",\"value\":\"true\",\"name\":\"§k<script>\",\"cat\":\"Not Valid!\"},"
				+ "{\"id\":\"fraction\",\"type\":\"int\",\"min\":0.5,\"max\":1e30,\"value\":\"3\"},"
				+ "\"just a string\",null]}";
		State state = WorldOptionsData.parse(json);
		assertNotNull(state);
		assertEquals(Role.HOST, state.role());
		assertEquals(1, state.operators().size(), "bad and repeated operators are dropped");
		assertEquals("Steve123", state.operators().getFirst().name(), "names are cleaned");
		List<String> ids = state.rules().stream().map(Rule::id).toList();
		assertEquals(List.of("ok_rule", "bad_key", "fraction"), ids);
		Rule badKey = state.rules().get(1);
		assertEquals("", badKey.nameKey(), "a key with odd characters is dropped");
		assertEquals("misc", badKey.category());
		Rule fraction = state.rules().get(2);
		assertEquals(Integer.MIN_VALUE, fraction.min());
		assertEquals(Integer.MAX_VALUE, fraction.max());
	}

	@Test
	void tenThousandRulesAreCapped() {
		List<Rule> many = new ArrayList<>();
		for (int i = 0; i < 10_000; i++) {
			many.add(new Rule("rule_" + i, "gamerule.rule_" + i, "", "misc", "gamerule.category.misc", i % 2 == 0, 0, i % 2 == 0 ? 1 : 100,
					i % 2 == 0 ? "false" : "1", i % 2 == 0 ? "true" : "2", false));
		}
		String json = WorldOptionsData.toJson(sample(many));
		assertTrue(json.length() <= WorldOptionsData.MAX_JSON);
		State state = WorldOptionsData.parse(json);
		assertNotNull(state);
		assertEquals(WorldOptionsData.MAX_RULES, state.rules().size());

		// A hostile server sending them all anyway: too long to read at all...
		assertNull(WorldOptionsData.parse(rules(10_000)));
		// ...and as many as fit in the limit: the reader still keeps at most MAX_RULES.
		String fits = rules(5_000);
		assertTrue(fits.length() <= WorldOptionsData.MAX_JSON);
		State capped = WorldOptionsData.parse(fits);
		assertNotNull(capped);
		assertEquals(WorldOptionsData.MAX_RULES, capped.rules().size());
	}

	private static String rules(int count) {
		StringBuilder json = new StringBuilder("{\"rules\":[");
		for (int i = 0; i < count; i++) {
			json.append(i == 0 ? "" : ",").append("{\"id\":\"r").append(i).append("\",\"type\":\"bool\",\"value\":\"true\"}");
		}
		return json.append("]}").toString();
	}
}

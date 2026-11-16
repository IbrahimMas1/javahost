package com.javahost.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The "World options" state the host's server sends each player (the {@code world_state} packet), as JSON, plus
 * checking game-rule values. Pure Java so both ends and the unit tests share it. The reader treats the text as
 * untrusted: sizes, counts, types and characters are checked, and anything odd is dropped rather than thrown.
 */
public final class WorldOptionsData {
	/** Longest {@code world_state} JSON accepted (characters). */
	public static final int MAX_JSON = 262144;
	public static final int MAX_RULES = 400;
	public static final int MAX_OPERATORS = 64;
	/** Longest translation key (rule name, description, category). */
	public static final int MAX_KEY = 128;
	/** Longest rule id: {@code "rule:" + id} must fit the 64 characters of a rules_change key. */
	public static final int MAX_ID = 59;
	public static final List<String> DIFFICULTIES = List.of("peaceful", "easy", "normal", "hard");
	public static final List<String> GAME_MODES = List.of("survival", "creative", "adventure", "spectator");
	public static final List<String> VOICE = List.of("nearby", "party");

	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:/-]{1," + MAX_ID + "}");
	private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:/-]{1," + MAX_KEY + "}");
	private static final Pattern CATEGORY = Pattern.compile("[a-z0-9_.:/-]{1,64}");

	public enum Role { HOST, OPERATOR, NONE }

	/**
	 * One game rule. {@code nameKey}/{@code descriptionKey}/{@code categoryKey} are the game's own translation keys
	 * (the description key may have no translation). Booleans have min 0, max 1.
	 */
	public record Rule(String id, String nameKey, String descriptionKey, String category, String categoryKey, boolean bool,
			int min, int max, String defaultValue, String value, boolean common) {
		public Rule withValue(String newValue) {
			return new Rule(id, nameKey, descriptionKey, category, categoryKey, bool, min, max, defaultValue, newValue, common);
		}
	}

	public record Operator(UUID id, String name) {
	}

	public record State(Role role, boolean locked, boolean announce, boolean cheats, boolean cheatsLocked, String cheatsReason,
			String difficulty, String gameMode, String voice, List<Operator> operators, List<Rule> rules) {
	}

	private WorldOptionsData() {
	}

	// ---------------------------------------------------------------- values

	/** {@code value} in its canonical form if it's acceptable for a rule of this kind, else null. */
	public static String validate(boolean bool, int min, int max, String value) {
		if (value == null) {
			return null;
		}
		if (bool) {
			return value.equals("true") || value.equals("false") ? value : null;
		}
		if (value.isEmpty() || value.length() > 11 || !value.matches("-?[0-9]+")) {
			return null;
		}
		long parsed;
		try {
			parsed = Long.parseLong(value);
		} catch (NumberFormatException e) {
			return null;
		}
		return parsed < min || parsed > max ? null : Long.toString(parsed);
	}

	public static String validate(Rule rule, String value) {
		return validate(rule.bool(), rule.min(), rule.max(), value);
	}

	// ---------------------------------------------------------------- writing

	public static String toJson(State state) {
		JsonObject root = new JsonObject();
		root.addProperty("v", 1);
		root.addProperty("role", state.role().name().toLowerCase(Locale.ROOT));
		root.addProperty("locked", state.locked());
		root.addProperty("announce", state.announce());
		root.addProperty("cheats", state.cheats());
		root.addProperty("cheatsLocked", state.cheatsLocked());
		root.addProperty("cheatsReason", Text.clean(state.cheatsReason(), 200));
		root.addProperty("difficulty", state.difficulty());
		root.addProperty("gameMode", state.gameMode());
		root.addProperty("voice", state.voice());
		JsonArray operators = new JsonArray();
		state.operators().stream().limit(MAX_OPERATORS).forEach(o -> {
			JsonObject e = new JsonObject();
			e.addProperty("id", o.id().toString());
			e.addProperty("name", Text.clean(o.name(), 32));
			operators.add(e);
		});
		root.add("operators", operators);
		JsonArray rules = new JsonArray();
		int length = root.toString().length();
		for (Rule rule : state.rules()) {
			if (rules.size() >= MAX_RULES) {
				break;
			}
			if (!ID.matcher(rule.id()).matches()) {
				continue;
			}
			JsonObject e = new JsonObject();
			e.addProperty("id", rule.id());
			e.addProperty("name", clipKey(rule.nameKey()));
			e.addProperty("desc", clipKey(rule.descriptionKey()));
			e.addProperty("cat", rule.category());
			e.addProperty("catName", clipKey(rule.categoryKey()));
			e.addProperty("type", rule.bool() ? "bool" : "int");
			if (!rule.bool()) {
				e.addProperty("min", rule.min());
				e.addProperty("max", rule.max());
			}
			e.addProperty("def", rule.defaultValue());
			e.addProperty("value", rule.value());
			if (rule.common()) {
				e.addProperty("common", true);
			}
			length += e.toString().length() + 1;
			if (length > MAX_JSON - 1024) {
				break; // never more than the reader takes
			}
			rules.add(e);
		}
		root.add("rules", rules);
		return root.toString();
	}

	private static String clipKey(String key) {
		return key == null ? "" : key.length() <= MAX_KEY ? key : key.substring(0, MAX_KEY);
	}

	// ---------------------------------------------------------------- reading (untrusted)

	/** The state in {@code json}, or null if it isn't a usable world state. Never throws. */
	public static State parse(String json) {
		if (json == null || json.length() > MAX_JSON) {
			return null;
		}
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				return null;
			}
			JsonObject root = parsed.getAsJsonObject();
			Role role = switch (string(root, "role", "none")) {
				case "host" -> Role.HOST;
				case "operator" -> Role.OPERATOR;
				default -> Role.NONE;
			};
			List<Operator> operators = new ArrayList<>();
			Set<UUID> seenOperators = new HashSet<>();
			JsonArray ops = array(root, "operators");
			if (ops != null) {
				for (JsonElement e : ops) {
					if (operators.size() >= MAX_OPERATORS) {
						break;
					}
					if (!e.isJsonObject()) {
						continue;
					}
					UUID id = uuid(string(e.getAsJsonObject(), "id", ""));
					if (id != null && seenOperators.add(id)) {
						operators.add(new Operator(id, Text.clean(string(e.getAsJsonObject(), "name", ""), 32)));
					}
				}
			}
			List<Rule> rules = new ArrayList<>();
			Set<String> seenRules = new HashSet<>();
			JsonArray list = array(root, "rules");
			if (list != null) {
				for (JsonElement e : list) {
					if (rules.size() >= MAX_RULES) {
						break;
					}
					Rule rule = e.isJsonObject() ? rule(e.getAsJsonObject()) : null;
					if (rule != null && seenRules.add(rule.id())) {
						rules.add(rule);
					}
				}
			}
			return new State(role, bool(root, "locked"), bool(root, "announce"), bool(root, "cheats"), bool(root, "cheatsLocked"),
					Text.clean(string(root, "cheatsReason", ""), 200), oneOf(DIFFICULTIES, string(root, "difficulty", ""), ""),
					oneOf(GAME_MODES, string(root, "gameMode", ""), ""), oneOf(VOICE, string(root, "voice", ""), "nearby"),
					List.copyOf(operators), List.copyOf(rules));
		} catch (RuntimeException | StackOverflowError e) {
			return null;
		}
	}

	private static Rule rule(JsonObject e) {
		String id = string(e, "id", "");
		if (!ID.matcher(id).matches()) {
			return null;
		}
		boolean bool = "bool".equals(string(e, "type", ""));
		if (!bool && !"int".equals(string(e, "type", ""))) {
			return null;
		}
		int min = bool ? 0 : integer(e, "min", Integer.MIN_VALUE);
		int max = bool ? 1 : integer(e, "max", Integer.MAX_VALUE);
		if (min > max) {
			return null;
		}
		String def = validate(bool, min, max, string(e, "def", ""));
		String value = validate(bool, min, max, string(e, "value", ""));
		if (value == null) {
			return null;
		}
		String category = string(e, "cat", "");
		if (!CATEGORY.matcher(category).matches()) {
			category = "misc";
		}
		return new Rule(id, key(string(e, "name", "")), key(string(e, "desc", "")), category, key(string(e, "catName", "")), bool, min, max,
				def == null ? value : def, value, bool(e, "common"));
	}

	private static String key(String key) {
		return KEY.matcher(key).matches() ? key : "";
	}

	private static String oneOf(List<String> allowed, String value, String otherwise) {
		return allowed.contains(value) ? value : otherwise;
	}

	private static UUID uuid(String text) {
		if (text.length() != 36) {
			return null;
		}
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static JsonArray array(JsonObject o, String name) {
		JsonElement e = o.get(name);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	private static String string(JsonObject o, String name, String otherwise) {
		JsonElement e = o.get(name);
		if (e instanceof JsonPrimitive p && p.isString()) {
			String s = p.getAsString();
			return s.length() > 1024 ? otherwise : s;
		}
		return otherwise;
	}

	private static boolean bool(JsonObject o, String name) {
		JsonElement e = o.get(name);
		return e instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
	}

	private static int integer(JsonObject o, String name, int otherwise) {
		JsonElement e = o.get(name);
		if (e instanceof JsonPrimitive p && p.isNumber()) {
			double d = p.getAsDouble();
			if (d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
				return (int) d;
			}
		}
		return otherwise;
	}
}

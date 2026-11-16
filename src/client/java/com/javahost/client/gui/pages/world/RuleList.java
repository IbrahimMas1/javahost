package com.javahost.client.gui.pages.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.WorldOptions;
import com.javahost.rules.WorldRules;
import net.minecraft.locale.Language;

/**
 * What the Rules part lists: every game rule the host's server sent (names and descriptions translated here), grouped
 * Common first and then by the game's own categories, or filtered by a search. Older hosts and versions where PvP isn't
 * a game rule add rows that change through the old world rules ({@link RulesClient}).
 */
final class RuleList {
	enum Kind { BOOL, INT, CHOICE }

	/**
	 * One row. {@code old}: changed through the old world rules, by its {@link WorldRules.Rule} name ({@code id}).
	 * {@code choices} only for {@link Kind#CHOICE}.
	 */
	record Entry(String id, String name, String description, Kind kind, int min, int max, String defaultValue, String value,
			boolean old, List<String> choices) {
		boolean on() {
			return "true".equals(value);
		}

		boolean changed() {
			return defaultValue != null && !defaultValue.isEmpty() && !defaultValue.equals(value);
		}

		/** "On", "Off", "3"... for the "Default: ..." hint and the change list. */
		String describe(String v) {
			return switch (kind) {
				case BOOL -> "true".equals(v) ? "On" : "Off";
				case CHOICE -> WorldRules.describe(WorldRules.Rule.valueOf(id), v);
				case INT -> v;
			};
		}
	}

	/** A run of rows under one label (none for the first). */
	record Group(String label, List<Entry> entries) {
	}

	private RuleList() {
	}

	// ---------------------------------------------------------------- names

	static String translate(String key, String fallback) {
		if (key == null || key.isEmpty()) {
			return fallback;
		}
		Language language = Language.getInstance();
		return language.has(key) ? language.getOrDefault(key) : fallback;
	}

	/** "keep_inventory", "keepInventory" or "minecraft:keep_inventory" → "Keep inventory". */
	static String readable(String id) {
		String plain = id.substring(id.indexOf(':') + 1);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);
			if (c == '_' || c == '.' || c == '-' || c == '/') {
				out.append(' ');
			} else if (Character.isUpperCase(c) && i > 0) {
				out.append(' ').append(Character.toLowerCase(c));
			} else {
				out.append(c);
			}
		}
		String text = out.toString().replaceAll(" +", " ").strip();
		return text.isEmpty() ? id : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static Entry entry(WorldOptions.Rule rule) {
		String name = translate(rule.nameKey(), readable(rule.id()));
		String description = translate(rule.descriptionKey(), "");
		// Some descriptions repeat the name or run over several lines: keep the first line, and only if it adds something.
		int newline = description.indexOf('\n');
		if (newline >= 0) {
			description = description.substring(0, newline);
		}
		if (description.strip().equalsIgnoreCase(name.strip())) {
			description = "";
		}
		return new Entry(rule.id(), name, description.strip(), rule.bool() ? Kind.BOOL : Kind.INT, rule.min(), rule.max(),
				rule.defaultValue(), rule.value(), false, List.of());
	}

	// ---------------------------------------------------------------- the old world rules

	private static final List<WorldRules.Rule> OLD_SHOWN = List.of(WorldRules.Rule.KEEP_INVENTORY, WorldRules.Rule.PVP,
			WorldRules.Rule.DAYLIGHT, WorldRules.Rule.WEATHER, WorldRules.Rule.MOB_GRIEFING, WorldRules.Rule.FIRE_SPREAD,
			WorldRules.Rule.NATURAL_REGEN, WorldRules.Rule.COORDINATES, WorldRules.Rule.SLEEPING);

	private static String oldDefault(WorldRules.Rule rule) {
		return switch (rule) {
			case KEEP_INVENTORY -> "false";
			case SLEEPING -> "100";
			default -> "true";
		};
	}

	private static Entry oldEntry(WorldRules.Rule rule, String value) {
		boolean bool = rule.choices.size() == 2 && rule.choices.contains("true");
		String description = switch (rule) {
			case PVP -> "Players can hurt each other";
			case COORDINATES -> "Coordinates on the debug screen (F3)";
			case SLEEPING -> "How many players must sleep to skip the night";
			default -> "";
		};
		return new Entry(rule.name(), rule.label, description, bool ? Kind.BOOL : Kind.CHOICE, 0, 1, oldDefault(rule), value, true,
				bool ? List.of() : rule.choices);
	}

	/** The old world rules of a pre-2.0 host (Difficulty is on General, voice reach on Sharing). */
	static List<Entry> olderHost(RulesClient.State state) {
		List<Entry> out = new ArrayList<>();
		for (WorldRules.Rule rule : OLD_SHOWN) {
			String value = state.values().get(rule.name());
			if (value != null) {
				out.add(oldEntry(rule, value));
			}
		}
		return out;
	}

	/**
	 * PvP before Minecraft 1.21.9 is the server's own setting, not a game rule: when the host's list has no PvP rule
	 * but the old world rules have one, it's added to the common rules so it can still be changed.
	 */
	private static Entry pvpFallback(List<WorldOptions.Rule> rules) {
		for (WorldOptions.Rule rule : rules) {
			if (rule.id().equalsIgnoreCase("pvp") || rule.id().toLowerCase(Locale.ROOT).endsWith(":pvp")) {
				return null;
			}
		}
		RulesClient.State old = RulesClient.state();
		String value = old == null ? null : old.values().get(WorldRules.Rule.PVP.name());
		return value == null ? null : oldEntry(WorldRules.Rule.PVP, value);
	}

	// ---------------------------------------------------------------- views

	/** Common rules first (no label), then each of the game's categories under its own name. */
	static List<Group> grouped() {
		List<WorldOptions.Rule> all = WorldOptions.allRules();
		List<Entry> common = new ArrayList<>();
		for (WorldOptions.Rule rule : WorldOptions.commonRules()) {
			common.add(entry(rule));
		}
		Entry pvp = pvpFallback(all);
		if (pvp != null) {
			common.add(Math.min(4, common.size()), pvp); // where the game rule sits in the common list on newer versions
		}
		List<Group> groups = new ArrayList<>();
		if (!common.isEmpty()) {
			groups.add(new Group(null, common));
		}
		Map<String, List<Entry>> byCategory = new LinkedHashMap<>();
		Map<String, String> labels = new LinkedHashMap<>();
		for (Map.Entry<String, List<WorldOptions.Rule>> category : WorldOptions.rules().entrySet()) {
			for (WorldOptions.Rule rule : category.getValue()) {
				if (rule.common()) {
					continue;
				}
				byCategory.computeIfAbsent(category.getKey(), c -> new ArrayList<>()).add(entry(rule));
				labels.putIfAbsent(category.getKey(), translate(rule.categoryKey(), readable(category.getKey())));
			}
		}
		byCategory.forEach((category, entries) -> groups.add(new Group(labels.get(category), entries)));
		return groups;
	}

	private static String squash(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	/** Every rule whose translated name or id has {@code query} in it (spaces, case and underscores don't matter). */
	static List<Entry> search(String query) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		String squashed = squash(q);
		List<Entry> out = new ArrayList<>();
		for (Group group : grouped()) {
			for (Entry entry : group.entries()) {
				String name = entry.name().toLowerCase(Locale.ROOT);
				String id = entry.id().toLowerCase(Locale.ROOT);
				if (name.contains(q) || id.contains(q)
						|| !squashed.isEmpty() && (squash(entry.name()).contains(squashed) || squash(entry.id()).contains(squashed))) {
					out.add(entry);
				}
			}
		}
		return out;
	}

	/** The name of a rule by its id, for the change list (null if unknown). */
	static Entry find(String id) {
		WorldOptions.Rule rule = WorldOptions.rule(id);
		return rule == null ? null : entry(rule);
	}
}

package com.javahost.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.javahost.util.WorldOptionsData;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.MinecraftServer;
//? if >=1.21.11 {
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRules;
//?} else {
/*import net.minecraft.world.level.GameRules;
*///?}

/**
 * Every game rule of the running Minecraft version, with the game's own translation keys and categories, for the
 * World options screen. Changing one goes through {@link #set}, which only takes values the rule accepts.
 */
public final class RuleCatalog {
	/** Shown first, in this order. Ids differ between 1.21.x (camelCase) and 1.21.11+; whichever exist are used. */
	static final List<String> COMMON = List.of(
			"keep_inventory", "keepInventory",
			"mob_griefing", "mobGriefing",
			"advance_time", "doDaylightCycle",
			"advance_weather", "doWeatherCycle",
			"pvp",
			"fire_spread_radius_around_player", "doFireTick",
			"natural_health_regeneration", "naturalRegeneration",
			"players_sleeping_percentage", "playersSleepingPercentage",
			"reduced_debug_info", "reducedDebugInfo",
			"spawn_phantoms", "doInsomnia",
			"spawn_mobs", "doMobSpawning");
	private static final List<String> CATEGORY_ORDER = List.of("player", "mobs", "spawning", "drops", "updates", "chat", "misc");

	private RuleCatalog() {
	}

	/** The rules with their current values: the common ones first (in {@link #COMMON} order), then by category and id. */
	public static List<WorldOptionsData.Rule> list(MinecraftServer s) {
		List<WorldOptionsData.Rule> rules = new ArrayList<>();
		collect(s, rules);
		rules.removeIf(r -> r.id().length() > WorldOptionsData.MAX_ID);
		rules.sort(Comparator.<WorldOptionsData.Rule>comparingInt(r -> r.common() ? COMMON.indexOf(r.id()) : Integer.MAX_VALUE)
				.thenComparingInt(r -> {
					int at = CATEGORY_ORDER.indexOf(r.category());
					return at < 0 ? CATEGORY_ORDER.size() : at;
				})
				.thenComparing(WorldOptionsData.Rule::category)
				.thenComparing(WorldOptionsData.Rule::id));
		return rules.size() <= WorldOptionsData.MAX_RULES ? rules : new ArrayList<>(rules.subList(0, WorldOptionsData.MAX_RULES));
	}

	public static WorldOptionsData.Rule find(MinecraftServer s, String id) {
		for (WorldOptionsData.Rule rule : list(s)) {
			if (rule.id().equals(id)) {
				return rule;
			}
		}
		return null;
	}

	/** Current value of a rule, or null if there's no such rule. */
	public static String value(MinecraftServer s, String id) {
		WorldOptionsData.Rule rule = find(s, id);
		return rule == null ? null : rule.value();
	}

	/**
	 * Sets a rule if {@code value} is acceptable for it. Returns the canonical value set, or null (unknown rule, wrong
	 * type, out of range) with nothing changed. Server thread only.
	 */
	public static String set(MinecraftServer s, String id, String value) {
		WorldOptionsData.Rule rule = find(s, id);
		String clean = rule == null ? null : WorldOptionsData.validate(rule, value);
		if (clean == null) {
			return null;
		}
		return apply(s, id, rule.bool(), clean) ? clean : null;
	}

	private static String category(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	private static int[] range(ArgumentType<?> argument) {
		if (argument instanceof IntegerArgumentType integer) {
			return new int[] {integer.getMinimum(), integer.getMaximum()};
		}
		return new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE};
	}

	//? if >=1.21.11 {
	private static void collect(MinecraftServer s, List<WorldOptionsData.Rule> out) {
		GameRules rules = WorldRules.gameRules(s);
		rules.availableRules().forEach(rule -> out.add(entry(rules, rule)));
	}

	private static <T> WorldOptionsData.Rule entry(GameRules rules, GameRule<T> rule) {
		boolean bool = rule.gameRuleType() == GameRuleType.BOOL;
		int[] range = bool ? new int[] {0, 1} : range(rule.argument());
		String categoryKey = rule.category().label().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
				? t.getKey() : "";
		return new WorldOptionsData.Rule(rule.id(), rule.getDescriptionId(), rule.getDescriptionId() + ".description",
				category(rule.category().id().getPath()), categoryKey, bool, range[0], range[1],
				rule.serialize(rule.defaultValue()), rules.getAsString(rule), COMMON.contains(rule.id()));
	}

	private static boolean apply(MinecraftServer s, String id, boolean bool, String value) {
		GameRules rules = WorldRules.gameRules(s);
		GameRule<?> rule = rules.availableRules().filter(r -> r.id().equals(id)).findFirst().orElse(null);
		return rule != null && applyTyped(s, rules, rule, value);
	}

	private static <T> boolean applyTyped(MinecraftServer s, GameRules rules, GameRule<T> rule, String value) {
		T parsed = rule.deserialize(value).result().orElse(null);
		if (parsed == null) {
			return false;
		}
		rules.set(rule, parsed, s);
		return true;
	}
	//?} else {
	/*private static void collect(MinecraftServer s, List<WorldOptionsData.Rule> out) {
		GameRules rules = WorldRules.gameRules(s);
		visit(rules, new GameRules.GameRuleTypeVisitor() {
			@Override
			public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
				out.add(new WorldOptionsData.Rule(key.getId(), key.getDescriptionId(), key.getDescriptionId() + ".description",
						category(key.getCategory().name()), key.getCategory().getDescriptionId(), true, 0, 1,
						String.valueOf(type.createRule().get()), String.valueOf(rules.getBoolean(key)), COMMON.contains(key.getId())));
			}

			@Override
			public void visitInteger(GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
				int[] range = range(type.createArgument("value").getType());
				out.add(new WorldOptionsData.Rule(key.getId(), key.getDescriptionId(), key.getDescriptionId() + ".description",
						category(key.getCategory().name()), key.getCategory().getDescriptionId(), false, range[0], range[1],
						String.valueOf(type.createRule().get()), String.valueOf(rules.getInt(key)), COMMON.contains(key.getId())));
			}
		});
	}

	private static boolean apply(MinecraftServer s, String id, boolean bool, String value) {
		GameRules rules = WorldRules.gameRules(s);
		boolean[] done = {false};
		visit(rules, new GameRules.GameRuleTypeVisitor() {
			@Override
			public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
				if (bool && key.getId().equals(id)) {
					rules.getRule(key).set(Boolean.parseBoolean(value), s);
					done[0] = true;
				}
			}

			@Override
			public void visitInteger(GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
				if (!bool && key.getId().equals(id)) {
					rules.getRule(key).set(Integer.parseInt(value), s);
					done[0] = true;
				}
			}
		});
		return done[0];
	}

	private static void visit(GameRules rules, GameRules.GameRuleTypeVisitor visitor) {
		//? if >=1.21.2 {
		rules.visitGameRuleTypes(visitor);
		//?} else
		/^GameRules.visitGameRuleTypes(visitor);^/
	}
	*///?}
}

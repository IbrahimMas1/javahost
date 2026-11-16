package com.javahost.client.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.javahost.net.Payloads.RulesChange;
import com.javahost.net.Payloads.WorldState;
import com.javahost.util.WorldOptionsData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The World options of the world we're in, as the host's server last described them (JavaHost 2.0+ hosts send
 * {@code world_state}), and requests to change them; the server decides. With an older host {@link #available()} is
 * false and {@link RulesClient} still has the old world rules.
 */
public final class WorldOptions {
	public enum Role { HOST, OPERATOR, NONE }

	public record Operator(UUID id, String name) {
	}

	/**
	 * One game rule: {@code nameKey}, {@code descriptionKey} and {@code categoryKey} are translation keys (translate
	 * them; the description may have none). Booleans have min 0 and max 1.
	 */
	public record Rule(String id, String nameKey, String descriptionKey, String category, String categoryKey, boolean bool,
			int min, int max, String defaultValue, String value, boolean common) {
		public boolean on() {
			return bool && "true".equals(value);
		}

		public int number() {
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException e) {
				return 0;
			}
		}
	}

	private record Snapshot(WorldOptionsData.State state, List<Rule> rules, List<Rule> common, Map<String, List<Rule>> byCategory,
			List<Operator> operators) {
	}

	private static volatile Snapshot snapshot;
	private static volatile int version;

	private WorldOptions() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(WorldState.TYPE, (payload, context) -> receive(payload.json()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> clear());
	}

	/** Takes a {@code world_state} JSON; anything unreadable is ignored. Public for tests. */
	public static boolean receive(String json) {
		WorldOptionsData.State state = WorldOptionsData.parse(json);
		if (state == null) {
			return false;
		}
		List<Rule> rules = new ArrayList<>();
		for (WorldOptionsData.Rule r : state.rules()) {
			rules.add(new Rule(r.id(), r.nameKey(), r.descriptionKey(), r.category(), r.categoryKey(), r.bool(), r.min(), r.max(),
					r.defaultValue(), r.value(), r.common()));
		}
		List<Rule> common = rules.stream().filter(Rule::common).toList();
		Map<String, List<Rule>> byCategory = new LinkedHashMap<>();
		for (Rule rule : rules) {
			byCategory.computeIfAbsent(rule.category(), c -> new ArrayList<>()).add(rule);
		}
		byCategory.replaceAll((c, list) -> List.copyOf(list));
		List<Operator> operators = state.operators().stream().map(o -> new Operator(o.id(), o.name())).toList();
		snapshot = new Snapshot(state, List.copyOf(rules), common, Collections.unmodifiableMap(byCategory), operators);
		version++;
		return true;
	}

	public static void clear() {
		if (snapshot != null) {
			snapshot = null;
			version++;
		}
	}

	/** False until a 2.0+ host has told us (and with older hosts, or outside a world). */
	public static boolean available() {
		return snapshot != null;
	}

	/** Changes whenever new options arrive (screens use it to know when to rebuild). */
	public static int version() {
		return version;
	}

	public static Role role() {
		Snapshot s = snapshot;
		if (s == null) {
			return Role.NONE;
		}
		return switch (s.state().role()) {
			case HOST -> Role.HOST;
			case OPERATOR -> Role.OPERATOR;
			case NONE -> Role.NONE;
		};
	}

	/** The host and operators can change rules, difficulty, default game mode and voice reach (unless locked). */
	public static boolean canChangeRules() {
		return role() != Role.NONE && !locked();
	}

	public static boolean cheats() {
		Snapshot s = snapshot;
		return s != null && s.state().cheats();
	}

	/** True when Cheats can't be switched off (see {@link #cheatsLockedReason()}). */
	public static boolean cheatsLocked() {
		Snapshot s = snapshot;
		return s != null && s.state().cheatsLocked();
	}

	public static String cheatsLockedReason() {
		Snapshot s = snapshot;
		return s == null ? "" : s.state().cheatsReason();
	}

	public static boolean locked() {
		Snapshot s = snapshot;
		return s != null && s.state().locked();
	}

	public static boolean announce() {
		Snapshot s = snapshot;
		return s != null && s.state().announce();
	}

	/** "peaceful", "easy", "normal" or "hard" ("" if unknown). */
	public static String difficulty() {
		Snapshot s = snapshot;
		return s == null ? "" : s.state().difficulty();
	}

	/** "survival", "creative", "adventure" or "spectator" ("" if unknown). */
	public static String defaultGameMode() {
		Snapshot s = snapshot;
		return s == null ? "" : s.state().gameMode();
	}

	/** "nearby" or "party". */
	public static String voice() {
		Snapshot s = snapshot;
		return s == null ? "nearby" : s.state().voice();
	}

	public static List<Operator> operators() {
		Snapshot s = snapshot;
		return s == null ? List.of() : s.operators();
	}

	public static boolean isOperator(UUID player) {
		return operators().stream().anyMatch(o -> o.id().equals(player));
	}

	/** Every rule, the common ones first. */
	public static List<Rule> allRules() {
		Snapshot s = snapshot;
		return s == null ? List.of() : s.rules();
	}

	/** The rules most people look for (keep inventory, mob griefing, daylight cycle...), in that order. */
	public static List<Rule> commonRules() {
		Snapshot s = snapshot;
		return s == null ? List.of() : s.common();
	}

	/** Rules grouped by the game's category id ("player", "mobs"...), in the order the server sent them. */
	public static Map<String, List<Rule>> rules() {
		Snapshot s = snapshot;
		return s == null ? Map.of() : s.byCategory();
	}

	public static Rule rule(String id) {
		for (Rule rule : allRules()) {
			if (rule.id().equals(id)) {
				return rule;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- asking the server

	private static void request(String key, String value) {
		if (ClientPlayNetworking.canSend(RulesChange.TYPE)) {
			ClientPlayNetworking.send(new RulesChange(key, value));
		}
	}

	/** Host only. */
	public static void setCheats(boolean on) {
		request("__cheats", String.valueOf(on));
	}

	/** Host only. */
	public static void setOperator(UUID player, boolean operator) {
		request("__op:" + player, String.valueOf(operator));
	}

	/** Host only: whether rule changes are announced in chat. */
	public static void setAnnounce(boolean on) {
		request("__announce", String.valueOf(on));
	}

	/** Host only: lock the rules for a challenge run. */
	public static void setLocked(boolean locked) {
		request("__lock", String.valueOf(locked));
	}

	/** Returns false (and sends nothing) if this isn't a value the rule takes. */
	public static boolean setRule(String id, String value) {
		Rule rule = rule(id);
		if (rule == null || WorldOptionsData.validate(rule.bool(), rule.min(), rule.max(), value) == null
				|| ("rule:" + id).length() > 64) {
			return false;
		}
		request("rule:" + id, WorldOptionsData.validate(rule.bool(), rule.min(), rule.max(), value));
		return true;
	}

	public static boolean setRule(String id, boolean on) {
		return setRule(id, String.valueOf(on));
	}

	public static boolean setRule(String id, int value) {
		return setRule(id, String.valueOf(value));
	}

	public static void setDifficulty(String difficulty) {
		if (WorldOptionsData.DIFFICULTIES.contains(difficulty)) {
			request("DIFFICULTY", difficulty);
		}
	}

	public static void setDefaultGameMode(String mode) {
		if (WorldOptionsData.GAME_MODES.contains(mode)) {
			request("__gamemode", mode);
		}
	}

	public static void setVoice(String reach) {
		if (WorldOptionsData.VOICE.contains(reach)) {
			request("VOICE", reach);
		}
	}

	/** Test hook: sends a change without checking it here first (the server must refuse bad ones). */
	public static void requestUnchecked(String key, String value) {
		request(key, value);
	}
}

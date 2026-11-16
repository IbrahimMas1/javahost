package com.javahost.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.javahost.JavaHost;
import com.javahost.net.Payloads.RulesChange;
import com.javahost.net.Payloads.RulesState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
//? if >=1.21.11 {
import net.minecraft.world.level.gamerules.GameRules;
//?} else {
/*import net.minecraft.world.level.GameRules;
*///?}
import net.minecraft.world.level.storage.LevelResource;

/**
 * World rules, kept fair: only the host and the friends they make operators (called co-hosts before 2.0, and still
 * stored as {@code coHosts}) can change them, every change is kept in a history (and announced in chat if the host
 * wants), and the host can lock the rules for a challenge run. All checks happen here on the host's server, so a
 * modified game can't get around them. While locked, the rules are held at their locked values even if a command
 * (/gamerule, /difficulty) tries to change them.
 *
 * <p>2.0 adds World options: Cheats, operators who may use commands while Cheats is on (see {@link #permissionLevel};
 * JavaHost never uses the game's own ops list), the default game mode, whether changes are announced, and every game
 * rule ({@link RuleCatalog}). All of it reaches 2.0 games as {@code WorldState}; {@code RulesState} stays as it was for
 * older games.
 */
public final class WorldRules {
	/** The rules JavaHost shows, with their choices. */
	public enum Rule {
		DIFFICULTY("Difficulty", List.of("peaceful", "easy", "normal", "hard")),
		KEEP_INVENTORY("Keep inventory", List.of("false", "true")),
		PVP("PvP", List.of("false", "true")),
		DAYLIGHT("Daylight cycle", List.of("false", "true")),
		WEATHER("Weather cycle", List.of("false", "true")),
		MOB_GRIEFING("Mob griefing", List.of("false", "true")),
		FIRE_SPREAD("Fire spread", List.of("false", "true")),
		NATURAL_REGEN("Natural regeneration", List.of("false", "true")),
		COORDINATES("Coordinates shown", List.of("false", "true")),
		SLEEPING("Sleeping to skip night", List.of("1", "50", "100")),
		/** Voice chat reaches nearby players only, or everyone in the world ("party"). Stored by JavaHost, not a gamerule. */
		VOICE("Voice chat reaches", List.of("nearby", "party"));

		public final String label;
		public final List<String> choices;

		Rule(String label, List<String> choices) {
			this.label = label;
			this.choices = choices;
		}
	}

	public record HistoryEntry(long time, String who, String what) {
	}

	private static final class Saved {
		boolean locked;
		Map<String, String> lockedValues = new LinkedHashMap<>();
		Map<String, String> coHosts = new LinkedHashMap<>(); // uuid -> name
		List<HistoryEntry> history = new ArrayList<>();
		String voiceMode = "nearby";
		/** Whether rule changes are announced in chat (off unless the host turns it on; older files have no field). */
		boolean announce = false;
		/** Cheats for operators, on versions where the world's own cheats flag can't be switched while it's open. */
		boolean cheats = false;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_HISTORY = 50;
	private static final String CHEATS_LOCKED_REASON = "This world was created with cheats on";
	private static volatile MinecraftServer server;
	private static Saved saved = new Saved();
	/** Read by the permission mixin from any thread; replaced (never changed) on the server thread. */
	private static volatile java.util.Set<UUID> operatorIds = java.util.Set.of();
	private static volatile boolean savedCheats;
	private static String lastWorldState = "";
	private static volatile int announcements;
	private static final Map<UUID, Long> lastRefusal = new java.util.HashMap<>();
	//? if >=26.2 {
	/** Switches the world's own cheats flag; the client sets the integrated server's version (see JavaHostClient). */
	private static volatile java.util.function.BiConsumer<MinecraftServer, Boolean> cheatsSwitch = (s, on) -> {
		s.getWorldData().setAllowCommands(on);
		refreshPermissions(s);
	};
	//?}

	private WorldRules() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			saved = load(started);
			publishSnapshot();
			lastWorldState = "";
			server = started;
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
			server = null;
			saved = new Saved();
			publishSnapshot();
			lastRefusal.clear();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> sendState(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> lastRefusal.remove(handler.player.getUUID()));
		ServerPlayNetworking.registerGlobalReceiver(RulesChange.TYPE, (change, context) -> {
			if (com.javahost.net.Guard.allow(context.player(), com.javahost.net.Guard.RULES, 1)) {
				handle(context.player(), change.rule(), change.value());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (saved.locked && s.getTickCount() % 20 == 0) {
				holdLockedValues(s);
			}
			if (s == server && s.getTickCount() % 20 == 10 && !s.getPlayerList().getPlayers().isEmpty()) {
				// Changes made some other way (commands, the game's own screens) reach the World options screen too.
				if (!fingerprint(s).equals(lastWorldState)) {
					broadcastWorldState(s);
				}
			}
		});
	}

	// ---------------------------------------------------------------- reading and writing rules

	public static String get(MinecraftServer s, Rule rule) {
		GameRules rules = gameRules(s);
		//? if >=1.21.11 {
		return switch (rule) {
			case DIFFICULTY -> s.getWorldData().getDifficulty().getSerializedName();
			case KEEP_INVENTORY -> String.valueOf(rules.get(GameRules.KEEP_INVENTORY));
			case PVP -> String.valueOf(rules.get(GameRules.PVP));
			case DAYLIGHT -> String.valueOf(rules.get(GameRules.ADVANCE_TIME));
			case WEATHER -> String.valueOf(rules.get(GameRules.ADVANCE_WEATHER));
			case MOB_GRIEFING -> String.valueOf(rules.get(GameRules.MOB_GRIEFING));
			case FIRE_SPREAD -> String.valueOf(rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) > 0);
			case NATURAL_REGEN -> String.valueOf(rules.get(GameRules.NATURAL_HEALTH_REGENERATION));
			case COORDINATES -> String.valueOf(!rules.get(GameRules.REDUCED_DEBUG_INFO));
			case SLEEPING -> sleeping(rules.get(GameRules.PLAYERS_SLEEPING_PERCENTAGE));
			case VOICE -> saved.voiceMode;
		};
		//?} else {
		/*return switch (rule) {
			case DIFFICULTY -> s.getWorldData().getDifficulty().getSerializedName();
			case KEEP_INVENTORY -> String.valueOf(rules.getBoolean(GameRules.RULE_KEEPINVENTORY));
			//? if >=1.21.9 {
			case PVP -> String.valueOf(rules.getBoolean(GameRules.RULE_PVP));
			//?} else
			/^case PVP -> String.valueOf(s.isPvpAllowed());^/
			case DAYLIGHT -> String.valueOf(rules.getBoolean(GameRules.RULE_DAYLIGHT));
			case WEATHER -> String.valueOf(rules.getBoolean(GameRules.RULE_WEATHER_CYCLE));
			case MOB_GRIEFING -> String.valueOf(rules.getBoolean(GameRules.RULE_MOBGRIEFING));
			case FIRE_SPREAD -> String.valueOf(rules.getBoolean(GameRules.RULE_DOFIRETICK));
			case NATURAL_REGEN -> String.valueOf(rules.getBoolean(GameRules.RULE_NATURAL_REGENERATION));
			case COORDINATES -> String.valueOf(!rules.getBoolean(GameRules.RULE_REDUCEDDEBUGINFO));
			case SLEEPING -> sleeping(rules.getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE));
			case VOICE -> saved.voiceMode;
		};
		*///?}
	}

	private static String sleeping(int percent) {
		return percent <= 1 ? "1" : percent <= 50 ? "50" : "100";
	}

	static GameRules gameRules(MinecraftServer s) {
		//? if >=26.1 || <1.21.11 {
		return s.getGameRules();
		//?} else
		/*return s.overworld().getGameRules();*/
	}

	private static void set(MinecraftServer s, Rule rule, String value) {
		GameRules rules = gameRules(s);
		boolean on = Boolean.parseBoolean(value);
		//? if >=1.21.11 {
		switch (rule) {
			case DIFFICULTY -> s.setDifficulty(Difficulty.valueOf(value.toUpperCase(java.util.Locale.ROOT)), true);
			case KEEP_INVENTORY -> rules.set(GameRules.KEEP_INVENTORY, on, s);
			case PVP -> rules.set(GameRules.PVP, on, s);
			case DAYLIGHT -> rules.set(GameRules.ADVANCE_TIME, on, s);
			case WEATHER -> rules.set(GameRules.ADVANCE_WEATHER, on, s);
			case MOB_GRIEFING -> rules.set(GameRules.MOB_GRIEFING, on, s);
			case FIRE_SPREAD -> rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, on ? GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.defaultValue() : 0, s);
			case NATURAL_REGEN -> rules.set(GameRules.NATURAL_HEALTH_REGENERATION, on, s);
			case COORDINATES -> rules.set(GameRules.REDUCED_DEBUG_INFO, !on, s);
			case SLEEPING -> rules.set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, Integer.parseInt(value), s);
			case VOICE -> saved.voiceMode = value;
		}
		//?} else {
		/*switch (rule) {
			case DIFFICULTY -> s.setDifficulty(Difficulty.valueOf(value.toUpperCase(java.util.Locale.ROOT)), true);
			case KEEP_INVENTORY -> rules.getRule(GameRules.RULE_KEEPINVENTORY).set(on, s);
			//? if >=1.21.9 {
			case PVP -> rules.getRule(GameRules.RULE_PVP).set(on, s);
			//?} else
			/^case PVP -> s.setPvpAllowed(on);^/
			case DAYLIGHT -> rules.getRule(GameRules.RULE_DAYLIGHT).set(on, s);
			case WEATHER -> rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(on, s);
			case MOB_GRIEFING -> rules.getRule(GameRules.RULE_MOBGRIEFING).set(on, s);
			case FIRE_SPREAD -> rules.getRule(GameRules.RULE_DOFIRETICK).set(on, s);
			case NATURAL_REGEN -> rules.getRule(GameRules.RULE_NATURAL_REGENERATION).set(on, s);
			case COORDINATES -> rules.getRule(GameRules.RULE_REDUCEDDEBUGINFO).set(!on, s);
			case SLEEPING -> rules.getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(Integer.parseInt(value), s);
			case VOICE -> saved.voiceMode = value;
		}
		*///?}
	}

	/** "Easy", "On", "One player"... for chat and the page. */
	public static String describe(Rule rule, String value) {
		return switch (rule) {
			case DIFFICULTY -> Character.toUpperCase(value.charAt(0)) + value.substring(1);
			case SLEEPING -> value.equals("1") ? "One" : value.equals("50") ? "Half" : "Everyone";
			case VOICE -> value.equals("party") ? "Everyone in the world" : "Nearby players";
			default -> Boolean.parseBoolean(value) ? "On" : "Off";
		};
	}

	/** Whether voice chat in this world reaches everyone (party) rather than only nearby players. */
	public static boolean partyVoice() {
		return "party".equals(saved.voiceMode);
	}

	// ---------------------------------------------------------------- permissions and changes

	public static boolean isHost(MinecraftServer s, ServerPlayer player) {
		return com.javahost.compat.Compat.isOwner(s, player);
	}

	/** The host, or one of their operators. */
	static boolean canEdit(MinecraftServer s, ServerPlayer player) {
		return isHost(s, player) || isOperator(player.getUUID());
	}

	/** Whether the host made this player an operator (a co-host before 2.0). */
	public static boolean isOperator(UUID player) {
		return operatorIds.contains(player);
	}

	/** Whether rule changes are announced in chat. */
	public static boolean announces() {
		return saved.announce;
	}

	/** How many announcements were sent to chat since the game started (for tests). */
	public static int announcementsSent() {
		return announcements;
	}

	// ---------------------------------------------------------------- cheats and commands

	/**
	 * Whether Cheats is on: from 26.2 the world's own flag (switched for real); before, the world's flag if it was
	 * created with cheats on, else JavaHost's switch, which only applies to operators.
	 */
	public static boolean cheats(MinecraftServer s) {
		//? if >=26.2 {
		return s.getWorldData().isAllowCommands();
		//?} else
		/*return s.getWorldData().isAllowCommands() || savedCheats;*/
	}

	/** True when Cheats can't be switched off here: before 26.2, a world created with cheats on keeps them. */
	public static boolean cheatsLocked(MinecraftServer s) {
		//? if >=26.2 {
		return false;
		//?} else
		/*return s.getWorldData().isAllowCommands();*/
	}

	public static String cheatsLockedReason(MinecraftServer s) {
		return cheatsLocked(s) ? CHEATS_LOCKED_REASON : "";
	}

	//? if >=26.2 {
	/** The integrated server's own switch (it also tells the host's game); set by the client at startup. */
	public static void setCheatsSwitch(java.util.function.BiConsumer<MinecraftServer, Boolean> value) {
		cheatsSwitch = value;
	}
	//?}

	private static void setCheats(MinecraftServer s, boolean on) {
		//? if >=26.2 {
		cheatsSwitch.accept(s, on);
		//?} else {
		/*saved.cheats = on;
		savedCheats = on;
		*///?}
	}

	/**
	 * The command permission level for a player, given the one the game decided ({@code vanilla}). Only changes
	 * anything on the world the host opened from their own game: the host keeps theirs, operators get "gamemasters"
	 * while Cheats is on, everyone else gets none. Called by the permission mixin, from any thread.
	 */
	public static int permissionLevel(MinecraftServer s, UUID player, boolean owner, int vanilla) {
		if (s == null || s != server || s.isDedicatedServer()) {
			return vanilla;
		}
		return com.javahost.util.OperatorPolicy.level(owner, isOperator(player), cheats(s), vanilla);
	}

	//? if >=1.21.11 {
	/** {@link #permissionLevel} for versions where permissions are sets. */
	public static net.minecraft.server.permissions.LevelBasedPermissionSet permissions(MinecraftServer s, net.minecraft.server.players.NameAndId profile,
			net.minecraft.server.permissions.LevelBasedPermissionSet vanilla) {
		if (s == null || s != server || vanilla == null) {
			return vanilla;
		}
		int before = vanilla.level().id();
		int level = permissionLevel(s, profile.id(), s.isSingleplayerOwner(profile), before);
		return level == before ? vanilla
				: net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.byId(level));
	}
	//?}

	/** Sends every player their (possibly new) permission level and command list, so chat suggestions match. */
	static void refreshPermissions(MinecraftServer s) {
		for (ServerPlayer player : List.copyOf(s.getPlayerList().getPlayers())) {
			s.getPlayerList().sendPlayerPermissionLevel(player);
		}
	}

	private static void publishSnapshot() {
		java.util.Set<UUID> ids = new java.util.HashSet<>();
		saved.coHosts.keySet().forEach(id -> {
			UUID parsed = parseUuid(id);
			if (parsed != null) {
				ids.add(parsed);
			}
		});
		operatorIds = java.util.Set.copyOf(ids);
		savedCheats = saved.cheats;
	}

	private static UUID parseUuid(String text) {
		if (text == null || text.length() != 36) {
			return null;
		}
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- changes

	private static GameType gameType(String name) {
		for (GameType type : GameType.values()) {
			if (type.getName().equals(name)) {
				return type;
			}
		}
		return null;
	}

	private static void handle(ServerPlayer player, String ruleName, String value) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}
		String who = com.javahost.compat.Compat.name(player);
		if (ruleName.equals("__lock")) {
			if (!isHost(s, player)) {
				refuse(player, "Only the host can lock or unlock the rules.");
				return;
			}
			boolean lock = Boolean.parseBoolean(value);
			if (lock == saved.locked) {
				return;
			}
			saved.locked = lock;
			saved.lockedValues.clear();
			if (lock) {
				for (Rule rule : Rule.values()) {
					saved.lockedValues.put(rule.name(), get(s, rule));
				}
				for (com.javahost.util.WorldOptionsData.Rule rule : RuleCatalog.list(s)) {
					saved.lockedValues.put("rule:" + rule.id(), rule.value());
				}
				saved.lockedValues.put("__gamemode", s.getDefaultGameType().getName());
			}
			record(s, who, lock ? "locked the rules" : "unlocked the rules");
			announce(s, who + (lock ? " locked the world rules. They can't be changed until unlocked." : " unlocked the world rules."));
		} else if (ruleName.startsWith("__cohost:") || ruleName.startsWith("__op:")) {
			if (!isHost(s, player)) {
				refuse(player, "Only the host can choose operators.");
				return;
			}
			UUID target = parseUuid(ruleName.substring(ruleName.indexOf(':') + 1));
			if (target == null || target.equals(player.getUUID())) {
				return;
			}
			ServerPlayer targetPlayer = s.getPlayerList().getPlayer(target);
			String name = targetPlayer != null ? com.javahost.compat.Compat.name(targetPlayer) : saved.coHosts.getOrDefault(target.toString(), "a player");
			if (Boolean.parseBoolean(value)) {
				if (saved.coHosts.containsKey(target.toString())) {
					return;
				}
				if (saved.coHosts.size() >= com.javahost.util.WorldOptionsData.MAX_OPERATORS) {
					refuse(player, "That's the most operators a world can have.");
					return;
				}
				saved.coHosts.put(target.toString(), name);
				record(s, who, "made " + name + " an operator");
				announce(s, name + " is now an operator and can change world rules.");
			} else if (saved.coHosts.remove(target.toString()) != null) {
				record(s, who, "removed " + name + " as operator");
				announce(s, name + " is no longer an operator.");
			} else {
				return;
			}
			publishSnapshot();
			refreshPermissions(s);
		} else if (ruleName.equals("__cheats")) {
			if (!isHost(s, player)) {
				refuse(player, "Only the host can turn cheats on or off.");
				return;
			}
			if (!value.equals("true") && !value.equals("false")) {
				return;
			}
			boolean on = value.equals("true");
			if (cheatsLocked(s)) {
				if (!on) {
					refuse(player, CHEATS_LOCKED_REASON + ", so they stay on.");
				}
				return;
			}
			if (on == cheats(s)) {
				return;
			}
			setCheats(s, on);
			publishSnapshot();
			refreshPermissions(s);
			record(s, who, on ? "turned cheats on" : "turned cheats off");
			announce(s, who + (on ? " turned cheats on." : " turned cheats off."));
		} else if (ruleName.equals("__announce")) {
			if (!isHost(s, player)) {
				refuse(player, "Only the host can choose whether changes are announced.");
				return;
			}
			if (!value.equals("true") && !value.equals("false") || value.equals(String.valueOf(saved.announce))) {
				return;
			}
			saved.announce = value.equals("true");
			record(s, who, saved.announce ? "turned on announcements" : "turned off announcements");
		} else if (ruleName.equals("__gamemode")) {
			GameType type = gameType(value);
			if (type == null) {
				return;
			}
			if (!checkCanChange(s, player)) {
				return;
			}
			GameType before = s.getDefaultGameType();
			if (before == type) {
				return;
			}
			s.setDefaultGameType(type);
			String what = "Default game mode: " + capitalize(before.getName()) + " → " + capitalize(type.getName());
			record(s, who, "changed " + what);
			announce(s, who + " changed " + what);
		} else if (ruleName.startsWith("rule:")) {
			String id = ruleName.substring("rule:".length());
			com.javahost.util.WorldOptionsData.Rule rule = RuleCatalog.find(s, id);
			if (rule == null || com.javahost.util.WorldOptionsData.validate(rule, value) == null) {
				sendState(player); // unknown rule or a value it doesn't take: nothing changes
				return;
			}
			if (!checkCanChange(s, player)) {
				return;
			}
			String set = com.javahost.util.WorldOptionsData.validate(rule, value);
			if (set.equals(rule.value())) {
				return;
			}
			if (RuleCatalog.set(s, id, set) == null) {
				sendState(player);
				return;
			}
			record(s, who, "changed " + id + ": " + rule.value() + " → " + set);
			if (saved.announce) {
				announce(s, Component.literal(who + " changed ").append(Component.translatable(rule.nameKey()))
						.append(": " + describeValue(rule.bool(), rule.value()) + " → " + describeValue(rule.bool(), set)));
			}
		} else {
			Rule rule;
			try {
				rule = Rule.valueOf(ruleName);
			} catch (IllegalArgumentException e) {
				return;
			}
			if (!rule.choices.contains(value)) {
				return;
			}
			if (!checkCanChange(s, player)) {
				return;
			}
			String before = get(s, rule);
			if (before.equals(value)) {
				return;
			}
			set(s, rule, value);
			String what = rule.label + ": " + describe(rule, before) + " → " + describe(rule, value);
			record(s, who, "changed " + what);
			announce(s, who + " changed " + what);
		}
		save(s);
		broadcastState(s);
	}

	/** The host and operators may change rules, unless they're locked; tells anyone else why not. */
	private static boolean checkCanChange(MinecraftServer s, ServerPlayer player) {
		if (!canEdit(s, player)) {
			refuse(player, "Only the host and operators can change world rules.");
			return false;
		}
		if (saved.locked) {
			refuse(player, "The world rules are locked.");
			return false;
		}
		return true;
	}

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static String describeValue(boolean bool, String value) {
		return bool ? Boolean.parseBoolean(value) ? "On" : "Off" : value;
	}

	/** While locked, puts back anything changed some other way (commands). */
	private static void holdLockedValues(MinecraftServer s) {
		boolean reverted = false;
		for (Rule rule : Rule.values()) {
			String want = saved.lockedValues.get(rule.name());
			if (want != null && !want.equals(get(s, rule))) {
				set(s, rule, want);
				reverted = true;
			}
		}
		// Then every game rule exactly (after the coarse ones above, so the exact value wins).
		for (com.javahost.util.WorldOptionsData.Rule rule : RuleCatalog.list(s)) {
			String want = saved.lockedValues.get("rule:" + rule.id());
			if (want != null && !want.equals(rule.value()) && RuleCatalog.set(s, rule.id(), want) != null) {
				reverted = true;
			}
		}
		GameType mode = gameType(saved.lockedValues.getOrDefault("__gamemode", ""));
		if (mode != null && mode != s.getDefaultGameType()) {
			s.setDefaultGameType(mode);
			reverted = true;
		}
		if (reverted) {
			s.getPlayerList().broadcastSystemMessage(prefix().append(Component.literal("The rules are locked, so that change was undone.")
					.withStyle(ChatFormatting.WHITE)), false);
			broadcastState(s);
		}
	}

	private static void refuse(ServerPlayer player, String why) {
		long now = System.currentTimeMillis();
		Long last = lastRefusal.get(player.getUUID());
		if (last == null || now - last > 5000) {
			if (lastRefusal.size() > 256) {
				lastRefusal.clear();
			}
			lastRefusal.put(player.getUUID(), now);
			player.sendSystemMessage(Component.literal("[World rules] " + why).withStyle(ChatFormatting.RED));
		}
		sendState(player);
	}

	private static net.minecraft.network.chat.MutableComponent prefix() {
		return Component.literal("[World rules] ").withStyle(ChatFormatting.GOLD);
	}

	private static void announce(MinecraftServer s, String text) {
		announce(s, Component.literal(text));
	}

	/** Tells everyone in chat, if the host has announcements on. */
	private static void announce(MinecraftServer s, Component text) {
		if (!saved.announce) {
			return;
		}
		announcements++;
		s.getPlayerList().broadcastSystemMessage(prefix().append(text.copy().withStyle(ChatFormatting.WHITE)), false);
	}

	private static void record(MinecraftServer s, String who, String what) {
		saved.history.addFirst(new HistoryEntry(System.currentTimeMillis(), who, what));
		while (saved.history.size() > MAX_HISTORY) {
			saved.history.removeLast();
		}
		JavaHost.LOGGER.info("[World rules] {} {}", who, what);
	}

	// ---------------------------------------------------------------- telling players

	private static void broadcastState(MinecraftServer s) {
		for (ServerPlayer player : s.getPlayerList().getPlayers()) {
			sendRulesState(player);
		}
		broadcastWorldState(s);
	}

	private static void sendState(ServerPlayer player) {
		sendRulesState(player);
		MinecraftServer s = server;
		if (s != null) {
			sendWorldState(s, player, worldState(s));
		}
	}

	/** The pre-2.0 state; its shape must stay exactly as it is (1.12 games read it with a fixed limit). */
	private static void sendRulesState(ServerPlayer player) {
		MinecraftServer s = server;
		if (s == null || !ServerPlayNetworking.canSend(player, RulesState.TYPE)) {
			return;
		}
		JsonObject state = new JsonObject();
		JsonObject values = new JsonObject();
		for (Rule rule : Rule.values()) {
			values.addProperty(rule.name(), get(s, rule));
		}
		state.add("values", values);
		state.addProperty("locked", saved.locked);
		state.addProperty("host", isHost(s, player));
		state.addProperty("canEdit", canEdit(s, player));
		JsonArray coHosts = new JsonArray();
		saved.coHosts.forEach((id, name) -> {
			JsonObject c = new JsonObject();
			c.addProperty("id", id);
			c.addProperty("name", name);
			coHosts.add(c);
		});
		state.add("coHosts", coHosts);
		JsonArray history = new JsonArray();
		saved.history.stream().limit(20).forEach(h -> {
			JsonObject e = new JsonObject();
			e.addProperty("time", h.time());
			e.addProperty("who", h.who());
			e.addProperty("what", h.what());
			history.add(e);
		});
		state.add("history", history);
		ServerPlayNetworking.send(player, new RulesState(state.toString()));
	}

	/** Everything but the reader's role, built once per change. */
	private static com.javahost.util.WorldOptionsData.State worldState(MinecraftServer s) {
		List<com.javahost.util.WorldOptionsData.Operator> operators = new ArrayList<>();
		saved.coHosts.forEach((id, name) -> {
			UUID parsed = parseUuid(id);
			if (parsed != null) {
				ServerPlayer online = s.getPlayerList().getPlayer(parsed);
				operators.add(new com.javahost.util.WorldOptionsData.Operator(parsed, online != null ? com.javahost.compat.Compat.name(online) : name));
			}
		});
		return new com.javahost.util.WorldOptionsData.State(com.javahost.util.WorldOptionsData.Role.NONE, saved.locked, saved.announce,
				cheats(s), cheatsLocked(s), cheatsLockedReason(s), s.getWorldData().getDifficulty().getSerializedName(),
				s.getDefaultGameType().getName(), saved.voiceMode, operators, RuleCatalog.list(s));
	}

	/** What the World options screen shows, to notice changes made outside JavaHost (commands, the game's screens). */
	private static String fingerprint(MinecraftServer s) {
		StringBuilder text = new StringBuilder();
		text.append(cheats(s)).append('|').append(s.getWorldData().getDifficulty().getSerializedName()).append('|')
				.append(s.getDefaultGameType().getName());
		for (com.javahost.util.WorldOptionsData.Rule rule : RuleCatalog.list(s)) {
			text.append('|').append(rule.value());
		}
		return text.toString();
	}

	private static void broadcastWorldState(MinecraftServer s) {
		lastWorldState = fingerprint(s);
		com.javahost.util.WorldOptionsData.State state = worldState(s);
		for (ServerPlayer player : s.getPlayerList().getPlayers()) {
			sendWorldState(s, player, state);
		}
	}

	private static void sendWorldState(MinecraftServer s, ServerPlayer player, com.javahost.util.WorldOptionsData.State state) {
		if (!ServerPlayNetworking.canSend(player, com.javahost.net.Payloads.WorldState.TYPE)) {
			return;
		}
		com.javahost.util.WorldOptionsData.Role role = isHost(s, player) ? com.javahost.util.WorldOptionsData.Role.HOST
				: isOperator(player.getUUID()) ? com.javahost.util.WorldOptionsData.Role.OPERATOR : com.javahost.util.WorldOptionsData.Role.NONE;
		com.javahost.util.WorldOptionsData.State mine = new com.javahost.util.WorldOptionsData.State(role, state.locked(), state.announce(),
				state.cheats(), state.cheatsLocked(), state.cheatsReason(), state.difficulty(), state.gameMode(), state.voice(),
				state.operators(), state.rules());
		ServerPlayNetworking.send(player, new com.javahost.net.Payloads.WorldState(com.javahost.util.WorldOptionsData.toJson(mine)));
	}

	// ---------------------------------------------------------------- saving with the world

	private static Path file(MinecraftServer s) {
		return s.getWorldPath(LevelResource.ROOT).resolve("javahost").resolve("rules.json");
	}

	private static Saved load(MinecraftServer s) {
		Path file = file(s);
		try {
			if (Files.exists(file)) {
				Saved loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
				if (loaded != null) {
					if (loaded.lockedValues == null) {
						loaded.lockedValues = new LinkedHashMap<>();
					}
					if (loaded.coHosts == null) {
						loaded.coHosts = new LinkedHashMap<>();
					}
					if (loaded.history == null) {
						loaded.history = new ArrayList<>();
					}
					if (!Rule.VOICE.choices.contains(loaded.voiceMode)) {
						loaded.voiceMode = "nearby";
					}
					return loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("[World rules] couldn't read {}", file, e);
		}
		return new Saved();
	}

	private static void save(MinecraftServer s) {
		Path file = file(s);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[World rules] couldn't save {}", file, e);
		}
	}
}

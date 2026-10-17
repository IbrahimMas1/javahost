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
//? if >=1.21.11 {
import net.minecraft.world.level.gamerules.GameRules;
//?} else {
/*import net.minecraft.world.level.GameRules;
*///?}
import net.minecraft.world.level.storage.LevelResource;

/**
 * World rules, kept fair: only the host and the friends they make co-hosts can change them, every change is
 * announced in chat and kept in a history, and the host can lock the rules for a challenge run. All checks happen
 * here on the host's server, so a modified game can't get around them. While locked, the rules are held at their
 * locked values even if a command (/gamerule, /difficulty) tries to change them.
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
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_HISTORY = 50;
	private static MinecraftServer server;
	private static Saved saved = new Saved();

	private WorldRules() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			server = started;
			saved = load(started);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
			server = null;
			saved = new Saved();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> sendState(handler.player));
		ServerPlayNetworking.registerGlobalReceiver(RulesChange.TYPE, (change, context) -> {
			if (com.javahost.net.Guard.allow(context.player(), com.javahost.net.Guard.RULES, 1)) {
				handle(context.player(), change.rule(), change.value());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (saved.locked && s.getTickCount() % 20 == 0) {
				holdLockedValues(s);
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

	private static GameRules gameRules(MinecraftServer s) {
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

	static boolean canEdit(MinecraftServer s, ServerPlayer player) {
		return isHost(s, player) || saved.coHosts.containsKey(player.getUUID().toString());
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
			}
			record(s, who, lock ? "locked the rules" : "unlocked the rules");
			announce(s, who + (lock ? " locked the world rules. They can't be changed until unlocked." : " unlocked the world rules."));
		} else if (ruleName.startsWith("__cohost:")) {
			if (!isHost(s, player)) {
				refuse(player, "Only the host can choose co-hosts.");
				return;
			}
			UUID target = UUID.fromString(ruleName.substring("__cohost:".length()));
			ServerPlayer targetPlayer = s.getPlayerList().getPlayer(target);
			String name = targetPlayer != null ? com.javahost.compat.Compat.name(targetPlayer) : saved.coHosts.getOrDefault(target.toString(), "a player");
			if (Boolean.parseBoolean(value)) {
				saved.coHosts.put(target.toString(), name);
				record(s, who, "made " + name + " a co-host");
				announce(s, name + " is now a co-host and can change world rules.");
			} else if (saved.coHosts.remove(target.toString()) != null) {
				record(s, who, "removed " + name + " as co-host");
				announce(s, name + " is no longer a co-host.");
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
			if (!canEdit(s, player)) {
				refuse(player, "Only the host and co-hosts can change world rules.");
				return;
			}
			if (saved.locked) {
				refuse(player, "The world rules are locked.");
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
		if (reverted) {
			announce(s, "The rules are locked, so that change was undone.");
			broadcastState(s);
		}
	}

	private static void refuse(ServerPlayer player, String why) {
		player.sendSystemMessage(Component.literal("[World rules] " + why).withStyle(ChatFormatting.RED));
		sendState(player);
	}

	private static void announce(MinecraftServer s, String text) {
		s.getPlayerList().broadcastSystemMessage(Component.literal("[World rules] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE)), false);
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
			sendState(player);
		}
	}

	private static void sendState(ServerPlayer player) {
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

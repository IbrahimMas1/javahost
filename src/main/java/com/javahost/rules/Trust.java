package com.javahost.rules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.javahost.HostState;
import com.javahost.JavaHost;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Who may build in a public world. Strangers join as visitors (Adventure: they can look around, fight and use
 * doors, but not break or place blocks) until the host trusts them. The host's friends are always trusted. Kept per
 * world in javahost/trust.json, next to the world rules.
 */
public final class Trust {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static MinecraftServer server;
	private static Map<String, String> trusted = new LinkedHashMap<>(); // uuid -> name

	private Trust() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			server = started;
			trusted = load(started);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
			server = null;
			trusted = new LinkedHashMap<>();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> welcome(s, handler.player));
	}

	/** A stranger in a public world who hasn't been trusted yet. */
	public static boolean isVisitor(MinecraftServer server, ServerPlayer player) {
		return HostState.publicWorld() && !com.javahost.compat.Compat.isOwner(server, player) && !isTrusted(player.getUUID());
	}

	public static boolean isTrusted(UUID player) {
		return HostState.isAllowed(player) || trusted.containsKey(player.toString());
	}

	/** Lets a visitor build: Survival (or the world's usual mode) from now on, in this world. Server thread. */
	public static void trust(UUID id, String name) {
		trusted.put(id.toString(), name);
		save();
		ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
		if (player != null) {
			player.setGameMode(server.getDefaultGameType());
			player.sendSystemMessage(Component.literal("[JavaHost] The host trusts you now: you can build here.").withStyle(ChatFormatting.GREEN));
		}
	}

	/** Back to visiting (Adventure). Server thread. */
	public static void untrust(UUID id) {
		trusted.remove(id.toString());
		save();
		ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
		if (player != null && isVisitor(server, player)) {
			player.setGameMode(GameType.ADVENTURE);
			player.sendSystemMessage(Component.literal("[JavaHost] You're a visitor again: you can look around but not build.").withStyle(ChatFormatting.YELLOW));
		}
	}

	private static void welcome(MinecraftServer s, ServerPlayer player) {
		if (!HostState.publicWorld() || com.javahost.compat.Compat.isOwner(s, player)) {
			return;
		}
		if (isVisitor(s, player)) {
			player.setGameMode(GameType.ADVENTURE);
			String host = s.getSingleplayerProfile() != null ? com.javahost.compat.Compat.hostName(s) : "the host";
			player.sendSystemMessage(Component.literal("[JavaHost] You're visiting " + host + "'s public world. Look around, say hi, "
					+ "and ask the host to trust you if you'd like to build.").withStyle(ChatFormatting.AQUA));
		} else if (com.javahost.compat.Compat.gameMode(player) == GameType.ADVENTURE && s.getDefaultGameType() != GameType.ADVENTURE) {
			player.setGameMode(s.getDefaultGameType()); // trusted while they were away
		}
	}

	private static Path file(MinecraftServer s) {
		return s.getWorldPath(LevelResource.ROOT).resolve("javahost").resolve("trust.json");
	}

	private static Map<String, String> load(MinecraftServer s) {
		try {
			Path file = file(s);
			if (Files.exists(file)) {
				Map<String, String> map = GSON.fromJson(Files.readString(file), new TypeToken<LinkedHashMap<String, String>>() {
				}.getType());
				return map == null ? new LinkedHashMap<>() : map;
			}
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("Couldn't read the trusted players list", e);
		}
		return new LinkedHashMap<>();
	}

	private static void save() {
		if (server == null) {
			return;
		}
		try {
			Path file = file(server);
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(trusted));
		} catch (IOException e) {
			JavaHost.LOGGER.warn("Couldn't save the trusted players list", e);
		}
	}
}

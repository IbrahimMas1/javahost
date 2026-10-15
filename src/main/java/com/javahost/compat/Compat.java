package com.javahost.compat;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/**
 * Server-side calls that differ between the Minecraft versions JavaHost is built for, kept in one place. The
 * {@code //?} comments are read by Stonecutter, which picks the right line for each version.
 */
public final class Compat {
	private Compat() {
	}

	/** Whether this player is the one whose game runs the world (singleplayer owner). */
	public static boolean isOwner(MinecraftServer server, Player player) {
		//? if >=1.21.9 {
		return server.isSingleplayerOwner(player.nameAndId());
		//?} else
		/*return server.isSingleplayerOwner(player.getGameProfile());*/
	}

	/** The same, for a player still logging in. */
	public static boolean isOwner(MinecraftServer server, GameProfile profile) {
		//? if >=1.21.9 {
		return server.isSingleplayerOwner(new net.minecraft.server.players.NameAndId(profile));
		//?} else
		/*return server.isSingleplayerOwner(profile);*/
	}

	public static String name(Player player) {
		//? if >=1.21.9 {
		return player.getPlainTextName();
		//?} else
		/*return player.getGameProfile().getName();*/
	}

	public static String name(GameProfile profile) {
		//? if >=1.21.9 {
		return profile.name();
		//?} else
		/*return profile.getName();*/
	}

	public static UUID id(GameProfile profile) {
		//? if >=1.21.9 {
		return profile.id();
		//?} else
		/*return profile.getId();*/
	}

	/** The host's name, or "" when the server has no singleplayer owner. */
	public static String hostName(MinecraftServer server) {
		GameProfile profile = server.getSingleplayerProfile();
		return profile == null ? "" : name(profile);
	}

	public static GameType gameMode(ServerPlayer player) {
		//? if >=1.21.5 {
		return player.gameMode();
		//?} else
		/*return player.gameMode.getGameModeForPlayer();*/
	}
}

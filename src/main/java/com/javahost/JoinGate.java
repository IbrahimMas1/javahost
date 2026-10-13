package com.javahost;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

/** Who may join a hosted world: not banned or blocked, room left, and (by default) on the host's friends list. */
public final class JoinGate {
	private JoinGate() {
	}

	/**
	 * Why this player can't join, or null. {@code alreadyIn}: checked after they've been added to the player list
	 * (the backstop on join) rather than at login.
	 */
	public static String refusal(MinecraftServer server, NameAndId player) {
		if (server.isSingleplayerOwner(player)) {
			return null;
		}
		if (HostState.isBanned(player.id())) {
			return "The host has banned you from this world.";
		}
		// Blocked players get the same answer as strangers, so they can't tell they were blocked.
		// Friends-only applies to worlds hosted from a player's game, not to dedicated servers running JavaHost.
		boolean hosted = !server.isDedicatedServer();
		if (hosted && (HostState.isBlocked(player.id()) || (HostState.friendsOnly() && !HostState.isAllowed(player.id())))) {
			return "This world is just for the host's friends. Ask them to add you in JavaHost.";
		}
		long others = server.getPlayerList().getPlayers().stream().filter(p -> !p.getUUID().equals(player.id())).count();
		if (others >= HostState.maxPlayers()) {
			return "This world is full (" + HostState.maxPlayers() + (HostState.maxPlayers() == 1 ? " player)." : " players).");
		}
		return null;
	}

	public static void log(String name, String refusal) {
		JavaHost.LOGGER.info("Turned away {}: {}", name, refusal);
	}
}

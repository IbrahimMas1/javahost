package com.javahost;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/** Who may join a hosted world: not banned or blocked, room left, and (by default) on the host's friends list. */
public final class JoinGate {
	private JoinGate() {
	}

	/**
	 * Why this player can't join, or null. {@code owner}: they're the player whose game runs the world.
	 */
	public static String refusal(MinecraftServer server, UUID id, boolean owner) {
		if (owner) {
			return null;
		}
		if (HostState.isBanned(id)) {
			return "The host has banned you from this world.";
		}
		// Blocked players get the same answer as strangers, so they can't tell they were blocked.
		// Friends-only applies to worlds hosted from a player's game, not to dedicated servers running JavaHost.
		boolean hosted = !server.isDedicatedServer();
		// A public world lets strangers in (as visitors); otherwise friends-only applies.
		boolean strangersWelcome = HostState.publicWorld();
		if (hosted && (HostState.isBlocked(id) || (HostState.friendsOnly() && !strangersWelcome && !HostState.isAllowed(id)))) {
			return "This world is just for the host's friends. Ask them to add you in JavaHost.";
		}
		long others = server.getPlayerList().getPlayers().stream().filter(p -> !p.getUUID().equals(id)).count();
		if (others >= HostState.maxPlayers()) {
			return "This world is full (" + HostState.maxPlayers() + (HostState.maxPlayers() == 1 ? " player)." : " players).");
		}
		return null;
	}

	public static void log(String name, String refusal) {
		JavaHost.LOGGER.info("Turned away {}: {}", name, refusal);
	}
}

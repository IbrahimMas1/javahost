package com.javahost;

import java.nio.file.Path;
import java.util.List;

import com.javahost.net.Payloads.PackInfo;

/**
 * What the server side needs to know about the current host's choices. On a hosted world the
 * client and the integrated server share a JVM, so the host's client fills this in when it starts
 * hosting, and the server reads it when players join. On a dedicated server it keeps its defaults.
 */
public final class HostState {
	/** A pack the host is sharing, along with the file on disk it is streamed from. */
	public record SharedPack(PackInfo info, Path file) {
	}

	private static volatile List<SharedPack> sharedPacks = List.of();
	private static volatile String shaderSettings = "";
	private static volatile int voiceRange = 48;
	private static volatile boolean voiceEnabled = true;
	private static volatile boolean friendsOnly = false;
	private static volatile java.util.Set<java.util.UUID> allowedPlayers = java.util.Set.of();
	private static volatile java.util.Set<java.util.UUID> bannedPlayers = java.util.Set.of();
	private static volatile int maxPlayers = Integer.MAX_VALUE;

	private HostState() {
	}

	public static List<SharedPack> sharedPacks() {
		return sharedPacks;
	}

	public static String shaderSettings() {
		return shaderSettings;
	}

	public static void setSharedPacks(List<SharedPack> packs, String settings) {
		sharedPacks = List.copyOf(packs);
		shaderSettings = settings == null ? "" : settings;
	}

	public static SharedPack findPack(String sha1) {
		for (SharedPack pack : sharedPacks) {
			if (pack.info().sha1().equals(sha1)) {
				return pack;
			}
		}
		return null;
	}

	public static int voiceRange() {
		return voiceRange;
	}

	public static boolean voiceEnabled() {
		return voiceEnabled;
	}

	public static void setVoice(boolean enabled, int range) {
		voiceEnabled = enabled;
		voiceRange = Math.max(4, Math.min(range, 256));
	}

	/** When on, only the host's friends may join the hosted world (accounts are verified by Minecraft itself). */
	public static boolean friendsOnly() {
		return friendsOnly;
	}

	public static void setFriendsOnly(boolean value) {
		friendsOnly = value;
	}

	public static boolean isAllowed(java.util.UUID player) {
		return allowedPlayers.contains(player);
	}

	public static void setAllowedPlayers(java.util.Set<java.util.UUID> players) {
		allowedPlayers = java.util.Set.copyOf(players);
	}

	public static boolean isBanned(java.util.UUID player) {
		return bannedPlayers.contains(player);
	}

	public static void setBannedPlayers(java.util.Set<java.util.UUID> players) {
		bannedPlayers = java.util.Set.copyOf(players);
	}

	/** Most players allowed in the hosted world, counting the host. */
	public static int maxPlayers() {
		return maxPlayers;
	}

	public static void setMaxPlayers(int value) {
		maxPlayers = value;
	}

	public static void clear() {
		sharedPacks = List.of();
		shaderSettings = "";
	}
}

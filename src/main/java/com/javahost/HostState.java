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

	public static void clear() {
		sharedPacks = List.of();
		shaderSettings = "";
	}
}

package com.javahost.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.javahost.JavaHost;
import com.javahost.client.host.Connectivity;
import net.fabricmc.loader.api.FabricLoader;

/** Settings saved to {@code config/javahost.json}. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("javahost.json");
	private static Config instance;

	// Hosting
	public int port = 25565;
	public boolean useUpnp = true;
	public boolean useIpv6 = true;
	/** AUTO: relay only when direct connections fail; ALWAYS; NEVER. */
	public String relayMode = "AUTO";
	public boolean shareResourcePacks = true;
	public boolean shareShaders = true;
	public boolean voiceEnabledWhenHosting = true;
	public int voiceRange = 48;

	// Joining
	public boolean autoAcceptHostPacks = false;
	/** Most recent first: codes or addresses the player joined, with a label. */
	public java.util.List<RecentJoin> recentJoins = new java.util.ArrayList<>();

	public record RecentJoin(String invite, String label, long time) {
	}

	// HUD
	public boolean showVoiceOverlay = true;
	/** "TOP_LEFT" or "TOP_RIGHT". */
	public String hudCorner = "TOP_LEFT";
	public boolean muted = false;

	// Voice
	public boolean voiceEnabled = true;
	public boolean pushToTalk = true;
	/** Microphone level (dBFS) above which voice activation opens the mic. */
	public double activationThresholdDb = -45;
	public double micGain = 1.0;
	public double outputVolume = 1.0;
	/** Name of the Java Sound mixer to record from; empty means the system default. */
	public String microphone = "";

	// Performance: the user's video settings from before "Quick Tune", so it can be undone.
	public Map<String, String> settingsBackup = new HashMap<>();

	public static Config get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Config load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE)) {
				Config config = GSON.fromJson(reader, Config.class);
				if (config != null) {
					if (config.settingsBackup == null) {
						config.settingsBackup = new HashMap<>();
					}
					if (config.recentJoins == null) {
						config.recentJoins = new java.util.ArrayList<>();
					}
					try {
						Connectivity.RelayMode.valueOf(config.relayMode);
					} catch (RuntimeException e) {
						config.relayMode = "AUTO";
					}
					return config;
				}
			} catch (Exception e) {
				JavaHost.LOGGER.warn("Could not read {}, using defaults", FILE, e);
			}
		}
		return new Config();
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("Could not save {}", FILE, e);
		}
	}
}

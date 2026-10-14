package com.javahost.mods;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/** Which mod set this game started with (written by {@code ModSwap} when it switched), readable on both sides. */
public final class ModSetsState {
	private ModSetsState() {
	}

	public static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("javahost").resolve("modsets");
	}

	public static Path stateFile() {
		return dir().resolve("active.properties");
	}

	/** The active set's details, or empty properties when the player's own mods are in use. */
	public static Properties active() {
		Properties state = new Properties();
		if (Files.exists(stateFile())) {
			try (InputStream in = Files.newInputStream(stateFile())) {
				state.load(in);
			} catch (IOException ignored) {
				// Treat as the player's own mods.
			}
		}
		return state;
	}

	/** The modpack in use ("Fabulously Optimized 7.1"), or "" if none. */
	public static String packName() {
		Properties state = active();
		return state.getProperty("set", "").startsWith("pack-") ? state.getProperty("label", "") : "";
	}
}

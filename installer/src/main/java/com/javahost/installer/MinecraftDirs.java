package com.javahost.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Where the vanilla Minecraft Launcher keeps things on each operating system. */
public final class MinecraftDirs {
	public static final String GAME_DIR_NAME = "javahost";

	private MinecraftDirs() {
	}

	public static Path defaultMinecraftDir() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			return Path.of(appData != null ? appData : home, ".minecraft");
		}
		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support", "minecraft");
		}
		return Path.of(home, ".minecraft");
	}

	/** The launcher creates this the first time it runs; we need it to add our profile. */
	public static boolean hasLauncherProfiles(Path minecraftDir) {
		return Files.isRegularFile(minecraftDir.resolve("launcher_profiles.json"))
				|| Files.isRegularFile(minecraftDir.resolve("launcher_profiles_microsoft_store.json"));
	}

	/** JavaHost's own game folder: its own mods, settings and worlds, separate from normal Minecraft. */
	public static Path gameDir(Path minecraftDir) {
		return minecraftDir.resolve(GAME_DIR_NAME);
	}

	/** True if the Minecraft Launcher seems to be running (it may overwrite profile changes when it exits). */
	public static boolean launcherRunning() {
		return ProcessHandle.allProcesses().anyMatch(process -> process.info().command().map(command -> {
			String c = command.toLowerCase(Locale.ROOT);
			return c.contains("minecraft.app/contents/macos/launcher")
					|| c.endsWith("minecraftlauncher.exe")
					|| (c.contains("minecraft launcher") && c.endsWith("minecraft.exe"))
					|| c.endsWith("/minecraft-launcher");
		}).orElse(false));
	}
}

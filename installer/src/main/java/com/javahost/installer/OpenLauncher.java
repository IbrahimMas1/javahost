package com.javahost.installer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Opens the Minecraft Launcher after installing (best effort; the player can always open it themselves). */
public final class OpenLauncher {
	private OpenLauncher() {
	}

	public static boolean open() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("mac")) {
				new ProcessBuilder("open", "-a", "Minecraft").start();
				return true;
			}
			if (os.contains("win")) {
				String pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
				for (Path exe : List.of(
						Path.of("C:\\XboxGames\\Minecraft Launcher\\Content\\Minecraft.exe"),
						Path.of(pf86, "Minecraft Launcher", "MinecraftLauncher.exe"),
						Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Minecraft Launcher", "MinecraftLauncher.exe"))) {
					if (Files.isRegularFile(exe)) {
						new ProcessBuilder(exe.toString()).start();
						return true;
					}
				}
				// Microsoft Store version.
				new ProcessBuilder("explorer.exe", "shell:AppsFolder\\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft").start();
				return true;
			}
			new ProcessBuilder("minecraft-launcher").start();
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}

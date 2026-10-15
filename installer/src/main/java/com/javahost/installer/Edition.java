package com.javahost.installer;

import java.nio.file.Path;

/**
 * JavaHost for one Minecraft version: its own launcher profile and its own game folder, so mods made for different
 * versions never mix. 26.3 keeps the profile and folder JavaHost has always used ("javahost").
 */
public record Edition(String minecraft) {
	/** The version JavaHost started on; installs from before 1.10 are this edition. */
	public static final String ORIGINAL = "26.3";

	public Edition {
		if (!minecraft.matches("[0-9]+(\\.[0-9]+){1,3}")) {
			throw new IllegalArgumentException("Not a Minecraft version: " + minecraft);
		}
	}

	private boolean original() {
		return ORIGINAL.equals(minecraft);
	}

	public String profileKey() {
		return original() ? "javahost" : "javahost-" + minecraft;
	}

	public String profileName() {
		return "JavaHost " + minecraft;
	}

	public Path gameDir(Path minecraftDir) {
		return minecraftDir.resolve(original() ? MinecraftDirs.GAME_DIR_NAME : MinecraftDirs.GAME_DIR_NAME + "-" + minecraft);
	}
}

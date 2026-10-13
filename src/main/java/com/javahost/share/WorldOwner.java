package com.javahost.share;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Makes a world someone sent you yours. The game plays the singleplayer owner as whoever {@code singleplayer_uuid}
 * in level.dat names (loading {@code players/data/<that uuid>.dat}), so pointing it at you means you continue as
 * yourself: your own inventory and position if you've played there before, a fresh start at spawn if not. The
 * sender's own player file is untouched, so they're where they left off when they join your copy.
 */
public final class WorldOwner {
	private static final String OWNER = "singleplayer_uuid";
	/** Very old worlds kept the owner's player right inside level.dat; it would win over the player files. */
	private static final String OLD_PLAYER = "Player";
	/** level.dat is a few KB; a friend's file can't make us unpack more than this (a "zip bomb" in NBT form). */
	private static final long LEVEL_DAT_LIMIT = 64L * 1024 * 1024;

	private WorldOwner() {
	}

	/** Sets the owner and the name shown in the world list. */
	public static void adopt(Path worldDir, UUID owner, String levelName) throws IOException {
		Path levelDat = worldDir.resolve("level.dat");
		CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.create(LEVEL_DAT_LIMIT));
		CompoundTag data = root.getCompoundOrEmpty("Data");
		data.store(OWNER, UUIDUtil.CODEC, owner);
		data.remove(OLD_PLAYER);
		data.putString("LevelName", levelName);
		root.put("Data", data);
		NbtIo.writeCompressed(root, levelDat);
		// The backup copy still names the sender; the game falls back to it only if level.dat breaks.
		Files.deleteIfExists(worldDir.resolve("level.dat_old"));
		// JavaHost's world rules (co-hosts, lock, history) belong to the sender's hosting, not the copy: otherwise a
		// sender could list themselves as a co-host and change rules when you host it.
		Files.deleteIfExists(worldDir.resolve("javahost").resolve("rules.json"));
	}

	/** The owner a world's level.dat names, if any. */
	public static Optional<UUID> ownerOf(Path worldDir) throws IOException {
		CompoundTag root = NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.create(LEVEL_DAT_LIMIT));
		return root.getCompoundOrEmpty("Data").read(OWNER, UUIDUtil.CODEC);
	}

	/** The name a world shows in the world list, or the folder name. */
	public static String nameOf(Path worldDir) {
		try {
			CompoundTag root = NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.create(LEVEL_DAT_LIMIT));
			return root.getCompoundOrEmpty("Data").getString("LevelName").filter(s -> !s.isBlank())
					.orElse(worldDir.getFileName().toString());
		} catch (IOException | RuntimeException e) {
			return worldDir.getFileName().toString();
		}
	}

	/** A folder name in {@code saves} that's free and safe on every OS: "Survival (from Alex)", then "(2)" and so on. */
	public static Path freeFolder(Path saves, String name) {
		String base = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^[ .]+|[ .]+$", "").trim();
		if (base.isEmpty() || base.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)")) {
			base = "World";
		}
		if (base.length() > 60) {
			base = base.substring(0, 60).trim();
		}
		Path candidate = saves.resolve(base);
		for (int i = 2; Files.exists(candidate); i++) {
			candidate = saves.resolve(base + " (" + i + ")");
		}
		return candidate;
	}
}

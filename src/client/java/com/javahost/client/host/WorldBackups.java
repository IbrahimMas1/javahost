package com.javahost.client.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.Toasts;
import com.javahost.util.WorldZip;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * World backups in javahost/backups/&lt;world&gt;/. Automatic ones (a friend joins, the world opens to the public)
 * happen at most once every 30 minutes per world, judged by the newest backup on disk, so restarting the game or
 * reopening the world doesn't make more; only the newest 3 are kept. "Back up now" always works. Restoring never
 * deletes anything: the current world is set aside under a new name first.
 */
public final class WorldBackups {
	private static final int KEEP = 3;
	private static final long MIN_GAP_MS = 30 * 60 * 1000L;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
	private static final DateTimeFormatter ASIDE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");
	private static volatile boolean running;

	public record Backup(String world, Path file, long size, long time) {
	}

	private WorldBackups() {
	}

	public static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("javahost").resolve("backups");
	}

	/** Called on the server thread when a friend joins a world you're hosting. */
	public static void onFriendJoined(MinecraftServer server) {
		if (Config.get().autoBackup) {
			backUpIfDue(server, "a friend joined");
		}
	}

	/**
	 * An automatic backup: only if this world's newest backup is over 30 minutes old (or there's none). Completes
	 * with the newest backup either way. Server thread only.
	 */
	public static CompletableFuture<Path> backUpIfDue(MinecraftServer server, String why) {
		String world = worldFolder(server);
		List<Backup> existing = list(world);
		if (running || (!existing.isEmpty() && System.currentTimeMillis() - existing.getFirst().time() < MIN_GAP_MS)) {
			JavaHost.LOGGER.info("[Backups] skipped ({}): the last backup of {} is recent", why, world);
			return CompletableFuture.completedFuture(existing.isEmpty() ? null : existing.getFirst().file());
		}
		return backUp(server, why);
	}

	/** Saves the world, pauses autosave, zips it in the background, then turns autosave back on. Server thread only. */
	public static CompletableFuture<Path> backUp(MinecraftServer server, String why) {
		String world = worldFolder(server);
		Path zip = root().resolve(world).resolve(LocalDateTime.now().format(STAMP) + ".zip");
		return snapshot(server, zip).whenComplete((done, error) -> {
			if (error == null) {
				JavaHost.LOGGER.info("[Backups] backed up {} because {}", world, why);
				try {
					trim(world);
				} catch (IOException e) {
					JavaHost.LOGGER.warn("[Backups] couldn't remove old backups of {}", world, e);
				}
			}
			Minecraft.getInstance().execute(() -> {
				if (error == null) {
					Toasts.notify("World backed up", "Restore it any time from the Host tab.");
				} else {
					JavaHost.LOGGER.warn("[Backups] could not back up {}", world, error);
					Toasts.notify("Couldn't back up your world", "JavaHost will try again later.");
				}
			});
		});
	}

	/**
	 * Saves the open world, pauses autosave, zips it to {@code zip} in the background, then turns autosave back
	 * on. Used for backups and for sending a live copy to a friend. Server thread only.
	 */
	public static CompletableFuture<Path> snapshot(MinecraftServer server, Path zip) {
		if (running) {
			return CompletableFuture.failedFuture(new IOException("A copy of this world is already being made"));
		}
		Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		running = true;
		server.saveEverything(true, true, false);
		List<ServerLevel> paused = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			if (!level.noSave) {
				level.noSave = true;
				paused.add(level);
			}
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				long size = WorldZip.zip(worldDir, zip);
				JavaHost.LOGGER.info("[Backups] zipped {} ({} KB)", worldDir.getFileName(), size / 1024);
				return zip;
			} catch (IOException e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, com.javahost.util.IoExecutor.POOL).whenComplete((done, error) -> {
			server.execute(() -> paused.forEach(level -> level.noSave = false));
			running = false;
		});
	}

	private static String worldFolder(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
	}

	/** Once at startup: every world down to the newest few backups (older versions kept 5). Background thread. */
	public static void trimAll() {
		com.javahost.util.IoExecutor.run(() -> {
			if (!Files.isDirectory(root())) {
				return;
			}
			try (Stream<Path> worlds = Files.list(root())) {
				for (Path world : worlds.filter(Files::isDirectory).toList()) {
					trim(world.getFileName().toString());
				}
			} catch (IOException e) {
				JavaHost.LOGGER.warn("[Backups] couldn't tidy old backups", e);
			}
		});
	}

	/** Keeps the newest few backups of a world. */
	private static void trim(String world) throws IOException {
		List<Backup> all = list(world);
		for (int i = KEEP; i < all.size(); i++) {
			Files.deleteIfExists(all.get(i).file());
		}
	}

	/** Every world that has backups, newest backup first within each. */
	public static List<Backup> listAll() {
		List<Backup> all = new ArrayList<>();
		if (!Files.isDirectory(root())) {
			return all;
		}
		try (Stream<Path> worlds = Files.list(root())) {
			for (Path dir : worlds.filter(Files::isDirectory).toList()) {
				all.addAll(list(dir.getFileName().toString()));
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Backups] could not list backups", e);
		}
		all.sort(Comparator.comparingLong(Backup::time).reversed());
		return all;
	}

	public static List<Backup> list(String world) {
		Path dir = root().resolve(world);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> f.getFileName().toString().endsWith(".zip")).map(f -> {
				try {
					return new Backup(world, f, Files.size(f), Files.getLastModifiedTime(f).toMillis());
				} catch (IOException e) {
					return null;
				}
			}).filter(b -> b != null).sorted(Comparator.comparingLong(Backup::time).reversed()).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/**
	 * Puts a backup back. Only when no world is open. The current world folder is renamed to
	 * "&lt;name&gt; (before restore &lt;date&gt;)" first, so nothing is ever lost. Returns that new name.
	 */
	public static String restore(Backup backup) throws IOException {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null || minecraft.getSingleplayerServer() != null) {
			throw new IOException("Close the world first");
		}
		Path saves = minecraft.getLevelSource().getBaseDir();
		Path world = saves.resolve(backup.world());
		String aside = null;
		if (Files.exists(world)) {
			aside = backup.world() + " (before restore " + LocalDateTime.now().format(ASIDE) + ")";
			Files.move(world, saves.resolve(aside));
		}
		try {
			// The same safe unpacking as worlds from friends: sizes come from the zip and are enforced while writing.
			WorldZip.unzipWorld(backup.file(), world, WorldZip.unpackedSize(backup.file()) + 64L * 1024 * 1024, 2_000_000);
		} catch (IOException e) {
			// Put the original back if the restore didn't work (keeping any half-restored copy aside, not deleting it).
			if (Files.exists(world)) {
				Files.move(world, saves.resolve(backup.world() + " (failed restore " + LocalDateTime.now().format(ASIDE) + ")"));
			}
			if (aside != null) {
				Files.move(saves.resolve(aside), world);
			}
			throw e;
		}
		JavaHost.LOGGER.info("[Backups] restored {} from {}", backup.world(), backup.file().getFileName());
		return aside;
	}
}

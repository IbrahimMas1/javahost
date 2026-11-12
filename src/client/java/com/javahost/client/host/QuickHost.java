package com.javahost.client.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.javahost.JavaHost;
import com.javahost.client.Toasts;
import com.javahost.share.WorldOwner;
import com.javahost.util.IoExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * Hosting straight from the title screen: your saved worlds (most recently played first), and "host this one", which
 * opens the world and starts hosting as soon as you're in it.
 */
public final class QuickHost {
	/** A singleplayer world on disk. {@code icon} is its picture (icon.png), or null if it has none yet. */
	public record SavedWorld(String folder, String name, long lastPlayed, Path icon) {
	}

	private static volatile List<SavedWorld> worlds = List.of();
	private static volatile int version;
	private static volatile boolean loading;
	private static long loadedAt;
	/** The world folder being opened to host, and since when (so a cancelled load doesn't host some later world). */
	private static String pending;
	private static long pendingSince;
	private static int readyTicks;

	private QuickHost() {
	}

	/** Your worlds as last read from disk; reads them again in the background when that's more than a few seconds old. */
	public static List<SavedWorld> worlds() {
		if (!loading && System.currentTimeMillis() - loadedAt > 5000) {
			loading = true;
			loadedAt = System.currentTimeMillis();
			Path saves = Minecraft.getInstance().getLevelSource().getBaseDir();
			IoExecutor.POOL.execute(() -> {
				try {
					List<SavedWorld> found = read(saves);
					if (!found.equals(worlds)) {
						worlds = found;
						version++;
					}
				} finally {
					loading = false;
				}
			});
		}
		return worlds;
	}

	/** Goes up whenever the list changes, so pages know to redraw. */
	public static int version() {
		return version;
	}

	private static List<SavedWorld> read(Path saves) {
		List<SavedWorld> found = new ArrayList<>();
		try (Stream<Path> list = Files.list(saves)) {
			for (Path dir : list.filter(d -> Files.isRegularFile(d.resolve("level.dat"))).toList()) {
				try {
					Path icon = dir.resolve("icon.png");
					found.add(new SavedWorld(dir.getFileName().toString(), WorldOwner.nameOf(dir),
							Files.getLastModifiedTime(dir.resolve("level.dat")).toMillis(), Files.isRegularFile(icon) ? icon : null));
				} catch (IOException | RuntimeException e) {
					JavaHost.LOGGER.debug("[QuickHost] skipping {}: {}", dir.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[QuickHost] couldn't list saved worlds", e);
		}
		found.sort(Comparator.comparingLong(SavedWorld::lastPlayed).reversed());
		return List.copyOf(found);
	}

	/** Opens {@code world} and starts hosting once you're in it. */
	public static void host(SavedWorld world) {
		pending = world.folder();
		pendingSince = System.currentTimeMillis();
		readyTicks = 0;
		play(world);
	}

	/** Opens {@code world} without hosting. */
	public static void play(SavedWorld world) {
		Minecraft minecraft = Minecraft.getInstance();
		net.minecraft.client.gui.screens.Screen back = minecraft.gui.screen();
		minecraft.createWorldOpenFlows().openWorld(world.folder(), () -> {
			pending = null; // backed out of a warning: nothing to host
			minecraft.gui.setScreen(back);
		});
	}

	/** The world's picture, or a tile with its first letter while it has none (or is still loading). */
	public static void drawIcon(net.minecraft.client.gui.GuiGraphicsExtractor g, SavedWorld world, int x, int y, int size) {
		com.javahost.client.friends.Screenshots.Thumbnail picture = world.icon() == null ? null : com.javahost.client.friends.Screenshots.thumbnail(world.icon());
		if (picture != null) {
			com.javahost.client.gui.ui.ImageButton.drawCover(g, picture, x, y, size, size);
			return;
		}
		com.javahost.client.gui.ui.Draw.roundRect(g, x, y, size, size, 4, com.javahost.client.gui.ui.Theme.withAlpha(com.javahost.client.gui.ui.Theme.ACCENT, 0.18f));
		String letter = world.name().isEmpty() ? "?" : world.name().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
		com.javahost.client.gui.ui.Draw.centered(g, letter, x + size / 2, y + (size - 8) / 2, com.javahost.client.gui.ui.Theme.ACCENT);
	}

	/** Whether a world is being opened to host. */
	public static boolean pending() {
		return pending != null;
	}

	/** "today", "yesterday", "3 days ago"... */
	public static String played(long time) {
		long days = Math.max(0, (System.currentTimeMillis() - time) / 86_400_000L);
		return days == 0 ? "Played today" : days == 1 ? "Played yesterday" : days < 14 ? "Played " + days + " days ago"
				: days < 60 ? "Played " + days / 7 + " weeks ago" : "Played " + days / 30 + " months ago";
	}

	public static void tick(Minecraft minecraft) {
		if (pending == null) {
			return;
		}
		if (System.currentTimeMillis() - pendingSince > 180_000) {
			pending = null; // never got into the world
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null || minecraft.level == null || minecraft.gui.screen() != null) {
			readyTicks = 0;
			return;
		}
		if (++readyTicks < 20) {
			return; // a second in the world first, so the join has settled
		}
		pending = null;
		if (HostController.isHosting()) {
			return;
		}
		String error = HostController.start(server);
		if (error != null) {
			Toasts.show("quick-host", "Couldn't start hosting", error);
		} else {
			Toasts.show("quick-host", "You're hosting " + server.getWorldData().getLevelName(),
					"Press " + com.javahost.client.JavaHostClient.OPEN_MENU.getTranslatedKeyMessage().getString() + " to invite friends.");
		}
	}
}

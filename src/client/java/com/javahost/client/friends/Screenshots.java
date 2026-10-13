package com.javahost.client.friends;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.javahost.JavaHost;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Taking, listing, shrinking and receiving screenshots, plus small thumbnail textures for the gallery. */
public final class Screenshots {
	private static final int MAX_SEND_WIDTH = 1920;
	private static final int[] SEND_WIDTHS = {1920, 1600, 1366, 1280, 1024, 800, 640};
	/** ntfy.sh's free tier takes files up to 2 MB; leave room for encryption overhead. */
	private static final int RELAY_LIMIT = 1_900_000;
	private static final int GAME_LIMIT = 3_800_000;
	private static final int THUMB_WIDTH = 160;
	private static final int PREVIEW_WIDTH = 960;
	private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	private record Thumb(Identifier id, int width, int height) {
	}

	private static final Map<String, Thumb> THUMBS = new HashMap<>();
	private static int thumbCounter;
	/** Bumped when thumbnails are released, so a decode that finishes afterwards is thrown away. */
	private static int thumbGeneration;
	private static final Thumb LOADING = new Thumb(null, -1, -1);

	private Screenshots() {
	}

	public static Path dir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
	}

	public static Path fromFriendsDir() {
		return dir().resolve("from-friends");
	}

	/** Takes a screenshot of the game (without the JavaHost menu) and hands back where it was saved. */
	public static void take(Consumer<Path> whenSaved) {
		Minecraft minecraft = Minecraft.getInstance();
		String name = "JavaHost_" + LocalDateTime.now().format(STAMP) + ".png";
		Path file = dir().resolve(name);
		Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
				message -> minecraft.execute(() -> {
					if (Files.exists(file)) {
						whenSaved.accept(file);
					}
				}));
	}

	/** Opens Finder/Explorer with this file selected (or just its folder where that isn't possible). */
	public static void reveal(Path file) {
		java.util.List<String> command = com.javahost.util.Reveal.command(System.getProperty("os.name", ""), file);
		if (command != null) {
			try {
				new ProcessBuilder(command).start();
				return;
			} catch (IOException e) {
				JavaHost.LOGGER.warn("Couldn't show {} in the file browser", file, e);
			}
		}
		com.mojang.blaze3d.Blaze3D.openPath(file.getParent());
	}

	/** Newest first. {@code fromFriends} picks the received folder instead of the player's own screenshots. */
	public static List<Path> list(boolean fromFriends) {
		Path folder = fromFriends ? fromFriendsDir() : dir();
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			// Each file's date is read once (sorting would otherwise read it on every comparison).
			record Dated(Path path, long modified) {
			}
			return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
					.map(p -> new Dated(p, modified(p)))
					.sorted(Comparator.comparingLong(Dated::modified).reversed())
					.limit(60)
					.map(Dated::path)
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	// ---------------------------------------------------------------- favourites, rename, delete

	/** Favourites are remembered by their path inside the game folder. */
	private static String key(Path file) {
		Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
		Path abs = file.toAbsolutePath().normalize();
		return (abs.startsWith(game) ? game.relativize(abs) : abs).toString().replace('\\', '/');
	}

	public static boolean isFavourite(Path file) {
		return com.javahost.client.Config.get().favouriteScreenshots.contains(key(file));
	}

	public static void setFavourite(Path file, boolean favourite) {
		var config = com.javahost.client.Config.get();
		if (favourite) {
			config.favouriteScreenshots.add(key(file));
		} else {
			config.favouriteScreenshots.remove(key(file));
		}
		config.save();
	}

	/** Favourites from both folders, newest first. */
	public static List<Path> favourites() {
		List<Path> all = new ArrayList<>(list(false));
		all.addAll(list(true));
		return all.stream().filter(Screenshots::isFavourite).toList(); // each list is newest first already
	}

	/** How many screenshots a folder holds (to notice new ones cheaply). */
	public static long count(boolean fromFriends) {
		Path folder = fromFriends ? fromFriendsDir() : dir();
		if (!Files.isDirectory(folder)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).count();
		} catch (IOException e) {
			return 0;
		}
	}

	/** Renames in place (keeping .png); returns the new path. The name may not contain folder separators. */
	public static Path rename(Path file, String newName) throws IOException {
		String name = newName.strip();
		if (name.toLowerCase().endsWith(".png")) {
			name = name.substring(0, name.length() - 4).strip();
		}
		if (name.isEmpty() || !name.matches("[^/\\\\:*?\"<>|]{1,80}") || name.startsWith(".")) {
			throw new IOException("Use letters, numbers, spaces, - or _ (no / \\ : * ? \" < > |).");
		}
		Path target = file.resolveSibling(name + ".png");
		if (Files.exists(target)) {
			throw new IOException("There's already a screenshot called " + name + ".");
		}
		boolean favourite = isFavourite(file);
		Files.move(file, target);
		if (favourite) {
			setFavourite(file, false);
			setFavourite(target, true);
		}
		return target;
	}

	public static void delete(Path file) throws IOException {
		setFavourite(file, false);
		Files.deleteIfExists(file);
	}

	private static long modified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	/**
	 * The PNG to send through the friend relay, which takes at most 2 MB per file: scaled down step by step until
	 * it fits. Call off the render thread.
	 */
	public static byte[] prepareForSending(Path file) throws IOException {
		return prepare(file, RELAY_LIMIT);
	}

	/** Same, for the in-game fast lane, which allows bigger files (so friends usually get the original). */
	public static byte[] prepareForGame(Path file) throws IOException {
		return prepare(file, GAME_LIMIT);
	}

	private static byte[] prepare(Path file, int limit) throws IOException {
		byte[] original = Files.readAllBytes(file);
		try (NativeImage image = NativeImage.read(original)) {
			if (original.length <= limit && image.getWidth() <= MAX_SEND_WIDTH) {
				return original;
			}
			byte[] best = null;
			for (int width : SEND_WIDTHS) {
				if (width >= image.getWidth() && best != null) {
					continue;
				}
				best = scaled(image, Math.min(width, image.getWidth()));
				if (best.length <= limit) {
					return best;
				}
			}
			throw new IOException("Screenshot is too big to send (" + best.length / 1024 + " KB)");
		}
	}

	private static byte[] scaled(NativeImage image, int width) throws IOException {
		int height = Math.max(1, image.getHeight() * width / image.getWidth());
		try (NativeImage scaled = new NativeImage(width, height, false)) {
			image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), scaled);
			Path temp = Files.createTempFile("javahost-send", ".png");
			try {
				scaled.writeToFile(temp);
				return Files.readAllBytes(temp);
			} finally {
				Files.deleteIfExists(temp);
			}
		}
	}

	/** Saves a screenshot a friend sent (only real PNG images are accepted). */
	static Path saveFromFriend(String friendName, byte[] png) throws IOException {
		// Checked from the header first: a tiny file claiming to be huge would otherwise eat gigabytes to decode.
		if (!isPng(png) || !com.javahost.util.PngInfo.safe(com.javahost.util.PngInfo.size(png))) {
			throw new IOException("Not a PNG image of a sensible size");
		}
		try (NativeImage check = NativeImage.read(png)) {
			// Parses fully, so a corrupt image is rejected before it's written.
		}
		Files.createDirectories(fromFriendsDir());
		String base = friendName + "_" + LocalDateTime.now().format(STAMP);
		Path file = fromFriendsDir().resolve(base + ".png");
		for (int i = 2; Files.exists(file); i++) {
			file = fromFriendsDir().resolve(base + "_" + i + ".png"); // two in the same second
		}
		Files.write(file, png);
		return file;
	}

	public static boolean isPng(byte[] data) {
		if (data.length < PNG_MAGIC.length) {
			return false;
		}
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			if (data[i] != PNG_MAGIC[i]) {
				return false;
			}
		}
		return true;
	}

	// ================================================================ thumbnails (render thread)

	/** A small texture for the gallery, made on first use. Returns null if the image can't be read. */
	public static Thumbnail thumbnail(Path file) {
		return load(file, THUMB_WIDTH);
	}

	/** A sharper copy for the big preview. */
	public static Thumbnail preview(Path file) {
		return load(file, PREVIEW_WIDTH);
	}

	private static Thumbnail load(Path file, int targetWidth) {
		String key = targetWidth + "|" + file;
		Thumb thumb = THUMBS.get(key);
		if (thumb == null) {
			// Decoding a big PNG takes tens of milliseconds: do it in the background and draw it when ready.
			THUMBS.put(key, LOADING);
			int generation = thumbGeneration;
			com.javahost.util.IoExecutor.supply(() -> {
				try {
					if (!com.javahost.util.PngInfo.safe(com.javahost.util.PngInfo.size(file))) {
						return null;
					}
					try (NativeImage image = NativeImage.read(Files.readAllBytes(file))) {
						int width = Math.min(targetWidth, image.getWidth());
						int height = Math.max(1, image.getHeight() * width / Math.max(1, image.getWidth()));
						NativeImage small = new NativeImage(width, height, false);
						image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
						return small;
					}
				} catch (Exception e) {
					return null;
				}
			}).thenAccept(small -> Minecraft.getInstance().execute(() -> {
				if (generation != thumbGeneration || THUMBS.get(key) != LOADING) {
					if (small != null) {
						small.close(); // the gallery closed meanwhile
					}
					return;
				}
				if (small == null) {
					THUMBS.put(key, new Thumb(null, 0, 0));
					return;
				}
				Identifier id = Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, "thumb/" + (thumbCounter++));
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "JavaHost thumbnail", small));
				THUMBS.put(key, new Thumb(id, small.getWidth(), small.getHeight()));
			}));
			return null;
		}
		return thumb.id() == null ? null : new Thumbnail(thumb.id(), thumb.width(), thumb.height());
	}

	public record Thumbnail(Identifier texture, int width, int height) {
	}

	/** Frees all thumbnail textures (when the gallery closes). */
	public static void releaseThumbnails() {
		for (Thumb thumb : new ArrayList<>(THUMBS.values())) {
			if (thumb.id() != null) {
				Minecraft.getInstance().getTextureManager().release(thumb.id());
			}
		}
		THUMBS.clear();
		thumbGeneration++;
	}
}

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
	private static final int THUMB_WIDTH = 160;
	private static final int PREVIEW_WIDTH = 960;
	private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	private record Thumb(Identifier id, int width, int height) {
	}

	private static final Map<String, Thumb> THUMBS = new HashMap<>();
	private static int thumbCounter;

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

	/** Newest first. {@code fromFriends} picks the received folder instead of the player's own screenshots. */
	public static List<Path> list(boolean fromFriends) {
		Path folder = fromFriends ? fromFriendsDir() : dir();
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png") && Files.isRegularFile(p))
					.sorted(Comparator.comparing(Screenshots::modified).reversed())
					.limit(60)
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static long modified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	/** The PNG to send: scaled down to at most 1920 px wide so it uploads quickly. Call off the render thread. */
	public static byte[] prepareForSending(Path file) throws IOException {
		byte[] original = Files.readAllBytes(file);
		try (NativeImage image = NativeImage.read(original)) {
			if (image.getWidth() <= MAX_SEND_WIDTH) {
				return original;
			}
			int height = Math.max(1, image.getHeight() * MAX_SEND_WIDTH / image.getWidth());
			try (NativeImage scaled = new NativeImage(MAX_SEND_WIDTH, height, false)) {
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
	}

	/** Saves a screenshot a friend sent (only real PNG images are accepted). */
	static Path saveFromFriend(String friendName, byte[] png) throws IOException {
		if (!isPng(png)) {
			throw new IOException("Not a PNG image");
		}
		try (NativeImage check = NativeImage.read(png)) {
			// Parses fully, so a corrupt image is rejected before it's written.
		}
		Files.createDirectories(fromFriendsDir());
		Path file = fromFriendsDir().resolve(friendName + "_" + LocalDateTime.now().format(STAMP) + ".png");
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
			try (NativeImage image = NativeImage.read(Files.readAllBytes(file))) {
				int width = Math.min(targetWidth, image.getWidth());
				int height = Math.max(1, image.getHeight() * width / Math.max(1, image.getWidth()));
				NativeImage small = new NativeImage(width, height, false);
				image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
				Identifier id = Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, "thumb/" + (thumbCounter++));
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "JavaHost thumbnail", small));
				thumb = new Thumb(id, width, height);
			} catch (Exception e) {
				thumb = new Thumb(null, 0, 0);
			}
			THUMBS.put(key, thumb);
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
	}
}

package com.javahost.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.imageio.ImageIO;

/**
 * Minecraft skin images: checking a PNG is a real skin before it's decoded, turning an old 64x32 skin into the
 * 64x64 layout, a flat front view for small previews, and a fingerprint to spot the same skin added twice. Pixels
 * are ARGB ints, row by row.
 */
public final class SkinImage {
	public static final int SIZE = 64;
	/** A real skin is a few KB; anything this big is not one. */
	public static final int MAX_BYTES = 512 * 1024;
	/** The front view: arms, body and head side by side, 16 wide and 32 tall. */
	public static final int FRONT_W = 16;
	public static final int FRONT_H = 32;

	/** A file that isn't a skin; the message says why, in words for the player. */
	public static final class Invalid extends Exception {
		public Invalid(String message) {
			super(message);
		}
	}

	private SkinImage() {
	}

	/**
	 * Reads a skin file: checks it's a PNG of 64x64 (or the old 64x32) from its header before decoding anything,
	 * and returns its 64x64 pixels (old skins converted).
	 */
	public static int[] read(byte[] png) throws Invalid {
		if (png == null || png.length == 0) {
			throw new Invalid("That file is empty.");
		}
		if (png.length > MAX_BYTES) {
			throw new Invalid("That file is too big to be a skin.");
		}
		int[] size = PngInfo.size(png);
		if (size == null) {
			throw new Invalid("That isn't a PNG image.");
		}
		if (size[0] != SIZE || (size[1] != SIZE && size[1] != SIZE / 2)) {
			throw new Invalid("A skin must be 64×64 pixels (this one is " + Math.max(0, size[0]) + "×" + Math.max(0, size[1]) + ").");
		}
		BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(png));
		} catch (IOException | RuntimeException e) {
			image = null;
		}
		if (image == null || image.getWidth() != size[0] || image.getHeight() != size[1]) {
			throw new Invalid("That image couldn't be read.");
		}
		int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
		return image.getHeight() == SIZE ? pixels : fromLegacy(pixels);
	}

	/**
	 * An old 64x32 skin in the 64x64 layout, the way Minecraft does it: the right leg and arm are mirrored into the
	 * new left leg and arm slots.
	 */
	public static int[] fromLegacy(int[] legacy) {
		if (legacy.length != SIZE * SIZE / 2) {
			throw new IllegalArgumentException("not a 64x32 skin");
		}
		int[] skin = new int[SIZE * SIZE];
		System.arraycopy(legacy, 0, skin, 0, legacy.length);
		copyRect(skin, 4, 16, 16, 32, 4, 4);
		copyRect(skin, 8, 16, 16, 32, 4, 4);
		copyRect(skin, 0, 20, 24, 32, 4, 12);
		copyRect(skin, 4, 20, 16, 32, 4, 12);
		copyRect(skin, 8, 20, 8, 32, 4, 12);
		copyRect(skin, 12, 20, 16, 32, 4, 12);
		copyRect(skin, 44, 16, -8, 32, 4, 4);
		copyRect(skin, 48, 16, -8, 32, 4, 4);
		copyRect(skin, 40, 20, 0, 32, 4, 12);
		copyRect(skin, 44, 20, -8, 32, 4, 12);
		copyRect(skin, 48, 20, -16, 32, 4, 12);
		copyRect(skin, 52, 20, -8, 32, 4, 12);
		// Old skins often filled the hat layer with a solid colour; Minecraft treats a fully opaque hat as no hat.
		boolean opaqueHat = true;
		for (int y = 0; y < 16 && opaqueHat; y++) {
			for (int x = 32; x < 64; x++) {
				if ((skin[y * SIZE + x] >>> 24) < 128) {
					opaqueHat = false;
					break;
				}
			}
		}
		if (opaqueHat) {
			for (int y = 0; y < 16; y++) {
				for (int x = 32; x < 64; x++) {
					skin[y * SIZE + x] &= 0x00FFFFFF;
				}
			}
		}
		return skin;
	}

	/** Copies a w x h block from (x, y) to (x + dx, y + dy), mirrored left to right (Minecraft's copyRect). */
	private static void copyRect(int[] skin, int x, int y, int dx, int dy, int w, int h) {
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				int from = (y + row) * SIZE + x + col;
				int to = (y + dy + row) * SIZE + x + dx + (w - 1 - col);
				skin[to] = skin[from];
			}
		}
	}

	/** Encodes pixels as a PNG. */
	public static byte[] png(int[] pixels, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, pixels, 0, width);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return out.toByteArray();
	}

	/** A 64x64 skin as a PNG. */
	public static byte[] png(int[] skin) {
		return png(skin, SIZE, SIZE);
	}

	/**
	 * The skin seen from the front, flat: head, body, arms and legs with the outer layer on top, 16x32 pixels.
	 * Slim arms are 3 pixels wide and sit against the body. Base layers are drawn opaque, as the game draws them.
	 */
	public static int[] front(int[] skin, boolean slim) {
		int[] out = new int[FRONT_W * FRONT_H];
		int arm = slim ? 3 : 4;
		// The player faces us: their right arm and leg are on our left.
		part(out, skin, 8, 8, 4, 0, 8, 8, true); // head
		part(out, skin, 20, 20, 4, 8, 8, 12, true); // body
		part(out, skin, 44, 20, 4 - arm, 8, arm, 12, true); // right arm
		part(out, skin, 36, 52, 12, 8, arm, 12, true); // left arm
		part(out, skin, 4, 20, 4, 20, 4, 12, true); // right leg
		part(out, skin, 20, 52, 8, 20, 4, 12, true); // left leg
		part(out, skin, 40, 8, 4, 0, 8, 8, false); // hat
		part(out, skin, 20, 36, 4, 8, 8, 12, false); // jacket
		part(out, skin, 44, 36, 4 - arm, 8, arm, 12, false); // right sleeve
		part(out, skin, 52, 52, 12, 8, arm, 12, false); // left sleeve
		part(out, skin, 4, 36, 4, 20, 4, 12, false); // right trouser leg
		part(out, skin, 4, 52, 8, 20, 4, 12, false); // left trouser leg
		return out;
	}

	private static void part(int[] out, int[] skin, int u, int v, int x, int y, int w, int h, boolean base) {
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				int pixel = skin[(v + row) * SIZE + u + col];
				int at = (y + row) * FRONT_W + x + col;
				if (base) {
					out[at] = pixel | 0xFF000000;
				} else {
					out[at] = over(pixel, out[at]);
				}
			}
		}
	}

	/** {@code top} drawn over {@code bottom}. */
	private static int over(int top, int bottom) {
		int a = top >>> 24;
		if (a == 0) {
			return bottom;
		}
		if (a == 255) {
			return top;
		}
		float t = a / 255f;
		int r = Math.round(((top >> 16) & 0xFF) * t + ((bottom >> 16) & 0xFF) * (1 - t));
		int g = Math.round(((top >> 8) & 0xFF) * t + ((bottom >> 8) & 0xFF) * (1 - t));
		int b = Math.round((top & 0xFF) * t + (bottom & 0xFF) * (1 - t));
		int alpha = Math.max(a, bottom >>> 24);
		return (alpha << 24) | (r << 16) | (g << 8) | b;
	}

	/**
	 * A fingerprint of how the skin looks with this model: the same picture saved twice (even as two different
	 * files) has the same one. Fully transparent pixels count as one colour, whatever their hidden RGB.
	 */
	public static String fingerprint(int[] skin, String model) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			sha.update(model.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			byte[] buffer = new byte[4];
			for (int pixel : skin) {
				int p = (pixel >>> 24) == 0 ? 0 : pixel;
				buffer[0] = (byte) (p >>> 24);
				buffer[1] = (byte) (p >>> 16);
				buffer[2] = (byte) (p >>> 8);
				buffer[3] = (byte) p;
				sha.update(buffer);
			}
			return HexFormat.of().formatHex(sha.digest(), 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

package com.javahost.client.gui.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.javahost.JavaHost;
import com.javahost.client.friends.Screenshots;
import com.javahost.util.IoExecutor;
import com.javahost.util.Modrinth;
import com.javahost.util.PngInfo;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Mod and modpack icons from Modrinth's CDN, loaded in the background and kept as small textures (the most recently
 * used 128). Most are WebP, which Minecraft can't read, so those are decoded with TwelveMonkeys' pure-Java reader and
 * handed to Minecraft as PNG. Until an icon arrives (or if it can't be read) a tile with the project's initial shows.
 */
public final class WebIcons {
	private static final int MAX_BYTES = 512 * 1024;
	private static final int MAX_SIDE = 1024;
	private static final int KEEP_SIDE = 128;
	private static final int MAX_ICONS = 128;

	private record Icon(Identifier texture, int width, int height) {
	}

	private static final Icon LOADING = new Icon(null, 0, 0);
	private static final Icon FAILED = new Icon(null, -1, -1);
	private static int counter;
	private static final Map<String, Icon> ICONS = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Icon> eldest) {
			if (size() <= MAX_ICONS) {
				return false;
			}
			if (eldest.getValue().texture() != null) {
				Minecraft.getInstance().getTextureManager().release(eldest.getValue().texture());
			}
			return true;
		}
	};

	private WebIcons() {
	}

	/** How many icons are loaded right now (for checks). */
	public static int loadedCount() {
		return (int) ICONS.values().stream().filter(icon -> icon.texture() != null).count();
	}

	/** Draws the icon at {@code url} (or a tile with {@code name}'s first letter while it loads or when there's none). */
	public static void draw(GuiGraphicsExtractor g, String url, String name, int x, int y, int size) {
		Icon icon = url == null || url.isEmpty() ? FAILED : icon(url);
		if (icon.texture() != null) {
			ImageButton.drawCover(g, new Screenshots.Thumbnail(icon.texture(), icon.width(), icon.height()), x, y, size, size);
			return;
		}
		Draw.roundRect(g, x, y, size, size, Math.max(2, size / 6), Theme.withAlpha(Theme.ACCENT, icon == LOADING ? 0.08f : 0.18f));
		String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
		float scale = size / 16f;
		int glyph = Draw.font().width(letter);
		Draw.scaledText(g, letter, Math.round(x + (size - glyph * scale) / 2f), Math.round(y + (size - 8 * scale) / 2f), scale, Theme.ACCENT);
	}

	private static Icon icon(String url) {
		Icon icon = ICONS.get(url);
		if (icon != null) {
			return icon;
		}
		ICONS.put(url, LOADING);
		IoExecutor.NET.execute(() -> {
			NativeImage image = null;
			try {
				image = decode(Modrinth.icon(url, MAX_BYTES));
			} catch (Exception | LinkageError e) {
				JavaHost.LOGGER.debug("[Icons] couldn't load {}", url, e);
			}
			NativeImage loaded = image;
			Minecraft.getInstance().execute(() -> {
				if (ICONS.get(url) != LOADING) {
					if (loaded != null) {
						loaded.close(); // dropped from the cache meanwhile
					}
					return;
				}
				if (loaded == null) {
					ICONS.put(url, FAILED);
					return;
				}
				Identifier id = Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, "icon/" + (counter++));
				//? if >=1.21.5 {
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "JavaHost mod icon", loaded));
				//?} else
				/*Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(loaded));*/
				ICONS.put(url, new Icon(id, loaded.getWidth(), loaded.getHeight()));
			});
		});
		return LOADING;
	}

	/** PNG straight in; WebP and JPEG through ImageIO and then PNG. Sizes are checked before decoding. */
	static NativeImage decode(byte[] data) throws Exception {
		byte[] png;
		if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
			int[] size = PngInfo.size(data);
			if (size == null || size[0] > MAX_SIDE || size[1] > MAX_SIDE) {
				throw new IllegalArgumentException("Icon too large");
			}
			png = data;
		} else {
			png = toPng(readOther(data));
		}
		NativeImage image = NativeImage.read(png);
		if (image.getWidth() <= KEEP_SIDE && image.getHeight() <= KEEP_SIDE) {
			return image;
		}
		try (image) {
			int width = Math.min(KEEP_SIDE, image.getWidth());
			int height = Math.max(1, image.getHeight() * width / Math.max(1, image.getWidth()));
			NativeImage small = new NativeImage(width, height, false);
			image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
			return small;
		}
	}

	private static BufferedImage readOther(byte[] data) throws Exception {
		boolean webp = data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
		// The WebP reader is made directly (not looked up through ImageIO's plugin registry).
		ImageReader reader = webp ? new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance()
				: ImageIO.getImageReadersByFormatName("jpeg").next();
		try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
			reader.setInput(in);
			if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) {
				throw new IllegalArgumentException("Icon too large");
			}
			return reader.read(0);
		} finally {
			reader.dispose();
		}
	}

	private static byte[] toPng(BufferedImage image) throws Exception {
		BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		argb.getGraphics().drawImage(image, 0, 0, null);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(argb, "png", out);
		return out.toByteArray();
	}
}

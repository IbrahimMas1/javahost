package com.javahost.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Drawing helpers for the JavaHost look: rounded panels, shadows, scaled and truncated text. */
public final class Draw {
	private Draw() {
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	/** A filled rectangle with corners rounded by {@code r} pixels (stepped, so it stays crisp at GUI scale). */
	public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			// Inset for row i of the corner, approximating a quarter circle.
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	/** A soft shadow under a panel. */
	public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
		for (int i = 4; i >= 1; i--) {
			roundRect(g, x - i, y - i + 2, w + i * 2, h + i * 2, r + i, Theme.withAlpha(Theme.SHADOW, 0.25f));
		}
	}

	/** A frosted-glass panel: soft shadow, translucent fill, a faint light edge and a highlight along the top. */
	public static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
		shadow(g, x, y, w, h, r);
		roundRect(g, x, y, w, h, r, Theme.GLASS_EDGE);
		roundRect(g, x + 1, y + 1, w - 2, h - 2, r - 1, Theme.GLASS);
		g.fill(x + r, y + 1, x + w - r, y + 2, 0x18FFFFFF);
	}

	/** A panel: fill with a hairline border. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill) {
		bordered(g, x, y, w, h, r, fill, Theme.BORDER);
	}

	/** A rounded rectangle with a one-pixel border. */
	public static void bordered(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill, int border) {
		roundRect(g, x, y, w, h, r, border);
		roundRect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
	}

	public static void text(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		g.text(font(), text, x, y, color, false);
	}

	public static void text(GuiGraphicsExtractor g, Component text, int x, int y, int color) {
		g.text(font(), text, x, y, color, false);
	}

	public static void centered(GuiGraphicsExtractor g, String text, int cx, int y, int color) {
		g.text(font(), text, cx - font().width(text) / 2, y, color, false);
	}

	/** Text drawn {@code scale} times larger, with its top-left at (x, y). */
	public static void scaledText(GuiGraphicsExtractor g, String text, int x, int y, float scale, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font(), text, 0, 0, color, false);
		g.pose().popMatrix();
	}

	/** Shortens text with "…" so it fits in {@code maxWidth} pixels. */
	public static String fit(String text, int maxWidth) {
		Font font = font();
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		int keep = text.length();
		while (keep > 0 && font.width(text.substring(0, keep) + ellipsis) > maxWidth) {
			keep--;
		}
		return text.substring(0, keep) + ellipsis;
	}

	/** Word-wraps text to {@code maxWidth} and draws it; returns the height used. */
	public static int wrapped(GuiGraphicsExtractor g, Component text, int x, int y, int maxWidth, int color) {
		int lineY = y;
		for (FormattedCharSequence line : font().split(text, maxWidth)) {
			g.text(font(), line, x, lineY, color, false);
			lineY += 10;
		}
		return lineY - y;
	}

	public static int wrappedHeight(Component text, int maxWidth) {
		return font().split(text, maxWidth).size() * 10;
	}

	/** Moves {@code current} toward {@code target} smoothly; call once per frame for simple animations. */
	public static float approach(float current, float target, float speed) {
		float delta = target - current;
		return Math.abs(delta) < 0.01f ? target : current + delta * speed;
	}
}

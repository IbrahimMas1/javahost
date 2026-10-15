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

	/** "1 friend", "3 friends". */
	public static String count(int n, String thing) {
		return n + " " + thing + (n == 1 ? "" : "s");
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
		push(g);
		translate(g, x, y);
		scale(g, scale, scale);
		g.text(font(), text, 0, 0, color, false);
		pop(g);
	}

	// ---------------------------------------------------------------- transforms (Matrix3x2f from 1.21.6, PoseStack before)

	public static void push(GuiGraphicsExtractor g) {
		//? if >=1.21.6 {
		g.pose().pushMatrix();
		//?} else
		/*g.pose().pushPose();*/
	}

	public static void pop(GuiGraphicsExtractor g) {
		//? if >=1.21.6 {
		g.pose().popMatrix();
		//?} else
		/*g.pose().popPose();*/
	}

	public static void translate(GuiGraphicsExtractor g, float x, float y) {
		//? if >=1.21.6 {
		g.pose().translate(x, y);
		//?} else
		/*g.pose().translate(x, y, 0);*/
	}

	public static void scale(GuiGraphicsExtractor g, float x, float y) {
		//? if >=1.21.6 {
		g.pose().scale(x, y);
		//?} else
		/*g.pose().scale(x, y, 1);*/
	}

	/** Where (x, y) in the current transform ends up on screen. */
	public static org.joml.Vector2f toScreen(GuiGraphicsExtractor g, float x, float y) {
		//? if >=1.21.6 {
		return g.pose().transformPosition(new org.joml.Vector2f(x, y));
		//?} else {
		/*org.joml.Vector4f v = g.pose().last().pose().transform(new org.joml.Vector4f(x, y, 0, 1));
		return new org.joml.Vector2f(v.x, v.y);
		*///?}
	}

	/** Shortens text with "…" so it fits in {@code maxWidth} pixels. */
	public static String fit(String text, int maxWidth) {
		Font font = font();
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		// Measure once to the right length (this runs for many labels every frame), then make sure it fits.
		int keep = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis))).length();
		while (keep > 0 && font.width(text.substring(0, keep) + ellipsis) > maxWidth) {
			keep--;
		}
		return text.substring(0, keep) + ellipsis;
	}

	// ---------------------------------------------------------------- full text on hover

	/** A spot where text was cut short with "…"; hovering it for a second shows the whole thing. */
	private record Truncated(float x0, float y0, float x1, float y1, String text) {
	}

	private static final java.util.List<Truncated> TRUNCATED = new java.util.ArrayList<>();
	private static float clipX0 = Float.NEGATIVE_INFINITY;
	private static float clipY0 = Float.NEGATIVE_INFINITY;
	private static float clipX1 = Float.POSITIVE_INFINITY;
	private static float clipY1 = Float.POSITIVE_INFINITY;
	private static String hovered;
	private static long hoveredSince;
	private static final long HOVER_DELAY_MS = 1000;

	/** Draws text cut to {@code maxWidth}; if it had to be cut, the full text shows on hover. */
	public static void textFit(GuiGraphicsExtractor g, String text, int maxWidth, int x, int y, int color) {
		String shown = fit(text, maxWidth);
		text(g, shown, x, y, color);
		if (!shown.equals(text)) {
			truncated(g, x, y - 1, font().width(shown), 10, text);
		}
	}

	/** Registers an area (in the current drawing transform) whose text was cut short. */
	public static void truncated(GuiGraphicsExtractor g, int x, int y, int w, int h, String fullText) {
		org.joml.Vector2f a = toScreen(g, x, y);
		org.joml.Vector2f b = toScreen(g, x + w, y + h);
		float x0 = Math.max(Math.min(a.x, b.x), clipX0);
		float y0 = Math.max(Math.min(a.y, b.y), clipY0);
		float x1 = Math.min(Math.max(a.x, b.x), clipX1);
		float y1 = Math.min(Math.max(a.y, b.y), clipY1);
		if (x1 <= x0 || y1 <= y0) {
			return; // scrolled out of view
		}
		if (TRUNCATED.size() > 256) {
			TRUNCATED.clear(); // nothing is showing tooltips (e.g. no screen open); don't grow forever
		}
		TRUNCATED.add(new Truncated(x0, y0, x1, y1, fullText));
	}

	/** Limits hover areas to a visible region (e.g. a scrolling page), until {@link #unclip()}. */
	public static void clip(int x0, int y0, int x1, int y1) {
		clipX0 = x0;
		clipY0 = y0;
		clipX1 = x1;
		clipY1 = y1;
	}

	public static void unclip() {
		clipX0 = clipY0 = Float.NEGATIVE_INFINITY;
		clipX1 = clipY1 = Float.POSITIVE_INFINITY;
	}

	/** Called after a screen is drawn: shows the full text of whatever cut-short text the mouse has rested on. */
	public static void showTruncatedTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		String found = null;
		for (int i = TRUNCATED.size() - 1; i >= 0; i--) {
			Truncated t = TRUNCATED.get(i);
			if (mouseX >= t.x0() && mouseX < t.x1() && mouseY >= t.y0() && mouseY < t.y1()) {
				found = t.text();
				break;
			}
		}
		TRUNCATED.clear();
		if (found == null) {
			hovered = null;
			return;
		}
		if (!found.equals(hovered)) {
			hovered = found;
			hoveredSince = System.currentTimeMillis();
			return;
		}
		if (System.currentTimeMillis() - hoveredSince >= HOVER_DELAY_MS) {
			//? if >=1.21.6 {
			g.setTooltipForNextFrame(font(), font().split(Component.literal(found), 220), mouseX, mouseY);
			//?} else {
			/*net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
			if (screen != null) {
				screen.setTooltipForNextRenderPass(font().split(Component.literal(found), 220));
			}
			*///?}
		}
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

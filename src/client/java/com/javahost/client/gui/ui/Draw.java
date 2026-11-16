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
		//? if <1.21.2 {
		/*// Before 1.21.2 every fill is its own draw call, so corners are drawn in whole GUI pixels there.
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
		*///?} else {
		int scale = Math.max(1, Math.round((float) net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
		// The corners are drawn in real screen pixels, so they stay smooth at big GUI scales instead of stepping in
		// whole GUI pixels.
		push(g);
		translate(g, x, y);
		scale(g, 1f / scale, 1f / scale);
		int radius = r * scale;
		int width = w * scale;
		int height = h * scale;
		net.minecraft.resources.Identifier mask = radius < ROUND.length ? ROUND[radius] : null;
		if (mask != null) {
			// Four quarters of a ready-made disc and two bands: seven pieces, where strips would take a hundred.
			corner(g, mask, 0, 0, 0, 0, radius, color);
			corner(g, mask, width - radius, 0, radius, 0, radius, color);
			corner(g, mask, 0, height - radius, 0, radius, radius, color);
			corner(g, mask, width - radius, height - radius, radius, radius, radius, color);
			g.fill(radius, 0, width - radius, radius, color);
			g.fill(radius, height - radius, width - radius, height, color);
			pop(g);
			return;
		}
		// A radius without a mask (a GUI scale above 6): one strip per row, the edge pixel only partly covered.
		for (int i = 0; i < radius; i++) {
			double dy = radius - i - 0.5;
			double edge = radius - Math.sqrt(Math.max(0, (double) radius * radius - dy * dy));
			int full = (int) Math.ceil(edge);
			g.fill(full, i, width - full, i + 1, color);
			g.fill(full, height - i - 1, width - full, height - i, color);
			float cover = (float) (full - edge);
			if (cover > 0.04f && full > 0) {
				int soft = Theme.withAlpha(color, cover);
				g.fill(full - 1, i, full, i + 1, soft);
				g.fill(width - full, i, width - full + 1, i + 1, soft);
				g.fill(full - 1, height - i - 1, full, height - i, soft);
				g.fill(width - full, height - i - 1, width - full + 1, height - i, soft);
			}
		}
		pop(g);
		//?}
	}

	/** Discs for rounded corners, by radius in screen pixels (scripts/gen-round.py); null where there isn't one. */
	private static final net.minecraft.resources.Identifier[] ROUND = new net.minecraft.resources.Identifier[61];

	static {
		for (int scale = 1; scale <= 6; scale++) {
			for (int r = 1; r <= 10; r++) {
				ROUND[r * scale] = net.minecraft.resources.Identifier.fromNamespaceAndPath("javahost", "textures/gui/round/" + r * scale + ".png");
			}
		}
	}

	//? if >=1.21.2 {
	/** One quarter of the disc {@code mask} (the one starting at u, v), {@code radius} screen pixels square. */
	private static void corner(GuiGraphicsExtractor g, net.minecraft.resources.Identifier mask, int x, int y, int u, int v, int radius, int color) {
		//? if >=1.21.6 {
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, mask, x, y, u, v, radius, radius, radius * 2, radius * 2, color);
		//?} else
		/*g.blit(net.minecraft.client.renderer.RenderType::guiTextured, mask, x, y, u, v, radius, radius, radius * 2, radius * 2, color);*/
	}
	//?}

	/**
	 * A progress ring around ({@code cx}, {@code cy}): {@code fraction} of it, clockwise from the top, in {@code color},
	 * the rest in {@code track}. Drawn in real screen pixels with soft edges. Before 1.21.2 (one draw call per fill)
	 * it's a short bar under the centre instead.
	 */
	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float radius, float thickness, double fraction, int color, int track) {
		//? if <1.21.2 {
		/*int w = Math.round(radius * 2);
		int x = Math.round(cx - radius);
		int y = Math.round(cy + radius - 2);
		g.fill(x, y, x + w, y + 2, track);
		g.fill(x, y, x + (int) Math.round(w * Math.max(0, Math.min(1, fraction))), y + 2, color);
		*///?} else {
		int scale = Math.max(1, Math.round((float) net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
		double r = radius * scale;
		double half = thickness * scale / 2;
		double outer = r + half + 1;
		double sweep = Math.max(0, Math.min(1, fraction)) * Math.PI * 2;
		push(g);
		translate(g, cx, cy);
		scale(g, 1f / scale, 1f / scale);
		int box = (int) Math.ceil(outer);
		for (int py = -box; py < box; py++) {
			for (int px = -box; px < box; px++) {
				double dx = px + 0.5;
				double dy = py + 0.5;
				double cover = half + 0.5 - Math.abs(Math.sqrt(dx * dx + dy * dy) - r);
				if (cover <= 0.04) {
					continue;
				}
				double angle = Math.atan2(dx, -dy); // 0 at the top, clockwise
				if (angle < 0) {
					angle += Math.PI * 2;
				}
				g.fill(px, py, px + 1, py + 1, Theme.withAlpha(angle <= sweep ? color : track, (float) Math.min(1, cover)));
			}
		}
		pop(g);
		//?}
	}

	private static final net.minecraft.resources.Identifier GLOW = net.minecraft.resources.Identifier.fromNamespaceAndPath("javahost", "textures/gui/glow.png");

	/**
	 * A soft round light of {@code color}, brightest at ({@code cx}, {@code cy}) and gone at the radii. Used for the
	 * hub's lighting: the glow behind the model, under big buttons, in the background.
	 */
	public static void glow(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int color) {
		if ((color >>> 24) == 0 || rx <= 0 || ry <= 0) {
			return;
		}
		int x = cx - rx;
		int y = cy - ry;
		//? if >=1.21.6 {
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, GLOW, x, y, 0, 0, rx * 2, ry * 2, 128, 128, 128, 128, color);
		//?} else if >=1.21.2 {
		/*g.blit(net.minecraft.client.renderer.RenderType::guiTextured, GLOW, x, y, 0, 0, rx * 2, ry * 2, 128, 128, 128, 128, color);
		*///?} else {
		/*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		g.setColor(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f, (color >>> 24) / 255f);
		g.blit(GLOW, x, y, rx * 2, ry * 2, 0, 0, 128, 128, 128, 128);
		g.setColor(1, 1, 1, 1);
		*///?}
	}

	/** A whole texture ({@code texW} x {@code texH} pixels) drawn into {@code w} x {@code h}, pixels kept sharp. */
	public static void image(GuiGraphicsExtractor g, net.minecraft.resources.Identifier texture, int x, int y, int w, int h, int texW, int texH) {
		//? if >=1.21.6 {
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, texW, texH, texW, texH, 0xFFFFFFFF);
		//?} else if >=1.21.2 {
		/*g.blit(net.minecraft.client.renderer.RenderType::guiTextured, texture, x, y, 0, 0, w, h, texW, texH, texW, texH, 0xFFFFFFFF);
		*///?} else {
		/*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		g.blit(texture, x, y, w, h, 0, 0, texW, texH, texW, texH);
		*///?}
	}

	/**
	 * Stage lighting for a 3D model {@code height} tall standing in a box that starts at {@code top}: a glow of the
	 * accent colour behind it (reaching {@code spread} to each side), a lit floor, and its shadow on it.
	 */
	public static void stage(GuiGraphicsExtractor g, int cx, int top, int height, int spread) {
		int feet = top + height - 2;
		glow(g, cx, top + Math.round(height * 0.46f), spread, Math.round(height * 0.66f), Theme.withAlpha(Theme.ACCENT, 0.24f));
		glow(g, cx, feet, Math.round(spread * 0.7f), 10, Theme.withAlpha(Theme.ACCENT, 0.60f));
		glow(g, cx, feet, Math.round(spread * 0.38f), 5, 0xC0000000);
	}

	/**
	 * A glass card: a translucent dark sheet with a light rim and a highlight along its top edge, so the hub's
	 * lighting shows through a little and the card looks lifted off the background.
	 */
	public static void glassCard(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
		if (w <= 2 || h <= 2) {
			return;
		}
		roundRect(g, x, y + 1, w, h, r, 0x30000000); // a hint of shadow under it
		roundRect(g, x, y, w, h, r, Theme.RIM);
		roundRect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), Theme.SURFACE);
		g.fill(x + r, y + 1, x + w - r, y + 2, 0x12FFFFFF);
	}

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

	// ---------------------------------------------------------------- text (in the menu's typeface, see Fonts)

	public static void text(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		g.text(font(), FormattedCharSequence.forward(text, Fonts.text()), x, y, color, false);
	}

	public static void text(GuiGraphicsExtractor g, Component text, int x, int y, int color) {
		g.text(font(), styled(text), x, y, color, false);
	}

	/** Heavier text, for names and titles. */
	public static void bold(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		g.text(font(), FormattedCharSequence.forward(text, Fonts.bold()), x, y, color, false);
	}

	public static void centered(GuiGraphicsExtractor g, String text, int cx, int y, int color) {
		text(g, text, cx - width(text) / 2, y, color);
	}

	/**
	 * Title text drawn about {@code scale} times larger, with its top-left at (x, y). Pixel titles are stretched by a
	 * whole number of screen pixels per font pixel, so they never look uneven; smooth ones have fonts of their own
	 * sizes. Either way they sit centred on the line the plainly stretched text would have had.
	 */
	public static void scaledText(GuiGraphicsExtractor g, String text, int x, int y, float scale, int color) {
		float guiScale = (float) net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
		Fonts.Mode mode = Fonts.mode();
		push(g);
		if (mode == Fonts.Mode.SMOOTH) {
			float base = scale >= 1.4f ? Fonts.BIG_SIZE : scale >= 1.1f ? Fonts.TITLE_SIZE : Fonts.TEXT_SIZE;
			float stretch = scale * Fonts.TEXT_SIZE / base; // 1, or close to it, for the usual sizes
			translate(g, x, Math.round((y + 7 * (scale - stretch)) * guiScale) / guiScale);
			if (Math.abs(stretch - 1) > 0.06f) {
				scale(g, stretch, stretch);
			}
			g.text(font(), FormattedCharSequence.forward(text, base == Fonts.TEXT_SIZE ? Fonts.bold() : Fonts.title(base == Fonts.BIG_SIZE)), 0, 0, color, false);
		} else {
			// Pixel text: stretched by a whole number of screen pixels per font pixel (so it never looks uneven),
			// centred on the line asked for, with the game's own drop shadow under it.
			float factor = pixelFactor(scale, guiScale);
			translate(g, x, Math.round((y + 3.5f * (scale - factor)) * guiScale) / guiScale);
			scale(g, factor, factor);
			// Light text only: a dark shadow under dark text (the logo's letter) would just smudge it.
			boolean light = (color >>> 24) > 0xC0 && ((color >> 16) & 0xFF) + ((color >> 8) & 0xFF) + (color & 0xFF) > 420;
			g.text(font(), text, 0, 0, color, light);
		}
		pop(g);
	}

	/** How wide {@link #scaledText} draws {@code text}. */
	public static int scaledWidth(String text, float scale) {
		Fonts.Mode mode = Fonts.mode();
		if (mode == Fonts.Mode.SMOOTH) {
			float base = scale >= 1.4f ? Fonts.BIG_SIZE : scale >= 1.1f ? Fonts.TITLE_SIZE : Fonts.TEXT_SIZE;
			float stretch = scale * Fonts.TEXT_SIZE / base;
			int w = font().width(FormattedCharSequence.forward(text, base == Fonts.TEXT_SIZE ? Fonts.bold() : Fonts.title(base == Fonts.BIG_SIZE)));
			return Math.abs(stretch - 1) > 0.06f ? Math.round(w * stretch) : w;
		}
		return Math.round(font().width(text) * pixelFactor(scale, (float) net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
	}

	private static float pixelFactor(float scale, float guiScale) {
		return scale < 1.1f ? 1 : scale > 1.7f ? Math.round(scale * guiScale) / guiScale : Fonts.titleFactor(scale >= 1.4f);
	}

	/** {@code text} cut with "…" so that {@link #scaledText} draws it no wider than {@code maxWidth}. */
	public static String fitScaled(String text, int maxWidth, float scale) {
		if (scaledWidth(text, scale) <= maxWidth) {
			return text;
		}
		int keep = text.length();
		while (keep > 0 && scaledWidth(text.substring(0, keep) + "…", scale) > maxWidth) {
			keep--;
		}
		return text.substring(0, keep) + "…";
	}

	/** How wide {@code text} is when drawn by {@link #text}. */
	public static int width(String text) {
		return font().width(FormattedCharSequence.forward(text, Fonts.text()));
	}

	public static int boldWidth(String text) {
		return font().width(FormattedCharSequence.forward(text, Fonts.bold()));
	}

	/** A small capital label (section names); pixel text gets a pixel of air between its letters. */
	public static void caps(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		String upper = text.toUpperCase(java.util.Locale.ROOT);
		if (Fonts.smooth()) {
			text(g, upper, x, y, color);
			return;
		}
		for (int i = 0; i < upper.length(); i++) {
			String letter = upper.substring(i, i + 1);
			g.text(font(), letter, x, y, color, false);
			x += font().width(letter) + 1;
		}
	}

	public static int width(FormattedCharSequence text) {
		return font().width(text);
	}

	/** {@code text} in the menu's typeface (its own colours and styles are kept). */
	public static Component styled(Component text) {
		return Fonts.smooth() ? Component.empty().withStyle(Fonts.text()).append(text) : text;
	}

	public static Component styled(String text) {
		return Component.literal(text).withStyle(Fonts.text());
	}

	/** {@code text} broken into lines no wider than {@code maxWidth}. */
	public static java.util.List<FormattedCharSequence> split(String text, int maxWidth) {
		return font().split(styled(text), maxWidth);
	}

	public static java.util.List<FormattedCharSequence> split(Component text, int maxWidth) {
		return font().split(styled(text), maxWidth);
	}

	public static void text(GuiGraphicsExtractor g, FormattedCharSequence line, int x, int y, int color) {
		g.text(font(), line, x, y, color, false);
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
		if (width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		// Measure once to the right length (this runs for many labels every frame), then make sure it fits.
		int keep = font().getSplitter().plainHeadByWidth(text, Math.max(0, maxWidth - width(ellipsis)), Fonts.text()).length();
		while (keep > 0 && width(text.substring(0, keep) + ellipsis) > maxWidth) {
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
			truncated(g, x, y - 1, width(shown), 10, text);
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
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof com.javahost.client.gui.JavaHostScreen menu && menu.popoverOpen()) {
			found = null; // nothing under an open popover shows its full text
		}
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
			g.setTooltipForNextFrame(font(), split(found, 220), mouseX, mouseY);
			//?} else {
			/*net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
			if (screen != null) {
				screen.setTooltipForNextRenderPass(split(found, 220));
			}
			*///?}
		}
	}

	/** Word-wraps text to {@code maxWidth} and draws it; returns the height used. */
	public static int wrapped(GuiGraphicsExtractor g, Component text, int x, int y, int maxWidth, int color) {
		int lineY = y;
		for (FormattedCharSequence line : split(text, maxWidth)) {
			g.text(font(), line, x, lineY, color, false);
			lineY += 10;
		}
		return lineY - y;
	}

	public static int wrappedHeight(Component text, int maxWidth) {
		return split(text, maxWidth).size() * 10;
	}

	/** Moves {@code current} toward {@code target} smoothly; call once per frame for simple animations. */
	public static float approach(float current, float target, float speed) {
		float delta = target - current;
		return Math.abs(delta) < 0.01f ? target : current + delta * speed;
	}
}

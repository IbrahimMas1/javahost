package com.javahost.client.gui.ui;

import com.javahost.client.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The menu's typefaces (Settings, "Text and icons"):
 * <ul>
 * <li>"Pixel" (the default): the game's own font and the two-tone pixel icons.</li>
 * <li>"Smooth": Inter with smooth Lucide icons, bundled as small fonts. They have one definition per GUI scale
 * (assets/javahost/font/NAME_N.json), each drawn at exactly N screen pixels per GUI pixel, so they're sharp at every
 * scale. See scripts/gen-fonts.py.</li>
 * </ul>
 */
public final class Fonts {
	public enum Mode { PIXEL, SMOOTH }

	private static final int SCALES = 6;
	private static final Style[] TEXT = styles("ui");
	private static final Style[] BOLD = styles("ui_bold");
	private static final Style[] TITLE = styles("ui_title");
	private static final Style[] BIG = styles("ui_big");
	private static final Style[] ICONS = styles("icons");
	private static final Style[] ICONS_BIG = styles("icons_big");
	/** The sizes the smooth fonts are made at, in GUI pixels (scripts/gen-fonts.py). */
	static final float TEXT_SIZE = 9;
	static final float TITLE_SIZE = 11;
	static final float BIG_SIZE = 14;
	static final float ICON_SIZE = 12;
	static final float ICON_BIG_SIZE = 16;

	private Fonts() {
	}

	private static Style[] styles(String name) {
		Style[] styles = new Style[SCALES + 1];
		for (int scale = 1; scale <= SCALES; scale++) {
			Identifier id = Identifier.fromNamespaceAndPath("javahost", name + "_" + scale);
			//? if >=1.21.9 {
			styles[scale] = Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(id));
			//?} else
			/*styles[scale] = Style.EMPTY.withFont(id);*/
		}
		return styles;
	}

	public static Mode mode() {
		return "SMOOTH".equals(Config.get().menuFont) ? Mode.SMOOTH : Mode.PIXEL;
	}

	/** Smooth text and icons, rather than pixels. */
	public static boolean smooth() {
		return mode() == Mode.SMOOTH;
	}

	static int scale() {
		return Math.max(1, Math.min(SCALES, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale())));
	}

	public static Style text() {
		return smooth() ? TEXT[scale()] : Style.EMPTY;
	}

	/** For names and titles at the normal size (heavier in the smooth typeface; pixel text has one weight). */
	public static Style bold() {
		return smooth() ? BOLD[scale()] : Style.EMPTY;
	}

	/** Smooth titles ({@code big}: names and the invite code), in a font of their own size. */
	static Style title(boolean big) {
		return (big ? BIG : TITLE)[scale()];
	}

	/**
	 * How much bigger than normal text a title is drawn, snapped so every font pixel is a whole number of screen
	 * pixels (wanted: 1.25, or 1.5 for {@code big}); used for pixel text.
	 */
	static float titleFactor(boolean big) {
		int scale = scale();
		return Math.max(scale + 1, Math.round((big ? 1.5f : 1.25f) * scale)) / (float) scale;
	}

	static Style icons(boolean big) {
		return (big ? ICONS_BIG : ICONS)[scale()];
	}
}

package com.javahost.client.gui.ui;

/** JavaHost's colours (ARGB). Dark panels with a green accent. */
public final class Theme {
	public static final int SCRIM = 0xB0070A0E;
	public static final int WINDOW = 0xF2161B22;
	public static final int SIDEBAR = 0xFF10141A;
	public static final int CARD = 0xFF1E252E;
	public static final int CARD_HOVER = 0xFF252D38;
	public static final int FIELD = 0xFF0D1116;
	public static final int BORDER = 0xFF2C3540;
	public static final int SHADOW = 0x60000000;

	public static final int TEXT = 0xFFE8EDF2;
	public static final int MUTED = 0xFF8B96A3;
	public static final int FAINT = 0xFF5A6572;

	/** Accent colours; not final, so the player's chosen theme applies everywhere at runtime. */
	public static int ACCENT = 0xFF3DDC84;
	public static int ACCENT_HOVER = 0xFF5AE89A;
	public static int ACCENT_DARK = 0xFF1F6B44;
	public static int ON_ACCENT = 0xFF0B1F14;

	/** The accent themes: name -> base colour. Hover, dark and text-on-accent shades are derived. */
	public static final java.util.Map<String, Integer> ACCENTS = new java.util.LinkedHashMap<>();

	static {
		ACCENTS.put("Green", 0xFF3DDC84);
		ACCENTS.put("Blue", 0xFF4F9DF7);
		ACCENTS.put("Purple", 0xFFA77BF3);
		ACCENTS.put("Orange", 0xFFF59E3B);
		ACCENTS.put("Pink", 0xFFF06BAA);
	}

	public static void apply(String name) {
		int base = ACCENTS.getOrDefault(name, ACCENTS.get("Green"));
		ACCENT = base;
		ACCENT_HOVER = mix(base, 0xFFFFFFFF, 0.18f);
		ACCENT_DARK = mix(base, 0xFF0B0F14, 0.62f);
		ON_ACCENT = mix(base, 0xFF000000, 0.85f);
	}
	public static final int WARNING = 0xFFF2B33D;
	public static final int DANGER = 0xFFEF5350;
	public static final int DANGER_HOVER = 0xFFF26D6A;
	public static final int INFO = 0xFF4FA3F7;

	private Theme() {
	}

	/** Blends two ARGB colours; {@code t} = 0 gives {@code a}, 1 gives {@code b}. */
	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
				| (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
	}

	public static int withAlpha(int color, float alpha) {
		return (Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha))) << 24) | (color & 0xFFFFFF);
	}
}

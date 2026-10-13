package com.javahost.util;

/** Cleans text that came from someone else before it's shown (world names, chat, captions). */
public final class Text {
	private Text() {
	}

	/**
	 * Removes Minecraft formatting codes (§ followed by a character, which would otherwise colour or scramble the
	 * text) and control characters, and trims it to {@code max} characters.
	 */
	public static String clean(String text, int max) {
		if (text == null) {
			return "";
		}
		String cleaned = text.replaceAll("§.?", "").replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
		return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
	}
}

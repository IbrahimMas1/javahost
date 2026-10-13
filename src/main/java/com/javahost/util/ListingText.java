package com.javahost.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The shape of a public world listing, checked in the game before it's sent, so the host hears about a problem
 * while typing. The list service (directory/src/rules.js) applies the same rules and is the final word.
 */
public final class ListingText {
	public static final int TITLE_MAX = 40;
	public static final int DESCRIPTION_MAX = 160;
	public static final List<String> TAGS = List.of("help", "building", "survival", "creative", "adventure", "chill");
	private static final Pattern LINK = Pattern.compile(
			"https?:|www\\.|discord\\.gg|\\b[a-z0-9-]+\\.(com|net|org|gg|io|me|xyz|ru|co|link|tk|ly|cc|to|sh|app|dev)\\b");
	private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
	private static final Pattern IPV6 = Pattern.compile("\\b[0-9a-f]{1,4}(:[0-9a-f]{0,4}){3,7}\\b");
	private static final Pattern RELAY = Pattern.compile("[a-z0-9-]{1,40}\\.[a-z0-9-]{1,10}\\.e4mc\\.link");

	private ListingText() {
	}

	/** How a tag is shown. */
	public static String tagLabel(String tag) {
		return switch (tag) {
			case "help" -> "Help wanted";
			case "building" -> "Building";
			case "survival" -> "Survival";
			case "creative" -> "Creative";
			case "adventure" -> "Adventure";
			case "chill" -> "Chill";
			default -> tag;
		};
	}

	/** Without formatting codes or control characters, spaces collapsed, cut to {@code max}. */
	public static String clean(String text, int max) {
		String cleaned = Text.clean(text, 1000).replaceAll("\\s+", " ").strip();
		return cleaned.codePointCount(0, cleaned.length()) <= max ? cleaned
				: cleaned.substring(0, cleaned.offsetByCodePoints(0, max));
	}

	/** What's wrong with a title or description, or null if it's fine to send. */
	public static String problem(String title, String description) {
		if (clean(title, TITLE_MAX).length() < 3) {
			return "The title needs at least 3 characters";
		}
		for (String text : List.of(title.toLowerCase(), description.toLowerCase())) {
			if (LINK.matcher(text).find()) {
				return "Links aren't allowed";
			}
			if (IPV4.matcher(text).find() || IPV6.matcher(text).find()) {
				return "Addresses aren't allowed (visitors join through the relay)";
			}
		}
		return null;
	}

	/** Only relay addresses are ever listed, never a home address. */
	public static boolean isRelayAddress(String address) {
		return address != null && RELAY.matcher(address).matches();
	}

	/** The relay's region from its address ("eu" in brave-otter.eu.e4mc.link). */
	public static String region(String relay) {
		String[] parts = relay.split("\\.");
		return parts.length > 1 ? parts[1].toUpperCase() : "";
	}
}

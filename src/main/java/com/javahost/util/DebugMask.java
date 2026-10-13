package com.javahost.util;

import java.util.List;
import java.util.regex.Pattern;

/** Removes personal details from text before it's shared: IP addresses, invite codes, relay addresses and names. */
public final class DebugMask {
	private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
	private static final Pattern IPV6 = Pattern.compile("\\[?\\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\\b\\]?");
	private static final Pattern INVITE = Pattern.compile("\\bJH6?(?:-[0-9A-Z]{4,5})+\\b");
	private static final Pattern RELAY = Pattern.compile("\\b[a-z0-9-]+\\.[a-z0-9-]+\\.e4mc\\.link\\b");

	private DebugMask() {
	}

	/** {@code names}: player names to hide (yours first, then friends), shown as "you", "friend-1", ... */
	public static String mask(String text, List<String> names) {
		String out = INVITE.matcher(text).replaceAll("[invite]");
		out = RELAY.matcher(out).replaceAll("[relay]");
		out = IPV6.matcher(out).replaceAll("[ip]");
		out = IPV4.matcher(out).replaceAll("[ip]");
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			if (name != null && name.length() >= 3) {
				out = out.replaceAll("(?i)\\b" + Pattern.quote(name) + "\\b", i == 0 ? "you" : "friend-" + i);
			}
		}
		return out;
	}
}

package com.javahost.util;

/** Compares JavaHost versions like "1.4.0" (missing parts count as 0, anything non-numeric as 0). */
public final class Versions {
	private Versions() {
	}

	/** Negative if {@code a} is older than {@code b}, positive if newer, 0 if the same. */
	public static int compare(String a, String b) {
		String[] x = a.split("[.+-]");
		String[] y = b.split("[.+-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int diff = Integer.compare(part(x, i), part(y, i));
			if (diff != 0) {
				return diff;
			}
		}
		return 0;
	}

	private static int part(String[] parts, int i) {
		if (i >= parts.length) {
			return 0;
		}
		try {
			return Integer.parseInt(parts[i]);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}

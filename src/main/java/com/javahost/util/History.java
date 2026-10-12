package com.javahost.util;

import java.util.List;

/** Small helpers for bounded histories (chat lines, benchmark runs). */
public final class History {
	private History() {
	}

	/** Removes the oldest entries (front of the list) until at most {@code max} remain. */
	public static <T> void keepNewest(List<T> list, int max) {
		while (list.size() > max) {
			list.removeFirst();
		}
	}
}

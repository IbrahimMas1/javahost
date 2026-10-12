package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class HistoryAndStatsTest {
	@Test
	void chatHistoryKeepsTheNewestLines() {
		List<Integer> lines = new ArrayList<>();
		for (int i = 0; i < 250; i++) {
			lines.add(i);
		}
		History.keepNewest(lines, 200);
		assertEquals(200, lines.size());
		assertEquals(50, lines.get(0));
		assertEquals(249, lines.get(199));

		List<Integer> short_ = new ArrayList<>(List.of(1, 2));
		History.keepNewest(short_, 200);
		assertEquals(List.of(1, 2), short_);
	}
}

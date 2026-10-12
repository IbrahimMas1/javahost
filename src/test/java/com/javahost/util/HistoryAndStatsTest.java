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

	@Test
	void benchmarkAverageAndOnePercentLow() {
		// 990 frames at 10 ms (100 FPS) and 10 slow frames at 50 ms (20 FPS).
		long[] frames = new long[1000];
		for (int i = 0; i < frames.length; i++) {
			frames[i] = (i % 100 == 0 ? 50 : 10) * 1_000_000L;
		}
		FrameStats.Result result = FrameStats.summarize(frames);
		assertEquals(1000, result.frames());
		// 1000 frames in 10.4 s
		assertEquals(1000 / 10.4, result.averageFps(), 0.01);
		assertEquals(20.0, result.onePercentLowFps(), 0.01);
	}

	@Test
	void benchmarkHandlesTinyAndEmptyRuns() {
		assertEquals(0, FrameStats.summarize(new long[0]).frames());
		FrameStats.Result one = FrameStats.summarize(new long[] {16_666_667L});
		assertEquals(60.0, one.averageFps(), 0.01);
		assertEquals(60.0, one.onePercentLowFps(), 0.01);
	}
}

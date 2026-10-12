package com.javahost.util;

import java.util.Arrays;

/** Frame-time statistics for the benchmark: average FPS and "1% low" (the FPS of the slowest 1% of frames). */
public final class FrameStats {
	public record Result(double averageFps, double onePercentLowFps, int frames) {
	}

	private FrameStats() {
	}

	/** {@code frameNanos} are the durations of individual frames in nanoseconds. */
	public static Result summarize(long[] frameNanos) {
		if (frameNanos.length == 0) {
			return new Result(0, 0, 0);
		}
		long total = 0;
		for (long frame : frameNanos) {
			total += frame;
		}
		double averageFps = frameNanos.length / (total / 1e9);

		long[] slowestFirst = frameNanos.clone();
		Arrays.sort(slowestFirst);
		int worst = Math.max(1, frameNanos.length / 100);
		long worstTotal = 0;
		for (int i = 0; i < worst; i++) {
			worstTotal += slowestFirst[slowestFirst.length - 1 - i];
		}
		double onePercentLow = worst / (worstTotal / 1e9);
		return new Result(averageFps, onePercentLow, frameNanos.length);
	}
}

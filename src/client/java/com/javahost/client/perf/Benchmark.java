package com.javahost.client.perf;

import java.util.Arrays;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.Config.BenchmarkResult;
import com.javahost.client.Toasts;
import com.javahost.util.FrameStats;
import com.javahost.util.History;

/**
 * Measures real frame times for a fixed period (stand still while it runs) and records average FPS and 1% low.
 * "Compare" runs one benchmark, applies Quick Tune, waits for the world to settle, and runs another.
 */
public final class Benchmark {
	public static final int SECONDS = 20;
	private static final int SETTLE_SECONDS = 3;

	private enum Phase { IDLE, RUNNING, SETTLING }

	private static Phase phase = Phase.IDLE;
	private static String label = "";
	private static boolean comparing;
	private static long[] frames = new long[4096];
	private static int count;
	private static long lastFrame;
	private static long phaseStart;

	private Benchmark() {
	}

	public static boolean running() {
		return phase != Phase.IDLE;
	}

	/** 0..1 progress of the current step, and a description for the UI. */
	public static float progress() {
		long elapsed = System.nanoTime() - phaseStart;
		long length = (phase == Phase.SETTLING ? SETTLE_SECONDS : SECONDS) * 1_000_000_000L;
		return Math.min(1f, elapsed / (float) length);
	}

	public static String step() {
		return switch (phase) {
			case RUNNING -> (comparing ? (label.equals("Before") ? "1/2 " : "2/2 ") : "") + "Measuring " + label.toLowerCase() + "... stand still";
			case SETTLING -> "Applying Quick Tune...";
			case IDLE -> "";
		};
	}

	public static void start(String name) {
		comparing = false;
		begin(name);
	}

	/** Benchmark, apply Quick Tune, benchmark again. */
	public static void compare() {
		comparing = true;
		begin("Before");
	}

	private static void begin(String name) {
		label = name;
		count = 0;
		lastFrame = 0;
		phase = Phase.RUNNING;
		phaseStart = System.nanoTime();
	}

	/** Called once per rendered frame (from the HUD). */
	public static void frame() {
		if (phase == Phase.IDLE) {
			return;
		}
		long now = System.nanoTime();
		if (phase == Phase.SETTLING) {
			if (now - phaseStart > SETTLE_SECONDS * 1_000_000_000L) {
				begin("After");
			}
			return;
		}
		if (lastFrame != 0) {
			if (count == frames.length) {
				frames = Arrays.copyOf(frames, frames.length * 2);
			}
			frames[count++] = now - lastFrame;
		}
		lastFrame = now;
		if (now - phaseStart > SECONDS * 1_000_000_000L) {
			finish();
		}
	}

	private static void finish() {
		FrameStats.Result result = FrameStats.summarize(Arrays.copyOf(frames, count));
		Config config = Config.get();
		config.benchmarks.add(new BenchmarkResult(label, result.averageFps(), result.onePercentLowFps(), System.currentTimeMillis()));
		History.keepNewest(config.benchmarks, 10);
		config.save();
		JavaHost.LOGGER.info("Benchmark {}: {} FPS average, {} FPS 1% low over {} frames", label,
				Math.round(result.averageFps()), Math.round(result.onePercentLowFps()), result.frames());
		if (comparing && label.equals("Before")) {
			if (!QuickTune.isApplied()) {
				QuickTune.apply();
			}
			phase = Phase.SETTLING;
			phaseStart = System.nanoTime();
			return;
		}
		phase = Phase.IDLE;
		Toasts.show("Benchmark done", Math.round(result.averageFps()) + " FPS average, " + Math.round(result.onePercentLowFps()) + " 1% low");
	}
}

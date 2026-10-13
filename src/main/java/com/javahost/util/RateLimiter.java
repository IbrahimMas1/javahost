package com.javahost.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * A token bucket per player: {@code rate} units a second, up to {@code burst} saved up. Used by the host to keep
 * one player's traffic (voice, relayed messages, world chunks, rule changes) from swamping the world, and to spot a
 * player who keeps going over the limit (a hacked client) so they can be removed. Not thread-safe: the server
 * calls it from its own thread.
 */
public final class RateLimiter {
	private static final long SECOND = 1_000_000_000L;
	/** Going over now and then is normal; this long without a single refusal clears the record. */
	private static final long QUIET = 2 * SECOND;

	private final double rate;
	private final double burst;
	private final LongSupplier clock;
	private final Map<UUID, Bucket> buckets = new HashMap<>();

	private static final class Bucket {
		double tokens;
		long last;
		long overSince = -1;
		long lastRefused = Long.MIN_VALUE / 2;
	}

	public RateLimiter(double rate, double burst) {
		this(rate, burst, System::nanoTime);
	}

	RateLimiter(double rate, double burst, LongSupplier clock) {
		this.rate = rate;
		this.burst = burst;
		this.clock = clock;
	}

	/** Takes {@code cost} units for this player; false (and nothing taken) when they're over the limit. */
	public boolean tryTake(UUID who, double cost) {
		long now = clock.getAsLong();
		Bucket bucket = buckets.computeIfAbsent(who, k -> {
			Bucket fresh = new Bucket();
			fresh.tokens = burst;
			fresh.last = now;
			return fresh;
		});
		bucket.tokens = Math.min(burst, bucket.tokens + (now - bucket.last) * rate / SECOND);
		bucket.last = now;
		if (bucket.tokens >= cost) {
			bucket.tokens -= cost;
			if (now - bucket.lastRefused > QUIET) {
				bucket.overSince = -1;
			}
			return true;
		}
		if (bucket.overSince < 0) {
			bucket.overSince = now;
		}
		bucket.lastRefused = now;
		return false;
	}

	/** True when this player has kept going over the limit for longer than {@code millis}. */
	public boolean abusive(UUID who, long millis) {
		Bucket bucket = buckets.get(who);
		long now = clock.getAsLong();
		return bucket != null && bucket.overSince >= 0 && now - bucket.lastRefused <= QUIET
				&& now - bucket.overSince > millis * 1_000_000L;
	}

	public void forget(UUID who) {
		buckets.remove(who);
	}
}

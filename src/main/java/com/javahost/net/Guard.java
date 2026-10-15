package com.javahost.net;

import java.util.ArrayList;
import java.util.List;

import com.javahost.JavaHost;
import com.javahost.util.RateLimiter;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The host's traffic limits for what guests send through JavaHost (voice, relayed messages, world chunks, rule
 * changes). Going over drops the extra; a game that keeps flooding for {@link #KICK_AFTER_MS} is removed, since
 * only a hacked client does that.
 */
public final class Guard {
	private static final long KICK_AFTER_MS = 10_000;
	private static final List<RateLimiter> ALL = new ArrayList<>();

	/** Voice frames: 50 a second is normal (20 ms frames); a little slack for jitter. */
	public static final RateLimiter VOICE = limiter(60, 120);
	/** Relayed friend messages (the fast lane), in bytes. */
	public static final RateLimiter FAST_LANE = limiter(2 * 1024 * 1024, 4 * 1024 * 1024);
	/** World copies, in bytes. */
	public static final RateLimiter WORLD_SHARE = limiter(4 * 1024 * 1024, 8 * 1024 * 1024);
	/** "Prove this is your world" requests (one per join is normal). */
	public static final RateLimiter PROOF = limiter(1, 3);
	/** World rule changes. */
	public static final RateLimiter RULES = limiter(2, 6);

	private Guard() {
	}

	private static RateLimiter limiter(double rate, double burst) {
		RateLimiter limiter = new RateLimiter(rate, burst);
		ALL.add(limiter);
		return limiter;
	}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ALL.forEach(l -> l.forget(handler.player.getUUID())));
	}

	/** True if this player may send {@code cost} more now; kicks them if they've been flooding for a while. */
	public static boolean allow(ServerPlayer player, RateLimiter limiter, double cost) {
		if (com.javahost.compat.Compat.isOwner(player.level().getServer(), player)) {
			return true; // the host can't flood their own world
		}
		if (limiter.tryTake(player.getUUID(), cost)) {
			return true;
		}
		if (limiter.abusive(player.getUUID(), KICK_AFTER_MS)) {
			JavaHost.LOGGER.warn("Removed {}: too much JavaHost traffic", com.javahost.compat.Compat.name(player));
			player.connection.disconnect(Component.literal("Too much traffic from your game. If this keeps happening, update JavaHost."));
		}
		return false;
	}
}

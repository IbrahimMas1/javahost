package com.javahost.net;

import com.javahost.net.Payloads.RelayDown;
import com.javahost.net.Payloads.RelayUp;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server half of the same-world fast lane: passes pieces of a message from one player to another in this
 * world, stamping who sent it. The pieces are end-to-end encrypted by the players, so the server (the host)
 * can't read them; it only forwards, and it keeps nothing.
 */
public final class FastLaneRouter {
	private static final int MAX_PIECES = Payloads.RELAY_MAX_BYTES / Payloads.RELAY_CHUNK_SIZE + 1;

	private FastLaneRouter() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(RelayUp.TYPE, (payload, context) -> {
			if (payload.total() < 1 || payload.total() > MAX_PIECES || payload.index() < 0 || payload.index() >= payload.total()) {
				return;
			}
			ServerPlayer from = context.player();
			ServerPlayer to = context.server().getPlayerList().getPlayer(payload.to());
			if (to == null || to == from || !ServerPlayNetworking.canSend(to, RelayDown.TYPE)) {
				return;
			}
			ServerPlayNetworking.send(to, new RelayDown(from.getUUID(), payload.id(), payload.index(), payload.total(), payload.data()));
		});
	}
}

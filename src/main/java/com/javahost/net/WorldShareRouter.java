package com.javahost.net;

import com.javahost.net.Payloads.ShareDown;
import com.javahost.net.Payloads.ShareUp;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server half of sending a world copy between two players in this world: stamps who each step is from and
 * passes it on. The world itself is end-to-end encrypted by the two friends, so the host only forwards bytes it
 * can't read, and it keeps nothing. Flow control (a small window of chunks in flight) is done by the two ends.
 */
public final class WorldShareRouter {
	/** Everything except chunks is tiny: an offer header, an answer, an ack or a short reason. */
	private static final int MAX_CONTROL_BYTES = 4096;

	private WorldShareRouter() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ShareUp.TYPE, (payload, context) -> {
			if (payload.kind() < Payloads.SHARE_OFFER || payload.kind() > Payloads.SHARE_CANCEL || payload.index() < 0
					|| (payload.kind() != Payloads.SHARE_CHUNK && payload.data().length > MAX_CONTROL_BYTES)) {
				return;
			}
			ServerPlayer from = context.player();
			ServerPlayer to = context.server().getPlayerList().getPlayer(payload.to());
			if (to == null || to == from || !ServerPlayNetworking.canSend(to, ShareDown.TYPE)) {
				if (payload.kind() == Payloads.SHARE_OFFER || payload.kind() == Payloads.SHARE_CHUNK) {
					// Tell the sender straight away instead of letting it wait for an answer that won't come.
					ServerPlayNetworking.send(from, new ShareDown(payload.to(), payload.id(), Payloads.SHARE_CANCEL, 0,
							(to == null ? "They left the world" : "They need JavaHost 1.6 or newer").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
				}
				return;
			}
			ServerPlayNetworking.send(to, new ShareDown(from.getUUID(), payload.id(), payload.kind(), payload.index(), payload.data()));
		});
	}
}

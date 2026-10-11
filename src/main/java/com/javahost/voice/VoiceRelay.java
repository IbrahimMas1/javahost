package com.javahost.voice;

import com.javahost.HostState;
import com.javahost.net.Payloads.VoiceConfig;
import com.javahost.net.Payloads.VoiceDown;
import com.javahost.net.Payloads.VoiceUp;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server half of proximity voice chat. Clients send their encoded microphone frames here and
 * the server forwards each frame to every other player in the same dimension who is close
 * enough to hear it. The server never decodes audio, so this costs almost nothing.
 */
public final class VoiceRelay {
	private VoiceRelay() {
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (HostState.voiceEnabled() && ServerPlayNetworking.canSend(handler, VoiceConfig.TYPE)) {
				sender.sendPacket(new VoiceConfig(HostState.voiceRange()));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(VoiceUp.TYPE, (payload, context) -> {
			if (!HostState.voiceEnabled()) {
				return;
			}
			ServerPlayer speaker = context.player();
			double range = HostState.voiceRange();
			double rangeSq = range * range;
			VoiceDown frame = new VoiceDown(speaker.getUUID(), payload.sequence(), payload.opus());

			for (ServerPlayer listener : context.server().getPlayerList().getPlayers()) {
				if (listener == speaker || listener.level() != speaker.level()) {
					continue;
				}
				if (listener.distanceToSqr(speaker) > rangeSq) {
					continue;
				}
				if (ServerPlayNetworking.canSend(listener, VoiceDown.TYPE)) {
					ServerPlayNetworking.send(listener, frame);
				}
			}
		});
	}
}

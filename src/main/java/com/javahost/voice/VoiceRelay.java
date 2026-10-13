package com.javahost.voice;

import com.javahost.HostState;
import com.javahost.net.Payloads.VoiceConfig;
import com.javahost.net.Payloads.VoiceDown;
import com.javahost.net.Payloads.VoiceDown2;
import com.javahost.net.Payloads.VoiceUp;
import com.javahost.net.Payloads.VoiceUp2;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server half of proximity voice chat. Clients send their encoded microphone frames here and
 * the server forwards each frame to every other player in the same dimension who is close
 * enough to hear it, or, for party voice, to everyone in the world. The server never decodes audio, so this costs almost nothing.
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

		ServerPlayNetworking.registerGlobalReceiver(VoiceUp.TYPE,
				(payload, context) -> relay(context.player(), payload.sequence(), false, payload.opus()));
		ServerPlayNetworking.registerGlobalReceiver(VoiceUp2.TYPE,
				(payload, context) -> relay(context.player(), payload.sequence(), payload.party(), payload.opus()));
	}

	/**
	 * Party frames go to everyone in the world (any distance or dimension); nearby frames only to players in range.
	 * Players on JavaHost 1.3 only get nearby frames, in the old format.
	 */
	private static void relay(ServerPlayer speaker, int sequence, boolean party, byte[] opus) {
		if (!HostState.voiceEnabled() || !com.javahost.net.Guard.allow(speaker, com.javahost.net.Guard.VOICE, 1)) {
			return;
		}
		double range = HostState.voiceRange();
		double rangeSq = range * range;
		VoiceDown2 frame = new VoiceDown2(speaker.getUUID(), sequence, party, opus);
		VoiceDown oldFrame = new VoiceDown(speaker.getUUID(), sequence, opus);
		for (ServerPlayer listener : speaker.level().getServer().getPlayerList().getPlayers()) {
			if (listener == speaker) {
				continue;
			}
			boolean near = listener.level() == speaker.level() && listener.distanceToSqr(speaker) <= rangeSq;
			if (ServerPlayNetworking.canSend(listener, VoiceDown2.TYPE)) {
				if (party || near) {
					ServerPlayNetworking.send(listener, frame);
				}
			} else if (near && ServerPlayNetworking.canSend(listener, VoiceDown.TYPE)) {
				ServerPlayNetworking.send(listener, oldFrame);
			}
		}
	}
}

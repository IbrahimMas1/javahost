package com.javahost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javahost.net.Payloads;
import com.javahost.pack.PackServer;
import com.javahost.voice.VoiceRelay;
import net.fabricmc.api.ModInitializer;

public class JavaHost implements ModInitializer {
	public static final String MOD_ID = "javahost";
	public static final Logger LOGGER = LoggerFactory.getLogger("JavaHost");
	/**
	 * Network compatibility: versions with the same protocol can play together. Bump it only when a change means
	 * older and newer JavaHost can't join each other's worlds.
	 */
	public static final int PROTOCOL = 3;
	/** This JavaHost's version, e.g. "1.4.0" (shown to friends for the version check). */
	public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("0");

	@Override
	public void onInitialize() {
		Payloads.register();
		com.javahost.net.Guard.register();
		VoiceRelay.register();
		PackServer.register();
		com.javahost.net.FastLaneRouter.register();
		com.javahost.net.WorldShareRouter.register();
		com.javahost.rules.WorldRules.register();
		com.javahost.rules.Trust.register();

		// Tell JavaHost players which version the host runs.
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(handler, Payloads.ServerHello.TYPE)) {
				sender.sendPacket(new Payloads.ServerHello(VERSION));
			}
		});

		// "Is this really your world?": the host's game signs the guest's challenge with its own key.
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Payloads.HostProofRequest.TYPE, (request, context) -> {
			HostState.Prover prover = HostState.prover();
			if (prover == null || request.nonce().length < 16 || !com.javahost.net.Guard.allow(context.player(), com.javahost.net.Guard.PROOF, 1)) {
				return;
			}
			byte[] data = com.javahost.util.FriendCrypto.hostProofBytes(request.nonce(), prover.host(), context.player().getUUID());
			context.responseSender().sendPacket(new Payloads.HostProof(prover.host(), prover.sign(data)));
		});

		// Backstop for the login check in PlayerListMixin (e.g. if the friends list changed in between).
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			String refusal = JoinGate.refusal(server, handler.player.nameAndId());
			if (refusal != null) {
				JoinGate.log(handler.player.getPlainTextName(), refusal);
				handler.disconnect(net.minecraft.network.chat.Component.literal(refusal));
			}
		});
	}
}

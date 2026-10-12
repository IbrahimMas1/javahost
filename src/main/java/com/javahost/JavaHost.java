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

	@Override
	public void onInitialize() {
		Payloads.register();
		VoiceRelay.register();
		PackServer.register();

		// Who may join a hosted world: not banned, room left, and (by default) on the host's friends list.
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (server.isSingleplayerOwner(handler.player.nameAndId())) {
				return;
			}
			String refusal = null;
			if (HostState.isBanned(handler.player.getUUID())) {
				refusal = "The host has banned you from this world.";
			} else if (server.getPlayerList().getPlayers().stream().filter(p -> p != handler.player).count() >= HostState.maxPlayers()) {
				// The joining player may or may not be in the list yet, so count everyone else.
				refusal = "This world is full (" + HostState.maxPlayers() + (HostState.maxPlayers() == 1 ? " player)." : " players).");
			} else if (HostState.friendsOnly() && !HostState.isAllowed(handler.player.getUUID())) {
				refusal = "This world is just for the host's friends. Ask them to add you in JavaHost.";
			}
			if (refusal != null) {
				LOGGER.info("Turned away {}: {}", handler.player.getPlainTextName(), refusal);
				handler.disconnect(net.minecraft.network.chat.Component.literal(refusal));
			}
		});
	}
}

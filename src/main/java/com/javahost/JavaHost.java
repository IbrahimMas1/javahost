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

		// Friends-only worlds: turn away anyone the host hasn't added as a friend.
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (HostState.friendsOnly() && !server.isSingleplayerOwner(handler.player.nameAndId())
					&& !HostState.isAllowed(handler.player.getUUID())) {
				LOGGER.info("Turned away {}: not on the host's friends list", handler.player.getPlainTextName());
				handler.disconnect(net.minecraft.network.chat.Component.literal(
						"This world is just for the host's friends. Ask them to add you in JavaHost."));
			}
		});
	}
}

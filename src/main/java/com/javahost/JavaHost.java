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
	}
}

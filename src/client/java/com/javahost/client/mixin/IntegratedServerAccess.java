package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;

/** For closing a world to LAN on versions without IntegratedServer.unpublishServer (before 26.2). */
@Mixin(IntegratedServer.class)
public interface IntegratedServerAccess {
	@Accessor("publishedPort")
	void javahost$setPublishedPort(int port);

	@Accessor("lanPinger")
	LanServerPinger javahost$lanPinger();

	@Accessor("lanPinger")
	void javahost$setLanPinger(LanServerPinger pinger);
}

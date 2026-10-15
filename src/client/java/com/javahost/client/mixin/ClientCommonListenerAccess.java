package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;

/** The server being joined, while the connection is still being set up (for rejoining after a mod switch). */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonListenerAccess {
	@Accessor("serverData")
	ServerData javahost$serverData();
}

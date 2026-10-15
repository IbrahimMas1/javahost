package com.javahost.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import io.netty.channel.ChannelFuture;
import net.minecraft.server.network.ServerConnectionListener;

/** The server's listening sockets, to close the LAN one on versions before 26.2. */
@Mixin(ServerConnectionListener.class)
public interface ServerConnectionListenerAccess {
	@Accessor("channels")
	List<ChannelFuture> javahost$channels();
}

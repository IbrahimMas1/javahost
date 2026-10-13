package com.javahost.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;

/** Lets the host see whether a player is still reading what it sends (so big transfers wait instead of piling up). */
@Mixin(Connection.class)
public interface ConnectionAccess {
	@Accessor("channel")
	Channel javahost$channel();
}

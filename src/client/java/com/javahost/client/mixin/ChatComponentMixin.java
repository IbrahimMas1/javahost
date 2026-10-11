package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.javahost.client.host.E4mcCompat;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

/** Watches client-side system messages so we can pick up the relay address e4mc announces. */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {
	@Inject(method = "addClientSystemMessage", at = @At("HEAD"))
	private void javahost$watchRelayDomain(Component message, CallbackInfo ci) {
		E4mcCompat.onChatMessage(message);
	}
}

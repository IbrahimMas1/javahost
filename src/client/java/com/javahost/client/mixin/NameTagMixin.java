package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.javahost.client.voice.VoiceClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Puts a green note in front of a player's nametag while they're talking on voice chat. */
@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {
	@Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
			at = @At("TAIL"))
	private void javahost$talkingIcon(Entity entity, EntityRenderState state, float partialTicks, double nameDistance, double belowDistance,
			CallbackInfo ci) {
		if (state.nameTag != null && entity instanceof Player player && VoiceClient.isTalking(player.getUUID())) {
			state.nameTag = Component.literal("♪ ").withStyle(ChatFormatting.GREEN).append(state.nameTag);
		}
	}
}

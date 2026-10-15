package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.javahost.client.voice.VoiceClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Puts a green note in front of a player's nametag while they're talking on voice chat. */
@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {
	//? if >=26.2 {
	@org.spongepowered.asm.mixin.injection.Inject(
			method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
			at = @At("TAIL"))
	private void javahost$talkingIcon(Entity entity, net.minecraft.client.renderer.entity.state.EntityRenderState state, float partialTicks,
			double nameDistance, double belowDistance, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (state.nameTag != null && entity instanceof Player player && VoiceClient.isTalking(player.getUUID())) {
			state.nameTag = talking(state.nameTag);
		}
	}
	//?} else if >=1.21.2 {
	/*@org.spongepowered.asm.mixin.injection.Inject(
			// Split so the build's extractRenderState -> render rename (for GUI code) leaves this name alone.
			method = "extractRenderState" + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("TAIL"))
	private void javahost$talkingIcon(Entity entity, net.minecraft.client.renderer.entity.state.EntityRenderState state, float partialTicks,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (state.nameTag != null && entity instanceof Player player && VoiceClient.isTalking(player.getUUID())) {
			state.nameTag = talking(state.nameTag);
		}
	}
	*///?} else {
	/*@org.spongepowered.asm.mixin.injection.ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true)
	private Component javahost$talkingIcon(Component name, Entity entity, Component original, com.mojang.blaze3d.vertex.PoseStack pose,
			net.minecraft.client.renderer.MultiBufferSource buffers, int light, float partialTicks) {
		return entity instanceof Player player && VoiceClient.isTalking(player.getUUID()) ? talking(name) : name;
	}
	*///?}

	private static Component talking(Component name) {
		return Component.literal("♪ ").withStyle(ChatFormatting.GREEN).append(name);
	}
}

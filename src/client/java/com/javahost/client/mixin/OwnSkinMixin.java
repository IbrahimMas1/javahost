package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * After you put on a skin in the Skins page, your own player (third person, the inventory, your hand) shows it at
 * once. Only on this computer and only for you: other players get your skin from the server when you join.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class OwnSkinMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void javahost$ownSkin(CallbackInfoReturnable<PlayerSkin> result) {
		if ((Object) this == Minecraft.getInstance().player && com.javahost.client.skins.SkinService.changedThisSession()) {
			result.setReturnValue(com.javahost.client.skins.SkinService.ownSkin(result.getReturnValue()));
		}
	}
}

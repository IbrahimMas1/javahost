package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.client.server.IntegratedServer;

/**
 * 26.3's integrated server answers permission questions itself (it no longer always asks MinecraftServer), so
 * JavaHost's operator rule is applied here too; see {@code com.javahost.mixin.MinecraftServerMixin}. Before 26.3 the
 * integrated server doesn't override the method and this mixin does nothing.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerPermissionMixin {
	//? if >=26.3 {
	@org.spongepowered.asm.mixin.injection.Inject(
			method = "getProfilePermissions(Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/server/permissions/LevelBasedPermissionSet;",
			at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void javahost$operators(net.minecraft.server.players.NameAndId profile,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.server.permissions.LevelBasedPermissionSet> result) {
		result.setReturnValue(com.javahost.rules.WorldRules.permissions((IntegratedServer) (Object) this, profile, result.getReturnValue()));
	}
	//?}
}

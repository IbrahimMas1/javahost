package com.javahost.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.javahost.rules.WorldRules;
import net.minecraft.server.MinecraftServer;

/**
 * Command permissions in a world the host opened from their own game come from JavaHost's operators and Cheats switch
 * (see {@link WorldRules#permissionLevel}), not the game's ops list. On 26.3 the integrated server overrides this
 * method, so the client has the same hook there ({@code IntegratedServerPermissionMixin}).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	//? if >=1.21.11 {
	@Inject(method = "getProfilePermissions(Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/server/permissions/LevelBasedPermissionSet;",
			at = @At("RETURN"), cancellable = true)
	private void javahost$operators(net.minecraft.server.players.NameAndId profile,
			CallbackInfoReturnable<net.minecraft.server.permissions.LevelBasedPermissionSet> result) {
		result.setReturnValue(WorldRules.permissions((MinecraftServer) (Object) this, profile, result.getReturnValue()));
	}
	//?} else if >=1.21.9 {
	/*@Inject(method = "getProfilePermissions(Lnet/minecraft/server/players/NameAndId;)I", at = @At("RETURN"), cancellable = true)
	private void javahost$operators(net.minecraft.server.players.NameAndId profile, CallbackInfoReturnable<Integer> result) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		result.setReturnValue(WorldRules.permissionLevel(server, profile.id(), server.isSingleplayerOwner(profile), result.getReturnValue()));
	}
	*///?} else {
	/*@Inject(method = "getProfilePermissions(Lcom/mojang/authlib/GameProfile;)I", at = @At("RETURN"), cancellable = true)
	private void javahost$operators(com.mojang.authlib.GameProfile profile, CallbackInfoReturnable<Integer> result) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		result.setReturnValue(WorldRules.permissionLevel(server, profile.getId(), server.isSingleplayerOwner(profile), result.getReturnValue()));
	}
	*///?}
}

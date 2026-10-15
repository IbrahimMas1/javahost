package com.javahost.mixin;

import java.net.SocketAddress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.javahost.JoinGate;
import net.minecraft.network.chat.Component;
//? if >=1.21.9 {
import net.minecraft.server.players.NameAndId;
//?} else
/*import com.mojang.authlib.GameProfile;*/
import net.minecraft.server.players.PlayerList;

/**
 * Turns away banned, blocked and (in friends-only worlds) unknown players at login, before they're placed in the
 * world: no "joined the game" message, no world details sent, nothing saved for them.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
	//? if >=1.21.9 {
	private void javahost$gate(SocketAddress address, NameAndId player, CallbackInfoReturnable<Component> result) {
		var server = ((PlayerList) (Object) this).getServer();
		String refusal = JoinGate.refusal(server, player.id(), server.isSingleplayerOwner(player));
		if (refusal != null) {
			JoinGate.log(player.name(), refusal);
	//?} else {
	/*private void javahost$gate(SocketAddress address, GameProfile player, CallbackInfoReturnable<Component> result) {
		var server = ((PlayerList) (Object) this).getServer();
		String refusal = JoinGate.refusal(server, player.getId(), server.isSingleplayerOwner(player));
		if (refusal != null) {
			JoinGate.log(player.getName(), refusal);
	*///?}
			result.setReturnValue(Component.literal(refusal));
		}
	}
}

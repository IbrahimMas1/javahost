package com.javahost.client.compat;

import java.nio.file.Path;

import net.minecraft.client.server.IntegratedServer;

/**
 * Calls that differ between the Minecraft versions JavaHost is built for, kept in one place. The {@code //?} comments
 * are read by Stonecutter, which picks the right line for each version (see stonecutter.gradle.kts).
 */
public final class ClientCompat {
	private ClientCompat() {
	}

	/** This game's Minecraft version, e.g. "1.21.1". */
	public static String minecraftVersion() {
		return net.minecraft.SharedConstants.getCurrentVersion().name();
	}

	/** Opens a folder or file in the computer's file browser. */
	public static void openPath(Path path) {
		//? if >=26.3 {
		com.mojang.blaze3d.Blaze3D.openPath(path);
		//?} else if >=1.21.11 {
		/*net.minecraft.util.Util.getPlatform().openPath(path);
		*///?} else {
		/*net.minecraft.Util.getPlatform().openPath(path);
		*///?}
	}

	/** Leaves the world or server we're in (saving it if it's ours), like the pause menu's Disconnect. */
	public static void leaveWorld(net.minecraft.network.chat.Component reason) {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		//? if >=1.21.9 {
		minecraft.disconnectFromWorld(reason);
		//?} else if >=1.21.6 {
		/*net.minecraft.client.gui.screens.PauseScreen.disconnectFromWorld(minecraft, reason);
		*///?} else {
		/*boolean local = minecraft.isLocalServer();
		if (minecraft.level != null) {
			minecraft.level.disconnect();
		}
		if (local) {
			minecraft.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
		} else {
			minecraft.disconnect();
		}
		minecraft.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
		*///?}
	}

	/** A player's profile from Mojang, skin included, or null if there's none. A network call: never on the render thread. */
	public static com.mojang.authlib.GameProfile fetchProfile(java.util.UUID id) {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		//? if >=1.21.9 {
		return minecraft.services().profileResolver().fetchById(id).orElse(null);
		//?} else {
		/*com.mojang.authlib.yggdrasil.ProfileResult result = minecraft.getMinecraftSessionService().fetchProfile(id, false);
		return result == null ? null : result.profile();
		*///?}
	}

	/** Loads the skin in a profile (downloading it if needed); empty when the profile has none. */
	public static java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.world.entity.player.PlayerSkin>> loadSkin(
			com.mojang.authlib.GameProfile profile) {
		net.minecraft.client.resources.SkinManager skins = net.minecraft.client.Minecraft.getInstance().getSkinManager();
		//? if >=1.21.9 {
		return skins.get(profile);
		//?} else if >=1.21.4 {
		/*return skins.getOrLoad(profile);
		*///?} else {
		/*return skins.getOrLoad(profile).thenApply(java.util.Optional::of);
		*///?}
	}

	/** Whether the player hid the HUD (F1). */
	public static boolean hudHidden() {
		//? if >=26.2 {
		return net.minecraft.client.Minecraft.getInstance().gui.hud.isHidden();
		//?} else
		/*return net.minecraft.client.Minecraft.getInstance().options.hideGui;*/
	}

	/** Saves a screenshot of the game (as it's drawn, without screens on top) to screenshots/{@code name}. */
	public static void screenshot(String name, java.util.function.Consumer<net.minecraft.network.chat.Component> done) {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		//? if >=26.2 {
		net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1, done);
		//?} else if >=1.21.5 {
		/*net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), 1, done);
		*///?} else
		/*net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), done);*/
	}

	/** Opens the world to LAN on {@code port} without changing anyone's game mode or commands. */
	public static boolean publish(IntegratedServer server, int port) {
		//? if >=26.2 {
		return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, port);
		//?} else
		/*return server.publishServer(null, false, port);*/
	}

	/** Closes the world to LAN again: stops listening, disconnects guests and stops the LAN broadcast. */
	public static void unpublish(IntegratedServer server) {
		//? if >=26.2 {
		server.unpublishServer();
		//?} else {
		/*if (!server.isPublished()) {
			return;
		}
		var channels = ((com.javahost.client.mixin.ServerConnectionListenerAccess) server.getConnection()).javahost$channels();
		synchronized (channels) {
			for (var it = channels.iterator(); it.hasNext();) {
				io.netty.channel.ChannelFuture future = it.next();
				if (!(future.channel() instanceof io.netty.channel.local.LocalServerChannel)) {
					future.channel().close().syncUninterruptibly();
					it.remove();
				}
			}
		}
		net.minecraft.network.chat.Component reason = net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.server_shutdown");
		for (var player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
			if (!com.javahost.compat.Compat.isOwner(server, player)) {
				player.connection.disconnect(reason);
			}
		}
		var access = (com.javahost.client.mixin.IntegratedServerAccess) server;
		if (access.javahost$lanPinger() != null) {
			access.javahost$lanPinger().interrupt();
			access.javahost$setLanPinger(null);
		}
		access.javahost$setPublishedPort(-1);
		*///?}
	}

	/** Whether players other than the host may use commands (the "Allow cheats" of Open to LAN). */
	public static void setGuestCommands(IntegratedServer server, boolean allowed) {
		//? if >=26.3 {
		server.setGuestCommandAccess(allowed);
		//?} else if >=26.2 {
		/*server.setCommandsAllowedForOtherPlayers(allowed);
		*///?} else {
		/*server.getPlayerList().setAllowCommandsForAllPlayers(allowed);
		*///?}
	}
}

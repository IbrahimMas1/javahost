package com.javahost.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.packs.PackType;

/** Test calls that differ between the Minecraft versions JavaHost is built for (see stonecutter.gradle.kts). */
final class TestCompat {
	private TestCompat() {
	}

	static void waitForChunks(TestSingleplayerContext world) {
		//? if >=26.2 {
		world.getConnection().waitForChunksRender();
		//?} else if >=26.1 {
		/*world.getClientLevel().waitForChunksRender();
		*///?} else
		/*world.getClientWorld().waitForChunksRender();*/
	}

	/** Re-lays out the GUI after a window size or GUI scale change. */
	static void resizeGui(Minecraft mc) {
		//? if >=26.1 {
		mc.resizeGui();
		//?} else
		/*mc.resizeDisplay();*/
	}

	/** True once no loading overlay (e.g. a resource reload) is showing. */
	static boolean noOverlay(Minecraft mc) {
		//? if >=26.2 {
		return mc.gui.overlay() == null;
		//?} else
		/*return mc.getOverlay() == null;*/
	}

	static void setGuestCommands(IntegratedServer server, boolean allowed) {
		com.javahost.client.compat.ClientCompat.setGuestCommands(server, allowed);
	}

	static boolean guestCommands(IntegratedServer server) {
		//? if >=26.3 {
		return server.getGuestCommandAccess();
		//?} else if >=26.2 {
		/*return server.commandsAllowedForOtherPlayers();
		*///?} else
		/*return server.getPlayerList().isAllowCommandsForAllPlayers();*/
	}

	/** pack.mcmeta for a resource pack made for this Minecraft version. */
	static String packMcmeta(String description) {
		//? if >=1.21.9 {
		int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
		return "{\"pack\":{\"description\":\"" + description + "\",\"min_format\":" + format + ",\"max_format\":" + format + "}}";
		//?} else {
		/*//? if >=1.21.6 {
		int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
		//?} else
		/^int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);^/
		return "{\"pack\":{\"description\":\"" + description + "\",\"pack_format\":" + format + "}}";
		*///?}
	}

	static void press(AbstractButton button) {
		//? if >=1.21.9 {
		button.onPress(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
		//?} else
		/*button.onPress();*/
	}
}

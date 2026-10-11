package com.javahost.client.gui.ui;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/** Draws a player's face from their skin, falling back to the default skin when it isn't known. */
public final class Faces {
	private Faces() {
	}

	public static PlayerSkin skinOf(UUID id) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && minecraft.player.getUUID().equals(id)) {
			return minecraft.player.getSkin();
		}
		ClientPacketListener connection = minecraft.getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
		return info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
	}

	public static void draw(GuiGraphicsExtractor g, UUID id, int x, int y, int size) {
		PlayerFaceExtractor.extractRenderState(g, skinOf(id), x, y, size);
	}
}

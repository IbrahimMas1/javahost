package com.javahost.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * JavaHost's screens. Each one calls {@link #background} first thing when drawing: from 1.21.6 Minecraft draws the
 * blurred background itself before the screen, but older versions leave it to the screen, inside Screen.render, which
 * would put it on top of whatever the screen drew first.
 */
public abstract class BaseScreen extends Screen {
	protected BaseScreen(Component title) {
		super(title);
	}

	//? if >=1.21.6 {
	protected void background(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	}
	//?} else {
	/*private boolean backgroundDrawn;

	protected void background(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.renderBackground(g, mouseX, mouseY, a);
		backgroundDrawn = true;
	}

	@Override
	public void renderBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// Already drawn at the start of this frame (and blurring twice a frame isn't allowed).
		if (backgroundDrawn) {
			backgroundDrawn = false;
			return;
		}
		super.renderBackground(g, mouseX, mouseY, a);
	}
	*///?}
}

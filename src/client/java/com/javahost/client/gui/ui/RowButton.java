package com.javahost.client.gui.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** An invisible clickable row (e.g. a friend in a list) that lights up on hover. Other buttons sit on top of it. */
public class RowButton extends Button {
	private float hover;

	public RowButton(int width, int height, Component narration, Consumer<RowButton> onPress) {
		super(0, 0, width, height, narration, button -> onPress.accept((RowButton) button), DEFAULT_NARRATION);
	}

	@Override
	//? if >=26.1 {
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	//?} else if >=1.21.11 {
	/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?} else {
	/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?}
		hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.35f);
		if (hover > 0.01f) {
			// A light tint, so whatever the row shows stays readable underneath.
			Draw.roundRect(g, getX(), getY(), getWidth(), getHeight(), 5, Theme.withAlpha(0x14FFFFFF, hover));
		}
	}
}

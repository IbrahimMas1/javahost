package com.javahost.client.gui.ui;

import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A round colour swatch for picking the accent; the selected one gets a ring. */
public class SwatchButton extends Button {
	private final int color;
	private final BooleanSupplier selected;
	private float hover;

	public SwatchButton(int size, Component name, int color, BooleanSupplier selected, Consumer<SwatchButton> onPress) {
		super(0, 0, size, size, name, button -> onPress.accept((SwatchButton) button), DEFAULT_NARRATION);
		this.color = color;
		this.selected = selected;
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
		int x = getX();
		int y = getY();
		int s = getWidth();
		if (selected.getAsBoolean()) {
			Draw.roundRect(g, x, y, s, s, s / 2, Theme.TEXT);
			Draw.roundRect(g, x + 2, y + 2, s - 4, s - 4, s / 2 - 2, Theme.WINDOW);
		} else if (hover > 0.01f) {
			Draw.roundRect(g, x, y, s, s, s / 2, Theme.withAlpha(Theme.TEXT, hover * 0.5f));
			Draw.roundRect(g, x + 2, y + 2, s - 4, s - 4, s / 2 - 2, Theme.WINDOW);
		}
		Draw.roundRect(g, x + 3, y + 3, s - 6, s - 6, s / 2 - 3, color);
	}
}

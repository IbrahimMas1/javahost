package com.javahost.client.gui.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A small rounded filter pill ("Magic", "Adventure"); the selected one is tinted in the accent colour. */
public class Chip extends Button {
	public static final int HEIGHT = 14;
	private final boolean selected;
	private float hover;

	public Chip(String label, boolean selected, Consumer<Chip> onPress) {
		super(0, 0, width(label), HEIGHT, Component.literal(label), button -> onPress.accept((Chip) button), DEFAULT_NARRATION);
		this.selected = selected;
	}

	public static int width(String label) {
		return Draw.font().width(label) + 12;
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
		int fill = selected ? Theme.withAlpha(Theme.ACCENT, 0.18f) : hover > 0.5f ? Theme.CARD_HOVER : Theme.CARD;
		Draw.roundRect(g, getX(), getY(), getWidth(), getHeight(), HEIGHT / 2, fill);
		int color = selected ? Theme.ACCENT : hover > 0.5f ? Theme.TEXT : Theme.MUTED;
		Draw.centered(g, getMessage().getString(), getX() + getWidth() / 2, getY() + (HEIGHT - 8) / 2 + 1, color);
	}
}

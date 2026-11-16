package com.javahost.client.gui.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * A small square on/off button with an icon, for rows too narrow for a labelled switch: off is a quiet outline, on is
 * lit in the accent colour. The label is its name for narration (and tests).
 */
public class IconToggle extends AbstractButton {
	private final Icons icon;
	private final Consumer<Boolean> onChange;
	private boolean value;
	private float lit;
	private float hover;

	public IconToggle(int size, Component label, Icons icon, boolean value, Consumer<Boolean> onChange) {
		super(0, 0, size, size, label);
		this.icon = icon;
		this.value = value;
		this.lit = value ? 1 : 0;
		this.onChange = onChange;
	}

	public boolean value() {
		return value;
	}

	@Override
	//? if >=1.21.9 {
	public void onPress(InputWithModifiers input) {
	//?} else
	/*public void onPress() {*/
		value = !value;
		onChange.accept(value);
	}

	@Override
	//? if >=26.1 {
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	//?} else if >=1.21.11 {
	/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?} else {
	/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?}
		lit = Draw.approach(lit, value ? 1 : 0, 0.3f);
		hover = Draw.approach(hover, active && isHoveredOrFocused() ? 1 : 0, 0.35f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		int border;
		int fill;
		int iconColor;
		if (!active) {
			border = 0x14FFFFFF;
			fill = 0xE6151B23;
			iconColor = value ? Theme.withAlpha(Theme.ACCENT, 0.5f) : Theme.FAINT;
		} else {
			int offBorder = Theme.mix(0x30FFFFFF, 0x66FFFFFF, hover);
			int onBorder = Theme.withAlpha(Theme.ACCENT, 0.55f + 0.35f * hover);
			border = Theme.mix(offBorder, onBorder, lit);
			int offFill = Theme.mix(0xE6151B23, 0xE6252E3A, hover);
			int onFill = Theme.mix(0xE6151B23, Theme.withAlpha(Theme.ACCENT_DARK, 0.9f), 0.85f);
			fill = Theme.mix(offFill, onFill, lit);
			iconColor = Theme.mix(Theme.mix(Theme.FAINT, Theme.MUTED, hover), Theme.ACCENT, lit);
		}
		if (active && isFocused() && net.minecraft.client.Minecraft.getInstance().getLastInputType().isKeyboard()) {
			Draw.roundRect(g, x - 1, y - 1, w + 2, h + 2, 5, Theme.withAlpha(Theme.ACCENT, 0.8f));
		}
		Draw.roundRect(g, x, y, w, h, 4, border);
		Draw.roundRect(g, x + 1, y + 1, w - 2, h - 2, 3, fill);
		icon.draw(g, x + (w - Icons.SIZE) / 2, y + (h - Icons.SIZE) / 2, iconColor);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.empty().append(getMessage()).append(value ? ": on" : ": off"));
	}
}

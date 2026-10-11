package com.javahost.client.gui.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A setting row: label (and optional hint) on the left, an animated switch on the right. */
public class ToggleSwitch extends AbstractButton {
	private final Consumer<Boolean> onChange;
	private final String hint;
	private boolean value;
	private float knob;
	private float hover;

	public ToggleSwitch(int width, Component label, String hint, boolean value, Consumer<Boolean> onChange) {
		super(0, 0, width, hint == null ? 16 : 24, label);
		this.hint = hint;
		this.value = value;
		this.knob = value ? 1 : 0;
		this.onChange = onChange;
	}

	public boolean value() {
		return value;
	}

	public void setValue(boolean value) {
		this.value = value;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		value = !value;
		onChange.accept(value);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		knob = Draw.approach(knob, value ? 1 : 0, 0.3f);
		hover = Draw.approach(hover, active && isHoveredOrFocused() ? 1 : 0, 0.35f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int switchW = 22;
		int switchH = 12;
		int sx = x + w - switchW;
		int sy = y + 2;

		int labelColor = active ? Theme.mix(Theme.TEXT, 0xFFFFFFFF, hover) : Theme.FAINT;
		Draw.text(g, Draw.fit(getMessage().getString(), w - switchW - 8), x, y + 4, labelColor);
		if (hint != null) {
			Draw.text(g, Draw.fit(hint, w - switchW - 8), x, y + 15, active ? Theme.MUTED : Theme.FAINT);
		}

		int track = active ? Theme.mix(Theme.FIELD, Theme.ACCENT_DARK, knob) : Theme.CARD;
		Draw.bordered(g, sx, sy, switchW, switchH, 6, track, active ? Theme.mix(Theme.BORDER, Theme.ACCENT, knob) : Theme.BORDER);
		int knobX = sx + 2 + Math.round(knob * (switchW - 12));
		Draw.roundRect(g, knobX, sy + 2, 8, 8, 4, active ? Theme.mix(Theme.MUTED, Theme.ACCENT, knob) : Theme.FAINT);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.empty().append(getMessage()).append(value ? ": on" : ": off"));
	}
}

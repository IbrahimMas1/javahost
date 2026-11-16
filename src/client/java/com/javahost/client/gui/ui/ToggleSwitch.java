package com.javahost.client.gui.ui;

import java.util.List;
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
	/** Just the switch: the row around it draws its own text. */
	private boolean bare;
	/** The hint wraps onto a second line instead of being cut (the row is taller when it needs to be). */
	private boolean wrapHint;

	public ToggleSwitch(int width, Component label, String hint, boolean value, Consumer<Boolean> onChange) {
		super(0, 0, width, hint == null ? 16 : 24, label);
		this.hint = hint;
		this.value = value;
		this.knob = value ? 1 : 0;
		this.onChange = onChange;
	}

	/** Draws only the switch (at the right end); the label stays its name for narration and tests. */
	public ToggleSwitch bare() {
		bare = true;
		return this;
	}

	/** Lets a long hint use two lines; call before the row is placed (its height changes). */
	public ToggleSwitch wrapHint() {
		wrapHint = true;
		if (hint != null && Draw.split(hint, getWidth() - 30).size() > 1) {
			setHeight(34);
		}
		return this;
	}

	public boolean value() {
		return value;
	}

	public void setValue(boolean value) {
		this.value = value;
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
		knob = Draw.approach(knob, value ? 1 : 0, 0.3f);
		hover = Draw.approach(hover, active && isHoveredOrFocused() ? 1 : 0, 0.35f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int switchW = 22;
		int switchH = 12;
		int sx = x + w - switchW;
		int sy = y + 2;

		if (!bare) {
			int labelColor = active ? Theme.mix(Theme.TEXT, 0xFFFFFFFF, hover) : Theme.FAINT;
			Draw.textFit(g, getMessage().getString(), w - switchW - 8, x, y + 4, labelColor);
			int hintColor = active ? Theme.MUTED : Theme.FAINT;
			if (hint != null && wrapHint && getHeight() > 24) {
				java.util.List<net.minecraft.util.FormattedCharSequence> lines = Draw.split(hint, w - switchW - 8);
				Draw.text(g, lines.get(0), x, y + 15, hintColor);
				if (lines.size() > 2) {
					Draw.truncated(g, x, y + 14, w - switchW - 8, 20, hint);
					lines = List.of(lines.get(0), net.minecraft.util.FormattedCharSequence.composite(lines.get(1),
							Component.literal("...").getVisualOrderText()));
				}
				Draw.text(g, lines.get(1), x, y + 25, hintColor);
			} else if (hint != null) {
				Draw.textFit(g, hint, w - switchW - 8, x, y + 15, hintColor);
			}
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

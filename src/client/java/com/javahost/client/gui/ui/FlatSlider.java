package com.javahost.client.gui.ui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Label and value above a thin track with a round handle. */
public class FlatSlider extends AbstractSliderButton {
	private final String label;
	private final double min;
	private final double max;
	private final DoubleConsumer onChange;
	private final DoubleFunction<String> format;
	private float hover;

	public FlatSlider(int width, String label, double value, double min, double max, DoubleConsumer onChange, DoubleFunction<String> format) {
		super(0, 0, width, 22, Component.literal(label), (value - min) / (max - min));
		this.label = label;
		this.min = min;
		this.max = max;
		this.onChange = onChange;
		this.format = format;
		updateMessage();
	}

	public double actual() {
		return min + value * (max - min);
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(label + ": " + format.apply(actual())));
	}

	@Override
	protected void applyValue() {
		onChange.accept(actual());
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		hover = Draw.approach(hover, active && isHoveredOrFocused() ? 1 : 0, 0.35f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		String valueText = format.apply(actual());
		Draw.text(g, label, x, y + 1, active ? Theme.TEXT : Theme.FAINT);
		Draw.text(g, valueText, x + w - Draw.font().width(valueText), y + 1, active ? Theme.MUTED : Theme.FAINT);

		int trackY = y + 15;
		Draw.roundRect(g, x, trackY, w, 4, 2, Theme.FIELD);
		int filled = (int) Math.round(value * (w - 8)) + 4;
		Draw.roundRect(g, x, trackY, filled, 4, 2, active ? Theme.ACCENT : Theme.FAINT);
		int knobSize = hover > 0.5f ? 10 : 8;
		Draw.roundRect(g, x + filled - knobSize / 2, trackY + 2 - knobSize / 2, knobSize, knobSize, knobSize / 2,
				active ? Theme.mix(0xFFDDE7EE, 0xFFFFFFFF, hover) : Theme.FAINT);
		handleCursor(g);
	}
}

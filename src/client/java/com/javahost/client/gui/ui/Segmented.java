package com.javahost.client.gui.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A row of mutually exclusive choices, e.g. "Push to talk | Voice activation". */
public class Segmented<T> extends AbstractWidget {
	private final List<T> options;
	private final Function<T, String> labels;
	private final Consumer<T> onChange;
	private T selected;
	private float slide;

	public Segmented(int width, List<T> options, T selected, Function<T, String> labels, Consumer<T> onChange) {
		super(0, 0, width, 18, Component.empty());
		this.options = options;
		this.labels = labels;
		this.onChange = onChange;
		this.selected = options.contains(selected) ? selected : options.getFirst();
		this.slide = options.indexOf(this.selected);
	}

	public T selected() {
		return selected;
	}

	private int segmentWidth() {
		return getWidth() / options.size();
	}

	@Override
	//? if >=1.21.9 {
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
	//?} else {
	/*public void onClick(double x, double y) {
		var event = new MouseButtonEvent(x, y, 0);
	*///?}
		int index = (int) ((event.x() - getX()) / segmentWidth());
		select(Math.max(0, Math.min(options.size() - 1, index)));
	}

	@Override
	//? if >=1.21.9 {
	public boolean keyPressed(KeyEvent event) {
	//?} else {
	/*public boolean keyPressed(int key, int scancode, int modifiers) {
		var event = new KeyEvent(key, scancode, modifiers);
	*///?}
		int index = options.indexOf(selected);
		if (event.isLeft() && index > 0) {
			select(index - 1);
			return true;
		}
		if (event.isRight() && index < options.size() - 1) {
			select(index + 1);
			return true;
		}
		return false;
	}

	private void select(int index) {
		T option = options.get(index);
		if (option != selected) {
			selected = option;
			onChange.accept(option);
		}
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		slide = Draw.approach(slide, options.indexOf(selected), 0.3f);
		int x = getX();
		int y = getY();
		int seg = segmentWidth();
		Draw.bordered(g, x, y, seg * options.size(), getHeight(), 4, Theme.FIELD, isFocused() ? Theme.FAINT : Theme.BORDER);
		Draw.roundRect(g, x + 2 + Math.round(slide * seg), y + 2, seg - 4, getHeight() - 4, 3, active ? Theme.CARD_HOVER : Theme.CARD);
		for (int i = 0; i < options.size(); i++) {
			String full = labels.apply(options.get(i));
			String text = Draw.fit(full, seg - 6);
			if (!text.equals(full)) {
				Draw.truncated(g, x + i * seg, y, seg, getHeight(), full); // full label on hover
			}
			boolean isSelected = options.get(i) == selected;
			boolean hovered = isHovered() && mouseX >= x + i * seg && mouseX < x + (i + 1) * seg;
			int color = !active ? Theme.FAINT : isSelected ? Theme.ACCENT : hovered ? Theme.TEXT : Theme.MUTED;
			Draw.centered(g, text, x + i * seg + seg / 2, y + (getHeight() - 8) / 2 + 1, color);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal(labels.apply(selected)));
	}
}

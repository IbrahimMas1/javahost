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

	/**
	 * Segment edges: each label gets its own width plus an even share of what's left, so a long label
	 * ("Fabulous", "Peaceful") isn't cut while short ones have room to spare. Equal widths if they don't fit.
	 */
	private int[] edges() {
		int n = options.size();
		int[] text = new int[n];
		int sum = 0;
		for (int i = 0; i < n; i++) {
			text[i] = Draw.font().width(labels.apply(options.get(i))) + 8;
			sum += text[i];
		}
		int[] edges = new int[n + 1];
		int spare = getWidth() - sum;
		for (int i = 0; i < n; i++) {
			int w = spare >= 0 ? text[i] + spare / n : getWidth() / n;
			edges[i + 1] = edges[i] + w;
		}
		if (spare >= 0) {
			edges[n] = getWidth();
		}
		return edges;
	}

	private int indexAt(double x) {
		int[] edges = edges();
		for (int i = 0; i < options.size(); i++) {
			if (x < getX() + edges[i + 1]) {
				return i;
			}
		}
		return options.size() - 1;
	}

	@Override
	//? if >=1.21.9 {
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
	//?} else {
	/*public void onClick(double x, double y) {
		var event = new MouseButtonEvent(x, y, 0);
	*///?}
		select(indexAt(event.x()));
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
		if (!java.util.Objects.equals(option, selected)) {
			selected = option;
			onChange.accept(option);
		}
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		slide = Draw.approach(slide, options.indexOf(selected), 0.3f);
		int x = getX();
		int y = getY();
		int[] edges = edges();
		int n = options.size();
		Draw.bordered(g, x, y, edges[n], getHeight(), 4, Theme.FIELD, isFocused() ? Theme.FAINT : Theme.BORDER);
		// The highlight slides between segments, easing both its position and its width.
		int from = Math.max(0, Math.min(n - 1, (int) Math.floor(slide)));
		int to = Math.min(n - 1, from + 1);
		float t = slide - from;
		float hx = edges[from] + (edges[to] - edges[from]) * t;
		float hw = (edges[from + 1] - edges[from]) + ((edges[to + 1] - edges[to]) - (edges[from + 1] - edges[from])) * t;
		Draw.roundRect(g, x + 2 + Math.round(hx), y + 2, Math.round(hw) - 4, getHeight() - 4, 3, active ? Theme.CARD_HOVER : Theme.CARD);
		for (int i = 0; i < n; i++) {
			int sx = x + edges[i];
			int seg = edges[i + 1] - edges[i];
			String full = labels.apply(options.get(i));
			String text = Draw.fit(full, seg - 6);
			if (!text.equals(full)) {
				Draw.truncated(g, sx, y, seg, getHeight(), full); // full label on hover
			}
			boolean isSelected = java.util.Objects.equals(options.get(i), selected);
			boolean hovered = isHovered() && mouseX >= sx && mouseX < sx + seg;
			int color = !active ? Theme.FAINT : isSelected ? Theme.ACCENT : hovered ? Theme.TEXT : Theme.MUTED;
			Draw.centered(g, text, sx + seg / 2, y + (getHeight() - 8) / 2 + 1, color);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal(labels.apply(selected)));
	}
}

package com.javahost.client.gui.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text box in the JavaHost style. The vanilla EditBox handles typing; it sits inset inside a frame
 * we draw ourselves, and clicks anywhere on the frame focus it.
 */
public class TextField extends EditBox {
	public static final int HEIGHT = 20;
	private static final int PAD_X = 6;
	private static final int PAD_Y = 6;
	private final int frameWidth;

	public TextField(int width, Component narration) {
		super(Draw.font(), 0, 0, width - PAD_X * 2, 8, narration);
		this.frameWidth = width;
		setBordered(false);
		setTextColor(Theme.TEXT);
		//? if >=1.21.6
		setTextShadow(false);
	}

	/** Positions the whole frame (not just the inner text area). */
	public void place(int x, int y) {
		setPosition(x + PAD_X, y + PAD_Y);
	}

	public int frameX() {
		return getX() - PAD_X;
	}

	public int frameY() {
		return getY() - PAD_Y;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return visible && active && mouseX >= frameX() && mouseX < frameX() + frameWidth && mouseY >= frameY() && mouseY < frameY() + HEIGHT;
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (!isVisible()) {
			return;
		}
		int border = isFocused() ? Theme.ACCENT : isMouseOver(mouseX, mouseY) ? Theme.FAINT : Theme.BORDER;
		Draw.bordered(g, frameX(), frameY(), frameWidth, HEIGHT, 4, Theme.FIELD, border);
		super.extractWidgetRenderState(g, mouseX, mouseY, a);
	}
}

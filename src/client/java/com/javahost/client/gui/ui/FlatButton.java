package com.javahost.client.gui.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A flat rounded button with an optional icon and a smooth hover. */
public class FlatButton extends AbstractButton {
	public enum Style { PRIMARY, SECONDARY, DANGER, GHOST }

	private final Consumer<FlatButton> onPress;
	private Style style;
	private Icons icon;
	private float hover;

	public FlatButton(int x, int y, int width, int height, Component label, Style style, Icons icon, Consumer<FlatButton> onPress) {
		super(x, y, width, height, label);
		this.style = style;
		this.icon = icon;
		this.onPress = onPress;
	}

	public FlatButton(int width, int height, Component label, Style style, Icons icon, Consumer<FlatButton> onPress) {
		this(0, 0, width, height, label, style, icon, onPress);
	}

	public FlatButton setStyle(Style style) {
		this.style = style;
		return this;
	}

	public FlatButton setIcon(Icons icon) {
		this.icon = icon;
		return this;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.accept(this);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		hover = Draw.approach(hover, active && isHoveredOrFocused() ? 1 : 0, 0.35f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();

		int fill;
		int text;
		switch (style) {
			case PRIMARY -> {
				fill = Theme.mix(Theme.ACCENT, Theme.ACCENT_HOVER, hover);
				text = Theme.ON_ACCENT;
			}
			case DANGER -> {
				fill = Theme.mix(Theme.withAlpha(Theme.DANGER, 0.18f), Theme.DANGER, hover);
				text = Theme.mix(Theme.DANGER, 0xFFFFFFFF, hover);
			}
			case GHOST -> {
				fill = Theme.withAlpha(Theme.CARD_HOVER, hover);
				text = Theme.mix(Theme.MUTED, Theme.TEXT, hover);
			}
			default -> {
				fill = Theme.mix(Theme.CARD, Theme.CARD_HOVER, hover);
				text = Theme.TEXT;
			}
		}
		if (!active) {
			fill = Theme.withAlpha(Theme.CARD, 0.6f);
			text = Theme.FAINT;
		}
		if (style == Style.SECONDARY && active) {
			Draw.bordered(g, x, y, w, h, 4, fill, Theme.mix(Theme.BORDER, Theme.FAINT, hover));
		} else {
			Draw.roundRect(g, x, y, w, h, 4, fill);
		}

		String label = getMessage().getString();
		int iconSpace = icon == null ? 0 : (label.isEmpty() ? Icons.SIZE : Icons.SIZE + 5);
		String shown = label.isEmpty() ? "" : Draw.fit(label, w - 10 - iconSpace);
		int contentWidth = iconSpace + Draw.font().width(shown);
		int cx = x + (w - contentWidth) / 2;
		int cy = y + (h - 8) / 2;
		if (icon != null) {
			icon.draw(g, cx, cy, text);
		}
		Draw.text(g, shown, cx + iconSpace, cy + 1, text);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}

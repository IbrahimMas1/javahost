package com.javahost.client.gui.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A flat rounded button with an optional icon and a smooth hover. A real {@link Button}, so tools and tests can press it. */
public class FlatButton extends Button {
	public enum Style { PRIMARY, SECONDARY, DANGER, GHOST }

	private Style style;
	private Icons icon;
	private float hover;
	private boolean iconOnly;

	public FlatButton(int x, int y, int width, int height, Component label, Style style, Icons icon, Consumer<FlatButton> onPress) {
		super(x, y, width, height, label, button -> onPress.accept((FlatButton) button), DEFAULT_NARRATION);
		this.style = style;
		this.icon = icon;
	}

	public FlatButton(int width, int height, Component label, Style style, Icons icon, Consumer<FlatButton> onPress) {
		this(0, 0, width, height, label, style, icon, onPress);
	}

	public FlatButton setStyle(Style style) {
		this.style = style;
		return this;
	}

	/** Shows just the icon; the label is still the button's name for narration (and tests). */
	public FlatButton iconOnly() {
		iconOnly = true;
		return this;
	}

	public FlatButton setIcon(Icons icon) {
		this.icon = icon;
		return this;
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

		String label = iconOnly ? "" : getMessage().getString();
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
}

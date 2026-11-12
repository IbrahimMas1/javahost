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
	private long pressedAt;

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

	@Override
	//? if >=1.21.9 {
	public void onPress(net.minecraft.client.input.InputWithModifiers input) {
	//?} else
	/*public void onPress() {*/
		pressedAt = System.currentTimeMillis();
		//? if >=1.21.9 {
		super.onPress(input);
		//?} else
		/*super.onPress();*/
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
	//? if >=26.1 {
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	//?} else if >=1.21.11 {
	/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?} else {
	/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?}
		hover = Draw.approach(hover, active && isHoveredOrFocused() ? 1 : 0, 0.35f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();

		int fill;
		int text;
		int border = 0;
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
				fill = Theme.withAlpha(0x1CFFFFFF, hover);
				text = Theme.mix(Theme.MUTED, Theme.TEXT, hover);
			}
			default -> {
				// Dark glass with a light rim, both brighter under the mouse.
				fill = Theme.mix(0xE6151B23, 0xE6252E3A, hover);
				border = Theme.mix(0x30FFFFFF, 0x66FFFFFF, hover);
				text = Theme.TEXT;
			}
		}
		boolean pressed = System.currentTimeMillis() - pressedAt < 140;
		if (!active) {
			fill = style == Style.SECONDARY ? 0xE6151B23 : 0x0CFFFFFF;
			border = style == Style.SECONDARY ? 0x14FFFFFF : 0;
			text = Theme.FAINT;
		} else if (pressed) {
			// A brief press-down shade, so clicks feel physical.
			fill = style == Style.GHOST ? 0x0AFFFFFF : Theme.mix(fill, 0xFF000000, 0.25f);
		}
		if (active && isFocused() && net.minecraft.client.Minecraft.getInstance().getLastInputType().isKeyboard()) {
			// Moving through buttons with Tab: a ring shows which one Enter presses.
			Draw.roundRect(g, x - 1, y - 1, w + 2, h + 2, 5, Theme.withAlpha(Theme.ACCENT, 0.8f));
		}
		if (style == Style.PRIMARY && active && h >= 16) {
			// Lit from within: the accent colour spills onto whatever is under the button, more so under the mouse.
			Draw.glow(g, x + w / 2, y + h - 1, w / 2 + 8, h / 2 + 7, Theme.withAlpha(Theme.ACCENT, (h >= 22 ? 0.30f : 0.16f) + 0.22f * hover));
		}
		if (border != 0) {
			Draw.roundRect(g, x, y, w, h, 4, border);
			Draw.roundRect(g, x + 1, y + 1, w - 2, h - 2, 3, fill);
		} else {
			Draw.roundRect(g, x, y, w, h, 4, fill);
		}
		if (style == Style.PRIMARY && active && h >= 16) {
			// A thin highlight along the top edge, like light catching it.
			g.fill(x + 3, y + 1, x + w - 3, y + 2, pressed ? 0x00FFFFFF : 0x2EFFFFFF);
		}

		String label = iconOnly ? "" : getMessage().getString();
		int iconSpace = icon == null ? 0 : (label.isEmpty() ? Icons.SIZE : Icons.SIZE + 5);
		String shown = label.isEmpty() ? "" : Draw.fit(label, w - 10 - iconSpace);
		if (!shown.equals(label)) {
			Draw.truncated(g, x, y, w, h, label); // full label on hover
		}
		int contentWidth = iconSpace + Draw.font().width(shown);
		int cx = x + (w - contentWidth) / 2;
		int cy = y + (h - 8) / 2;
		if (icon != null) {
			icon.draw(g, cx, cy, text);
		}
		Draw.text(g, shown, cx + iconSpace, cy + 1, text);
	}
}

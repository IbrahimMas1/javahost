package com.javahost.client.gui.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A big clickable card: icon tile, title and a line of description. */
public class CardButton extends AbstractButton {
	private final Icons icon;
	private final String subtitle;
	private final Consumer<CardButton> onPress;
	private float hover;

	public CardButton(int width, int height, Icons icon, Component title, String subtitle, Consumer<CardButton> onPress) {
		super(0, 0, width, height, title);
		this.icon = icon;
		this.subtitle = subtitle;
		this.onPress = onPress;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.accept(this);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		hover = Draw.approach(hover, active && isHoveredOrFocused() ? 1 : 0, 0.3f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		Draw.bordered(g, x, y, w, h, 6, Theme.mix(Theme.CARD, Theme.CARD_HOVER, hover),
				active ? Theme.mix(Theme.BORDER, Theme.ACCENT, hover) : Theme.BORDER);

		int tile = 22;
		int tx = x + 10;
		int ty = y + (h - tile) / 2;
		Draw.roundRect(g, tx, ty, tile, tile, 5, active ? Theme.mix(Theme.ACCENT_DARK, Theme.ACCENT, hover) : Theme.FIELD);
		icon.draw(g, tx + (tile - Icons.SIZE) / 2, ty + (tile - Icons.SIZE) / 2,
				active ? Theme.mix(Theme.ACCENT, Theme.ON_ACCENT, hover) : Theme.FAINT);

		int textX = tx + tile + 9;
		int textW = x + w - textX - 8;
		var lines = Draw.font().split(Component.literal(subtitle), textW);
		int shown = Math.min(2, lines.size());
		int blockH = 10 + shown * 10;
		int ty0 = y + (h - blockH) / 2;
		Draw.textFit(g, getMessage().getString(), textW, textX, ty0, active ? Theme.TEXT : Theme.FAINT);
		for (int i = 0; i < shown; i++) {
			g.text(Draw.font(), lines.get(i), textX, ty0 + 11 + i * 10, active ? Theme.MUTED : Theme.FAINT, false);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}

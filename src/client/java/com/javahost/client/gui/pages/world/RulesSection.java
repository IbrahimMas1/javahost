package com.javahost.client.gui.pages.world;

import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.WorldOptions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The world's game rules: for now one card with the lock state and the way to the World rules page. */
public final class RulesSection extends WorldSection {
	private static final int CARD = 44;
	private static final int BUTTON_W = 96;
	private int cardY;

	public RulesSection(Host host) {
		super(host);
	}

	private boolean locked() {
		RulesClient.State old = RulesClient.state();
		return WorldOptions.available() ? WorldOptions.locked() : old != null && old.locked();
	}

	@Override
	protected int layout(int y) {
		cardY = y;
		FlatButton open = add(new FlatButton(BUTTON_W, 18, Component.literal("Open rules"), FlatButton.Style.SECONDARY, Icons.SHIELD,
				b -> screen.switchTo(JavaHostScreen.Tab.RULES)), width - BUTTON_W - PAD, y + LABEL + (CARD - 18) / 2);
		open.setTooltip(tip("Keep inventory, mob griefing, the lock and recent changes"));
		return y + LABEL + CARD;
	}

	@Override
	public String signature() {
		return WorldOptions.version() + "/" + RulesClient.version();
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int y = top + cardY;
		label(g, "Game rules", left, y);
		card(g, left, y + LABEL, width, CARD);
		boolean locked = locked();
		int c = y + LABEL;
		(locked ? Icons.LOCK : Icons.UNLOCK).draw(g, left + PAD, c + 13, locked ? Theme.WARNING : Theme.MUTED);
		int textW = width - 2 * PAD - 16 - BUTTON_W - 8;
		Draw.textFit(g, locked ? "Locked for a challenge run" : "Unlocked", textW, left + PAD + 16, c + 12, locked ? Theme.WARNING : Theme.TEXT);
		WorldOptions.Role role = WorldOptions.role();
		RulesClient.State old = RulesClient.state();
		boolean canEdit = WorldOptions.available() ? role != WorldOptions.Role.NONE : old != null && old.canEdit();
		String who = locked ? "Nobody can change them"
				: role == WorldOptions.Role.HOST ? "Operators can change them"
				: canEdit ? "You can change them" : "Set by the host";
		Draw.textFit(g, who, textW, left + PAD + 16, c + 24, Theme.FAINT);
	}
}

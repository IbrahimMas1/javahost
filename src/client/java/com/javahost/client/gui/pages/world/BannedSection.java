package com.javahost.client.gui.pages.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.javahost.client.Config;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.HostController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Players the host has banned from their worlds, each with Unban (nothing at all when there are none). */
public final class BannedSection extends WorldSection {
	private static final int ROW = 22;
	private List<Map.Entry<String, String>> banned = List.of();
	private int sectionY = -1;

	public BannedSection(Host host) {
		super(host);
	}

	@Override
	protected int layout(int y) {
		banned = new ArrayList<>(Config.get().bannedPlayers.entrySet());
		sectionY = -1;
		if (banned.isEmpty()) {
			return y;
		}
		sectionY = y + GAP;
		int rowY = sectionY + LABEL;
		for (var entry : banned) {
			add(new FlatButton(56, 16, Component.literal("Unban"), FlatButton.Style.SECONDARY, null, b -> {
				HostController.unban(UUID.fromString(entry.getKey()));
				screen.refresh();
			}), width - 56 - PAD, rowY + 3);
			rowY += ROW;
		}
		return rowY;
	}

	@Override
	public String signature() {
		return String.valueOf(Config.get().bannedPlayers.size());
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (sectionY < 0) {
			return;
		}
		label(g, "Banned", left, top + sectionY);
		int y = top + sectionY + LABEL;
		card(g, left, y, width, banned.size() * ROW);
		for (var entry : banned) {
			Faces.draw(g, UUID.fromString(entry.getKey()), left + PAD, y + 4, 14);
			Draw.textFit(g, entry.getValue(), width - 110, left + PAD + 20, y + 7, Theme.MUTED);
			y += ROW;
		}
	}
}

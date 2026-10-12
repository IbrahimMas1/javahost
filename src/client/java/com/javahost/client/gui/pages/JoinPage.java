package com.javahost.client.gui.pages;

import java.time.Duration;
import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.Config.RecentJoin;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.JoinHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public class JoinPage extends Page {
	private static final int ROW = 24;
	private TextField codeField;
	private String error;
	private List<RecentJoin> recent = List.of();

	@Override
	public String title() {
		return "Join a friend";
	}

	@Override
	public String subtitle() {
		return "Paste the invite your friend copied from their Host tab.";
	}

	@Override
	protected void build() {
		codeField = add(new TextField(width - 86, Component.literal("Invite code or address")), 0, 12);
		codeField.setMaxLength(160);
		codeField.setHint(Component.literal("JH-XXXXX-XXXXX or an address").withStyle(s -> s.withColor(Theme.FAINT)));
		codeField.setResponder(value -> error = null);
		add(new FlatButton(78, 20, Component.literal("Join"), FlatButton.Style.PRIMARY, Icons.JOIN, b -> join(codeField.getValue())),
				width - 78, 12);
		screen.focusOn(codeField);

		recent = Config.get().recentJoins;
		int y = 76;
		for (RecentJoin entry : recent) {
			add(new FlatButton(56, 16, Component.literal("Join"), FlatButton.Style.SECONDARY, null, b -> join(entry.invite())),
					width - 64, y + 4);
			y += ROW;
		}
		reserve(y + 10);
	}

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (isEnter(event) && codeField.isFocused()) {
			join(codeField.getValue());
			return true;
		}
		return false;
	}

	private void join(String input) {
		String problem = JoinHelper.join(screen, input);
		if (problem != null) {
			error = problem;
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "Invite", left, top);
		if (error != null) {
			Icons.WARNING.draw(g, left, top + 38, Theme.WARNING);
			Draw.text(g, Draw.fit(error, width - 14), left + 13, top + 39, Theme.WARNING);
		} else {
			Draw.text(g, "Codes, relay addresses (…e4mc.link) and normal server addresses all work.", left, top + 39, Theme.FAINT);
		}

		sectionLabel(g, "Recent", left, top + 62);
		int y = top + 76;
		if (recent.isEmpty()) {
			card(g, left, y - 2, width, ROW + 4);
			Draw.text(g, "Worlds you join show up here.", left + 10, y + 7, Theme.MUTED);
			return;
		}
		card(g, left, y - 2, width, recent.size() * ROW + 4);
		for (RecentJoin entry : recent) {
			Icons.GLOBE.draw(g, left + 9, y + 7, Theme.MUTED);
			Draw.text(g, Draw.fit(entry.label(), width - 150), left + 24, y + 4, Theme.TEXT);
			Draw.text(g, Draw.fit(entry.invite(), width - 150), left + 24, y + 14, Theme.FAINT);
			Draw.text(g, ago(entry.time()), left + width - 64 - 50, y + 8, Theme.FAINT);
			y += ROW;
		}
	}

	private static String ago(long time) {
		Duration d = Duration.ofMillis(System.currentTimeMillis() - time);
		if (d.toMinutes() < 1) {
			return "just now";
		}
		if (d.toHours() < 1) {
			return d.toMinutes() + "m ago";
		}
		if (d.toDays() < 1) {
			return d.toHours() + "h ago";
		}
		return d.toDays() + "d ago";
	}
}

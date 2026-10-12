package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Friends.Invite;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Notifications;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A slim panel on the right of the title screen: who's online, a Join button for anyone hosting, and any invite
 * waiting for you. The panel itself is inactive (clicks pass through it); its buttons sit on top.
 */
public final class TitleFriendsPanel extends Button {
	private static final int W = 128;
	private static final int JOIN_W = 30;
	private static final int HEADER = 20;
	private static final int ROW = 24;
	private static final int INVITE = 36;
	private static final int MAX_ROWS = 5;

	private record Entry(Friend friend, Presence.Status status) {
	}

	private final int screenHeight;
	private final List<FlatButton> joins = new ArrayList<>();
	private FlatButton inviteJoin;
	private List<Entry> entries = List.of();
	private Notifications.Item invite;
	private int online;
	private float fade;

	private TitleFriendsPanel(int x, int y, int screenHeight) {
		super(x, y, W, HEADER, Component.literal("Friends"), button -> {
		}, DEFAULT_NARRATION);
		this.screenHeight = screenHeight;
		this.active = false;
	}

	/** Adds the panel beside the dock when there's room; it only shows when someone is online or an invite waits. */
	public static void attach(Minecraft minecraft, Screen screen, int width, int height) {
		int x = MenuDock.left(width) - W - 8;
		if (x < width / 2 + 104) {
			return; // too narrow: the dock's Friends button shows who's online instead
		}
		TitleFriendsPanel panel = new TitleFriendsPanel(x, height / 4 + 48, height);
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		widgets.add(panel);
		panel.inviteJoin = new FlatButton(JOIN_W, 14, Component.literal("Join"), FlatButton.Style.PRIMARY, null, b -> {
			if (panel.invite != null && panel.invite.payload() instanceof Invite pending) {
				Notifications.remove(panel.invite);
				InviteFlow.join(pending);
			}
		});
		widgets.add(panel.inviteJoin);
		for (int i = 0; i < MAX_ROWS; i++) {
			int row = i;
			FlatButton join = new FlatButton(JOIN_W, 14, Component.literal("Join"), FlatButton.Style.PRIMARY, null, b -> {
				if (row < panel.entries.size()) {
					Entry entry = panel.entries.get(row);
					InviteFlow.join(entry.status().asInvite(entry.friend().name()));
				}
			});
			panel.joins.add(join);
			widgets.add(join);
		}
		panel.update();
		ScreenEvents.afterTick(screen).register(s -> panel.update());
	}

	private int maxRows() {
		int room = screenHeight - 24 - getY() - HEADER - (invite != null ? INVITE : 0);
		return Math.max(1, Math.min(MAX_ROWS, room / ROW));
	}

	/** Re-reads presence and invites and moves the buttons to match. Cheap; runs every tick. */
	private void update() {
		invite = Notifications.items().stream()
				.filter(item -> item.payload() instanceof Invite pending && !pending.expired())
				.findFirst().orElse(null);

		List<Entry> list = new ArrayList<>();
		for (Friend friend : Friends.friends()) {
			Optional<Presence.Status> status = Presence.of(friend.id());
			status.ifPresent(s -> list.add(new Entry(friend, s)));
		}
		online = list.size();
		visible = invite != null || !list.isEmpty();
		list.sort(Comparator.comparing((Entry e) -> !e.status().hosting()).thenComparing(e -> e.friend().name().toLowerCase()));
		entries = list.subList(0, Math.min(list.size(), maxRows()));

		int x = getX();
		int y = getY() + HEADER;
		inviteJoin.visible = visible && invite != null;
		if (invite != null) {
			inviteJoin.setPosition(x + W - JOIN_W - 8, y + 9);
			y += INVITE;
		}
		for (int i = 0; i < joins.size(); i++) {
			FlatButton join = joins.get(i);
			join.visible = visible && i < entries.size() && InviteFlow.joinable(entries.get(i).friend().id());
			join.setPosition(x + W - JOIN_W - 6, y + i * ROW + 5);
		}
		int body = entries.isEmpty() ? (invite != null ? 0 : ROW) : entries.size() * ROW;
		setHeight(HEADER + (invite != null ? INVITE : 0) + body + 4);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		fade = Draw.approach(fade, 1, 0.12f);
		int x = getX();
		int y = getY();
		int h = getHeight();
		Draw.shadow(g, x, y, W, h, 8);
		Draw.roundRect(g, x, y, W, h, 8, Theme.withAlpha(Theme.WINDOW, 0.9f * fade));

		Icons.USERS.draw(g, x + 8, y + 6, Theme.ACCENT);
		Draw.text(g, "Friends", x + 22, y + 7, Theme.TEXT);
		if (online > 0) {
			String count = String.valueOf(online);
			int countX = x + W - 8 - Draw.font().width(count);
			Draw.roundRect(g, countX - 7, y + 8, 4, 4, 2, Theme.ACCENT);
			Draw.text(g, count, countX, y + 7, Theme.MUTED);
		}
		y += HEADER;

		if (invite != null && invite.payload() instanceof Invite pending) {
			Draw.roundRect(g, x + 4, y, W - 8, INVITE - 4, 6, Theme.withAlpha(Theme.ACCENT, 0.16f));
			Faces.draw(g, pending.from(), x + 10, y + 8, 16);
			Draw.text(g, Draw.fit(pending.name(), W - 70), x + 31, y + 6, Theme.TEXT);
			Draw.text(g, Draw.fit("invited you", W - 70), x + 31, y + 17, Theme.ACCENT);
			y += INVITE;
		}

		if (entries.isEmpty()) {
			if (invite != null) {
				return;
			}
			String hint = Friends.friends().isEmpty() ? "No friends yet" : "No one online";
			Draw.text(g, Draw.fit(hint, W - 16), x + 8, y + 8, Theme.FAINT);
			return;
		}
		for (Entry entry : entries) {
			Presence.Status status = entry.status();
			Faces.draw(g, entry.friend().id(), x + 8, y + 4, 16);
			Draw.roundRect(g, x + 20, y + 16, 6, 6, 3, Theme.WINDOW);
			Draw.roundRect(g, x + 21, y + 17, 4, 4, 2, status.hosting() ? Theme.ACCENT : Theme.INFO);
			int textW = W - 36 - (status.hosting() ? JOIN_W + 6 : 0);
			Draw.text(g, Draw.fit(entry.friend().name(), textW), x + 30, y + 3, Theme.TEXT);
			String line = switch (status.state()) {
				case HOSTING -> status.hosting() ? status.world() + " " + status.players() + "/" + status.max() : "Playing";
				case PLAYING -> "Playing";
				default -> "Online";
			};
			Draw.text(g, Draw.fit(line, textW), x + 30, y + 13, status.hosting() ? Theme.ACCENT : Theme.FAINT);
			y += ROW;
		}
	}
}

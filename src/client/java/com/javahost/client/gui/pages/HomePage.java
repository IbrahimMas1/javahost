package com.javahost.client.gui.pages;

import java.util.ArrayList;
import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Identity;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.IdleSkin;
import com.javahost.client.gui.ui.RowButton;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.HostController;
import com.javahost.client.perf.BoostMods;
import com.javahost.client.voice.VoiceClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * The hub's front page: you (the 3D model, alive) and the world you're in on the left; the one thing to do next and
 * the friends you could play with right now on the right; voice and performance at a glance along the bottom.
 */
public class HomePage extends Page {
	private static final int CHIP_H = 24;
	private static final int PLATE_H = 30;
	private static final int FRIEND_ROW = 24;

	private int heroW;
	private int heroH;
	private int skinH;
	private boolean emptyButton;
	private int rightX;
	private int rightW;
	private int friendsY;
	private int chipsY;
	private List<Friend> shown = List.of();
	private int moreFriends;
	private String lastState = "";

	@Override
	public String title() {
		int hour = java.time.LocalTime.now().getHour();
		String greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
		return greeting + ", " + Identity.name();
	}

	@Override
	public String subtitle() {
		if (HostController.isHosting()) {
			int guests = HostController.guests().size();
			return guests == 0 ? "You're hosting. Invite a friend to get started." : "You're hosting " + Draw.count(guests, "friend") + ".";
		}
		String route = com.javahost.client.host.DirectLink.guestRoute();
		if (route != null) {
			// In a friend's world over a JavaHost link: say how, and how fast.
			int ping = com.javahost.client.host.DirectLink.guestPing();
			return (route.equals("direct") ? "Connected straight to the host" : "Connected through the " + route) + (ping > 0 ? " · " + ping + " ms" : "") + ".";
		}
		int online = Presence.onlineFriends().size();
		return online > 0 ? Draw.count(online, "friend") + " online right now." : "Host, talk, and play together.";
	}

	private boolean canHost() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && com.javahost.compat.Compat.isOwner(server, minecraft.player);
	}

	private String state() {
		StringBuilder s = new StringBuilder().append(com.javahost.client.host.QuickHost.version()).append(HostController.isHosting()).append(HostController.guests().size())
				.append(Friends.incoming().size()).append(canHost()).append(InviteFlow.canInvite()).append(Friends.friends().size());
		for (Friend f : Presence.onlineFriends()) {
			s.append(f.id()).append(InviteFlow.status(f.id())).append(InviteFlow.joinable(f.id())).append(InviteFlow.playingTogether(f.id()))
					.append(InviteFlow.busy(f.id()));
		}
		return s.toString();
	}

	@Override
	public void tick() {
		if (!state().equals(lastState)) {
			screen.refresh();
		}
	}

	@Override
	protected void build() {
		lastState = state();
		int height = Math.max(150, screen.contentHeight() - 4);
		chipsY = height - CHIP_H;
		heroH = chipsY - 8;
		heroW = Math.max(104, Math.min(176, Math.round(width * 0.38f)));
		rightX = heroW + 10;
		rightW = width - rightX;

		// You, alive, on a lit stage (see draw); the world you're in is written underneath.
		skinH = heroH - PLATE_H - 6;
		int skinW = Math.min(heroW - 8, Math.round(skinH * 0.62f));
		// Click yourself to change your skin (dragging still turns the model).
		add(IdleSkin.clickable(skinW, skinH, Identity.id(), true, "Change skin", () -> screen.switchTo(JavaHostScreen.Tab.SKINS)), (heroW - skinW) / 2, 2);

		// The one thing to do next, then a quieter second choice.
		boolean hosting = HostController.isHosting();
		int y = 0;
		if (hosting) {
			add(new FlatButton(rightW, 26, Component.literal("Invite friends"), FlatButton.Style.PRIMARY, Icons.USERS,
					b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), rightX, y);
			y += 30;
			add(new FlatButton(rightW, 18, Component.literal("Manage your world"), FlatButton.Style.SECONDARY, Icons.HOST,
					b -> screen.switchTo(JavaHostScreen.Tab.HOST)), rightX, y);
		} else if (canHost()) {
			add(new FlatButton(rightW, 26, Component.literal("Start hosting"), FlatButton.Style.PRIMARY, Icons.PLAY, b -> {
				HostPage.startWhenOpened();
				screen.switchTo(JavaHostScreen.Tab.HOST);
			}), rightX, y);
			y += 30;
			add(new FlatButton(rightW, 18, Component.literal("Hosting options"), FlatButton.Style.SECONDARY, Icons.GEAR,
					b -> screen.switchTo(JavaHostScreen.Tab.HOST)), rightX, y);
		} else if (minecraft.level == null) {
			// The title screen: host the world you played last in one click, or pick another.
			List<com.javahost.client.host.QuickHost.SavedWorld> worlds = com.javahost.client.host.QuickHost.worlds();
			if (worlds.isEmpty()) {
				add(new FlatButton(rightW, 26, Component.literal("Choose a world"), FlatButton.Style.PRIMARY, Icons.PLAY,
						b -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(screen))), rightX, y);
			} else {
				com.javahost.client.host.QuickHost.SavedWorld last = worlds.get(0);
				FlatButton host = add(new FlatButton(rightW, 26, Component.literal("Host " + last.name()), FlatButton.Style.PRIMARY, Icons.HOST,
						b -> com.javahost.client.host.QuickHost.host(last)), rightX, y);
				host.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Draw.styled("Open " + last.name() + " and start hosting")));
			}
			y += 30;
			add(new FlatButton(rightW, 18, Component.literal(worlds.size() > 1 ? "Your worlds (" + worlds.size() + ")" : "Discover public worlds"),
					FlatButton.Style.SECONDARY, worlds.size() > 1 ? Icons.FOLDER : Icons.GLOBE,
					b -> screen.switchTo(worlds.size() > 1 ? JavaHostScreen.Tab.HOST : JavaHostScreen.Tab.DISCOVER)), rightX, y);
		} else {
			// In someone else's world.
			add(new FlatButton(rightW, 26, Component.literal("Friends"), FlatButton.Style.PRIMARY, Icons.USERS,
					b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), rightX, y);
			y += 30;
			add(new FlatButton(rightW, 18, Component.literal("Voice chat"), FlatButton.Style.SECONDARY, Icons.MIC,
					b -> screen.switchTo(JavaHostScreen.Tab.VOICE)), rightX, y);
		}
		y += 18 + 8;

		// Friends you could play with right now, in a card of their own: hosting first, then in a world, then in the menus.
		friendsY = y;
		y += 19;
		List<Friend> online = new ArrayList<>(Presence.onlineFriends());
		online.sort(java.util.Comparator.comparing((Friend f) -> !com.javahost.client.friends.FriendLog.isFavourite(f.id()))
				.thenComparingInt(f -> InviteFlow.joinable(f.id()) ? 0 : InviteFlow.playingTogether(f.id()) ? 1 : 2)
				.thenComparing(f -> f.name().toLowerCase(java.util.Locale.ROOT)));
		int room = Math.max(1, (heroH - y - 3) / FRIEND_ROW);
		boolean overflow = online.size() > room;
		int rows = overflow ? Math.max(1, room - 1) : online.size();
		shown = List.copyOf(online.subList(0, Math.min(rows, online.size())));
		moreFriends = online.size() - shown.size();
		for (Friend friend : shown) {
			add(new RowButton(rightW - 8 - 60, FRIEND_ROW - 2, Component.literal(friend.name()), b -> screen.openProfile(friend.id())), rightX + 4, y);
			add(FriendsPage.action(screen, friend, 56), rightX + rightW - 62, y + 2);
			y += FRIEND_ROW;
		}
		emptyButton = online.isEmpty() && heroH - friendsY >= 76;
		if (moreFriends > 0 || emptyButton) {
			boolean none = Friends.friends().isEmpty();
			String label = moreFriends > 0 ? "See all " + online.size() + " online" : none ? "Add a friend" : "See your friends";
			add(new FlatButton(rightW - 16, 18, Component.literal(label), moreFriends > 0 ? FlatButton.Style.GHOST : FlatButton.Style.SECONDARY,
					moreFriends > 0 ? null : Icons.USERS, b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), rightX + 8,
					moreFriends > 0 ? y + 2 : heroH - 26);
		}

		// At a glance, along the bottom: each chip opens its page.
		int chipW = (width - 12) / 3;
		add(new RowButton(chipW, CHIP_H, Component.literal("Voice"), b -> screen.switchTo(JavaHostScreen.Tab.VOICE)), 0, chipsY);
		add(new RowButton(chipW, CHIP_H, Component.literal("Performance mods"), b -> screen.switchTo(JavaHostScreen.Tab.PERFORMANCE)), chipW + 6, chipsY);
		add(new RowButton(width - (chipW + 6) * 2, CHIP_H, Component.literal("Frame rate"), b -> screen.switchTo(JavaHostScreen.Tab.PERFORMANCE)),
				(chipW + 6) * 2, chipsY);
		reserve(height);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		// The stage: no box, just light. A glow of the accent colour behind you, a lit floor and your shadow on it.
		int cx = left + heroW / 2;
		Draw.stage(g, cx, top + 2, skinH, Math.round(heroW * 0.80f));

		// Where you are: the world's name, and what you're doing there in a small pill.
		boolean hosting = HostController.isHosting();
		IntegratedServer server = minecraft.getSingleplayerServer();
		String place = server != null ? server.getWorldData().getLevelName() : minecraft.level != null ? "A friend's world" : "Main menu";
		String doing = hosting ? (HostController.guests().isEmpty() ? "Hosting" : "Hosting · " + (HostController.guests().size() + 1) + " playing")
				: server != null ? "Singleplayer" : minecraft.level != null ? "Playing together" : "Not in a world";
		String placeShown = Draw.fit(place, heroW - 8);
		Draw.textFit(g, place, heroW - 8, cx - Draw.width(placeShown) / 2, top + heroH - PLATE_H + 4, Theme.TEXT);
		int doingW = Math.min(Draw.width(doing), heroW - 30);
		int pillW = doingW + 22;
		int pillX = cx - pillW / 2;
		int pillY = top + heroH - 14;
		int statusColor = hosting ? Theme.ACCENT : minecraft.level != null ? Theme.INFO : Theme.FAINT;
		Draw.roundRect(g, pillX, pillY, pillW, 13, 6, Theme.withAlpha(statusColor, 0.14f));
		statusFit(g, pillX + 6, pillY + 2, statusColor, doing, doingW, Theme.mix(statusColor, Theme.TEXT, 0.55f));

		// Friends online.
		int x = left + rightX;
		int online = shown.size() + moreFriends;
		Draw.glassCard(g, x, top + friendsY, rightW, heroH - friendsY, 6);
		sectionLabel(g, online > 0 ? "Online now" : "Friends", x + 8, top + friendsY + 7);
		if (online > 0) {
			String count = String.valueOf(online);
			int countW = Draw.width(count) + 9;
			Draw.roundRect(g, x + rightW - 8 - countW, top + friendsY + 5, countW, 11, 5, Theme.withAlpha(Theme.ACCENT, 0.18f));
			Draw.text(g, count, x + rightW - 8 - countW + 5, top + friendsY + 7, Theme.ACCENT);
		}
		int y = top + friendsY + 19;
		for (Friend friend : shown) {
			FriendsPage.faceWithDot(g, friend.id(), x + 8, y + 3, 16);
			Draw.textFit(g, friend.name(), rightW - 98, x + 30, y + 2, Theme.TEXT);
			Draw.textFit(g, FriendsPage.statusLine(friend), rightW - 98, x + 30, y + 12,
					InviteFlow.joinable(friend.id()) || InviteFlow.playingTogether(friend.id()) ? Theme.ACCENT : Theme.MUTED);
			y += FRIEND_ROW;
		}
		if (online == 0) {
			String line = !Identity.canSign() ? "Sign in with Microsoft to use friends."
					: Friends.friends().isEmpty() ? "Add a friend by their Minecraft name, then play together in one click."
					: "Nobody's online right now. They'll show up here when they are.";
			wrap(g, line, x + 8, y + 1, rightW - 16, !Identity.canSign() ? Theme.WARNING : Theme.MUTED);
		}

		// At a glance.
		Config config = Config.get();
		String voice = !config.voiceEnabled ? "Off" : config.muted ? "Muted"
				: config.pushToTalk ? "Hold " + JavaHostClient.PUSH_TO_TALK.getTranslatedKeyMessage().getString() : "Open mic";
		long installed = BoostMods.MODS.stream().filter(BoostMods.BoostMod::recommended).filter(BoostMods::isInstalled).count();
		long total = BoostMods.MODS.stream().filter(BoostMods.BoostMod::recommended).count();
		int chipW = (width - 12) / 3;
		int cy = top + chipsY;
		chip(g, left, cy, chipW, Icons.MIC, "Voice", voice, VoiceClient.isAvailable() && config.voiceEnabled ? Theme.ACCENT : Theme.MUTED);
		chip(g, left + chipW + 6, cy, chipW, Icons.BOLT, "Boost", installed + "/" + total, installed == total ? Theme.ACCENT : Theme.WARNING);
		chip(g, left + (chipW + 6) * 2, cy, width - (chipW + 6) * 2, Icons.GAUGE, "FPS", String.valueOf(minecraft.getFps()), Theme.INFO);
	}

	/** A one-line glass tile: its icon on a tinted square, what it is, and its value on the right. */
	private static void chip(GuiGraphicsExtractor g, int x, int y, int w, Icons icon, String label, String value, int accent) {
		Draw.glassCard(g, x, y, w, CHIP_H, 6);
		Draw.roundRect(g, x + 5, y + 5, 14, 14, 4, Theme.withAlpha(accent, 0.16f));
		icon.drawCentred(g, x + 12, y + 12, Icons.SIZE, accent);
		int valueW = Math.min(Draw.width(value), w - 34);
		if (w - 34 - valueW > Draw.width(label) + 4) {
			Draw.text(g, label, x + 24, y + 8, Theme.MUTED);
		}
		Draw.textFit(g, value, w - 34, x + w - 8 - valueW, y + 8, Theme.TEXT);
	}
}

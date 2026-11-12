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
	private static final int CHIP_H = 22;
	private static final int FRIEND_ROW = 24;

	private int heroW;
	private int heroH;
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
		int online = Presence.onlineFriends().size();
		return online > 0 ? Draw.count(online, "friend") + " online right now." : "Host, talk, and play together.";
	}

	private boolean canHost() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && com.javahost.compat.Compat.isOwner(server, minecraft.player);
	}

	private String state() {
		StringBuilder s = new StringBuilder().append(HostController.isHosting()).append(HostController.guests().size())
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
		heroW = Math.max(96, Math.min(150, Math.round(width * 0.36f)));
		rightX = heroW + 10;
		rightW = width - rightX;

		// You, alive. The world you're in is written underneath (see draw).
		int skinH = heroH - 40;
		int skinW = Math.min(heroW - 12, Math.round(skinH * 0.62f));
		add(IdleSkin.create(skinW, skinH, Identity.id(), true), (heroW - skinW) / 2, 6);

		// The one thing to do next, then a quieter second choice.
		boolean hosting = HostController.isHosting();
		int y = 0;
		if (hosting) {
			add(new FlatButton(rightW, 24, Component.literal("Invite friends"), FlatButton.Style.PRIMARY, Icons.USERS,
					b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), rightX, y);
			y += 28;
			add(new FlatButton(rightW, 18, Component.literal("Manage your world"), FlatButton.Style.SECONDARY, Icons.HOST,
					b -> screen.switchTo(JavaHostScreen.Tab.HOST)), rightX, y);
		} else if (canHost()) {
			add(new FlatButton(rightW, 24, Component.literal("Start hosting"), FlatButton.Style.PRIMARY, Icons.PLAY, b -> {
				HostPage.startWhenOpened();
				screen.switchTo(JavaHostScreen.Tab.HOST);
			}), rightX, y);
			y += 28;
			add(new FlatButton(rightW, 18, Component.literal("Hosting options"), FlatButton.Style.SECONDARY, Icons.GEAR,
					b -> screen.switchTo(JavaHostScreen.Tab.HOST)), rightX, y);
		} else if (minecraft.level == null) {
			add(new FlatButton(rightW, 24, Component.literal("Choose a world"), FlatButton.Style.PRIMARY, Icons.PLAY,
					b -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(screen))), rightX, y);
			y += 28;
			add(new FlatButton(rightW, 18, Component.literal("Discover public worlds"), FlatButton.Style.SECONDARY, Icons.GLOBE,
					b -> screen.switchTo(JavaHostScreen.Tab.DISCOVER)), rightX, y);
		} else {
			// In someone else's world.
			add(new FlatButton(rightW, 24, Component.literal("Friends"), FlatButton.Style.PRIMARY, Icons.USERS,
					b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), rightX, y);
			y += 28;
			add(new FlatButton(rightW, 18, Component.literal("Voice chat"), FlatButton.Style.SECONDARY, Icons.MIC,
					b -> screen.switchTo(JavaHostScreen.Tab.VOICE)), rightX, y);
		}
		y += 18 + 10;

		// Friends you could play with right now: hosting first, then in a world, then in the menus.
		friendsY = y;
		y += 13;
		List<Friend> online = new ArrayList<>(Presence.onlineFriends());
		online.sort(java.util.Comparator.comparingInt((Friend f) -> InviteFlow.joinable(f.id()) ? 0 : InviteFlow.playingTogether(f.id()) ? 1 : 2)
				.thenComparing(f -> f.name().toLowerCase(java.util.Locale.ROOT)));
		int room = Math.max(1, (heroH - y - (online.size() > 1 ? 0 : 0)) / FRIEND_ROW);
		boolean overflow = online.size() > room;
		int rows = overflow ? Math.max(1, room - 1) : online.size();
		shown = List.copyOf(online.subList(0, Math.min(rows, online.size())));
		moreFriends = online.size() - shown.size();
		for (Friend friend : shown) {
			add(new RowButton(rightW - 62, FRIEND_ROW - 2, Component.literal(friend.name()), b -> screen.openProfile(friend.id())), rightX, y);
			add(FriendsPage.action(screen, friend, 56), rightX + rightW - 58, y + 2);
			y += FRIEND_ROW;
		}
		if (moreFriends > 0 || online.isEmpty()) {
			boolean none = Friends.friends().isEmpty();
			String label = moreFriends > 0 ? "See all " + online.size() + " online" : none ? "Add a friend" : "See your friends";
			add(new FlatButton(rightW, 18, Component.literal(label), FlatButton.Style.GHOST, none ? Icons.USERS : null,
					b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), rightX, online.isEmpty() ? y + 24 : y + 2);
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
		// The stage: a card with a soft glow of the accent colour rising from the floor, and a shadow under the feet.
		card(g, left, top, heroW, heroH);
		g.fillGradient(left + 1, top + heroH - 78, left + heroW - 1, top + heroH - 34, Theme.withAlpha(Theme.ACCENT, 0), Theme.withAlpha(Theme.ACCENT, 0.16f));
		g.fill(left + 1, top + heroH - 34, left + heroW - 1, top + heroH - 33, Theme.withAlpha(Theme.ACCENT, 0.35f));
		Draw.roundRect(g, left + heroW / 2 - 16, top + heroH - 38, 32, 5, 2, 0x50000000);
		Draw.roundRect(g, left + 1, top + heroH - 33, heroW - 2, 32, 5, Theme.CARD);

		// Where you are.
		boolean hosting = HostController.isHosting();
		IntegratedServer server = minecraft.getSingleplayerServer();
		String place = server != null ? server.getWorldData().getLevelName() : minecraft.level != null ? "A friend's world" : "Main menu";
		String doing = hosting ? (HostController.guests().isEmpty() ? "Hosting" : "Hosting · " + (HostController.guests().size() + 1) + " playing")
				: server != null ? "Singleplayer" : minecraft.level != null ? "Playing together" : "Not in a world";
		Draw.textFit(g, place, heroW - 16, left + 8, top + heroH - 27, Theme.TEXT);
		statusFit(g, left + 8, top + heroH - 15, hosting ? Theme.ACCENT : minecraft.level != null ? Theme.INFO : Theme.FAINT, doing, heroW - 26, Theme.MUTED);

		// Friends online.
		int x = left + rightX;
		int online = shown.size() + moreFriends;
		sectionLabel(g, online > 0 ? "Online now (" + online + ")" : "Friends", x, top + friendsY);
		int y = top + friendsY + 13;
		for (Friend friend : shown) {
			FriendsPage.faceWithDot(g, friend.id(), x + 3, y + 3, 16);
			Draw.textFit(g, friend.name(), rightW - 88, x + 25, y + 2, Theme.TEXT);
			Draw.textFit(g, FriendsPage.statusLine(friend), rightW - 88, x + 25, y + 12,
					InviteFlow.joinable(friend.id()) || InviteFlow.playingTogether(friend.id()) ? Theme.ACCENT : Theme.MUTED);
			y += FRIEND_ROW;
		}
		if (online == 0) {
			String line = !Identity.canSign() ? "Sign in with Microsoft to use friends."
					: Friends.friends().isEmpty() ? "Add a friend by their Minecraft name, then play together in one click."
					: "Nobody's online right now. They'll show up here when they are.";
			wrap(g, line, x, y + 2, rightW, !Identity.canSign() ? Theme.WARNING : Theme.MUTED);
		}

		// Chips.
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

	/** A one-line card: icon, what it is, and its value on the right. */
	private static void chip(GuiGraphicsExtractor g, int x, int y, int w, Icons icon, String label, String value, int accent) {
		Draw.panel(g, x, y, w, CHIP_H, 6, Theme.CARD);
		icon.draw(g, x + 7, y + (CHIP_H - Icons.SIZE) / 2, accent);
		int valueW = Math.min(Draw.font().width(value), w - 30);
		if (w - 30 - valueW > Draw.font().width(label) + 4) {
			Draw.text(g, label, x + 22, y + 7, Theme.MUTED);
		}
		Draw.textFit(g, value, w - 30, x + w - 8 - valueW, y + 7, Theme.TEXT);
	}
}

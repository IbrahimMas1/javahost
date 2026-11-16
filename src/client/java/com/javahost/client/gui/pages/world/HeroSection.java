package com.javahost.client.gui.pages.world;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.Toasts;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.Connectivity.Route;
import com.javahost.client.host.Connectivity.State;
import com.javahost.client.host.HostController;
import com.javahost.client.host.QuickHost;
import com.javahost.client.rules.WorldOptions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;

/**
 * The top of the World page, always in view: your worlds (no world open), the world and Start hosting, the invite
 * code with Invite friends / Stop hosting, or, in someone else's world, who you are there.
 */
public final class HeroSection extends WorldSection {
	public enum Kind { WORLDS, SETUP, HOSTING, GUEST }

	public static final int HERO_H = 60;
	private static final int GUEST_H = 44;
	private static final int WORLD_ROW = 28;
	private static final int MAX_WORLDS = 4;

	private final Runnable onStarted;
	private Kind kind = Kind.SETUP;
	private String error;
	private long copiedAt;
	private String copiedValue;
	private List<QuickHost.SavedWorld> worlds = List.of();
	private int worldListH;

	public HeroSection(Host host, Runnable onStarted) {
		super(host);
		this.onStarted = onStarted;
	}

	public void show(Kind kind) {
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	/** Can this player host the open world (it's their own singleplayer world)? */
	public boolean canHost() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && com.javahost.compat.Compat.isOwner(server, minecraft.player);
	}

	private String worldName() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server == null ? "No world open" : server.getWorldData().getLevelName();
	}

	@Override
	protected int layout(int y) {
		return switch (kind) {
			case WORLDS -> buildWorldList(y);
			case SETUP -> buildSetup(y);
			case HOSTING -> buildHosting(y);
			case GUEST -> y + GUEST_H;
		};
	}

	@Override
	public String signature() {
		StringBuilder s = new StringBuilder().append(kind).append(QuickHost.version());
		if (kind == Kind.HOSTING) {
			for (Route r : Connectivity.routes()) {
				s.append(r.name()).append(r.state()).append(r.invite());
			}
			s.append(isCopying());
		}
		return s.toString();
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		switch (kind) {
			case WORLDS -> drawWorldList(g, left, top);
			case SETUP -> drawSetup(g, left, top);
			case HOSTING -> drawHosting(g, left, top);
			case GUEST -> drawGuest(g, left, top);
		}
	}

	// ================================================================ on the title screen: pick a world to host

	private int buildWorldList(int top) {
		worlds = QuickHost.worlds();
		int y = top + 14;
		int rows = Math.min(worlds.size(), MAX_WORLDS);
		for (int i = 0; i < rows; i++) {
			QuickHost.SavedWorld world = worlds.get(i);
			FlatButton hostButton = add(new FlatButton(54, 18, Component.literal("Host"), FlatButton.Style.PRIMARY, Icons.HOST, b -> QuickHost.host(world)),
					width - 62, y + 5);
			hostButton.setTooltip(tip("Open " + world.name() + " and start hosting"));
			add(new FlatButton(40, 18, Component.literal("Play"), FlatButton.Style.SECONDARY, null, b -> QuickHost.play(world)), width - 106, y + 5);
			y += WORLD_ROW;
		}
		if (rows == 0) {
			add(new FlatButton(110, 18, Component.literal("Create a world"), FlatButton.Style.PRIMARY, Icons.PLAY,
					b -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(screen))), width - 120, y + 7);
			y += 32;
		} else if (worlds.size() > rows) {
			add(new FlatButton(width - 16, 16, Component.literal("All " + worlds.size() + " worlds"), FlatButton.Style.GHOST, null,
					b -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(screen))), 8, y + 1);
			y += 18;
		}
		worldListH = y + 2 - top - 12;
		return y + 2;
	}

	private void drawWorldList(GuiGraphicsExtractor g, int left, int top) {
		label(g, "Your worlds", left, top);
		card(g, left, top + 12, width, worldListH);
		int y = top + 14;
		int rows = Math.min(worlds.size(), MAX_WORLDS);
		for (int i = 0; i < rows; i++) {
			QuickHost.SavedWorld world = worlds.get(i);
			if (i > 0) {
				g.fill(left + 8, y, left + width - 8, y + 1, 0x12FFFFFF);
			}
			QuickHost.drawIcon(g, world, left + 8, y + 4, 20);
			Draw.textFit(g, world.name(), width - 150, left + 34, y + 5, Theme.TEXT);
			Draw.textFit(g, QuickHost.played(world.lastPlayed()), width - 150, left + 34, y + 16, Theme.MUTED);
			y += WORLD_ROW;
		}
		if (rows == 0) {
			Draw.textFit(g, "No worlds yet", width - 140, left + 10, y + 7, Theme.TEXT);
			Draw.textFit(g, "Make one, then host it from here.", width - 140, left + 10, y + 18, Theme.MUTED);
		}
	}

	// ================================================================ a world of yours, not hosted yet

	private int buildSetup(int top) {
		boolean ready = canHost();
		FlatButton start = add(new FlatButton(118, 24, Component.literal("Start hosting"), FlatButton.Style.PRIMARY, Icons.PLAY,
				b -> start()), width - 128, top + (HERO_H - 24) / 2);
		start.active = ready;
		if (!ready) {
			start.setTooltip(tip("Open one of your singleplayer worlds first"));
		}
		return top + HERO_H + (error != null ? 16 : 0);
	}

	private void drawSetup(GuiGraphicsExtractor g, int left, int top) {
		boolean ready = canHost();
		Draw.panel(g, left, top, width, HERO_H, 8, Theme.CARD);
		Draw.roundRect(g, left, top, 3, HERO_H, 1, ready ? Theme.ACCENT : Theme.FAINT);
		Icons.HOST.draw(g, left + 12, top + 14, ready ? Theme.ACCENT : Theme.FAINT);
		int textW = width - 128 - 40;
		Draw.textFit(g, ready ? worldName() : "No world open", textW, left + 30, top + 15, Theme.TEXT);
		Draw.textFit(g, ready ? "Friends join in one click" : "Open a world to host it", textW, left + 30, top + 27, Theme.MUTED);
		Draw.textFit(g, ready ? "Stops when you leave" : "Then come back here", textW, left + 30, top + 39, Theme.FAINT);
		if (error != null) {
			Icons.WARNING.draw(g, left, top + HERO_H + 4, Theme.WARNING);
			Draw.textFit(g, error, width - 14, left + 14, top + HERO_H + 5, Theme.WARNING);
		}
	}

	/** Starts hosting the open world (the port typed in Advanced, if any). */
	public void start() {
		Config config = Config.get();
		int port = config.port;
		String typed = AdvancedSection.typedPort();
		if (typed != null) {
			try {
				port = Integer.parseInt(typed.trim());
			} catch (NumberFormatException e) {
				port = -1;
			}
		}
		if (port < 1024 || port > 65535) {
			error = "The port must be a number from 1024 to 65535.";
			screen.refresh();
			return;
		}
		if (!HttpUtil.isPortAvailable(port)) {
			error = "Port " + port + " is busy. Change it in Advanced.";
			screen.refresh();
			return;
		}
		config.port = port;
		config.save();
		AdvancedSection.forgetTypedPort();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			error = "Open a singleplayer world first.";
			screen.refresh();
			return;
		}
		String problem = HostController.start(server);
		error = problem;
		if (problem == null) {
			onStarted.run();
		}
		screen.refresh();
	}

	// ================================================================ hosting

	private int buildHosting(int top) {
		add(new FlatButton(100, 18, Component.literal("Invite friends"), FlatButton.Style.PRIMARY, Icons.USERS,
				b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), width - 110, top + 8);
		add(new FlatButton(100, 18, Component.literal("Stop hosting"), FlatButton.Style.DANGER, Icons.STOP, b -> {
			HostController.stop();
			screen.refresh();
		}), width - 110, top + 32);
		Route best = Connectivity.recommended();
		if (best != null && best.invite() != null) {
			add(new FlatButton(18, 16, Component.literal("Copy"), FlatButton.Style.GHOST, isCopied(best.invite()) ? Icons.CHECK : Icons.COPY,
					b -> copy(best.invite())).iconOnly(), 12 + inviteWidth(best.invite()) + 4, top + 26);
		}
		return top + HERO_H;
	}

	private void drawHosting(GuiGraphicsExtractor g, int left, int top) {
		Route best = Connectivity.recommended();
		Draw.panel(g, left, top, width, HERO_H, 8, Theme.CARD);
		Draw.roundRect(g, left, top, 3, HERO_H, 1, best == null ? Theme.INFO : stateColor(best.state()));
		int textW = width - 132; // keep clear of the buttons on the right
		if (best == null || best.invite() == null) {
			statusFit(g, left + 12, top + 10, Theme.INFO, "Getting ready...", textW);
			Draw.textFit(g, "Finding the best way for friends to join.", textW, left + 12, top + 26, Theme.MUTED);
		} else {
			String headline = best.state() != State.WORKING ? "Friends may not reach it" : switch (best.name()) {
				case "Internet", "Relay" -> "Friends anywhere can join";
				case "IPv6" -> "Friends with IPv6 can join";
				case "Same Wi-Fi" -> "Only people on your Wi-Fi can join";
				default -> "Ready";
			};
			statusFit(g, left + 12, top + 9, stateColor(best.state()), headline, textW);
			String invite = best.invite();
			float scale = inviteScale(invite);
			Draw.scaledText(g, invite, left + 12, top + 26 + Math.round((1.5f - scale) * 4), scale, Theme.ACCENT);
			Draw.textFit(g, "Invites find a way in.", textW, left + 12, top + 44, Theme.FAINT);
		}
	}

	private static void statusFit(GuiGraphicsExtractor g, int x, int y, int color, String text, int maxWidth) {
		Draw.roundRect(g, x, y + 1, 6, 6, 3, color);
		Draw.textFit(g, text, maxWidth, x + 10, y, Theme.TEXT);
	}

	/** The invite code is never cut: it's drawn smaller when the window is narrow, leaving room for the copy button. */
	private float inviteScale(String invite) {
		int room = width - 132 - 12 - 24;
		return Math.max(0.75f, Math.min(1.5f, room / (float) Math.max(1, Draw.width(invite))));
	}

	private int inviteWidth(String invite) {
		return Draw.scaledWidth(invite, inviteScale(invite)); // as drawn: pixel text is stretched by whole screen pixels
	}

	/** Copies an invite (also used by Connection details). */
	public void copy(String value) {
		minecraft.keyboardHandler.setClipboard(value);
		copiedValue = value;
		copiedAt = System.currentTimeMillis();
		Toasts.show("Invite copied", "Paste it to your friends.");
		screen.refresh();
	}

	public boolean isCopied(String value) {
		return value.equals(copiedValue) && isCopying();
	}

	private boolean isCopying() {
		return copiedValue != null && System.currentTimeMillis() - copiedAt < 2000;
	}

	// ================================================================ someone else's world

	private void drawGuest(GuiGraphicsExtractor g, int left, int top) {
		WorldOptions.Role role = WorldOptions.role();
		boolean operator = role == WorldOptions.Role.OPERATOR;
		Draw.panel(g, left, top, width, GUEST_H, 8, Theme.CARD);
		Draw.roundRect(g, left, top, 3, GUEST_H, 1, operator ? Theme.ACCENT : Theme.INFO);
		(operator ? Icons.SHIELD : Icons.USERS).draw(g, left + 12, top + 12, operator ? Theme.ACCENT : Theme.INFO);
		int textW = width - 40;
		Draw.textFit(g, operator ? "You're an operator here" : "You're a guest here", textW, left + 30, top + 13, Theme.TEXT);
		String detail = !WorldOptions.available() ? "The host has an older JavaHost."
				: operator ? "You can change difficulty and rules." : "The host and operators run this world.";
		Draw.textFit(g, detail, textW, left + 30, top + 25, Theme.MUTED);
	}

	static int stateColor(State state) {
		return switch (state) {
			case WORKING -> Theme.ACCENT;
			case CHECKING -> Theme.INFO;
			case MAYBE -> Theme.WARNING;
			case UNAVAILABLE -> Theme.DANGER;
			case OFF -> Theme.FAINT;
		};
	}
}

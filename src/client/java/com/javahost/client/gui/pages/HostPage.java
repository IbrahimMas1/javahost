package com.javahost.client.gui.pages;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.Connectivity.Route;
import com.javahost.client.host.Connectivity.State;
import com.javahost.client.host.E4mcCompat;
import com.javahost.client.host.HostController;
import com.javahost.client.host.HostController.Guest;
import com.javahost.client.pack.IrisCompat;
import com.javahost.client.Toasts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;

public class HostPage extends Page {
	private static final int ROW = 22;

	private TextField portField;
	private String error;
	private String lastSignature = "";
	private long copiedAt;
	private String copiedValue;

	// Layout positions remembered from build() for draw().
	private int heroY;
	private int routesY;
	private int playersY;
	private int footerY;
	private List<Route> routes = List.of();
	private List<Guest> guests = List.of();

	@Override
	public String title() {
		return HostController.isHosting() ? "Your world is open" : "Host this world";
	}

	@Override
	public String subtitle() {
		return HostController.isHosting()
				? "Send friends the invite below."
				: "Your PC is the server. No rental needed.";
	}

	private boolean canHost() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && server.isSingleplayerOwner(minecraft.player.nameAndId());
	}

	@Override
	protected void build() {
		if (HostController.isHosting()) {
			buildHosting();
		} else {
			buildSetup();
		}
	}

	// ================================================================ before hosting

	private void buildSetup() {
		Config config = Config.get();
		int inner = width - 20;

		// Connection card: y 12 .. 140
		portField = add(new TextField(70, Component.literal("Port")), width - 80, 20);
		portField.setMaxLength(5);
		portField.setValue(String.valueOf(config.port));
		add(new ToggleSwitch(inner, Component.literal("Open router port automatically"), "No router setup needed (UPnP)",
				config.useUpnp, v -> config.useUpnp = v), 10, 46);
		add(new ToggleSwitch(inner, Component.literal("Allow IPv6 connections"), "Direct, and works behind CGNAT",
				config.useIpv6, v -> config.useIpv6 = v), 10, 74);
		if (E4mcCompat.isInstalled()) {
			add(new Segmented<>(150, List.of("AUTO", "ALWAYS", "NEVER"), config.relayMode,
					mode -> switch (mode) {
						case "ALWAYS" -> "Always";
						case "NEVER" -> "Never";
						default -> "When needed";
					}, mode -> config.relayMode = mode), width - 160, 104);
		} else {
			add(new FlatButton(110, 18, Component.literal("Get relay"), FlatButton.Style.SECONDARY, Icons.SPARKLE,
					b -> screen.switchTo(JavaHostScreen.Tab.PERFORMANCE)), width - 120, 104);
		}

		// Sharing & voice card: y 162 .. 282
		add(new ToggleSwitch(inner, Component.literal("Share my resource packs"), "Everyone who joins sees them too",
				config.shareResourcePacks, v -> config.shareResourcePacks = v), 10, 170);
		ToggleSwitch shader = add(new ToggleSwitch(inner, Component.literal("Share my shader"),
				IrisCompat.isInstalled() ? "Including your shader settings" : "Install Iris on Performance first",
				config.shareShaders && IrisCompat.isInstalled(), v -> config.shareShaders = v), 10, 198);
		shader.active = IrisCompat.isInstalled();
		add(new ToggleSwitch(inner, Component.literal("Proximity voice chat"), "Hear players who are close by",
				config.voiceEnabledWhenHosting, v -> config.voiceEnabledWhenHosting = v), 10, 226);
		add(new Segmented<>(inner - 70, List.of(16, 32, 48, 64, 96, 128), config.voiceRange, r -> String.valueOf(r),
				r -> config.voiceRange = r), 80, 256);

		FlatButton start = add(new FlatButton(width, 24, Component.literal("Start hosting"), FlatButton.Style.PRIMARY, Icons.PLAY,
				b -> start()), 0, 294);
		start.active = canHost();
		if (!canHost() && error == null) {
			error = "Open one of your singleplayer worlds to host it.";
		}
		reserve(330);
	}

	private void start() {
		Config config = Config.get();
		int port;
		try {
			port = Integer.parseInt(portField.getValue().trim());
		} catch (NumberFormatException e) {
			port = -1;
		}
		if (port < 1024 || port > 65535) {
			error = "The port must be a number from 1024 to 65535.";
			return;
		}
		if (!HttpUtil.isPortAvailable(port)) {
			error = "Port " + port + " is busy. Try another one, like 25566.";
			return;
		}
		config.port = port;
		config.save();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			error = "Open a singleplayer world first.";
			return;
		}
		String problem = HostController.start(server);
		if (problem != null) {
			error = problem;
			return;
		}
		error = null;
		screen.refresh();
	}

	// ================================================================ while hosting

	private void buildHosting() {
		portField = null;
		routes = Connectivity.routes();
		guests = HostController.guests();
		lastSignature = signature();

		heroY = 0;
		Route best = Connectivity.recommended();
		if (best != null && best.invite() != null) {
			add(new FlatButton(64, 18, Component.literal(isCopied(best.invite()) ? "Copied" : "Copy"),
					FlatButton.Style.PRIMARY, isCopied(best.invite()) ? Icons.CHECK : Icons.COPY,
					b -> copy(best.invite())), width - 74, heroY + 28);
		}

		routesY = heroY + 82;
		int y = routesY + 12;
		for (Route route : routes) {
			if (route.invite() != null && route.state() != State.UNAVAILABLE) {
				add(new FlatButton(18, 16, Component.empty(), FlatButton.Style.GHOST,
						isCopied(route.invite()) ? Icons.CHECK : Icons.COPY, b -> copy(route.invite())), width - 26, y + 3);
			}
			y += ROW;
		}

		playersY = y + 10;
		y = playersY + 12;
		if (guests.isEmpty()) {
			y += ROW;
		}
		for (Guest guest : guests) {
			add(new FlatButton(44, 16, Component.literal("Kick"), FlatButton.Style.DANGER, null,
					b -> HostController.kick(guest.id())), width - 52, y + 3);
			y += ROW;
		}

		footerY = y + 10;
		add(new FlatButton(width, 22, Component.literal("Stop hosting"), FlatButton.Style.DANGER, Icons.STOP, b -> {
			HostController.stop();
			screen.refresh();
		}), 0, footerY + 14);
		reserve(footerY + 40);
	}

	private void copy(String value) {
		minecraft.keyboardHandler.setClipboard(value);
		copiedValue = value;
		copiedAt = System.currentTimeMillis();
		Toasts.show("Invite copied", "Paste it to your friends.");
		screen.refresh();
	}

	private boolean isCopied(String value) {
		return value.equals(copiedValue) && System.currentTimeMillis() - copiedAt < 2000;
	}

	private String signature() {
		StringBuilder s = new StringBuilder();
		for (Route r : Connectivity.routes()) {
			s.append(r.name()).append(r.state()).append(r.invite());
		}
		for (Guest guest : HostController.guests()) {
			s.append(guest.id());
		}
		s.append(copiedValue != null && System.currentTimeMillis() - copiedAt < 2000);
		return s.toString();
	}

	@Override
	public void tick() {
		if (HostController.isHosting()) {
			// Rebuild when hosting just started (setup widgets still showing) or something visible changed.
			if (portField != null || !signature().equals(lastSignature)) {
				screen.refresh();
			}
			guests = HostController.guests(); // pings update without a rebuild
		} else if (portField == null) {
			screen.refresh();
		}
	}

	// ================================================================ drawing

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (HostController.isHosting()) {
			drawHosting(g, left, top);
		} else {
			drawSetup(g, left, top);
		}
	}

	private void drawSetup(GuiGraphicsExtractor g, int left, int top) {
		sectionLabel(g, "Connection", left, top);
		card(g, left, top + 12, width, 120);
		Draw.text(g, "Game port", left + 10, top + 26, Theme.TEXT);
		Draw.text(g, "Relay fallback", left + 10, top + 106, Theme.TEXT);
		Draw.text(g, E4mcCompat.isInstalled() ? "For friends who can't connect directly" : "Not installed",
				left + 10, top + 117, Theme.MUTED);

		sectionLabel(g, "Sharing & voice", left, top + 150);
		card(g, left, top + 162, width, 120);
		Draw.text(g, "Range", left + 10, top + 261, Theme.TEXT);

		if (error != null) {
			Icons.WARNING.draw(g, left, top + 324, Theme.WARNING);
			Draw.text(g, Draw.fit(error, width - 14), left + 13, top + 325, Theme.WARNING);
		}
	}

	private void drawHosting(GuiGraphicsExtractor g, int left, int top) {
		// Hero card with the invite to share.
		Route best = Connectivity.recommended();
		int y = top + heroY;
		Draw.panel(g, left, y, width, 72, 8, Theme.CARD);
		Draw.roundRect(g, left, y, 3, 72, 1, best == null ? Theme.INFO : stateColor(best.state()));
		if (best == null || best.invite() == null) {
			status(g, left + 12, y + 10, Theme.INFO, "Checking how friends can reach you...", Theme.TEXT);
			Draw.text(g, "This takes a few seconds.", left + 12, y + 30, Theme.MUTED);
		} else {
			String headline = best.state() != State.WORKING ? "Friends might be able to join (see below)" : switch (best.name()) {
				case "Internet" -> "Friends anywhere can join";
				case "Relay" -> "Friends anywhere can join (via relay)";
				case "IPv6" -> "Friends with IPv6 can join";
				case "Same Wi-Fi" -> "Only people on your Wi-Fi can join";
				default -> "Ready";
			};
			status(g, left + 12, y + 10, stateColor(best.state()), headline, Theme.TEXT);
			Draw.text(g, "INVITE", left + 12, y + 26, Theme.FAINT);
			String invite = best.invite();
			float scale = Draw.font().width(invite) * 1.5f < width - 100 ? 1.5f : 1f;
			Draw.scaledText(g, Draw.fit(invite, (int) ((width - 100) / scale)), left + 12, y + 37, scale, Theme.ACCENT);
			Draw.text(g, Draw.fit(best.detail(), width - 24), left + 12, y + 56, Theme.MUTED);
		}

		// All ways of connecting.
		sectionLabel(g, "Ways to connect", left, top + routesY);
		int rowY = top + routesY + 12;
		card(g, left, rowY - 2, width, routes.size() * ROW + 4);
		for (Route route : routes) {
			Icons icon = switch (route.name()) {
				case "Relay" -> Icons.SPARKLE;
				case "Same Wi-Fi" -> Icons.WIFI;
				default -> Icons.GLOBE;
			};
			icon.draw(g, left + 9, rowY + 6, stateColor(route.state()));
			Draw.text(g, route.name(), left + 24, rowY + 7, Theme.TEXT);
			String state = switch (route.state()) {
				case WORKING -> "Ready";
				case CHECKING -> "Checking...";
				case MAYBE -> "Might work";
				case UNAVAILABLE -> "Not available";
				case OFF -> "Off";
			};
			Draw.text(g, state, left + 84, rowY + 7, stateColor(route.state()));
			String right = route.invite() != null && route.state() != State.UNAVAILABLE ? route.invite() : route.detail();
			Draw.text(g, Draw.fit(right, width - 180), left + 150, rowY + 7,
					route.invite() != null && route.state() != State.UNAVAILABLE ? Theme.MUTED : Theme.FAINT);
			rowY += ROW;
		}

		// Players.
		sectionLabel(g, "Players (" + guests.size() + ")", left, top + playersY);
		rowY = top + playersY + 12;
		card(g, left, rowY - 2, width, Math.max(1, guests.size()) * ROW + 4);
		if (guests.isEmpty()) {
			Icons.USER.draw(g, left + 9, rowY + 6, Theme.FAINT);
			Draw.text(g, "No one has joined yet. Send them the invite above.", left + 24, rowY + 7, Theme.MUTED);
		}
		for (Guest guest : guests) {
			Faces.draw(g, guest.id(), left + 8, rowY + 3, 16);
			Draw.text(g, guest.name(), left + 30, rowY + 7, Theme.TEXT);
			int ping = guest.pingMs();
			Draw.text(g, ping + " ms", left + width - 100, rowY + 7, ping < 80 ? Theme.ACCENT : ping < 200 ? Theme.WARNING : Theme.DANGER);
			rowY += ROW;
		}

		String packs = HostController.packStatus();
		if (!packs.isEmpty()) {
			Icons.PACKAGE.draw(g, left, top + footerY, Theme.MUTED);
			Draw.text(g, Draw.fit(packs, width - 14), left + 13, top + footerY + 1, Theme.MUTED);
		}
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

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
import com.javahost.client.host.WorldBackups;
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
	private List<java.util.Map.Entry<String, String>> banned = List.of();
	private int playersCardH;
	private int startY;
	private static final int PLAYER_ROW = 28;

	@Override
	public String title() {
		return HostController.isHosting() ? "Your world is open" : "Host this world";
	}

	@Override
	public String subtitle() {
		return HostController.isHosting()
				? "Invite friends, or share the code."
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
		add(new ToggleSwitch(inner, Component.literal("Only friends can join"), "Strangers with the address are turned away",
				config.friendsOnly, v -> config.friendsOnly = v), 10, 102);
		if (E4mcCompat.isInstalled()) {
			add(new Segmented<>(150, List.of("AUTO", "ALWAYS", "NEVER"), config.relayMode,
					mode -> switch (mode) {
						case "ALWAYS" -> "Always";
						case "NEVER" -> "Never";
						default -> "When needed";
					}, mode -> config.relayMode = mode), width - 160, 132);
		} else {
			add(new FlatButton(110, 18, Component.literal("Get relay"), FlatButton.Style.SECONDARY, Icons.SPARKLE,
					b -> screen.switchTo(JavaHostScreen.Tab.PERFORMANCE)), width - 120, 132);
		}

		// Sharing & voice card: y 162 .. 282
		add(new ToggleSwitch(inner, Component.literal("Share my resource packs"), "Everyone who joins sees them too",
				config.shareResourcePacks, v -> config.shareResourcePacks = v), 10, 198);
		ToggleSwitch shader = add(new ToggleSwitch(inner, Component.literal("Share my shader"),
				IrisCompat.isInstalled() ? "Including your shader settings" : "Install Iris on Performance first",
				config.shareShaders && IrisCompat.isInstalled(), v -> config.shareShaders = v), 10, 226);
		shader.active = IrisCompat.isInstalled();
		add(new ToggleSwitch(inner, Component.literal("Proximity voice chat"), "Hear players who are close by",
				config.voiceEnabledWhenHosting, v -> config.voiceEnabledWhenHosting = v), 10, 254);
		add(new Segmented<>(inner - 70, List.of(16, 32, 48, 64, 96, 128), config.voiceRange, r -> String.valueOf(r),
				r -> config.voiceRange = r), 80, 284);

		// Players card: limit and banned players.
		add(new Segmented<>(150, List.of(2, 4, 8, 16), config.maxPlayers, n -> String.valueOf(n), n -> {
			config.maxPlayers = n;
			HostController.applyHostRules(config);
		}), width - 160, 348);
		banned = new java.util.ArrayList<>(config.bannedPlayers.entrySet());
		int y = 374;
		for (var entry : banned) {
			add(new FlatButton(56, 16, Component.literal("Unban"), FlatButton.Style.SECONDARY, null, b -> {
				HostController.unban(java.util.UUID.fromString(entry.getKey()));
				screen.refresh();
			}), width - 66, y + 2);
			y += 22;
		}
		playersCardH = y - 338 + 6;

		startY = 338 + playersCardH + 14;
		FlatButton start = add(new FlatButton(width, 24, Component.literal("Start hosting"), FlatButton.Style.PRIMARY, Icons.PLAY,
				b -> start()), 0, startY);
		start.active = canHost();
		if (!canHost() && error == null) {
			error = "Open one of your singleplayer worlds to host it.";
		}
		buildBackups(startY + 46);
	}

	// ================================================================ backups

	private static final int BACKUP_ROW = 22;
	private int backupsY;
	private List<WorldBackups.Backup> backups = List.of();
	private java.nio.file.Path confirmRestore;
	private long confirmUntil;
	private String backupMessage;

	private void buildBackups(int y) {
		Config config = Config.get();
		backupsY = y;
		backups = WorldBackups.listAll().stream().limit(8).toList();
		add(new ToggleSwitch(width - 20, Component.literal("Back up when friends join"), "Every 30 min at most; 5 kept",
				config.autoBackup, v -> {
					config.autoBackup = v;
					config.save();
				}), 10, y + 18);
		boolean worldOpen = minecraft.getSingleplayerServer() != null;
		int rowY = y + 50;
		for (WorldBackups.Backup backup : backups) {
			boolean confirming = backup.file().equals(confirmRestore) && System.currentTimeMillis() < confirmUntil;
			FlatButton restore = add(new FlatButton(confirming ? 96 : 64, 16, Component.literal(confirming ? "Click again" : "Restore"),
					confirming ? FlatButton.Style.DANGER : FlatButton.Style.SECONDARY, null, b -> restore(backup)),
					width - (confirming ? 106 : 74), rowY + 3);
			restore.active = !worldOpen;
			if (worldOpen) {
				restore.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Leave the world to restore a backup")));
			} else {
				restore.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
						"Your current \"" + backup.world() + "\" is kept as a copy, then this backup takes its place")));
			}
			rowY += BACKUP_ROW;
		}
		if (backups.isEmpty()) {
			rowY += BACKUP_ROW;
		}
		int half = (width - 6) / 2;
		FlatButton now = add(new FlatButton(half, 18, Component.literal("Back up now"), FlatButton.Style.SECONDARY, Icons.PACKAGE, b -> {
			net.minecraft.client.server.IntegratedServer server = minecraft.getSingleplayerServer();
			if (server != null) {
				backupMessage = "Backing up...";
				server.execute(() -> WorldBackups.backUp(server, "you asked").whenComplete((done, error) -> minecraft.execute(() -> {
					backupMessage = error == null ? "Backed up." : "Couldn't back up.";
					screen.refresh();
				})));
				screen.refresh();
			}
		}), 0, rowY + 10);
		now.active = worldOpen;
		add(new FlatButton(width - half - 6, 18, Component.literal("Open folder"), FlatButton.Style.GHOST, Icons.PACKAGE, b -> {
			try {
				java.nio.file.Files.createDirectories(WorldBackups.root());
			} catch (java.io.IOException ignored) {
				// Opening the parent folder below still helps.
			}
			com.mojang.blaze3d.Blaze3D.openPath(WorldBackups.root());
		}), half + 6, rowY + 10);
		reserve(rowY + 50);
	}

	private void restore(WorldBackups.Backup backup) {
		if (!backup.file().equals(confirmRestore) || System.currentTimeMillis() > confirmUntil) {
			confirmRestore = backup.file();
			confirmUntil = System.currentTimeMillis() + 4000;
			screen.refresh();
			return;
		}
		confirmRestore = null;
		try {
			String aside = WorldBackups.restore(backup);
			backupMessage = "Restored " + backup.world() + (aside != null ? ". Your previous copy is \"" + aside + "\"." : ".");
		} catch (java.io.IOException e) {
			backupMessage = "Couldn't restore: " + e.getMessage();
		}
		screen.refresh();
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

	private static final java.time.format.DateTimeFormatter BACKUP_TIME = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")
			.withZone(java.time.ZoneId.systemDefault());

	private void drawBackups(GuiGraphicsExtractor g, int left, int top) {
		int y = top + backupsY;
		sectionLabel(g, "Backups", left, y);
		card(g, left, y + 12, width, 38 + Math.max(1, backups.size()) * BACKUP_ROW);
		int rowY = y + 50;
		if (backups.isEmpty()) {
			Draw.text(g, Draw.fit("No backups yet. One is made when a friend joins.", width - 20), left + 10, rowY + 6, Theme.FAINT);
		}
		for (WorldBackups.Backup backup : backups) {
			Icons.PACKAGE.draw(g, left + 10, rowY + 5, Theme.MUTED);
			String when = BACKUP_TIME.format(java.time.Instant.ofEpochMilli(backup.time())) + " · " + Math.max(1, backup.size() / (1024 * 1024)) + " MB";
			Draw.text(g, Draw.fit(backup.world(), width - 190), left + 26, rowY + 2, Theme.TEXT);
			Draw.text(g, Draw.fit(when, width - 120), left + 26, rowY + 12, Theme.FAINT);
			rowY += BACKUP_ROW;
		}
		if (backups.isEmpty()) {
			rowY += BACKUP_ROW;
		}
		if (backupMessage != null) {
			Draw.text(g, Draw.fit(backupMessage, width), left, rowY + 32, backupMessage.startsWith("Couldn't") ? Theme.WARNING : Theme.ACCENT);
		}
	}

	// ================================================================ while hosting

	private void buildHosting() {
		portField = null;
		routes = Connectivity.routes();
		guests = HostController.guests();
		lastSignature = signature();

		heroY = 0;
		add(new FlatButton(104, 18, Component.literal("Invite friends"), FlatButton.Style.PRIMARY, Icons.USERS,
				b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), width - 114, heroY + 6);
		Route best = Connectivity.recommended();
		if (best != null && best.invite() != null) {
			add(new FlatButton(64, 18, Component.literal(isCopied(best.invite()) ? "Copied" : "Copy"),
					FlatButton.Style.SECONDARY, isCopied(best.invite()) ? Icons.CHECK : Icons.COPY,
					b -> copy(best.invite())), width - 74, heroY + 30);
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
			net.minecraft.world.level.GameType mode = HostController.gameModeOf(guest.id());
			add(new Segmented<>(112, List.of(net.minecraft.world.level.GameType.SURVIVAL, net.minecraft.world.level.GameType.CREATIVE,
					net.minecraft.world.level.GameType.ADVENTURE, net.minecraft.world.level.GameType.SPECTATOR),
					mode == null ? net.minecraft.world.level.GameType.SURVIVAL : mode,
					m -> switch (m) {
						case CREATIVE -> "C";
						case ADVENTURE -> "A";
						case SPECTATOR -> "Sp";
						default -> "S";
					}, m -> HostController.setGameMode(guest.id(), m)), width - 200, y + 5);
			add(new FlatButton(40, 18, Component.literal("Kick"), FlatButton.Style.SECONDARY, null,
					b -> HostController.kick(guest.id())), width - 84, y + 5);
			add(new FlatButton(36, 18, Component.literal("Ban"), FlatButton.Style.DANGER, null,
					b -> HostController.ban(guest.id(), guest.name())), width - 40, y + 5);
			y += PLAYER_ROW;
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
		card(g, left, top + 12, width, 148);
		Draw.text(g, "Game port", left + 10, top + 26, Theme.TEXT);
		Draw.text(g, "Relay fallback", left + 10, top + 134, Theme.TEXT);
		Draw.text(g, E4mcCompat.isInstalled() ? "For friends who can't connect directly" : "Not installed",
				left + 10, top + 145, Theme.MUTED);

		sectionLabel(g, "Sharing & voice", left, top + 178);
		card(g, left, top + 190, width, 120);
		Draw.text(g, "Range", left + 10, top + 289, Theme.TEXT);

		sectionLabel(g, "Players", left, top + 326);
		card(g, left, top + 338, width, playersCardH);
		Draw.text(g, "Player limit", left + 10, top + 352, Theme.TEXT);
		Draw.text(g, "Including you", left + 10, top + 362, Theme.FAINT);
		int y = top + 374;
		for (var entry : banned) {
			Faces.draw(g, java.util.UUID.fromString(entry.getKey()), left + 10, y + 3, 14);
			Draw.text(g, entry.getValue() + " (banned)", left + 30, y + 6, Theme.MUTED);
			y += 22;
		}

		if (error != null) {
			Icons.WARNING.draw(g, left, top + startY + 30, Theme.WARNING);
			Draw.text(g, Draw.fit(error, width - 14), left + 13, top + startY + 31, Theme.WARNING);
		}
		drawBackups(g, left, top);
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
			String headline = best.state() != State.WORKING ? "Friends might be able to join" : switch (best.name()) {
				case "Internet" -> "Friends anywhere can join";
				case "Relay" -> "Friends anywhere can join (via relay)";
				case "IPv6" -> "Friends with IPv6 can join";
				case "Same Wi-Fi" -> "Only people on your Wi-Fi can join";
				default -> "Ready";
			};
			status(g, left + 12, y + 10, stateColor(best.state()), Draw.fit(headline, width - 140), Theme.TEXT);
			Draw.text(g, "INVITE", left + 12, y + 26, Theme.FAINT);
			String invite = best.invite();
			float scale = Draw.font().width(invite) * 1.5f < width - 100 ? 1.5f : 1f;
			Draw.scaledText(g, Draw.fit(invite, (int) ((width - 100) / scale)), left + 12, y + 37, scale, Theme.ACCENT);
			String hint = best.state() == State.WORKING ? "Share this code, or just invite friends." : "May not work for everyone. Invites try every way.";
			Draw.text(g, Draw.fit(hint, width - 24), left + 12, y + 56, Theme.MUTED);
		}

		// All ways of connecting.
		sectionLabel(g, "Ways to connect", left, top + routesY);
		int rowY = top + routesY + 12;
		card(g, left, rowY - 2, width, routes.size() * ROW + 4);
		for (Route route : routes) {
			Icons icon = switch (route.name()) {
				case "Relay" -> Icons.SPARKLE;
				case "Same Wi-Fi" -> Icons.HOME;
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
		card(g, left, rowY - 2, width, guests.isEmpty() ? ROW + 4 : guests.size() * PLAYER_ROW + 4);
		if (guests.isEmpty()) {
			Icons.USER.draw(g, left + 9, rowY + 6, Theme.FAINT);
			Draw.text(g, Draw.fit("No one has joined yet. Invite friends from the Friends tab.", width - 34), left + 24, rowY + 7, Theme.MUTED);
		}
		for (Guest guest : guests) {
			Faces.draw(g, guest.id(), left + 8, rowY + 5, 18);
			Draw.text(g, Draw.fit(guest.name(), width - 240), left + 32, rowY + 5, Theme.TEXT);
			int ping = guest.pingMs();
			Draw.text(g, ping <= 0 ? "ping —" : ping + " ms", left + 32, rowY + 15,
					ping <= 0 ? Theme.FAINT : ping < 80 ? Theme.ACCENT : ping < 200 ? Theme.WARNING : Theme.DANGER);
			rowY += PLAYER_ROW;
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

package com.javahost.client.gui.pages;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.Toasts;
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
import com.javahost.client.host.WorldBackups;
import com.javahost.client.share.WorldShare;
import com.javahost.client.gui.WorldPickerScreen;
import com.javahost.client.pack.IrisCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

/**
 * Hosting, laid out so the important things are always in view: the world and its Start/Stop button first, then
 * who's playing and who can join; the technical connection settings and details are folded away underneath.
 */
public class HostPage extends Page {
	private static final int ROW = 22;
	private static final int PLAYER_ROW = 28;
	private static final int HERO_H = 60;
	/** Folded sections (remembered while the game runs). */
	private static boolean showAdvanced;
	private static boolean showConnection;

	private TextField portField;
	private boolean builtHosting;
	private String error;
	private String lastSignature = "";
	private long copiedAt;
	private String copiedValue;

	// Layout positions remembered from build() for draw().
	private int whoY;
	private int shareY;
	private int advancedY;
	private int bannedY;
	private int playersY;
	private int connectionY;
	private int footerY;
	private List<Route> routes = List.of();
	private List<Guest> guests = List.of();
	private List<java.util.Map.Entry<String, String>> banned = List.of();

	@Override
	public String title() {
		return HostController.isHosting() ? "Your world is open" : "Host this world";
	}

	@Override
	public String subtitle() {
		return HostController.isHosting() ? "Invite friends, or share the code." : "Your PC is the server. No rental.";
	}

	private boolean canHost() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && com.javahost.compat.Compat.isOwner(server, minecraft.player);
	}

	private String worldName() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server == null ? "No world open" : server.getWorldData().getLevelName();
	}

	private enum Section { PLAYERS, SETTINGS, BACKUPS }

	/** The sub-tab under the hero (remembered while the game runs). */
	private static Section section = Section.SETTINGS;
	private Section shown = Section.SETTINGS;
	private int sectionY;

	/** For tests: open a sub-tab ("players", "settings", "backups"). */
	public static void showSection(String name) {
		section = Section.valueOf(name.toUpperCase(java.util.Locale.ROOT));
	}

	@Override
	protected void build() {
		builtHosting = HostController.isHosting();
		portField = null;
		guests = HostController.guests();
		lastSignature = signature();
		// The hero (the world and Start, or the invite code and Invite / Stop) stays on top; the rest is in sub-tabs.
		int y = builtHosting ? buildHostingHero() : buildSetupHero();
		List<Section> sections = builtHosting ? List.of(Section.PLAYERS, Section.SETTINGS, Section.BACKUPS) : List.of(Section.SETTINGS, Section.BACKUPS);
		shown = sections.contains(section) ? section : Section.SETTINGS;
		add(new Segmented<>(width, sections, shown, s -> switch (s) {
			case PLAYERS -> "Players (" + guests.size() + ")";
			case SETTINGS -> "Settings";
			case BACKUPS -> "Backups";
		}, s -> {
			section = s;
			screen.refresh();
		}), 0, y);
		sectionY = y + 28;
		switch (shown) {
			case PLAYERS -> buildPlayers(sectionY);
			case SETTINGS -> buildSettings(sectionY);
			case BACKUPS -> buildBackups(sectionY);
		}
	}

	// ================================================================ before hosting

	/** The world and the big button. Returns where the sub-tabs go. */
	private int buildSetupHero() {
		boolean ready = canHost();
		FlatButton start = add(new FlatButton(118, 24, Component.literal("Start hosting"), FlatButton.Style.PRIMARY, Icons.PLAY,
				b -> start()), width - 128, (HERO_H - 24) / 2);
		start.active = ready;
		if (!ready) {
			start.setTooltip(Tooltip.create(Component.literal("Open one of your singleplayer worlds first")));
		}
		return HERO_H + (error != null ? 16 : 0) + 10;
	}

	// ================================================================ settings

	private void buildSettings(int y) {
		Config config = Config.get();
		int inner = width - 20;

		// Who can join (applies right away, also while hosting).
		whoY = y;
		add(new ToggleSwitch(inner, Component.literal("Only friends can join"), "Others with the code are turned away",
				config.friendsOnly, v -> {
					config.friendsOnly = v;
					config.save();
					HostController.applyHostRules(config);
				}), 10, y + 18);
		add(new Segmented<>(132, List.of(2, 4, 8, 16), config.maxPlayers, String::valueOf, n -> {
			config.maxPlayers = n;
			config.save();
			HostController.applyHostRules(config);
		}), width - 142, y + 48);
		y += 12 + 64;

		// Sharing and voice.
		shareY = y + 10;
		y = shareY + 12;
		add(new ToggleSwitch(inner, Component.literal("Share my resource packs"), "Everyone who joins sees them too",
				config.shareResourcePacks, v -> {
					config.shareResourcePacks = v;
					config.save();
				}), 10, y + 6);
		ToggleSwitch shader = add(new ToggleSwitch(inner, Component.literal("Share my shader"),
				IrisCompat.isInstalled() ? "Including your shader settings" : "Install Iris on Performance first",
				config.shareShaders && IrisCompat.isInstalled(), v -> {
					config.shareShaders = v;
					config.save();
				}), 10, y + 34);
		shader.active = IrisCompat.isInstalled();
		add(new ToggleSwitch(inner, Component.literal("Voice chat"), "Players can talk in your world",
				config.voiceEnabledWhenHosting, v -> {
					config.voiceEnabledWhenHosting = v;
					config.save();
				}), 10, y + 62);
		y += 92;

		// Banned players (only when there are any).
		banned = new java.util.ArrayList<>(config.bannedPlayers.entrySet());
		bannedY = -1;
		if (!banned.isEmpty()) {
			bannedY = y + 10;
			y = bannedY + 12;
			for (var entry : banned) {
				add(new FlatButton(56, 16, Component.literal("Unban"), FlatButton.Style.SECONDARY, null, b -> {
					HostController.unban(java.util.UUID.fromString(entry.getKey()));
					screen.refresh();
				}), width - 66, y + 3);
				y += ROW;
			}
			y += 4;
		}

		// The world: public listing and its rules.
		y = buildPublic(y);
		if (minecraft.level != null) {
			add(new FlatButton(width, 18, Component.literal("World rules"), FlatButton.Style.SECONDARY, Icons.SHIELD,
					b -> screen.switchTo(JavaHostScreen.Tab.RULES)), 0, y + 10);
			y += 24;
		}

		// How friends can connect (while hosting): folded away, invites pick the best way automatically.
		connectionY = -1;
		if (builtHosting) {
			routes = Connectivity.routes();
			connectionY = y + 10;
			add(new FlatButton(width, 18, Component.literal(showConnection ? "Hide connection details" : "Connection details"),
					FlatButton.Style.GHOST, Icons.GLOBE, b -> {
						showConnection = !showConnection;
						screen.refresh();
					}), 0, connectionY);
			y = connectionY + 22;
			if (showConnection) {
				for (Route route : routes) {
					if (route.invite() != null && route.state() != State.UNAVAILABLE) {
						add(new FlatButton(18, 16, Component.literal("Copy " + route.name()), FlatButton.Style.GHOST,
								isCopied(route.invite()) ? Icons.CHECK : Icons.COPY, b -> copy(route.invite())).iconOnly(), width - 26, y + 3);
					}
					y += ROW;
				}
				y += 4;
			}
		}

		// Advanced: folded away. While hosting these wait for the next time.
		advancedY = y + 10;
		add(new FlatButton(width, 18, Component.literal(showAdvanced ? "Hide advanced settings" : "Advanced settings"),
				FlatButton.Style.GHOST, Icons.GEAR, b -> {
					showAdvanced = !showAdvanced;
					screen.refresh();
				}), 0, advancedY);
		y = advancedY + 22;
		if (showAdvanced) {
			boolean editable = !builtHosting;
			if (editable) {
				portField = add(new TextField(70, Component.literal("Port")), width - 80, y + 8);
				portField.setMaxLength(5);
				portField.setValue(String.valueOf(config.port));
			}
			ToggleSwitch upnp = add(new ToggleSwitch(inner, Component.literal("Open router port automatically"), "No router setup needed (UPnP)",
					config.useUpnp, v -> {
						config.useUpnp = v;
						config.save();
					}), 10, y + 34);
			ToggleSwitch ipv6 = add(new ToggleSwitch(inner, Component.literal("Allow IPv6 connections"), "Direct, and works behind CGNAT",
					config.useIpv6, v -> {
						config.useIpv6 = v;
						config.save();
					}), 10, y + 62);
			upnp.active = editable;
			ipv6.active = editable;
			if (E4mcCompat.isInstalled()) {
				Segmented<String> relay = add(new Segmented<>(inner, List.of("AUTO", "ALWAYS", "NEVER"), config.relayMode,
						mode -> switch (mode) {
							case "ALWAYS" -> "Always";
							case "NEVER" -> "Never";
							default -> "When needed";
						}, mode -> {
							config.relayMode = mode;
							config.save();
						}), 10, y + 104);
				relay.active = editable;
			} else {
				add(new FlatButton(110, 18, Component.literal("Get relay"), FlatButton.Style.SECONDARY, Icons.CLOUD,
						b -> screen.switchTo(JavaHostScreen.Tab.PERFORMANCE)), width - 120, y + 98);
			}
			Segmented<Integer> range = add(new Segmented<>(inner - 70, List.of(16, 32, 48, 64, 96, 128), config.voiceRange, String::valueOf, r -> {
				config.voiceRange = r;
				config.save();
			}), 80, y + 134);
			range.active = editable;
			y += 160;
		}
		reserve(y + 10);
	}

	// ================================================================ public world (beta)

	private int publicY = -1;

	/** "Open to the public", or, when it's open, its listing and Stop listing. Returns the y below it. */
	private int buildPublic(int y) {
		if (minecraft.level == null || minecraft.getSingleplayerServer() == null) {
			publicY = -1;
			return y;
		}
		if (com.javahost.client.discover.PublicHosting.isPublic()) {
			publicY = y + 10;
			add(new FlatButton(90, 18, Component.literal("Stop listing"), FlatButton.Style.DANGER, Icons.STOP, b -> {
				com.javahost.client.discover.PublicHosting.close();
				screen.refresh();
			}), width - 100, publicY + 11);
			return y + 10 + 40;
		}
		publicY = -1;
		String problem = com.javahost.client.discover.PublicHosting.cannotOpen();
		FlatButton open = add(new FlatButton(width, 18, Component.literal("Open to the public (Beta)"), FlatButton.Style.SECONDARY, Icons.GLOBE,
				b -> minecraft.gui.setScreen(new com.javahost.client.gui.PublicWorldScreen(screen))), 0, y + 10);
		open.active = problem == null;
		open.setTooltip(Tooltip.create(Component.literal(problem != null ? problem
				: "List your world so anyone with JavaHost can find it in Discover. Strangers visit until you trust them.")));
		return y + 24;
	}

	private void drawPublic(GuiGraphicsExtractor g, int left, int top) {
		if (publicY < 0) {
			return;
		}
		int y = top + publicY;
		Draw.panel(g, left, y, width, 40, 8, Theme.CARD);
		Draw.roundRect(g, left, y, 3, 40, 1, com.javahost.client.discover.PublicHosting.listed() ? Theme.ACCENT : Theme.INFO);
		Icons.GLOBE.draw(g, left + 12, y + 8, Theme.ACCENT);
		Draw.textFit(g, "Public: \"" + Config.get().publicTitle + "\"", width - 140, left + 28, y + 9, Theme.TEXT);
		Draw.textFit(g, com.javahost.client.discover.PublicHosting.status(), width - 140, left + 28, y + 22, Theme.MUTED);
	}

	private void start() {
		Config config = Config.get();
		int port = config.port;
		if (portField != null) {
			try {
				port = Integer.parseInt(portField.getValue().trim());
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
			error = "Port " + port + " is busy. Change it in Advanced settings.";
			screen.refresh();
			return;
		}
		config.port = port;
		config.save();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			error = "Open a singleplayer world first.";
			screen.refresh();
			return;
		}
		String problem = HostController.start(server);
		error = problem;
		if (problem == null) {
			section = Section.PLAYERS; // who's here matters most now
		}
		screen.refresh();
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
			if (!WorldShare.reachableFriends().isEmpty()) {
				FlatButton send = add(new FlatButton(18, 16, Component.literal("Send"), FlatButton.Style.GHOST, Icons.UPLOAD,
						b -> openPicker(null, backup.file())).iconOnly(), width - (confirming ? 106 : 74) - 22, rowY + 3);
				send.setTooltip(Tooltip.create(Component.literal("Send this backup to a friend in this world")));
			}
			restore.active = !worldOpen;
			restore.setTooltip(Tooltip.create(Component.literal(worldOpen ? "Leave the world to restore a backup"
					: "Your current \"" + backup.world() + "\" is kept as a copy, then this backup takes its place")));
			rowY += BACKUP_ROW;
		}
		if (backups.isEmpty()) {
			rowY += BACKUP_ROW;
		}
		int half = (width - 6) / 2;
		FlatButton now = add(new FlatButton(half, 18, Component.literal("Back up now"), FlatButton.Style.SECONDARY, Icons.PACKAGE, b -> {
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server != null) {
				backupMessage = "Backing up...";
				server.execute(() -> WorldBackups.backUp(server, "you asked").whenComplete((done, err) -> minecraft.execute(() -> {
					backupMessage = err == null ? "Backed up." : "Couldn't back up.";
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
				// Opening the folder below still helps.
			}
			com.javahost.client.compat.ClientCompat.openPath(WorldBackups.root());
		}), half + 6, rowY + 10);
		reserve(rowY + 50);
	}

	private void openPicker(com.javahost.client.friends.Friends.Friend friend, java.nio.file.Path preselect) {
		minecraft.gui.setScreen(new WorldPickerScreen(screen, friend, preselect, () -> screen.switchTo(JavaHostScreen.Tab.NOTIFICATIONS)));
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

	// ================================================================ while hosting

	/** Status, the invite code, Invite friends and Stop hosting. Returns where the sub-tabs go. */
	private int buildHostingHero() {
		routes = Connectivity.routes();
		add(new FlatButton(100, 18, Component.literal("Invite friends"), FlatButton.Style.PRIMARY, Icons.USERS,
				b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), width - 110, 8);
		add(new FlatButton(100, 18, Component.literal("Stop hosting"), FlatButton.Style.DANGER, Icons.STOP, b -> {
			HostController.stop();
			screen.refresh();
		}), width - 110, 32);
		Route best = Connectivity.recommended();
		if (best != null && best.invite() != null) {
			add(new FlatButton(18, 16, Component.literal("Copy"), FlatButton.Style.GHOST, isCopied(best.invite()) ? Icons.CHECK : Icons.COPY,
					b -> copy(best.invite())).iconOnly(), 12 + inviteWidth(best.invite()) + 4, 26);
		}
		return HERO_H + 10;
	}

	// ================================================================ players

	private static String modeName(GameType mode) {
		return switch (mode) {
			case CREATIVE -> "Creative";
			case ADVENTURE -> "Adventure";
			case SPECTATOR -> "Spectator";
			default -> "Survival";
		};
	}

	/** One row per player: face, name, ping, a game-mode button and a "more" menu (trust, send a world, kick, ban). */
	private void buildPlayers(int y) {
		playersY = y;
		y += 2;
		for (Guest guest : guests) {
			GameType mode = HostController.gameModeOf(guest.id());
			GameType current = mode != null ? mode : guest.gameMode() != null ? guest.gameMode() : GameType.SURVIVAL;
			FlatButton modeButton = add(new FlatButton(70, 18, Component.literal(modeName(current)), FlatButton.Style.SECONDARY, Icons.CHEVRON_DOWN,
					b -> screen.openPopover(com.javahost.client.gui.ui.Popover.menu(
							List.of(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR).stream()
									.map(m -> com.javahost.client.gui.ui.Popover.Item.of(modeName(m), null, () -> {
										HostController.setGameMode(guest.id(), m);
										screen.refresh();
									}).selected(m == current)).toList()), b)), width - 100, y + 5);
			modeButton.setTooltip(Tooltip.create(Component.literal("Game mode for " + guest.name())));
			FlatButton more = add(new FlatButton(22, 18, Component.literal("More"), FlatButton.Style.GHOST, Icons.MORE,
					b -> screen.openPopover(com.javahost.client.gui.ui.Popover.menu(playerActions(guest)), b)).iconOnly(), width - 26, y + 5);
			more.setTooltip(Tooltip.create(Component.literal("More for " + guest.name())));
			y += PLAYER_ROW;
		}
		if (guests.isEmpty()) {
			y += ROW;
		}
		footerY = y + 8;
		reserve(footerY + 20);
	}

	/** What the host can do about a player, for the row's "more" menu. */
	private List<com.javahost.client.gui.ui.Popover.Item> playerActions(Guest guest) {
		List<com.javahost.client.gui.ui.Popover.Item> items = new java.util.ArrayList<>();
		if (guest.visitor() || guest.trusted()) {
			items.add(com.javahost.client.gui.ui.Popover.Item.of(guest.visitor() ? "Trust" : "Untrust", Icons.SHIELD,
					() -> HostController.trust(guest.id(), guest.name(), guest.visitor())));
		}
		if (com.javahost.client.friends.Friends.friend(guest.id()).isPresent()) {
			String problem = WorldShare.cannotSendTo(guest.id());
			var send = com.javahost.client.gui.ui.Popover.Item.of("Send a world", Icons.UPLOAD,
					() -> openPicker(com.javahost.client.friends.Friends.friend(guest.id()).orElse(null), null));
			items.add(problem == null ? send : send.disabled("Can't now"));
		}
		items.add(com.javahost.client.gui.ui.Popover.Item.of("Kick", Icons.CROSS, () -> HostController.kick(guest.id())));
		items.add(com.javahost.client.gui.ui.Popover.Item.danger("Ban", Icons.STOP, () -> HostController.ban(guest.id(), guest.name())));
		return items;
	}

	/** The invite code is never cut: it's drawn smaller when the window is narrow, leaving room for the copy button. */
	private float inviteScale(String invite) {
		int room = width - 132 - 12 - 24;
		return Math.max(0.75f, Math.min(1.5f, room / (float) Math.max(1, Draw.font().width(invite))));
	}

	private int inviteWidth(String invite) {
		return (int) (Draw.font().width(invite) * inviteScale(invite));
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
			s.append(guest.id()).append(WorldShare.cannotSendTo(guest.id())).append(guest.visitor()).append(guest.trusted())
					.append(HostController.gameModeOf(guest.id()));
		}
		s.append(com.javahost.client.discover.PublicHosting.isPublic()).append(com.javahost.client.discover.PublicHosting.listed());
		s.append(copiedValue != null && System.currentTimeMillis() - copiedAt < 2000);
		return s.toString();
	}

	@Override
	public void tick() {
		if (HostController.isHosting() != builtHosting) {
			if (!builtHosting) {
				section = Section.PLAYERS; // hosting just started: who's here matters most now
			}
			screen.refresh(); // hosting started or stopped
		} else if (builtHosting) {
			if (!signature().equals(lastSignature)) {
				screen.refresh();
			}
			guests = HostController.guests(); // pings update without a rebuild
		}
	}

	// ================================================================ drawing

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (builtHosting) {
			drawHostingHero(g, left, top);
		} else {
			drawSetupHero(g, left, top);
		}
		switch (shown) {
			case PLAYERS -> drawPlayers(g, left, top);
			case SETTINGS -> drawSettings(g, left, top);
			case BACKUPS -> drawBackups(g, left, top);
		}
	}

	private void drawSetupHero(GuiGraphicsExtractor g, int left, int top) {
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

	private void drawSettings(GuiGraphicsExtractor g, int left, int top) {
		sectionLabel(g, "Who can join", left, top + whoY);
		card(g, left, top + whoY + 12, width, 64);
		Draw.text(g, "Player limit", left + 10, top + whoY + 52, Theme.TEXT);
		Draw.text(g, "Including you", left + 10, top + whoY + 62, Theme.FAINT);

		sectionLabel(g, "Share with players", left, top + shareY);
		card(g, left, top + shareY + 12, width, 92);

		if (bannedY >= 0) {
			sectionLabel(g, "Banned", left, top + bannedY);
			card(g, left, top + bannedY + 12, width, banned.size() * ROW + 4);
			int y = top + bannedY + 12;
			for (var entry : banned) {
				Faces.draw(g, java.util.UUID.fromString(entry.getKey()), left + 10, y + 4, 14);
				Draw.textFit(g, entry.getValue(), width - 110, left + 30, y + 7, Theme.MUTED);
				y += ROW;
			}
		}

		drawPublic(g, left, top);
		if (connectionY >= 0 && showConnection) {
			int rowY = top + connectionY + 22;
			card(g, left, rowY - 2, width, routes.size() * ROW + 4);
			for (Route route : routes) {
				Icons icon = switch (route.name()) {
					case "Relay" -> Icons.CLOUD;
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
				Draw.textFit(g, right, width - 180, left + 150, rowY + 7, route.invite() != null && route.state() != State.UNAVAILABLE ? Theme.MUTED : Theme.FAINT);
				rowY += ROW;
			}
		}
		if (showAdvanced) {
			int y = top + advancedY + 22;
			card(g, left, y, width, 156);
			Draw.text(g, "Game port", left + 10, y + 13, Theme.TEXT);
			if (builtHosting) {
				Draw.text(g, String.valueOf(Config.get().port), left + width - 10 - Draw.font().width(String.valueOf(Config.get().port)), y + 13, Theme.MUTED);
				Draw.textFit(g, "Applies next time you host", width - 140, left + 68, y + 13, Theme.FAINT);
			}
			Draw.text(g, "Relay, for friends who can't connect directly", left + 10, y + 92, Theme.TEXT);
			if (!E4mcCompat.isInstalled()) {
				Draw.text(g, "Not installed", left + 10, y + 103, Theme.FAINT);
			}
			Draw.text(g, "Voice range", left + 10, y + 139, Theme.TEXT);
		}
	}

	private void drawHostingHero(GuiGraphicsExtractor g, int left, int top) {
		Route best = Connectivity.recommended();
		Draw.panel(g, left, top, width, HERO_H, 8, Theme.CARD);
		Draw.roundRect(g, left, top, 3, HERO_H, 1, best == null ? Theme.INFO : stateColor(best.state()));
		int textW = width - 132; // keep clear of the buttons on the right
		if (best == null || best.invite() == null) {
			statusFit(g, left + 12, top + 10, Theme.INFO, "Getting ready...", textW, Theme.TEXT);
			Draw.textFit(g, "Finding the best way for friends to join.", textW, left + 12, top + 26, Theme.MUTED);
		} else {
			String headline = best.state() != State.WORKING ? "Friends may not reach it" : switch (best.name()) {
				case "Internet", "Relay" -> "Friends anywhere can join";
				case "IPv6" -> "Friends with IPv6 can join";
				case "Same Wi-Fi" -> "Only people on your Wi-Fi can join";
				default -> "Ready";
			};
			statusFit(g, left + 12, top + 9, stateColor(best.state()), headline, textW, Theme.TEXT);
			String invite = best.invite();
			float scale = inviteScale(invite);
			Draw.scaledText(g, invite, left + 12, top + 26 + Math.round((1.5f - scale) * 4), scale, Theme.ACCENT);
			Draw.textFit(g, "Invites find a way in.", textW, left + 12, top + 44, Theme.FAINT);
		}
	}

	private void drawPlayers(GuiGraphicsExtractor g, int left, int top) {
		int rowY = top + playersY + 2;
		card(g, left, rowY - 2, width, guests.isEmpty() ? ROW + 4 : guests.size() * PLAYER_ROW + 4);
		if (guests.isEmpty()) {
			Icons.USER.draw(g, left + 9, rowY + 6, Theme.FAINT);
			Draw.textFit(g, "No one yet. Invite friends to play.", width - 34, left + 24, rowY + 7, Theme.MUTED);
		}
		for (int i = 0; i < guests.size(); i++) {
			Guest guest = guests.get(i);
			if (i > 0) {
				g.fill(left + 8, rowY - 1, left + width - 8, rowY, Theme.withAlpha(Theme.BORDER, 0.6f));
			}
			Faces.draw(g, guest.id(), left + 8, rowY + 5, 18);
			int textW = width - 32 - 108;
			Draw.textFit(g, guest.name(), textW, left + 32, rowY + 5, Theme.TEXT);
			int ping = guest.pingMs();
			String pingText = ping <= 0 ? "ping —" : ping + " ms";
			Draw.text(g, pingText, left + 32, rowY + 15,
					ping <= 0 ? Theme.FAINT : ping < 80 ? Theme.ACCENT : ping < 200 ? Theme.WARNING : Theme.DANGER);
			if (guest.visitor() || guest.trusted()) {
				Draw.textFit(g, guest.visitor() ? "· visitor" : "· trusted", textW - Draw.font().width(pingText) - 4,
						left + 36 + Draw.font().width(pingText), rowY + 15, guest.visitor() ? Theme.WARNING : Theme.MUTED);
			}
			rowY += PLAYER_ROW;
		}

		String packs = HostController.packStatus();
		if (!packs.isEmpty()) {
			Icons.PACKAGE.draw(g, left, top + footerY, Theme.MUTED);
			Draw.textFit(g, packs, width - 14, left + 13, top + footerY + 1, Theme.MUTED);
		}
	}

	private static final java.time.format.DateTimeFormatter BACKUP_TIME = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")
			.withZone(java.time.ZoneId.systemDefault());

	private void drawBackups(GuiGraphicsExtractor g, int left, int top) {
		int y = top + backupsY;
		sectionLabel(g, "Backups", left, y);
		card(g, left, y + 12, width, 38 + Math.max(1, backups.size()) * BACKUP_ROW);
		int rowY = y + 50;
		if (backups.isEmpty()) {
			Draw.textFit(g, "No backups yet. One is made when a friend joins.", width - 20, left + 10, rowY + 6, Theme.FAINT);
		}
		for (WorldBackups.Backup backup : backups) {
			Icons.PACKAGE.draw(g, left + 10, rowY + 5, Theme.MUTED);
			String when = BACKUP_TIME.format(java.time.Instant.ofEpochMilli(backup.time())) + " · " + Math.max(1, backup.size() / (1024 * 1024)) + " MB";
			Draw.textFit(g, backup.world(), width - 190, left + 26, rowY + 2, Theme.TEXT);
			Draw.textFit(g, when, width - 120, left + 26, rowY + 12, Theme.FAINT);
			rowY += BACKUP_ROW;
		}
		if (backups.isEmpty()) {
			rowY += BACKUP_ROW;
		}
		if (backupMessage != null) {
			Draw.textFit(g, backupMessage, width, left, rowY + 32, backupMessage.startsWith("Couldn't") ? Theme.WARNING : Theme.ACCENT);
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

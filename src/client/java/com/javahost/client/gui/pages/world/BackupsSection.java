package com.javahost.client.gui.pages.world;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.WorldPickerScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.host.WorldBackups;
import com.javahost.client.share.WorldShare;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/** Automatic backups, the newest ones (restore with no world open, or send one to a friend), Back up now and the folder. */
public final class BackupsSection extends WorldSection {
	private static final int ROW = 22;
	private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")
			.withZone(java.time.ZoneId.systemDefault());

	private int sectionY;
	private List<WorldBackups.Backup> backups = List.of();
	private java.nio.file.Path confirmRestore;
	private long confirmUntil;
	private String message;

	public BackupsSection(Host host) {
		super(host);
	}

	@Override
	protected int layout(int y) {
		Config config = Config.get();
		sectionY = y;
		backups = WorldBackups.listAll().stream().limit(8).toList();
		add(new ToggleSwitch(width - 2 * PAD, Component.literal("Back up when friends join"), "Every 30 min at most; 5 kept",
				config.autoBackup, v -> {
					config.autoBackup = v;
					config.save();
				}), PAD, y + LABEL + 8);
		boolean worldOpen = minecraft.getSingleplayerServer() != null;
		int rowY = y + LABEL + 40;
		for (WorldBackups.Backup backup : backups) {
			boolean confirming = backup.file().equals(confirmRestore) && System.currentTimeMillis() < confirmUntil;
			int restoreW = confirming ? 96 : 64;
			FlatButton restore = add(new FlatButton(restoreW, 16, Component.literal(confirming ? "Click again" : "Restore"),
					confirming ? FlatButton.Style.DANGER : FlatButton.Style.SECONDARY, null, b -> restore(backup)), width - restoreW - PAD, rowY + 3);
			if (!WorldShare.reachableFriends().isEmpty()) {
				FlatButton send = add(new FlatButton(18, 16, Component.literal("Send"), FlatButton.Style.GHOST, Icons.UPLOAD,
						b -> minecraft.gui.setScreen(new WorldPickerScreen(screen, null, backup.file(),
								() -> screen.switchTo(JavaHostScreen.Tab.NOTIFICATIONS)))).iconOnly(), width - restoreW - PAD - 22, rowY + 3);
				send.setTooltip(tip("Send this backup to a friend in this world"));
			}
			restore.active = !worldOpen;
			restore.setTooltip(tip(worldOpen ? "Leave the world to restore a backup"
					: "Your current \"" + backup.world() + "\" is kept as a copy, then this backup takes its place"));
			rowY += ROW;
		}
		if (backups.isEmpty()) {
			rowY += ROW;
		}
		int half = (width - 8) / 2;
		FlatButton now = add(new FlatButton(half, 18, Component.literal("Back up now"), FlatButton.Style.SECONDARY, Icons.PACKAGE, b -> {
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server != null) {
				message = "Backing up...";
				server.execute(() -> WorldBackups.backUp(server, "you asked").whenComplete((done, err) -> minecraft.execute(() -> {
					message = err == null ? "Backed up." : "Couldn't back up.";
					screen.refresh();
				})));
				screen.refresh();
			}
		}), 0, rowY + 12);
		now.active = worldOpen;
		add(new FlatButton(width - half - 8, 18, Component.literal("Open folder"), FlatButton.Style.GHOST, Icons.FOLDER, b -> {
			try {
				java.nio.file.Files.createDirectories(WorldBackups.root());
			} catch (java.io.IOException ignored) {
				// Opening the folder below still helps.
			}
			com.javahost.client.compat.ClientCompat.openPath(WorldBackups.root());
		}), half + 8, rowY + 12);
		return rowY + 12 + 18 + (message != null ? 18 : 0);
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
			message = "Restored " + backup.world() + (aside != null ? ". Your previous copy is \"" + aside + "\"." : ".");
		} catch (java.io.IOException e) {
			message = "Couldn't restore: " + e.getMessage();
		}
		screen.refresh();
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int y = top + sectionY;
		label(g, "Backups", left, y);
		int c = y + LABEL;
		card(g, left, c, width, 40 + Math.max(1, backups.size()) * ROW + 2);
		divider(g, left, c + 38, width);
		int rowY = c + 40;
		if (backups.isEmpty()) {
			Draw.textFit(g, "No backups yet. One is made when a friend joins.", width - 2 * PAD, left + PAD, rowY + 7, Theme.FAINT);
		}
		for (WorldBackups.Backup backup : backups) {
			Icons.PACKAGE.draw(g, left + PAD, rowY + 5, Theme.MUTED);
			String when = TIME.format(java.time.Instant.ofEpochMilli(backup.time())) + " · " + Math.max(1, backup.size() / (1024 * 1024)) + " MB";
			Draw.textFit(g, backup.world(), width - 190, left + PAD + 16, rowY + 2, Theme.TEXT);
			Draw.textFit(g, when, width - 120, left + PAD + 16, rowY + 12, Theme.FAINT);
			rowY += ROW;
		}
		if (backups.isEmpty()) {
			rowY += ROW;
		}
		if (message != null) {
			Draw.textFit(g, message, width, left, rowY + 12 + 18 + 8, message.startsWith("Couldn't") ? Theme.WARNING : Theme.ACCENT);
		}
	}
}

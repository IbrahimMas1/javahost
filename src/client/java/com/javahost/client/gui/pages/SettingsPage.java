package com.javahost.client.gui.pages;

import java.util.List;
import java.util.Map;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.SwatchButton;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public class SettingsPage extends Page {
	private static final List<KeyMapping> KEYS = List.of(JavaHostClient.OPEN_MENU, JavaHostClient.PUSH_TO_TALK,
			JavaHostClient.TOGGLE_MUTE, JavaHostClient.PARTY_VOICE, JavaHostClient.SCREENSHOT_SHARE, JavaHostClient.TOGGLE_OVERLAY);

	/** Rows in a card start this far below the card's top; cards end this far below their last row. */
	private static final int CARD_PAD = 6;
	/** Space from the end of one card to the next section's label. */
	private static final int SECTION_GAP = 12;
	/** The Look card: accent, text and icons, overlay position, title screen. */
	private static final int LOOK_H = 110;
	/** The Voice row at the bottom of the Friends & voice card. */
	private static final int VOICE_ROW = 28;

	@Override
	public String title() {
		return "Settings";
	}

	@Override
	public String subtitle() {
		return "Colours, sounds and keys.";
	}

	// Where each section starts (relative to the page top); worked out in build(), used by draw().
	private int playingY;
	private int playingH;
	private int friendsY;
	private int friendsH;
	private int videoY = -1;
	private int keysTop;

	/** Adds a row of switches to a card starting at {@code y} (the section label's y); returns the card's height. */
	private int switches(int y, List<ToggleSwitch> rows) {
		int rowY = y + 12 + CARD_PAD;
		for (ToggleSwitch row : rows) {
			add(row, 10, rowY);
			rowY += row.getHeight() + 4;
		}
		return rowY - (y + 12);
	}

	/** The top of the "Voice chat" row at the bottom of the Friends & voice card. */
	private int voiceRowY() {
		return friendsY + 12 + friendsH - VOICE_ROW;
	}

	private static ToggleSwitch toggle(int width, String label, String hint, boolean value, java.util.function.Consumer<Boolean> set) {
		return new ToggleSwitch(width, Component.literal(label), hint, value, v -> {
			set.accept(v);
			Config.get().save();
		});
	}

	@Override
	protected void build() {
		Config config = Config.get();
		int inner = width - 20;

		// Look: only how JavaHost looks.
		int x = width - 10 - Theme.ACCENTS.size() * 24;
		for (Map.Entry<String, Integer> accent : Theme.ACCENTS.entrySet()) {
			add(new SwatchButton(20, Component.literal(accent.getKey()), accent.getValue(), () -> config.accent.equals(accent.getKey()), b -> {
				config.accent = accent.getKey();
				Theme.apply(config.accent);
				config.save();
				screen.refresh();
			}), x, 18);
			x += 24;
		}
		add(new Segmented<>(140, List.of("PIXEL", "SMOOTH"), com.javahost.client.gui.ui.Fonts.mode().name(),
				font -> font.equals("PIXEL") ? "Pixel" : "Smooth", font -> {
					config.menuFont = font;
					config.save();
					screen.refresh();
				}), width - 150, 46);
		add(new Segmented<>(140, List.of("TOP_LEFT", "TOP_RIGHT"), config.hudCorner,
				corner -> corner.equals("TOP_LEFT") ? "Top left" : "Top right", corner -> {
					config.hudCorner = corner;
					config.save();
				}), width - 150, 72);
		// What JavaHost adds to Minecraft's own screens (used the next time they open).
		add(new Segmented<>(140, List.of("FULL", "DOCK", "OFF"), config.titleScreen,
				mode -> mode.equals("FULL") ? "Full" : mode.equals("DOCK") ? "Dock" : "Off", mode -> {
					config.titleScreen = mode;
					config.save();
				}), width - 150, 98);

		// While playing: what JavaHost shows (or doesn't) on top of the game.
		playingY = 12 + LOOK_H + SECTION_GAP;
		playingH = switches(playingY, List.of(
				toggle(inner, "Quiet notifications", "Pop-ups only for invites, messages and problems. The rest waits under the bell",
						config.quiet, v -> config.quiet = v).wrapHint(),
				toggle(inner, "Notification sounds", "A soft chime for invites, requests and messages",
						config.notificationSounds, v -> config.notificationSounds = v),
				toggle(inner, "Voice status on screen", "Muted and Party voice in a corner",
						config.showVoiceStatus, v -> config.showVoiceStatus = v),
				toggle(inner, "Show who's talking", "Faces of speaking players on screen",
						config.showVoiceOverlay, v -> config.showVoiceOverlay = v),
				toggle(inner, "FPS overlay", "A small frame-rate counter in a corner",
						config.fpsOverlay, v -> config.fpsOverlay = v),
				toggle(inner, "Buttons on the pause menu", "Home, Friends and World beside it",
						config.pauseDock, v -> config.pauseDock = v)));

		// Friends & voice
		friendsY = playingY + 12 + playingH + SECTION_GAP;
		int packsH = switches(friendsY, List.of(
				toggle(inner, "Always use the host's packs", "Don't ask when a host shares packs",
						config.autoAcceptHostPacks, v -> config.autoAcceptHostPacks = v)));
		friendsH = packsH + VOICE_ROW;
		add(new FlatButton(VOICE_BUTTON, 18, Component.literal("Voice settings"), FlatButton.Style.SECONDARY, Icons.MIC,
				b -> screen.switchTo(com.javahost.client.gui.JavaHostScreen.Tab.VOICE)), width - 10 - VOICE_BUTTON, voiceRowY() + 3);
		int next = friendsY + 12 + friendsH + SECTION_GAP;
		videoY = -1;
		if (com.javahost.client.perf.VideoMenu.sodiumInstalled()) {
			videoY = next;
			add(new Segmented<>(150, List.of("SIMPLE", "SODIUM"), config.videoMenu, m -> m.equals("SIMPLE") ? "Simple" : "Sodium", m -> {
				config.videoMenu = m;
				config.save();
			}), width - 160, videoY + 18);
			next = videoY + 12 + 30 + SECTION_GAP;
		}
		keysTop = next;
		// Updates and help, under the keys.
		updatesY = keysTop + 12 + KEYS.size() * 20 + 46;
		int half = (inner - 6) / 2;
		var update = com.javahost.client.update.UpdateChecker.available();
		if (com.javahost.client.update.Updater.staged()) {
			// Something happens when Minecraft closes: let the player change their mind.
			boolean rollback = "rollback".equals(com.javahost.client.update.Updater.stagedKind());
			add(new FlatButton(half, 18, Component.literal(rollback ? "Cancel going back" : "Cancel update"), FlatButton.Style.SECONDARY,
					Icons.CROSS, b -> {
						com.javahost.client.update.Updater.cancelStaged();
						com.javahost.client.Toasts.show("update", rollback ? "Staying on " + com.javahost.JavaHost.VERSION : "Update cancelled",
								rollback ? "Nothing changes when Minecraft closes." : "You'll be offered it again next launch.");
						screen.refresh();
					}), 10, updatesY + 34);
		} else {
			if (com.javahost.util.Distribution.fromModSite()) {
				// Copies from CurseForge or Modrinth are updated by the site and the launcher: nothing to press here.
			} else if (update != null) {
				add(new FlatButton(half, 18, Component.literal("Update to " + update.version()), FlatButton.Style.PRIMARY, Icons.CHECK,
						b -> minecraft.gui.setScreen(new com.javahost.client.update.UpdateScreen(screen, update))), 10, updatesY + 34);
			} else {
				FlatButton check = add(new FlatButton(half, 18, Component.literal(com.javahost.client.update.UpdateChecker.checking() ? "Checking..." : "Check for updates"),
						FlatButton.Style.SECONDARY, Icons.SPARKLE, b -> {
							checkedManually = true;
							com.javahost.client.update.UpdateChecker.checkNow(screen::refresh);
							screen.refresh();
						}), 10, updatesY + 34);
				check.active = !com.javahost.client.update.UpdateChecker.checking();
			}
			String previous = com.javahost.client.update.Updater.previousVersion();
			if (previous != null) {
				String blocked = com.javahost.client.update.Updater.rollBackBlockedReason(com.javahost.client.update.UpdateChecker.latest());
				FlatButton restore = add(new FlatButton(half, 18, Component.literal(confirmRollback ? "Click again to confirm" : "Restore " + previous),
						confirmRollback ? FlatButton.Style.DANGER : FlatButton.Style.GHOST, blocked != null ? Icons.LOCK : Icons.BACK, b -> {
							if (!confirmRollback) {
								confirmRollback = true;
								screen.refresh();
								return;
							}
							try {
								com.javahost.client.update.Updater.stageRollBack();
								com.javahost.client.Toasts.notify("Going back to JavaHost " + previous,
										"It's restored when Minecraft closes. Changed your mind? Settings → Cancel going back.");
							} catch (java.io.IOException e) {
								com.javahost.client.Toasts.notify("Couldn't go back", String.valueOf(e.getMessage()));
							}
							confirmRollback = false;
							screen.refresh();
						}), 10 + half + 6, updatesY + 34);
				restore.active = blocked == null;
				restore.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled(blocked != null ? blocked
						: "Puts JavaHost " + previous + " back when Minecraft closes. You can cancel until then.")));
			}
		}
		helpY = updatesY + 70;
		add(new FlatButton(half, 18, Component.literal("Copy debug report"), FlatButton.Style.SECONDARY, Icons.COPY, b -> {
			minecraft.keyboardHandler.setClipboard(com.javahost.client.DebugReport.build());
			com.javahost.client.Toasts.show("Debug report copied", "Paste it wherever you're asking for help.");
		}), 10, helpY + 20);
		add(new FlatButton(half, 18, Component.literal("Welcome tour"), FlatButton.Style.SECONDARY, Icons.PLAY,
				b -> screen.startTour()), 10 + half + 6, helpY + 20);

		// Blocked players: each with Unblock.
		blockedY = helpY + 52;
		blocked = new java.util.ArrayList<>(config.blockedPlayers.entrySet());
		blocked.sort(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER));
		int rowY = blockedY + 14;
		for (Map.Entry<String, String> entry : blocked) {
			add(new FlatButton(64, 16, Component.literal("Unblock"), FlatButton.Style.SECONDARY, null, b -> {
				com.javahost.client.friends.Friends.unblock(java.util.UUID.fromString(entry.getKey()));
				screen.refresh();
			}), width - 74, rowY + 3);
			rowY += 22;
		}
		reserve(blockedY + 14 + Math.max(1, blocked.size()) * 22 + 10);
	}

	private static final int VOICE_BUTTON = 104;

	private int blockedY;
	private List<Map.Entry<String, String>> blocked = List.of();

	private int updatesY;
	private int helpY;
	private boolean checkedManually;
	private boolean confirmRollback;
	private String lastUpdateState = "";

	private String updateState() {
		var update = com.javahost.client.update.UpdateChecker.available();
		return com.javahost.client.update.UpdateChecker.checking() + "|" + (update == null ? "" : update.version()) + "|"
				+ com.javahost.client.update.Updater.staged() + "|" + com.javahost.client.update.Updater.stagedKind();
	}

	@Override
	public void tick() {
		if (!updateState().equals(lastUpdateState)) {
			lastUpdateState = updateState();
			screen.refresh();
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "Look", left, top);
		card(g, left, top + 12, width, LOOK_H);
		Draw.text(g, "Accent colour", left + 10, top + 24, Theme.TEXT);
		Draw.text(g, "Text and icons", left + 10, top + 51, Theme.TEXT);
		Draw.text(g, "Overlay position", left + 10, top + 77, Theme.TEXT);
		Draw.textFit(g, "Title screen", width - 170, left + 10, top + 103, Theme.TEXT);

		sectionLabel(g, "While playing", left, top + playingY);
		card(g, left, top + playingY + 12, width, playingH);

		sectionLabel(g, "Friends & voice", left, top + friendsY);
		card(g, left, top + friendsY + 12, width, friendsH);
		// Voice has its own page; a way there from here.
		int voiceY = top + voiceRowY();
		Draw.textFit(g, "Voice chat", width - VOICE_BUTTON - 28, left + 10, voiceY + 4, Theme.TEXT);
		Draw.textFit(g, "Microphone, push to talk, volume", width - VOICE_BUTTON - 28, left + 10, voiceY + 15, Theme.MUTED);

		if (videoY >= 0) {
			sectionLabel(g, "Video", left, top + videoY);
			card(g, left, top + videoY + 12, width, 30);
			Draw.textFit(g, "Video settings menu", width - 180, left + 10, top + videoY + 23, Theme.TEXT);
		}
		int keysTop = top + this.keysTop;
		sectionLabel(g, "Keys", left, keysTop);
		card(g, left, keysTop + 12, width, KEYS.size() * 20 + 8);
		int y = keysTop + 20;
		for (KeyMapping key : KEYS) {
			Draw.text(g, Component.translatable(key.getName()), left + 10, y + 3, Theme.TEXT);
			String bound = key.isUnbound() ? "Not set" : key.getTranslatedKeyMessage().getString();
			int w = Draw.width(bound) + 12;
			Draw.bordered(g, left + width - w - 10, y, w, 14, 3, Theme.FIELD, Theme.BORDER);
			Draw.text(g, bound, left + width - w - 4, y + 3, key.isUnbound() ? Theme.FAINT : Theme.ACCENT);
			y += 20;
		}
		Draw.textFit(g, "Change keys in Options → Controls → Key Binds → JavaHost.", width, left, y + 12, Theme.FAINT);

		sectionLabel(g, "Updates", left, top + updatesY);
		card(g, left, top + updatesY + 12, width, 46);
		var update = com.javahost.client.update.UpdateChecker.available();
		String status;
		int color = Theme.MUTED;
		if (com.javahost.util.Distribution.fromModSite()) {
			status = "JavaHost " + com.javahost.JavaHost.VERSION + " · from " + com.javahost.util.Distribution.siteName();
		} else if (com.javahost.client.update.Updater.staged()) {
			boolean rollback = "rollback".equals(com.javahost.client.update.Updater.stagedKind());
			status = rollback ? "Going back to " + com.javahost.client.update.Updater.stagedVersion() + " when Minecraft closes"
					: "JavaHost " + com.javahost.client.update.Updater.stagedVersion() + " installs when Minecraft closes";
			color = rollback ? Theme.WARNING : Theme.ACCENT;
		} else if (com.javahost.client.update.UpdateChecker.checking()) {
			status = "Checking for updates...";
		} else if (update != null) {
			status = (com.javahost.client.update.UpdateChecker.important() ? "Important update: JavaHost " : "JavaHost ") + update.version()
					+ " is available (you have " + com.javahost.JavaHost.VERSION + ")";
			color = Theme.ACCENT;
		} else {
			status = "JavaHost " + com.javahost.JavaHost.VERSION + (checkedManually ? " · you're up to date" : " · checks automatically");
		}
		Draw.textFit(g, status, width - 20, left + 10, top + updatesY + 22, color);
		if (com.javahost.util.Distribution.fromModSite()) {
			// Where the buttons would be: how this copy gets new versions.
			Draw.textFit(g, "Updates come through your launcher.", width - 20,
					left + 10, top + updatesY + 38, Theme.FAINT);
		}

		sectionLabel(g, "Help", left, top + helpY);
		card(g, left, top + helpY + 12, width, 30);

		sectionLabel(g, "Blocked players", left, top + blockedY);
		int y2 = top + blockedY + 12;
		card(g, left, y2, width, Math.max(1, blocked.size()) * 22 + 4);
		if (blocked.isEmpty()) {
			Draw.textFit(g, "Nobody blocked.", width - 20, left + 10, y2 + 8, Theme.FAINT);
		}
		for (Map.Entry<String, String> entry : blocked) {
			try {
				com.javahost.client.gui.ui.Faces.draw(g, java.util.UUID.fromString(entry.getKey()), left + 8, y2 + 5, 14);
			} catch (IllegalArgumentException ignored) {
				// A hand-edited entry; just show the name.
			}
			Draw.textFit(g, entry.getValue(), width - 120, left + 28, y2 + 8, Theme.TEXT);
			y2 += 22;
		}
	}
}

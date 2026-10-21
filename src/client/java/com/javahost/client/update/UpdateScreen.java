package com.javahost.client.update;

import java.util.List;

import com.javahost.JavaHost;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.util.UpdateManifest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * "JavaHost 1.5.1 is out": Update now, or Not now. Not now explains whether you can still play with friends who
 * updated, then lets you keep your version until the next launch. Updating downloads and verifies the new files
 * here; they're installed when Minecraft closes. When a launcher (CurseForge, Modrinth App...) installed JavaHost, it
 * only says what's new and to update there, since that launcher keeps track of the mods folder.
 */
public class UpdateScreen extends com.javahost.client.gui.BaseScreen {
	private enum Stage { OFFER, LAUNCHER, NEEDS_INSTALLER, COMPATIBILITY, DOWNLOADING, READY, FAILED }

	private final Screen behind;
	private final UpdateManifest update;
	/** Older than the minimum safe version: this update can't be skipped (only put off for a few minutes). */
	private final boolean important;
	private Stage stage;
	private volatile double progress;
	private volatile String error;
	private int boxX;
	private int boxY;
	private int boxW;
	private int boxH;

	public UpdateScreen(Screen behind, UpdateManifest update) {
		super(Component.literal("JavaHost update"));
		this.behind = behind;
		// The build for this Minecraft version, if the release has one.
		UpdateManifest mine = update.forMinecraft(minecraftVersion());
		this.update = mine != null ? mine : update;
		this.stage = !Updater.selfUpdates() ? Stage.LAUNCHER : mine != null ? Stage.OFFER : Stage.NEEDS_INSTALLER;
		this.important = update.requires(JavaHost.VERSION);
	}

	public static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
	}

	private boolean compatible() {
		return update.protocol() == JavaHost.PROTOCOL;
	}

	private String headline() {
		return switch (stage) {
			case OFFER, LAUNCHER, NEEDS_INSTALLER -> important ? "Important update: JavaHost " + update.version() : "JavaHost " + update.version() + " is out";
			case COMPATIBILITY -> compatible() ? "You can keep playing together" : "Friends on " + update.version() + " can't play with you";
			case DOWNLOADING -> "Downloading JavaHost " + update.version();
			case READY -> "Ready to install";
			case FAILED -> "Couldn't download the update";
		};
	}

	private String body() {
		return switch (stage) {
			case OFFER -> (important ? "This update fixes something important, so it can't be skipped. " : "")
					+ "You have " + JavaHost.VERSION + " (" + Math.max(1, update.totalSize() / (1024 * 1024)) + " MB to download)."
					+ (update.notes().isEmpty() ? "" : "\n\n" + update.notes());
			case LAUNCHER -> (important ? "This update fixes something important. " : "") + "You have " + JavaHost.VERSION
					+ ". Your launcher installed JavaHost, so update it there (in the CurseForge app, for example)."
					+ (update.notes().isEmpty() ? "" : "\n\n" + update.notes());
			case NEEDS_INSTALLER -> "JavaHost " + update.version() + " isn't made for Minecraft " + minecraftVersion() + ". It's for "
					+ String.join(", ", update.minecraftVersions()) + ". Use the JavaHost installer to switch Minecraft version.";
			case COMPATIBILITY -> compatible()
					? "Friends on " + update.version() + " can still join your worlds, and you can join theirs. Some new features need you both on "
							+ update.version() + "."
					: "Friends who updated won't be able to join your worlds, and you won't be able to join theirs, until you update too.";
			case DOWNLOADING -> "Checking every file as it arrives. You can keep using the menu.";
			case READY -> "JavaHost " + update.version() + " is downloaded and checked. It installs when Minecraft closes; open it again afterwards.";
			case FAILED -> (error == null ? "Something went wrong." : error) + " Nothing was changed.";
		};
	}

	@Override
	protected void init() {
		boxW = Math.min(width - 20, 300);
		List<FormattedCharSequence> lines = Draw.font().split(Component.literal(body()), boxW - 32);
		int maxLines = Math.max(3, (height - 120) / 10);
		boxH = 52 + Math.min(lines.size(), maxLines) * 10 + (stage == Stage.DOWNLOADING ? 14 : 0) + 40;
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;
		int buttonW = (boxW - 40) / 2;
		int buttonY = boxY + boxH - 32;
		switch (stage) {
			case OFFER -> {
				if (important) {
					buttons(buttonW, buttonY, "Remind me soon", this::keep, "Update now", this::startDownload, Icons.CHECK);
				} else {
					buttons(buttonW, buttonY, "Not now", () -> go(Stage.COMPATIBILITY), "Update now", this::startDownload, Icons.CHECK);
				}
			}
			case LAUNCHER -> buttons(buttonW, buttonY, important ? "Remind me soon" : "OK", this::keep, "What's new",
					() -> ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(update.releasePage())), Icons.SPARKLE);
			case NEEDS_INSTALLER -> buttons(buttonW, buttonY, "Not now", this::keep, "Get installer",
					() -> ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(update.releasePage())), Icons.PACKAGE);
			case COMPATIBILITY -> buttons(buttonW, buttonY, "Keep " + JavaHost.VERSION, this::keep, "Update now", this::startDownload, Icons.CHECK);
			case READY -> buttons(buttonW, buttonY, "Later", this::closeToBehind, "Close Minecraft", () -> minecraft.stop(), Icons.CHECK);
			case FAILED -> buttons(buttonW, buttonY, "Close", this::keep, "Try again", this::startDownload, Icons.CHECK);
			case DOWNLOADING -> {
			}
		}
	}

	private void buttons(int w, int y, String no, Runnable onNo, String yes, Runnable onYes, Icons yesIcon) {
		addRenderableWidget(new FlatButton(boxX + 16, y, w, 22, Component.literal(no), FlatButton.Style.SECONDARY, null, b -> onNo.run()));
		addRenderableWidget(new FlatButton(boxX + boxW - 16 - w, y, w, 22, Component.literal(yes), FlatButton.Style.PRIMARY, yesIcon, b -> onYes.run()));
	}

	private void go(Stage next) {
		stage = next;
		rebuildWidgets();
	}

	private void startDownload() {
		error = null;
		progress = 0;
		go(Stage.DOWNLOADING);
		Updater.download(update, p -> progress = p).whenComplete((ok, failure) -> minecraft.execute(() -> {
			if (failure != null) {
				Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
				error = cause.getMessage();
				JavaHost.LOGGER.warn("[Update] download failed", cause);
				go(Stage.FAILED);
			} else {
				go(Stage.READY);
			}
		}));
	}

	private void keep() {
		UpdateChecker.keepForNow();
		closeToBehind();
	}

	private void closeToBehind() {
		minecraft.gui.setScreen(behind);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		background(g, mouseX, mouseY, a);
		g.fill(0, 0, width, height, Theme.SCRIM_GLASS);
		Draw.glass(g, boxX, boxY, boxW, boxH, 10);
		Icons icon = switch (stage) {
			case COMPATIBILITY -> compatible() ? Icons.USERS : Icons.WARNING;
			case FAILED, NEEDS_INSTALLER -> Icons.WARNING;
			case OFFER, LAUNCHER -> important ? Icons.SHIELD : Icons.SPARKLE;
			case READY -> Icons.CHECK;
			default -> Icons.SPARKLE;
		};
		int iconColor = stage == Stage.FAILED || (stage == Stage.COMPATIBILITY && !compatible()) || stage == Stage.NEEDS_INSTALLER
				? Theme.WARNING : Theme.ACCENT;
		Draw.roundRect(g, boxX + 16, boxY + 16, 22, 22, 6, Theme.withAlpha(iconColor, 0.18f));
		icon.drawCentred(g, boxX + 27, boxY + 27, 14, iconColor);
		Draw.scaledText(g, Draw.fit(headline(), (int) ((boxW - 64) / 1.15f)), boxX + 46, boxY + 22, 1.15f, Theme.TEXT);

		List<FormattedCharSequence> lines = Draw.font().split(Component.literal(body()), boxW - 32);
		int maxLines = Math.max(3, (height - 120) / 10);
		int y = boxY + 50;
		for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
			g.text(Draw.font(), lines.get(i), boxX + 16, y, Theme.MUTED, false);
			y += 10;
		}
		if (stage == Stage.DOWNLOADING) {
			Draw.roundRect(g, boxX + 16, y + 6, boxW - 32, 5, 2, Theme.FIELD);
			Draw.roundRect(g, boxX + 16, y + 6, Math.max(5, (int) Math.round((boxW - 32) * progress)), 5, 2, Theme.ACCENT);
			Draw.text(g, Math.round(progress * 100) + "%", boxX + boxW - 16 - Draw.font().width(Math.round(progress * 100) + "%"), boxY + boxH - 26, Theme.FAINT);
		}
		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return stage != Stage.DOWNLOADING;
	}

	@Override
	public void onClose() {
		if (stage == Stage.READY) {
			closeToBehind();
		} else {
			keep();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

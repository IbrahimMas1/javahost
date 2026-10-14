package com.javahost.client.gui;

import java.util.List;

import com.javahost.JavaHost;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.mods.ModSets;
import com.javahost.client.mods.PendingJoin;
import com.javahost.util.IoExecutor;
import com.javahost.util.ModManifest;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * "AlexPlays's world uses mods": the mods a world needs that we don't have, with one button to download the very
 * same versions from Modrinth, switch to them when Minecraft restarts, and come straight back into the world.
 */
public class ModsNeededScreen extends Screen {
	private static final int ROW = 18;
	private static final int MAX_ROWS = 6;

	private enum Stage { ASK, DOWNLOADING, READY, FAILED }

	private final ModSets.Needed needed;
	private final List<ModManifest.Entry> fromModrinth;
	private final List<ModManifest.Entry> elsewhere;
	private Stage stage = Stage.ASK;
	private volatile String progress = "";
	private String problem = "";
	private int boxX;
	private int boxY;
	private int boxW;
	private int boxH;
	private int listY;

	public ModsNeededScreen(ModSets.Needed needed) {
		super(Component.literal("This world uses mods"));
		this.needed = needed;
		this.fromModrinth = needed.missing().stream().filter(ModManifest.Entry::onModrinth).toList();
		this.elsewhere = needed.missing().stream().filter(m -> !m.onModrinth()).toList();
	}

	private int rows() {
		return Math.min(needed.missing().size(), MAX_ROWS) + (needed.missing().size() > MAX_ROWS ? 1 : 0);
	}

	@Override
	protected void init() {
		boxW = Math.min(width - 20, 330);
		boxH = Math.min(height - 16, 86 + rows() * ROW + (elsewhere.isEmpty() ? 0 : 14) + 46);
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;
		listY = boxY + 62;
		int buttonW = (boxW - 40) / 2;
		int buttonY = boxY + boxH - 30;
		switch (stage) {
			case ASK, FAILED -> {
				addRenderableWidget(new FlatButton(boxX + 16, buttonY, buttonW, 20, Component.literal("Not now"), FlatButton.Style.SECONDARY, null,
						b -> close()));
				FlatButton get = addRenderableWidget(new FlatButton(boxX + boxW - 16 - buttonW, buttonY, buttonW, 20,
						Component.literal(stage == Stage.FAILED ? "Try again" : "Download & restart"), FlatButton.Style.PRIMARY, Icons.UPLOAD,
						b -> download()));
				get.active = !fromModrinth.isEmpty();
			}
			case DOWNLOADING -> {
			}
			case READY -> {
				addRenderableWidget(new FlatButton(boxX + 16, buttonY, buttonW, 20, Component.literal("Later"), FlatButton.Style.SECONDARY, null,
						b -> close()));
				addRenderableWidget(new FlatButton(boxX + boxW - 16 - buttonW, buttonY, buttonW, 20, Component.literal("Close Minecraft"),
						FlatButton.Style.PRIMARY, Icons.CHECK, b -> minecraft.stop()));
			}
		}
		if (stage == Stage.ASK && !elsewhere.isEmpty()) {
			addRenderableWidget(new FlatButton(boxX + boxW - 16 - 90, boxY + boxH - 50, 90, 14, Component.literal("Mods folder"),
					FlatButton.Style.GHOST, Icons.FOLDER, b -> com.mojang.blaze3d.Blaze3D.openPath(
							net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods"))));
		}
	}

	private void download() {
		stage = Stage.DOWNLOADING;
		progress = "Starting...";
		rebuildWidgets();
		IoExecutor.NET.execute(() -> {
			try {
				String folder = ModSets.download(needed, text -> progress = text);
				ModSets.stageSwitch(folder);
				PendingJoin.save(needed);
				minecraft.execute(() -> {
					stage = Stage.READY;
					rebuildWidgets();
				});
			} catch (Exception e) {
				JavaHost.LOGGER.warn("[Mods] download failed", e);
				minecraft.execute(() -> {
					stage = Stage.FAILED;
					problem = e.getMessage() == null ? "The download failed." : e.getMessage();
					rebuildWidgets();
				});
			}
		});
	}

	private void close() {
		ModSets.clearNeeded();
		minecraft.gui.setScreen(new TitleScreen());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, Theme.SCRIM_GLASS);
		Draw.glass(g, boxX, boxY, boxW, boxH, 10);
		Icons.PACKAGE.draw(g, boxX + 16, boxY + 16, 18, Theme.ACCENT);
		int textW = boxW - 58;
		switch (stage) {
			case READY -> {
				Draw.scaledText(g, Draw.fit("Ready to play with " + needed.hostName(), (int) (textW / 1.25f)), boxX + 42, boxY + 16, 1.25f, Theme.TEXT);
				Draw.textFit(g, "Close Minecraft, then press Play: you'll go", textW, boxX + 42, boxY + 30, Theme.MUTED);
				Draw.textFit(g, "straight back into their world.", textW, boxX + 42, boxY + 40, Theme.MUTED);
			}
			default -> {
				Draw.scaledText(g, Draw.fit(needed.hostName() + "'s world uses mods", (int) (textW / 1.25f)), boxX + 42, boxY + 16, 1.25f, Theme.TEXT);
				Draw.textFit(g, needed.missing().size() == 1 ? "Get " + needed.missing().getFirst().name() + " to play together."
						: "Get the same " + needed.missing().size() + " mods to play together.", textW, boxX + 42, boxY + 30, Theme.MUTED);
				Draw.textFit(g, "Your own mods are kept, and come back any time.", textW, boxX + 42, boxY + 40, Theme.MUTED);
			}
		}

		// The list.
		Draw.panel(g, boxX + 12, listY - 2, boxW - 24, rows() * ROW + 4, 6, Theme.CARD);
		int y = listY;
		for (int i = 0; i < Math.min(needed.missing().size(), MAX_ROWS); i++) {
			ModManifest.Entry mod = needed.missing().get(i);
			boolean ok = mod.onModrinth();
			(ok ? Icons.PACKAGE : Icons.WARNING).draw(g, boxX + 20, y + 4, ok ? Theme.ACCENT : Theme.WARNING);
			Draw.textFit(g, mod.name(), boxW - 160, boxX + 36, y + 5, Theme.TEXT);
			String right = ok ? String.format("%.1f MB", mod.size() / 1048576.0) : "Not on Modrinth";
			int rightX = boxX + boxW - 22 - Draw.font().width(right);
			Draw.text(g, right, rightX, y + 5, ok ? Theme.MUTED : Theme.WARNING);
			Draw.textFit(g, mod.version(), rightX - (boxX + boxW - 160 + 40) - 6, boxX + boxW - 160 + 40, y + 5, Theme.FAINT);
			y += ROW;
		}
		if (needed.missing().size() > MAX_ROWS) {
			Draw.text(g, "and " + (needed.missing().size() - MAX_ROWS) + " more", boxX + 36, y + 5, Theme.FAINT);
			y += ROW;
		}

		// What happens next.
		int noteY = y + 8;
		switch (stage) {
			case ASK -> {
				long bytes = needed.manifest().totalSize(fromModrinth);
				Draw.textFit(g, String.format("%.1f MB from Modrinth, checked file by file.", bytes / 1048576.0), boxW - 32, boxX + 16, noteY, Theme.FAINT);
				if (!elsewhere.isEmpty()) {
					Icons.WARNING.draw(g, boxX + 16, noteY + 13, Theme.WARNING);
					Draw.textFit(g, Draw.count(elsewhere.size(), "mod") + " to get yourself (click it)", boxW - 140, boxX + 30, noteY + 14,
							Theme.WARNING);
				}
			}
			case DOWNLOADING -> Draw.textFit(g, progress, boxW - 32, boxX + 16, noteY, Theme.ACCENT);
			case FAILED -> {
				Icons.WARNING.draw(g, boxX + 16, noteY - 1, Theme.WARNING);
				Draw.textFit(g, problem, boxW - 50, boxX + 32, noteY, Theme.WARNING);
			}
			case READY -> Draw.textFit(g, "Downloaded. Mods switch when Minecraft closes.", boxW - 32, boxX + 16, noteY, Theme.ACCENT);
		}
		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		// A mod that isn't on Modrinth: its row opens the page it came from (after Minecraft's usual link check).
		if (stage == Stage.ASK && event.y() >= listY && event.x() >= boxX + 12 && event.x() < boxX + boxW - 12) {
			int index = (int) ((event.y() - listY) / ROW);
			if (index >= 0 && index < Math.min(needed.missing().size(), MAX_ROWS)) {
				ModManifest.Entry mod = needed.missing().get(index);
				if (!mod.onModrinth() && !mod.page().isEmpty()) {
					ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(mod.page()));
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return stage != Stage.DOWNLOADING;
	}

	@Override
	public void onClose() {
		close();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

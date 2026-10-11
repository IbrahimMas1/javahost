package com.javahost.client.gui.pages;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.perf.BoostMods;
import com.javahost.client.perf.BoostMods.BoostMod;
import com.javahost.client.perf.QuickTune;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public class PerformancePage extends Page {
	private static final int ROW = 24;
	private static volatile String installStatus = "";
	private static volatile boolean installing;
	private static volatile boolean needsRestart;

	private int modsY;
	private int extrasY;
	private String lastSignature = "";

	@Override
	public String title() {
		return "Performance";
	}

	@Override
	public String subtitle() {
		return "The mods most players use for more FPS.";
	}

	private static List<BoostMod> recommended() {
		return BoostMods.MODS.stream().filter(BoostMod::recommended).toList();
	}

	private static List<BoostMod> extras() {
		return BoostMods.MODS.stream().filter(m -> !m.recommended()).toList();
	}

	@Override
	protected void build() {
		lastSignature = signature();
		add(new ToggleSwitch(width - 20, Component.literal("Quick Tune"), "Faster video settings; turn off to undo",
				QuickTune.isApplied(), on -> {
					if (on) {
						QuickTune.apply();
					} else {
						QuickTune.restore();
					}
				}), 10, 20);

		modsY = 64;
		boolean allInstalled = recommended().stream().allMatch(BoostMods::isInstalled);
		FlatButton installAll = add(new FlatButton(104, 16, Component.literal(allInstalled ? "All installed" : "Install all"),
				FlatButton.Style.PRIMARY, allInstalled ? Icons.CHECK : Icons.BOLT, b -> install(recommended())), width - 104, modsY - 4);
		installAll.active = !installing && !allInstalled;

		extrasY = modsY + 14 + recommended().size() * ROW + 16;
		int y = extrasY + 12;
		for (BoostMod mod : extras()) {
			FlatButton button = add(new FlatButton(64, 16, Component.literal("Install"), FlatButton.Style.SECONDARY, null,
					b -> install(List.of(mod))), width - 72, y + 4);
			button.active = !installing && !BoostMods.isInstalled(mod);
			button.visible = !BoostMods.isInstalled(mod);
			y += ROW;
		}
		reserve(y + 30);
	}

	private void install(List<BoostMod> mods) {
		List<BoostMod> missing = mods.stream().filter(mod -> !BoostMods.isInstalled(mod)).toList();
		if (missing.isEmpty() || installing) {
			return;
		}
		installing = true;
		installStatus = "Contacting Modrinth...";
		CompletableFuture.runAsync(() -> {
			int count = BoostMods.install(missing, line -> installStatus = line);
			if (count > 0) {
				needsRestart = true;
				installStatus = "Installed " + count + " mod(s). Restart Minecraft to turn them on.";
			}
			installing = false;
		});
		screen.refresh();
	}

	private String signature() {
		StringBuilder s = new StringBuilder().append(installing).append(QuickTune.isApplied());
		BoostMods.MODS.forEach(m -> s.append(BoostMods.isInstalled(m)));
		return s.toString();
	}

	@Override
	public void tick() {
		if (!signature().equals(lastSignature)) {
			screen.refresh();
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "Right now: " + minecraft.getFps() + " FPS", left, top);
		card(g, left, top + 12, width, 36);

		sectionLabel(g, "FPS mods", left, top + modsY);
		int y = top + modsY + 14;
		card(g, left, y - 2, width, recommended().size() * ROW + 4);
		for (BoostMod mod : recommended()) {
			row(g, left, y, mod);
			y += ROW;
		}

		sectionLabel(g, "Extras", left, top + extrasY);
		y = top + extrasY + 12;
		card(g, left, y - 2, width, extras().size() * ROW + 4);
		for (BoostMod mod : extras()) {
			row(g, left, y, mod);
			y += ROW;
		}

		String status = installStatus.isEmpty()
				? "Downloads come straight from Modrinth, the official mod site, and are checked before installing."
				: installStatus;
		int color = needsRestart ? Theme.WARNING : installing ? Theme.INFO : Theme.FAINT;
		if (installing) {
			int pulse = (int) ((System.currentTimeMillis() / 8) % (width + 60)) - 60;
			Draw.roundRect(g, left, y + 8, width, 3, 1, Theme.FIELD);
			Draw.roundRect(g, left + Math.max(0, pulse), y + 8, Math.min(60, width - Math.max(0, pulse)), 3, 1, Theme.INFO);
		}
		Draw.text(g, Draw.fit(status, width), left, y + 16, color);
	}

	private void row(GuiGraphicsExtractor g, int left, int y, BoostMod mod) {
		boolean installed = BoostMods.isInstalled(mod);
		boolean loaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod.modId());
		(installed ? Icons.CHECK : Icons.PACKAGE).draw(g, left + 9, y + 7, installed ? Theme.ACCENT : Theme.MUTED);
		Draw.text(g, mod.name(), left + 24, y + 4, Theme.TEXT);
		String badge = loaded ? "Active" : "Restart needed";
		int bw = Draw.font().width(badge) + 10;
		int reserved = installed ? bw + 16 : 80;
		Draw.text(g, Draw.fit(mod.benefit(), width - 24 - reserved), left + 24, y + 14, Theme.MUTED);
		if (installed) {
			int bx = left + width - bw - 8;
			Draw.roundRect(g, bx, y + 6, bw, 12, 6, Theme.withAlpha(loaded ? Theme.ACCENT : Theme.WARNING, 0.18f));
			Draw.text(g, badge, bx + 5, y + 8, loaded ? Theme.ACCENT : Theme.WARNING);
		}
	}
}

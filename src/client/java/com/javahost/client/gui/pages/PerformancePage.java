package com.javahost.client.gui.pages;

import java.util.List;

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
	private static final int MOD_ROW = 30;
	/** Catalog slug to icon URL, fetched once from Modrinth. */
	private static volatile java.util.Map<String, String> icons;
	/** The last install started here (it also shows in the menu's download list). */
	private static volatile com.javahost.client.mods.Downloads.Job job;

	private int modsY;

	private int extrasY;
	private String lastSignature = "";

	@Override
	public String title() {
		return "Performance";
	}

	@Override
	public String subtitle() {
		return "More FPS with mods and smart settings.";
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
		// Video settings, in whichever menu the player prefers (Settings -> Video settings menu).
		add(new FlatButton(104, 14, Component.literal("Video settings"), FlatButton.Style.GHOST, Icons.GEAR, b -> {
			if (com.javahost.client.perf.VideoMenu.sodiumInstalled() && "SODIUM".equals(com.javahost.client.Config.get().videoMenu)) {
				com.javahost.client.perf.VideoMenu.openSodium(minecraft, screen);
			} else {
				minecraft.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(screen, minecraft, minecraft.options));
			}
		}), width - 104, -3);
		add(new ToggleSwitch(width - 20, Component.literal("Quick Tune"), "Faster video settings; turn off to undo",
				QuickTune.isApplied(), on -> {
					if (on) {
						QuickTune.apply();
					} else {
						QuickTune.restore();
					}
				}), 10, 20);

		// Quick settings: the options that matter most for FPS, right here.
		var options = minecraft.options;
		int inner = width - 20;
		add(new com.javahost.client.gui.ui.FlatSlider(inner, "Render distance", options.renderDistance().get(), 2, 32, v -> {
			options.renderDistance().set((int) Math.round(v));
			options.save();
		}, v -> Math.round(v) + " chunks"), 10, 76);
		add(new com.javahost.client.gui.ui.FlatSlider(inner, "Max frame rate", Math.min(260, options.framerateLimit().get()), 30, 260, v -> {
			options.framerateLimit().set((int) (Math.round(v / 10) * 10));
			options.save();
		}, v -> v >= 255 ? "Unlimited" : (Math.round(v / 10) * 10) + " FPS"), 10, 104);
		//? if >=1.21.11 {
		net.minecraft.client.GraphicsPreset preset = options.graphicsPreset().get();
		List<net.minecraft.client.GraphicsPreset> presets = new java.util.ArrayList<>(List.of(
				net.minecraft.client.GraphicsPreset.FAST, net.minecraft.client.GraphicsPreset.FANCY, net.minecraft.client.GraphicsPreset.FABULOUS));
		if (!presets.contains(preset)) {
			presets.add(preset); // e.g. Custom, from changing settings one by one
		}
		add(new com.javahost.client.gui.ui.Segmented<>(Math.min(inner - 60, 230), presets, preset, p -> switch (p) {
			case FAST -> "Fast";
			case FANCY -> "Fancy";
			case FABULOUS -> "Fabulous";
			default -> "Custom";
		}, p -> {
			options.applyGraphicsPreset(p);
			options.save();
		}), width - 10 - Math.min(inner - 60, 230), 134);
		//?} else {
		/*net.minecraft.client.GraphicsStatus preset = options.graphicsMode().get();
		List<net.minecraft.client.GraphicsStatus> presets = List.of(net.minecraft.client.GraphicsStatus.FAST, net.minecraft.client.GraphicsStatus.FANCY,
				net.minecraft.client.GraphicsStatus.FABULOUS);
		add(new com.javahost.client.gui.ui.Segmented<>(Math.min(inner - 60, 230), presets, preset, p -> switch (p) {
			case FAST -> "Fast";
			case FANCY -> "Fancy";
			case FABULOUS -> "Fabulous";
		}, p -> {
			options.graphicsMode().set(p);
			options.save();
		}), width - 10 - Math.min(inner - 60, 230), 134);
		*///?}

		if (icons == null) {
			icons = java.util.Map.of();
			com.javahost.util.IoExecutor.NET.execute(() -> {
				try {
					icons = com.javahost.util.Modrinth.iconUrls(BoostMods.MODS.stream().map(BoostMod::slug).toList());
				} catch (Exception e) {
					com.javahost.JavaHost.LOGGER.debug("[Perf] couldn't fetch mod icons", e);
				}
			});
		}
		modsY = 56 + 12 + 92 + 16;
		boolean allInstalled = recommended().stream().allMatch(BoostMods::isInstalled);
		FlatButton installAll = add(new FlatButton(104, 16, Component.literal(allInstalled ? "All installed" : "Install all"),
				FlatButton.Style.PRIMARY, allInstalled ? Icons.CHECK : Icons.BOLT, b -> install(recommended())), width - 104, modsY - 4);
		installAll.active = !installing() && !allInstalled;

		extrasY = modsY + 14 + recommended().size() * MOD_ROW + 16;
		int y = extrasY + 12;
		for (BoostMod mod : extras()) {
			FlatButton button = add(new FlatButton(64, 16, Component.literal("Install"), FlatButton.Style.SECONDARY, null,
					b -> install(List.of(mod))), width - 72, y + 7);
			button.active = !installing() && !BoostMods.isInstalled(mod);
			setShown(button, !BoostMods.isInstalled(mod));
			y += MOD_ROW;
		}
		reserve(y + 30);
	}

	private static boolean installing() {
		return job != null && job.active();
	}

	private void install(List<BoostMod> mods) {
		List<BoostMod> missing = mods.stream().filter(mod -> !BoostMods.isInstalled(mod)).toList();
		if (missing.isEmpty() || installing()) {
			return;
		}
		String title = missing.size() == 1 ? missing.getFirst().name() : Draw.count(missing.size(), "FPS mod");
		String icon = missing.size() == 1 && icons != null ? icons.getOrDefault(missing.getFirst().slug(), "") : "";
		job = com.javahost.client.mods.Downloads.installMods("fps:" + String.join(",", missing.stream().map(BoostMod::slug).toList()), title, icon,
				missing);
		screen.refresh();
	}

	private String signature() {
		StringBuilder s = new StringBuilder().append(installing()).append(job == null ? "" : job.stage()).append(QuickTune.isApplied());
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

		// Quick settings card.
		sectionLabel(g, "Quick settings", left, top + 56);
		card(g, left, top + 68, width, 92);
		Draw.text(g, "Graphics", left + 10, top + 140, Theme.TEXT);

		sectionLabel(g, "FPS mods", left, top + modsY);
		int y = top + modsY + 14;
		card(g, left, y - 2, width, recommended().size() * MOD_ROW + 4);
		for (BoostMod mod : recommended()) {
			row(g, left, y, mod);
			y += MOD_ROW;
		}

		sectionLabel(g, "Extras", left, top + extrasY);
		y = top + extrasY + 12;
		card(g, left, y - 2, width, extras().size() * MOD_ROW + 4);
		for (BoostMod mod : extras()) {
			row(g, left, y, mod);
			y += MOD_ROW;
		}

		var current = job;
		boolean restart = current != null && current.needsRestart() && current.stage() == com.javahost.client.mods.Downloads.Stage.DONE;
		String status = current == null || current.stage() == com.javahost.client.mods.Downloads.Stage.CANCELLED
				? "Downloads come straight from Modrinth, the official mod site, and are checked before installing."
				: current.statusText();
		int color = restart ? Theme.WARNING : installing() ? Theme.INFO : current != null && current.stage() == com.javahost.client.mods.Downloads.Stage.FAILED
				? Theme.WARNING : Theme.FAINT;
		if (installing()) {
			Draw.roundRect(g, left, y + 8, width, 3, 1, Theme.FIELD);
			int filled = (int) Math.round(width * current.progress());
			if (filled > 0) {
				Draw.roundRect(g, left, y + 8, Math.max(2, filled), 3, 1, Theme.INFO);
			}
		}
		Draw.textFit(g, status, width, left, y + 16, color);
	}

	private void row(GuiGraphicsExtractor g, int left, int y, BoostMod mod) {
		boolean installed = BoostMods.isInstalled(mod);
		boolean loaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod.modId());
		com.javahost.client.gui.ui.WebIcons.draw(g, icons == null ? "" : icons.getOrDefault(mod.slug(), ""), mod.name(), left + 7, y + 5, 20);
		Draw.text(g, mod.name(), left + 34, y + 5, Theme.TEXT);
		String badge = loaded ? "Active" : "Restart needed";
		int bw = Draw.font().width(badge) + 10;
		int reserved = installed ? bw + 16 : 80;
		Draw.textFit(g, mod.benefit(), width - 34 - reserved, left + 34, y + 16, Theme.MUTED);
		if (installed) {
			int bx = left + width - bw - 8;
			Draw.roundRect(g, bx, y + 9, bw, 12, 6, Theme.withAlpha(loaded ? Theme.ACCENT : Theme.WARNING, 0.18f));
			Draw.text(g, badge, bx + 5, y + 11, loaded ? Theme.ACCENT : Theme.WARNING);
		}
	}
}

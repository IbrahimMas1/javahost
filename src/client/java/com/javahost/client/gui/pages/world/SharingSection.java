package com.javahost.client.gui.pages.world;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.pack.IrisCompat;
import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.WorldOptions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** What the host shares with players: resource packs, shader, and voice chat (on/off, who hears whom, range). */
public final class SharingSection extends WorldSection {
	private static final int PACKS_CARD = 80;

	private boolean worldOpen;
	private boolean hosting;
	private int packsY;
	private int voiceY;
	private int voiceH;
	private int reachY = -1;
	private int rangeY;
	private int noteY = -1;

	public SharingSection(Host host) {
		super(host);
	}

	/** {@code worldOpen}: show voice reach (a rule of the open world); {@code hosting}: voice changes wait for next time. */
	public void configure(boolean worldOpen, boolean hosting) {
		this.worldOpen = worldOpen;
		this.hosting = hosting;
	}

	@Override
	protected int layout(int y) {
		Config config = Config.get();
		int inner = width - 2 * PAD;

		packsY = y;
		int top = y + LABEL;
		add(new ToggleSwitch(inner, Component.literal("Share my resource packs"), "Everyone who joins sees them too",
				config.shareResourcePacks, v -> {
					config.shareResourcePacks = v;
					config.save();
				}), PAD, top + 8);
		ToggleSwitch shader = add(new ToggleSwitch(inner, Component.literal("Share my shader"),
				IrisCompat.isInstalled() ? "Including your shader settings" : "Install Iris on Performance first",
				config.shareShaders && IrisCompat.isInstalled(), v -> {
					config.shareShaders = v;
					config.save();
				}), PAD, top + 48);
		shader.active = IrisCompat.isInstalled();
		y = top + PACKS_CARD;

		voiceY = y + GAP;
		top = voiceY + LABEL;
		add(new ToggleSwitch(inner, Component.literal("Voice chat"), "Players can talk in your world",
				config.voiceEnabledWhenHosting, v -> {
					config.voiceEnabledWhenHosting = v;
					config.save();
				}), PAD, top + 8);
		int row = top + 40;
		reachY = -1;
		if (worldOpen) {
			reachY = row;
			boolean available = WorldOptions.available();
			RulesClient.State old = RulesClient.state();
			String reach = available ? WorldOptions.voice() : old != null ? old.values().getOrDefault("VOICE", "nearby") : "nearby";
			boolean canChange = available ? WorldOptions.canChangeRules() : old != null && old.canEdit() && !old.locked();
			Segmented<String> who = add(new Segmented<>(inner, List.of("nearby", "party"), reach,
					mode -> mode.equals("party") ? "Everyone in the world" : "Nearby players", mode -> {
						if (available) {
							WorldOptions.setVoice(mode);
						} else {
							RulesClient.request("VOICE", mode);
						}
						host.sent();
					}), PAD, row + 22);
			who.active = canChange;
			who.setTooltip(tip(canChange ? "Changes it for everyone in this world" : "The host and operators choose this for everyone"));
			row += 48;
		}
		rangeY = row;
		Segmented<Integer> range = add(new Segmented<>(inner - 70, List.of(16, 32, 48, 64, 96, 128), config.voiceRange, String::valueOf, r -> {
			config.voiceRange = r;
			config.save();
		}), width - PAD - (inner - 70), row + 9);
		range.active = !hosting;
		if (hosting) {
			range.setTooltip(tip("Applies next time you host"));
		}
		row += 36;
		voiceH = row - top;
		y = row;
		noteY = -1;
		if (hosting) {
			noteY = y + 8;
			y = noteY + 10;
		}
		return y;
	}

	@Override
	public String signature() {
		return WorldOptions.version() + "/" + RulesClient.version();
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int y = top + packsY;
		label(g, "Share with players", left, y);
		card(g, left, y + LABEL, width, PACKS_CARD);
		divider(g, left, y + LABEL + 40, width);

		y = top + voiceY;
		label(g, "Voice chat", left, y);
		card(g, left, y + LABEL, width, voiceH);
		divider(g, left, y + LABEL + 40, width);
		if (reachY >= 0) {
			Draw.text(g, "Who hears whom", left + PAD, top + reachY + 10, Theme.TEXT);
			divider(g, left, top + reachY + 48, width);
		}
		Draw.text(g, "Range", left + PAD, top + rangeY + 9, Theme.TEXT);
		Draw.text(g, "blocks", left + PAD, top + rangeY + 20, Theme.FAINT);
		if (noteY >= 0) {
			note(g, Icons.MIC, "Voice changes apply next time you host.", left + 2, top + noteY, width - 2, Theme.FAINT);
		}
	}
}

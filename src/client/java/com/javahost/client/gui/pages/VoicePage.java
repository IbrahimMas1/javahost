package com.javahost.client.gui.pages;

import java.util.ArrayList;
import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatSlider;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.voice.VoiceClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public class VoicePage extends Page {
	private static final String DEFAULT_MIC = "System default";

	@Override
	public String title() {
		return "Voice chat";
	}

	@Override
	public String subtitle() {
		return "Talk nearby, or to the whole world.";
	}

	@Override
	protected void build() {
		Config config = Config.get();
		VoiceClient.setTestingMic(true);
		int inner = width - 20;

		add(new ToggleSwitch(inner, Component.literal("Voice chat"), "Hear and talk to nearby players",
				config.voiceEnabled, v -> {
					config.voiceEnabled = v;
					config.save();
				}), 10, 20);
		add(new ToggleSwitch(inner, Component.literal("Mute my microphone"), "Toggle in game with " + JavaHostClient.TOGGLE_MUTE.getTranslatedKeyMessage().getString(),
				config.muted, v -> {
					config.muted = v;
					config.save();
				}), 10, 48);
		add(new Segmented<>(inner, List.of(true, false), config.pushToTalk,
				ptt -> ptt ? "Push to talk (" + JavaHostClient.PUSH_TO_TALK.getTranslatedKeyMessage().getString() + ")" : "Voice activation",
				ptt -> {
					config.pushToTalk = ptt;
					config.save();
					screen.refresh();
				}), 10, 80);
		// Who hears whom is one setting for the whole world, chosen by the host and co-hosts (a world rule).
		String partyKey = JavaHostClient.PARTY_VOICE.getTranslatedKeyMessage().getString();
		String rule = VoiceClient.worldRule();
		var rules = com.javahost.client.rules.RulesClient.state();
		boolean worldDecides = rule != null;
		String selected = worldDecides ? rule : config.voiceMode.toLowerCase(java.util.Locale.ROOT);
		Segmented<String> who = add(new Segmented<>(inner, List.of("nearby", "party"), selected,
				mode -> mode.equals("party") ? "Everyone in the world" : "Nearby players", mode -> {
					if (worldDecides) {
						com.javahost.client.rules.RulesClient.request("VOICE", mode);
					} else {
						config.voiceMode = mode.toUpperCase(java.util.Locale.ROOT);
						config.save();
					}
				}), 10, 104);
		who.active = !worldDecides || (rules.canEdit() && !rules.locked());
		who.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(!worldDecides
				? "Who hears you. Party voice reaches everyone in the world, anywhere (" + partyKey + " in game)."
						+ (VoiceClient.isAvailable() && !VoiceClient.partyAvailable() ? " This host needs JavaHost 1.4 for party voice." : "")
				: rules.locked() ? "The world rules are locked."
				: rules.canEdit() ? "Changes it for everyone in this world (" + partyKey + " in game)."
				: "The host and co-hosts choose this for everyone in this world.")));
		rulesVersion = com.javahost.client.rules.RulesClient.version();

		List<String> mics = new ArrayList<>();
		mics.add(DEFAULT_MIC);
		mics.addAll(VoiceClient.microphones());
		String current = config.microphone.isEmpty() || !mics.contains(config.microphone) ? DEFAULT_MIC : config.microphone;
		int currentIndex = mics.indexOf(current);
		add(new com.javahost.client.gui.ui.FlatButton(inner, 20, Component.literal("Microphone: " + current),
				com.javahost.client.gui.ui.FlatButton.Style.SECONDARY, Icons.MIC, b -> {
					int next = (currentIndex + 1) % mics.size();
					config.microphone = next == 0 ? "" : mics.get(next);
					config.save();
					screen.refresh();
				}), 10, 168);

		int half = (inner - 10) / 2;
		add(new FlatSlider(half, "Mic boost", config.micGain, 0, 3, v -> config.micGain = v, v -> Math.round(v * 100) + "%"), 10, 196);
		add(new FlatSlider(half, "Voice volume", config.outputVolume, 0, 2, v -> config.outputVolume = v, v -> Math.round(v * 100) + "%"),
				20 + half, 196);
		FlatSlider threshold = add(new FlatSlider(inner, "Voice activation level", config.activationThresholdDb, -80, 0,
				v -> config.activationThresholdDb = v, v -> Math.round(v) + " dB"), 10, 226);
		threshold.active = !config.pushToTalk;

		// People nearby: a volume and mute for each.
		nearby = new java.util.ArrayList<>(VoiceClient.nearbyPlayers().entrySet());
		nearbyKey = nearby.toString();
		int y = 310;
		for (var entry : nearby) {
			String id = entry.getKey().toString();
			add(new FlatSlider(width - 150, "Volume", config.playerVolumes.getOrDefault(id, 1.0), 0, 2,
					v -> config.playerVolumes.put(id, v), v -> Math.round(v * 100) + "%"), 104, y + 3);
			boolean muted = config.mutedPlayers.contains(id);
			add(new com.javahost.client.gui.ui.FlatButton(24, 20, Component.empty(), muted
					? com.javahost.client.gui.ui.FlatButton.Style.DANGER : com.javahost.client.gui.ui.FlatButton.Style.GHOST,
					muted ? Icons.MIC_OFF : Icons.MIC, b -> {
						if (!config.mutedPlayers.remove(id)) {
							config.mutedPlayers.add(id);
						}
						config.save();
						screen.refresh();
					}), width - 34, y + 4);
			y += 30;
		}
		problemY = Math.max(y, 340) + 10;
		reserve(problemY + 40);
	}

	private java.util.List<java.util.Map.Entry<java.util.UUID, String>> nearby = java.util.List.of();
	private String nearbyKey = "";
	private int problemY = 340;
	private int rulesVersion = -1;

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "People nearby", left, top + 298);
		int rowY = top + 310;
		card(g, left, rowY - 2, width, Math.max(1, nearby.size()) * 30 + 4);
		if (nearby.isEmpty()) {
			Draw.textFit(g, VoiceClient.isAvailable() ? "Nobody in voice range right now." : "Join a world with voice chat to see people here.", width - 20, left + 10, rowY + 10, Theme.MUTED);
		}
		for (var entry : nearby) {
			com.javahost.client.gui.ui.Faces.draw(g, entry.getKey(), left + 8, rowY + 6, 18);
			Draw.textFit(g, entry.getValue(), 66, left + 32, rowY + 11, VoiceClient.isTalking(entry.getKey()) ? Theme.ACCENT : Theme.TEXT);
			rowY += 30;
		}
		Config config = Config.get();
		sectionLabel(g, "Talking", left, top);
		card(g, left, top + 12, width, 132);
		String rule = VoiceClient.worldRule();
		var rules = com.javahost.client.rules.RulesClient.state();
		Draw.textFit(g, rule == null ? (minecraftInWorld() ? "This host lets each player choose." : "In a world, the host chooses this for everyone.")
				: rules.canEdit() ? "You choose this for everyone in the world." : "The host chooses this for everyone in the world.",
				width - 20, left + 10, top + 128, Theme.FAINT);
		sectionLabel(g, "Microphone", left, top + 154);
		card(g, left, top + 162, width, 118);

		// Live level meter under the sliders.
		int meterY = top + 256;
		int x = left + 10;
		int w = width - 20;
		float level = Math.max(-80, Math.min(0, VoiceClient.micLevelDb()));
		int filled = (int) ((level + 80) / 80 * w);
		int mark = (int) ((config.activationThresholdDb + 80) / 80 * w);
		boolean loud = config.pushToTalk ? VoiceClient.isTransmitting() : level > config.activationThresholdDb;
		Draw.roundRect(g, x, meterY, w, 6, 3, Theme.FIELD);
		Draw.roundRect(g, x, meterY, Math.max(6, filled), 6, 3, loud ? Theme.ACCENT : Theme.FAINT);
		if (!config.pushToTalk) {
			g.fill(x + mark, meterY - 2, x + mark + 1, meterY + 8, Theme.TEXT);
		}
		Draw.text(g, loud ? "You're being heard" : "Speak to test your mic", x, meterY + 11, loud ? Theme.ACCENT : Theme.FAINT);

		String problem = VoiceClient.micProblem();
		if (problem != null) {
			int h = wrappedHeight(problem, width - 30) + 12;
			Draw.panel(g, left, top + problemY, width, h, 6, Theme.withAlpha(Theme.WARNING, 0.12f));
			Icons.WARNING.draw(g, left + 8, top + problemY + 6, Theme.WARNING);
			wrap(g, problem, left + 22, top + problemY + 7, width - 30, Theme.WARNING);
		}
	}

	private static boolean minecraftInWorld() {
		return net.minecraft.client.Minecraft.getInstance().level != null;
	}

	@Override
	public void tick() {
		if (com.javahost.client.rules.RulesClient.version() != rulesVersion) {
			screen.refresh(); // the world's voice mode (or who may change it) changed
			return;
		}
		if (!new java.util.ArrayList<>(VoiceClient.nearbyPlayers().entrySet()).toString().equals(nearbyKey)) {
			screen.refresh();
		}
		// Keep the mic open while this tab is visible so the level meter works, even outside a world.
		VoiceClient.setTestingMic(Config.get().voiceEnabled);
	}
}

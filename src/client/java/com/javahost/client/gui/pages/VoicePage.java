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
		return "Talk to players near you. Voices fade with distance.";
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
				}), 10, 128);

		int half = (inner - 10) / 2;
		add(new FlatSlider(half, "Mic boost", config.micGain, 0, 3, v -> config.micGain = v, v -> Math.round(v * 100) + "%"), 10, 156);
		add(new FlatSlider(half, "Voice volume", config.outputVolume, 0, 2, v -> config.outputVolume = v, v -> Math.round(v * 100) + "%"),
				20 + half, 156);
		FlatSlider threshold = add(new FlatSlider(inner, "Voice activation level", config.activationThresholdDb, -80, 0,
				v -> config.activationThresholdDb = v, v -> Math.round(v) + " dB"), 10, 186);
		threshold.active = !config.pushToTalk;
		reserve(250);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		Config config = Config.get();
		sectionLabel(g, "Talking", left, top);
		card(g, left, top + 12, width, 92);
		sectionLabel(g, "Microphone", left, top + 114);
		card(g, left, top + 122, width, 118);

		// Live level meter under the sliders.
		int meterY = top + 216;
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
			Draw.panel(g, left, top + 250, width, h, 6, Theme.withAlpha(Theme.WARNING, 0.12f));
			Icons.WARNING.draw(g, left + 8, top + 256, Theme.WARNING);
			wrap(g, problem, left + 22, top + 257, width - 30, Theme.WARNING);
		}
	}

	@Override
	public void tick() {
		// Keep the mic open while this tab is visible so the level meter works, even outside a world.
		VoiceClient.setTestingMic(Config.get().voiceEnabled);
	}
}

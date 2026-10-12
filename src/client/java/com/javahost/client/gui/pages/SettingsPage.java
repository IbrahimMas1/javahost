package com.javahost.client.gui.pages;

import java.util.List;
import java.util.Map;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
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

	private static final int KEYS_TOP = 276;

	@Override
	public String title() {
		return "Settings";
	}

	@Override
	public String subtitle() {
		return "Colours, sounds and keys.";
	}

	@Override
	protected void build() {
		Config config = Config.get();
		int inner = width - 20;

		// Look
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
		add(new Segmented<>(140, List.of("TOP_LEFT", "TOP_RIGHT"), config.hudCorner,
				corner -> corner.equals("TOP_LEFT") ? "Top left" : "Top right", corner -> {
					config.hudCorner = corner;
					config.save();
				}), width - 150, 46);
		add(new ToggleSwitch(inner, Component.literal("FPS overlay"), "A small frame-rate counter in a corner",
				config.fpsOverlay, v -> {
					config.fpsOverlay = v;
					config.save();
				}), 10, 72);

		// Friends & sound
		add(new ToggleSwitch(inner, Component.literal("Always use the host's packs"), "Skip the question when a host shares packs",
				config.autoAcceptHostPacks, v -> {
					config.autoAcceptHostPacks = v;
					config.save();
				}), 10, 132);
		add(new ToggleSwitch(inner, Component.literal("Show who's talking"), "Faces of speaking players on screen",
				config.showVoiceOverlay, v -> {
					config.showVoiceOverlay = v;
					config.save();
				}), 10, 160);
		add(new ToggleSwitch(inner, Component.literal("Notification sounds"), "A soft chime for invites, requests and messages",
				config.notificationSounds, v -> {
					config.notificationSounds = v;
					config.save();
				}), 10, 188);
		if (com.javahost.client.perf.VideoMenu.sodiumInstalled()) {
			add(new Segmented<>(150, List.of("SIMPLE", "SODIUM"), config.videoMenu, m -> m.equals("SIMPLE") ? "Simple" : "Sodium", m -> {
				config.videoMenu = m;
				config.save();
			}), width - 160, 240);
		}
		reserve(KEYS_TOP + 12 + KEYS.size() * 20 + 30);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "Look", left, top);
		card(g, left, top + 12, width, 90);
		Draw.text(g, "Accent colour", left + 10, top + 24, Theme.TEXT);
		Draw.text(g, "Overlay position", left + 10, top + 51, Theme.TEXT);

		sectionLabel(g, "Friends & sound", left, top + 114);
		card(g, left, top + 126, width, 90);

		if (com.javahost.client.perf.VideoMenu.sodiumInstalled()) {
			sectionLabel(g, "Video", left, top + 222);
			card(g, left, top + 234, width, 30);
			Draw.text(g, Draw.fit("Video settings menu", width - 180), left + 10, top + 245, Theme.TEXT);
		}
		int keysTop = top + KEYS_TOP;
		sectionLabel(g, "Keys", left, keysTop);
		card(g, left, keysTop + 12, width, KEYS.size() * 20 + 8);
		int y = keysTop + 20;
		for (KeyMapping key : KEYS) {
			Draw.text(g, Component.translatable(key.getName()), left + 10, y + 3, Theme.TEXT);
			String bound = key.isUnbound() ? "Not set" : key.getTranslatedKeyMessage().getString();
			int w = Draw.font().width(bound) + 12;
			Draw.bordered(g, left + width - w - 10, y, w, 14, 3, Theme.FIELD, Theme.BORDER);
			Draw.text(g, bound, left + width - w - 4, y + 3, key.isUnbound() ? Theme.FAINT : Theme.ACCENT);
			y += 20;
		}
		Draw.text(g, Draw.fit("Change keys in Options → Controls → Key Binds → JavaHost.", width), left, y + 12, Theme.FAINT);
	}
}

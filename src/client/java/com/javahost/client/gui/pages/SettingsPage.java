package com.javahost.client.gui.pages;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public class SettingsPage extends Page {
	@Override
	public String title() {
		return "Settings";
	}

	@Override
	public String subtitle() {
		return "How JavaHost behaves when you join other people's worlds.";
	}

	@Override
	protected void build() {
		Config config = Config.get();
		int inner = width - 20;
		add(new ToggleSwitch(inner, Component.literal("Always use the host's packs"), "Skip the question when a host shares packs",
				config.autoAcceptHostPacks, v -> {
					config.autoAcceptHostPacks = v;
					config.save();
				}), 10, 20);
		add(new ToggleSwitch(inner, Component.literal("Show who's talking"), "Faces of speaking players on screen",
				config.showVoiceOverlay, v -> {
					config.showVoiceOverlay = v;
					config.save();
				}), 10, 48);
		add(new Segmented<>(140, List.of("TOP_LEFT", "TOP_RIGHT"), config.hudCorner,
				corner -> corner.equals("TOP_LEFT") ? "Top left" : "Top right", corner -> {
					config.hudCorner = corner;
					config.save();
				}), width - 150, 78);
		reserve(220);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "General", left, top);
		card(g, left, top + 12, width, 92);
		Draw.text(g, "Overlay position", left + 10, top + 83, Theme.TEXT);

		sectionLabel(g, "Keys", left, top + 116);
		card(g, left, top + 128, width, 72);
		int y = top + 136;
		for (KeyMapping key : List.of(JavaHostClient.OPEN_MENU, JavaHostClient.PUSH_TO_TALK, JavaHostClient.TOGGLE_MUTE)) {
			Draw.text(g, Component.translatable(key.getName()), left + 10, y + 3, Theme.TEXT);
			String bound = key.getTranslatedKeyMessage().getString();
			int w = Draw.font().width(bound) + 12;
			Draw.bordered(g, left + width - w - 10, y, w, 14, 3, Theme.FIELD, Theme.BORDER);
			Draw.text(g, bound, left + width - w - 4, y + 3, Theme.ACCENT);
			y += 20;
		}
		Draw.text(g, "Change keys in Options → Controls → Key Binds → JavaHost.", left, top + 206, Theme.FAINT);
	}
}

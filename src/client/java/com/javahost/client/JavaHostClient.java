package com.javahost.client;

import java.util.Map;
import java.util.UUID;

import com.javahost.JavaHost;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.E4mcCompat;
import com.javahost.client.host.HostController;
import com.javahost.client.pack.PackReceiver;
import com.javahost.client.voice.VoiceClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class JavaHostClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, "javahost"));
	public static final KeyMapping PUSH_TO_TALK = new KeyMapping("key.javahost.push_to_talk", InputConstants.KEY_V, CATEGORY);
	public static final KeyMapping OPEN_MENU = new KeyMapping("key.javahost.menu", InputConstants.KEY_J, CATEGORY);
	public static final KeyMapping TOGGLE_MUTE = new KeyMapping("key.javahost.mute", InputConstants.KEY_M, CATEGORY);

	@Override
	public void onInitializeClient() {
		Config.get();
		KeyMappingHelper.registerKeyMapping(PUSH_TO_TALK);
		KeyMappingHelper.registerKeyMapping(OPEN_MENU);
		KeyMappingHelper.registerKeyMapping(TOGGLE_MUTE);

		VoiceClient.register();
		PackReceiver.register();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			VoiceClient.setPushToTalkHeld(PUSH_TO_TALK.isDown());
			HostController.tick();
			while (OPEN_MENU.consumeClick()) {
				minecraft.gui.setScreen(new JavaHostScreen(null));
			}
			while (TOGGLE_MUTE.consumeClick()) {
				Config config = Config.get();
				config.muted = !config.muted;
				config.save();
				Toasts.show(config.muted ? "Microphone muted" : "Microphone on", "Press " + TOGGLE_MUTE.getTranslatedKeyMessage().getString() + " to toggle");
			}
		});

		// Leaving the world (or the integrated server stopping) ends hosting and closes the router port.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostController.cleanup());
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> E4mcCompat.shutdownForExit());

		// Tell the host when a friend joins their world.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (HostController.isHosting() && !server.isSingleplayerOwner(handler.player.nameAndId())) {
				UUID id = handler.player.getUUID();
				String name = handler.player.getPlainTextName();
				Toasts.player(id, name + " joined your world", "Say hi!");
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, "status"), (graphics, delta) -> drawHud(graphics));
		addMenuButtons();
	}

	private static void addMenuButtons() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen || (screen instanceof PauseScreen pause && pause.showsPauseMenu())) {
				Screens.getWidgets(screen).add(new FlatButton(4, 4, 82, 20, Component.literal("JavaHost"), FlatButton.Style.SECONDARY,
						Icons.HOST, b -> minecraft.gui.setScreen(new JavaHostScreen(screen))));
			} else if (screen instanceof JoinMultiplayerScreen) {
				Screens.getWidgets(screen).add(new FlatButton(width - 108, 4, 104, 20, Component.literal("Join with code"),
						FlatButton.Style.PRIMARY, Icons.JOIN, b -> minecraft.gui.setScreen(new JavaHostScreen(screen, JavaHostScreen.Tab.JOIN))));
			}
		});
	}

	// ---------------------------------------------------------------- HUD

	private static void drawHud(GuiGraphicsExtractor g) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.hud.isHidden()) {
			return;
		}
		Config config = Config.get();
		boolean right = "TOP_RIGHT".equals(config.hudCorner);
		int y = 4;

		if (VoiceClient.isAvailable() && config.voiceEnabled && config.showVoiceOverlay) {
			if (config.muted) {
				y = pill(g, right, y, Icons.MIC_OFF, null, "Muted", Theme.DANGER);
			} else if (VoiceClient.isTransmitting()) {
				y = pill(g, right, y, Icons.MIC, null, "Talking", Theme.ACCENT);
			}
			for (Map.Entry<UUID, String> speaker : VoiceClient.talking().entrySet()) {
				y = pill(g, right, y, null, speaker.getKey(), speaker.getValue(), Theme.ACCENT);
			}
		}

		float progress = PackReceiver.progress();
		if (progress >= 0) {
			int w = 170;
			int x = (g.guiWidth() - w) / 2;
			Draw.roundRect(g, x, 4, w, 26, 6, Theme.withAlpha(Theme.WINDOW, 0.92f));
			Icons.PACKAGE.draw(g, x + 8, 9, Theme.ACCENT);
			Draw.text(g, "Getting the host's packs", x + 22, 9, Theme.TEXT);
			String pct = (int) (progress * 100) + "%";
			Draw.text(g, pct, x + w - 8 - Draw.font().width(pct), 9, Theme.MUTED);
			Draw.roundRect(g, x + 8, 21, w - 16, 3, 1, Theme.FIELD);
			Draw.roundRect(g, x + 8, 21, Math.max(3, Math.round((w - 16) * progress)), 3, 1, Theme.ACCENT);
		}
	}

	/** A rounded label with an icon or a player's face; returns the y for the next one. */
	private static int pill(GuiGraphicsExtractor g, boolean right, int y, Icons icon, UUID face, String text, int accent) {
		int w = 26 + Draw.font().width(text);
		int x = right ? g.guiWidth() - w - 4 : 4;
		Draw.roundRect(g, x, y, w, 16, 8, Theme.withAlpha(Theme.WINDOW, 0.85f));
		if (face != null) {
			Draw.roundRect(g, x + 2, y + 2, 12, 12, 3, accent);
			Faces.draw(g, face, x + 3, y + 3, 10);
		} else {
			icon.draw(g, x + 5, y + 4, accent);
		}
		Draw.text(g, text, x + 18, y + 4, face != null ? Theme.TEXT : accent);
		return y + 19;
	}
}

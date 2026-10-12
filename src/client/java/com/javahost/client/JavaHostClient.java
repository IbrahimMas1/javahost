package com.javahost.client;

import java.util.Map;
import java.util.UUID;

import com.javahost.JavaHost;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Presence;
import com.javahost.client.friends.Screenshots;
import com.javahost.client.gui.TitleFriendsPanel;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.E4mcCompat;
import com.javahost.client.host.HostController;
import com.javahost.client.pack.PackReceiver;
import com.javahost.client.perf.Benchmark;
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
	public static final KeyMapping SCREENSHOT_SHARE = new KeyMapping("key.javahost.screenshot", InputConstants.KEY_F6, CATEGORY);
	public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping("key.javahost.overlay", InputConstants.UNKNOWN.getValue(), CATEGORY);

	@Override
	public void onInitializeClient() {
		Theme.apply(Config.get().accent);
		KeyMappingHelper.registerKeyMapping(PUSH_TO_TALK);
		KeyMappingHelper.registerKeyMapping(OPEN_MENU);
		KeyMappingHelper.registerKeyMapping(TOGGLE_MUTE);
		KeyMappingHelper.registerKeyMapping(SCREENSHOT_SHARE);
		KeyMappingHelper.registerKeyMapping(TOGGLE_OVERLAY);

		VoiceClient.register();
		PackReceiver.register();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			VoiceClient.setPushToTalkHeld(PUSH_TO_TALK.isDown());
			HostController.tick();
			InviteFlow.tick(minecraft);
			Popups.tick(minecraft);
			Friends.tick();
			Presence.tick(minecraft);
			while (OPEN_MENU.consumeClick()) {
				minecraft.gui.setScreen(new JavaHostScreen(null));
			}
			while (TOGGLE_MUTE.consumeClick()) {
				Config config = Config.get();
				config.muted = !config.muted;
				config.save();
				Toasts.show(config.muted ? "Microphone muted" : "Microphone on", "Press " + TOGGLE_MUTE.getTranslatedKeyMessage().getString() + " to toggle");
			}
			while (TOGGLE_OVERLAY.consumeClick()) {
				Config config = Config.get();
				config.fpsOverlay = !config.fpsOverlay;
				config.save();
			}
			while (SCREENSHOT_SHARE.consumeClick()) {
				Screenshots.take(file -> {
					JavaHostScreen menu = new JavaHostScreen(null, JavaHostScreen.Tab.SCREENSHOTS);
					menu.selectScreenshot(file);
					minecraft.gui.setScreen(menu);
				});
			}
		});

		// Leaving the world (or the integrated server stopping) ends hosting and closes the router port.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostController.cleanup());
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			Presence.goOffline();
			E4mcCompat.shutdownForExit();
		});
		ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> Popups.startFriends());

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
				if (screen instanceof TitleScreen) {
					TitleFriendsPanel.attach(minecraft, screen, width, height);
				}
			} else if (screen instanceof JoinMultiplayerScreen) {
				Screens.getWidgets(screen).add(new FlatButton(width - 88, 4, 84, 20, Component.literal("Friends"),
						FlatButton.Style.PRIMARY, Icons.USER, b -> minecraft.gui.setScreen(new JavaHostScreen(screen, JavaHostScreen.Tab.FRIENDS))));
			}
		});
	}

	// ---------------------------------------------------------------- HUD

	private static void drawHud(GuiGraphicsExtractor g) {
		Benchmark.frame();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.hud.isHidden()) {
			return;
		}
		Config config = Config.get();
		boolean right = "TOP_RIGHT".equals(config.hudCorner);
		int y = 4;

		if (config.fpsOverlay) {
			y = pill(g, right, y, Icons.BOLT, null, overlayText(minecraft), Theme.TEXT);
		}

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

		// Centre bars sit below the corner pills when the screen is too narrow for both side by side.
		int top = y > 4 && g.guiWidth() < 600 ? y + 2 : 4;
		float progress = PackReceiver.progress();
		if (progress >= 0) {
			top = bar(g, top, Icons.PACKAGE, "Getting the host's packs", (int) (progress * 100) + "%", progress);
		}
		if (Benchmark.running()) {
			bar(g, top, Icons.BOLT, Benchmark.step(), null, Benchmark.progress());
		}
	}

	/** A centred card with a label and progress bar; returns the y for the next one. */
	private static int bar(GuiGraphicsExtractor g, int top, Icons icon, String label, String right, float progress) {
		int w = 190;
		int x = (g.guiWidth() - w) / 2;
		Draw.roundRect(g, x, top, w, 26, 6, Theme.withAlpha(Theme.WINDOW, 0.92f));
		icon.draw(g, x + 8, top + 5, Theme.ACCENT);
		int rightW = right == null ? 0 : Draw.font().width(right) + 6;
		Draw.text(g, Draw.fit(label, w - 30 - rightW), x + 22, top + 5, Theme.TEXT);
		if (right != null) {
			Draw.text(g, right, x + w - 8 - Draw.font().width(right), top + 5, Theme.MUTED);
		}
		Draw.roundRect(g, x + 8, top + 17, w - 16, 3, 1, Theme.FIELD);
		Draw.roundRect(g, x + 8, top + 17, Math.max(3, Math.round((w - 16) * progress)), 3, 1, Theme.ACCENT);
		return top + 30;
	}

	/** "120 FPS · 35 ms · 1.4/4.0 GB" */
	private static String overlayText(Minecraft minecraft) {
		StringBuilder text = new StringBuilder().append(minecraft.getFps()).append(" FPS");
		if (minecraft.getConnection() != null && minecraft.player != null && !minecraft.isLocalServer()) {
			var info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
			if (info != null && info.getLatency() > 0) {
				text.append(" \u00B7 ").append(info.getLatency()).append(" ms");
			}
		}
		Runtime runtime = Runtime.getRuntime();
		double used = (runtime.totalMemory() - runtime.freeMemory()) / 1073741824.0;
		double max = runtime.maxMemory() / 1073741824.0;
		text.append(String.format(" \u00B7 %.1f/%.1f GB", used, max));
		return text.toString();
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

package com.javahost.client;

import java.util.Map;
import java.util.UUID;

import com.javahost.JavaHost;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Presence;
import com.javahost.client.friends.Screenshots;
import com.javahost.client.gui.MenuDock;
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
	/** The JavaHost version of the world we're in (null if not JavaHost 1.4+, or not connected). */
	public static volatile String hostVersion;
	public static final KeyMapping PARTY_VOICE = new KeyMapping("key.javahost.party", InputConstants.KEY_B, CATEGORY);
	public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping("key.javahost.overlay", InputConstants.UNKNOWN.getValue(), CATEGORY);

	@Override
	public void onInitializeClient() {
		Theme.apply(Config.get().accent);
		KeyMappingHelper.registerKeyMapping(PUSH_TO_TALK);
		KeyMappingHelper.registerKeyMapping(OPEN_MENU);
		KeyMappingHelper.registerKeyMapping(TOGGLE_MUTE);
		KeyMappingHelper.registerKeyMapping(SCREENSHOT_SHARE);
		KeyMappingHelper.registerKeyMapping(TOGGLE_OVERLAY);
		KeyMappingHelper.registerKeyMapping(PARTY_VOICE);

		VoiceClient.register();
		com.javahost.client.rules.RulesClient.register();
		com.javahost.client.perf.VideoMenu.register();
		// The version check, in world: the host tells us its JavaHost version when we join.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.javahost.net.Payloads.ServerHello.TYPE,
				(hello, context) -> {
					hostVersion = hello.version();
					Minecraft minecraft = Minecraft.getInstance();
					if (minecraft.getSingleplayerServer() == null && com.javahost.util.Versions.compare(hello.version(), JavaHost.VERSION) != 0) {
						boolean hostOlder = com.javahost.util.Versions.compare(hello.version(), JavaHost.VERSION) < 0;
						Toasts.notify("Host runs JavaHost " + hello.version(), hostOlder
								? "You have " + JavaHost.VERSION + ". Some newer features won't work until they update."
								: "You have " + JavaHost.VERSION + ". Run the latest installer to update.");
					}
				});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> hostVersion = null);
		PackReceiver.register();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			VoiceClient.setPushToTalkHeld(PUSH_TO_TALK.isDown());
			HostController.tick();
			InviteFlow.tick(minecraft);
			Popups.tick(minecraft);
			Toasts.tick(minecraft);
			com.javahost.client.update.UpdateChecker.tick(minecraft);
			Friends.tick();
			Presence.tick(minecraft);
			com.javahost.client.share.WorldShare.tick(minecraft);
			while (OPEN_MENU.consumeClick()) {
				minecraft.gui.setScreen(new JavaHostScreen(null));
			}
			while (TOGGLE_MUTE.consumeClick()) {
				Config config = Config.get();
				config.muted = !config.muted;
				config.save();
				Toasts.show("mic", config.muted ? "Microphone muted" : "Microphone on", "Press " + TOGGLE_MUTE.getTranslatedKeyMessage().getString() + " to toggle");
			}
			while (PARTY_VOICE.consumeClick()) {
				toggleParty();
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
			com.javahost.client.update.Updater.launchSwapIfStaged();
			Presence.goOffline();
			E4mcCompat.shutdownForExit();
		});
		ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> {
			Popups.startFriends();
			com.javahost.client.share.WorldShare.register();
			com.javahost.client.update.UpdateChecker.start();
		});

		// Tell the host when a friend joins their world.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (HostController.isHosting() && !server.isSingleplayerOwner(handler.player.nameAndId())) {
				UUID id = handler.player.getUUID();
				String name = handler.player.getPlainTextName();
				Toasts.player(id, name + " joined your world", "Say hi!");
				com.javahost.client.host.WorldBackups.onFriendJoined(server);
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, "status"), (graphics, delta) -> drawHud(graphics));
		addMenuButtons();
	}

	private static void addMenuButtons() {
		// Full text for anything cut short with "…", after resting the mouse on it for a second.
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) ->
				ScreenEvents.beforeExtract(screen).register((s, g, mouseX, mouseY, delta) -> Draw.showTruncatedTooltip(g, mouseX, mouseY)));
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen || (screen instanceof PauseScreen pause && pause.showsPauseMenu())) {
				MenuDock.attach(minecraft, screen, width, height, screen instanceof TitleScreen);
				if (screen instanceof TitleScreen) {
					TitleFriendsPanel.attach(minecraft, screen, width, height);
				}
			} else if (screen instanceof JoinMultiplayerScreen) {
				Screens.getWidgets(screen).add(new FlatButton(width - 88, 4, 84, 20, Component.literal("Friends"),
						FlatButton.Style.PRIMARY, Icons.USERS, b -> minecraft.gui.setScreen(new JavaHostScreen(screen, JavaHostScreen.Tab.FRIENDS))));
			}
		});
	}

	/** Switches between nearby (proximity) and party (whole world) voice. */
	public static void toggleParty() {
		Config config = Config.get();
		config.voiceMode = "PARTY".equals(config.voiceMode) ? "NEARBY" : "PARTY";
		config.save();
		String key = PARTY_VOICE.getTranslatedKeyMessage().getString();
		if (!"PARTY".equals(config.voiceMode)) {
			Toasts.show("voice-mode", "Nearby voice", "Only players close to you hear you. " + key + " for party.");
		} else if (VoiceClient.isAvailable() && !VoiceClient.partyAvailable()) {
			Toasts.show("voice-mode", "Party voice needs JavaHost 1.4", "The host needs to update. Nearby voice still works.");
		} else {
			Toasts.show("voice-mode", "Party voice", "Everyone in this world hears you, wherever they are. " + key + " to switch back.");
		}
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

		if (config.fpsOverlay) {
			y = fpsCounter(g, right, y, minecraft);
		}

		if (VoiceClient.isAvailable() && config.voiceEnabled && config.showVoiceOverlay) {
			if (config.muted) {
				y = pill(g, right, y, Icons.MIC_OFF, null, "Muted", Theme.DANGER);
			} else if (VoiceClient.isTransmitting()) {
				y = pill(g, right, y, Icons.MIC, null, VoiceClient.partyMode() ? "Talking to party" : "Talking", Theme.ACCENT);
			} else if (VoiceClient.partyMode()) {
				y = pill(g, right, y, Icons.USERS, null, "Party voice", Theme.INFO);
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
	}

	/** A centred card with a label and progress bar; returns the y for the next one. */
	private static int bar(GuiGraphicsExtractor g, int top, Icons icon, String label, String right, float progress) {
		int w = 190;
		int x = (g.guiWidth() - w) / 2;
		Draw.roundRect(g, x, top, w, 26, 6, Theme.withAlpha(Theme.WINDOW, 0.92f));
		icon.draw(g, x + 8, top + 5, Theme.ACCENT);
		int rightW = right == null ? 0 : Draw.font().width(right) + 6;
		Draw.textFit(g, label, w - 30 - rightW, x + 22, top + 5, Theme.TEXT);
		if (right != null) {
			Draw.text(g, right, x + w - 8 - Draw.font().width(right), top + 5, Theme.MUTED);
		}
		Draw.roundRect(g, x + 8, top + 17, w - 16, 3, 1, Theme.FIELD);
		Draw.roundRect(g, x + 8, top + 17, Math.max(3, Math.round((w - 16) * progress)), 3, 1, Theme.ACCENT);
		return top + 30;
	}

	/**
	 * A small, clean FPS counter: the number coloured by smoothness (green 60+, amber 30+, red below) and a muted
	 * "FPS" label, on a subtle dark chip. Returns the y for the next HUD item.
	 */
	private static int fpsCounter(GuiGraphicsExtractor g, boolean right, int y, Minecraft minecraft) {
		int fps = minecraft.getFps();
		String number = String.valueOf(fps);
		String label = "FPS";
		int color = fps >= 60 ? 0xFF7CE0A3 : fps >= 30 ? 0xFFF2C14E : 0xFFF07070;
		int w = Draw.font().width(number) + 3 + Draw.font().width(label) + 10;
		int x = right ? g.guiWidth() - w - 4 : 4;
		Draw.roundRect(g, x, y, w, 13, 4, 0x8C000000);
		Draw.text(g, number, x + 5, y + 3, color);
		Draw.text(g, label, x + 5 + Draw.font().width(number) + 3, y + 3, 0xFFB7BEC7);
		return y + 16;
	}

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

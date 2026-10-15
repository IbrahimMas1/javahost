package com.javahost.client.perf;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/**
 * Lets players pick which Video Settings they get when Sodium is installed: Minecraft's own simple screen
 * (default) or Sodium's detailed one, with a button on each to switch. Shared options (render distance,
 * max FPS, graphics, brightness, GUI scale...) are the same settings underneath, so both stay in sync.
 */
public final class VideoMenu {
	/** Sodium's video settings screen: VideoSettingsScreen from Sodium 0.7, SodiumOptionsGUI before (1.21.1-1.21.4). */
	private static final java.util.List<String> SODIUM_SCREENS = java.util.List.of("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen",
			"net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI");

	private VideoMenu() {
	}

	public static boolean sodiumInstalled() {
		return FabricLoader.getInstance().isModLoaded("sodium");
	}

	public static void register() {
		if (!sodiumInstalled()) {
			return;
		}
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (SODIUM_SCREENS.contains(screen.getClass().getName())) {
				if ("SIMPLE".equals(Config.get().videoMenu)) {
					// Sodium swapped its own screen in; put Minecraft's back (on the next tick, not mid-init).
					Screen parent = sodiumParent(screen);
					ScreenEvents.afterTick(screen).register(s -> {
						if (minecraft.gui.screen() == s) {
							minecraft.gui.setScreen(new VideoSettingsScreen(parent, minecraft, minecraft.options));
						}
					});
					return;
				}
				// Sits in the empty space above Sodium's Apply/Done buttons, matching their width.
				FlatButton simple = new FlatButton(width - 70, height - 72, 64, 18, Component.literal("Simple"), FlatButton.Style.SECONDARY,
						Icons.GEAR, b -> {
							setMenu("SIMPLE");
							minecraft.gui.setScreen(new VideoSettingsScreen(sodiumParent(screen), minecraft, minecraft.options));
						});
				simple.setTooltip(Tooltip.create(Component.literal("Minecraft's own Video Settings: fewer, simpler options")));
				Screens.getWidgets(screen).add(simple);
				// Sodium's screen handles clicks itself and doesn't pass them on to added buttons, so catch ours first.
				//? if >=1.21.9 {
				net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
					if (simple.isMouseOver(click.x(), click.y())) {
						simple.onPress(click);
						return false;
					}
					return true;
				});
				//?} else {
				/*net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, x, y, button) -> {
					if (simple.isMouseOver(x, y)) {
						simple.onPress();
						return false;
					}
					return true;
				});
				*///?}
			} else if (screen instanceof VideoSettingsScreen vanilla) {
				FlatButton more = new FlatButton(width - 156, 6, 150, 18, Component.literal("More settings (Sodium)"), FlatButton.Style.SECONDARY,
						Icons.BOLT, b -> {
							setMenu("SODIUM");
							openSodium(minecraft, lastScreen(vanilla));
						});
				more.setTooltip(Tooltip.create(Component.literal("Sodium's detailed settings for performance and quality")));
				Screens.getWidgets(screen).add(more);
			}
		});
	}

	private static void setMenu(String menu) {
		Config config = Config.get();
		config.videoMenu = menu;
		config.save();
	}

	/** Opens Sodium's Video Settings directly (Sodium is optional, so this goes through reflection). */
	public static void openSodium(Minecraft minecraft, Screen parent) {
		try {
			Method create = sodiumScreenClass().getMethod("createScreen", Screen.class);
			minecraft.gui.setScreen((Screen) create.invoke(null, parent));
		} catch (ReflectiveOperationException | LinkageError e) {
			JavaHost.LOGGER.warn("Couldn't open Sodium's video settings", e);
			minecraft.gui.setScreen(new VideoSettingsScreen(parent, minecraft, minecraft.options));
		}
	}

	private static Class<?> sodiumScreenClass() throws ClassNotFoundException {
		for (String name : SODIUM_SCREENS) {
			try {
				return Class.forName(name);
			} catch (ClassNotFoundException ignored) {
				// try the older name
			}
		}
		throw new ClassNotFoundException("Sodium's video settings screen");
	}

	private static Screen sodiumParent(Screen sodium) {
		return field(sodium, sodium.getClass(), "prevScreen");
	}

	private static Screen lastScreen(VideoSettingsScreen vanilla) {
		return ((com.javahost.client.mixin.OptionsSubScreenAccess) vanilla).javahost$lastScreen();
	}

	private static Screen field(Object owner, Class<?> type, String name) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return (Screen) field.get(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}

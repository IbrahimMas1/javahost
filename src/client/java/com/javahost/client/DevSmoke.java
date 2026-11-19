package com.javahost.client;

import java.util.ArrayList;
import java.util.List;

import com.javahost.JavaHost;
import com.javahost.client.gui.JavaHostScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * A quick in-game check, for Minecraft versions without Fabric's in-game test API (1.21.1) and for the shipped jar
 * in a production run (./gradlew :1.21.1:runSmoke, :1.21.1:runProdSmoke): it opens every JavaHost tab, creates a
 * world (1.21.1), hosts it, takes screenshots along the way and quits. Only with {@code -Djavahost.smoke=true}.
 *
 * <p>Run on a CurseForge/Modrinth copy (scripts/site-jar-test.sh), which has no self-updater, it also checks that
 * every JavaHost class in the jar still loads, that Settings and the bell work without the updater, and (on 26.3 as
 * well) enters a world before quitting normally.
 */
public final class DevSmoke {
	private static final List<Runnable> STEPS = new ArrayList<>();
	private static int wait = 100;
	private static int step;

	private DevSmoke() {
	}

	static void register() {
		if (!Boolean.getBoolean("javahost.smoke")) {
			return;
		}
		JavaHost.LOGGER.info("[Smoke] starting ({} environment)", FabricLoader.getInstance().isDevelopmentEnvironment() ? "development" : "production");
		Minecraft mc = Minecraft.getInstance();
		Config.get().tourDone = true;
		boolean siteCopy = com.javahost.util.Distribution.fromModSite();
		if (siteCopy) {
			STEPS.add(() -> {
				JavaHost.LOGGER.info("[Smoke] a copy from {}; first screen: {}", com.javahost.util.Distribution.siteName(),
						mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
				if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen)) {
					mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
				}
			});
			STEPS.add(() -> check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen, "the title screen is up"));
			STEPS.add(() -> shot("site-title"));
			STEPS.add(() -> check(!com.javahost.update.Updates.present() && !com.javahost.update.Updates.selfUpdates()
					&& com.javahost.update.Updates.availableVersion() == null && !com.javahost.update.Updates.staged(),
					"no self-updater in this copy (updates come from " + com.javahost.util.Distribution.siteName() + ")"));
			STEPS.add(DevSmoke::loadEveryClass);
		}
		STEPS.add(() -> mc.gui.setScreen(new JavaHostScreen(mc.gui.screen(), JavaHostScreen.Tab.HOME)));
		for (JavaHostScreen.Tab tab : JavaHostScreen.Tab.values()) {
			STEPS.add(() -> {
				if (mc.gui.screen() instanceof JavaHostScreen screen) {
					screen.switchTo(tab);
				}
			});
			STEPS.add(() -> shot("tab-" + tab.name().toLowerCase(java.util.Locale.ROOT)));
		}
		// Mod icons from Modrinth (mostly WebP) load with the bundled decoder.
		STEPS.add(() -> {
			if (mc.gui.screen() instanceof JavaHostScreen screen) {
				screen.switchTo(JavaHostScreen.Tab.MODS);
			}
			wait = 200;
		});
		STEPS.add(() -> check(com.javahost.client.gui.ui.WebIcons.loadedCount() > 0, "mod icons from Modrinth load (WebP decoder works)"));
		STEPS.add(() -> shot("mods-browse"));
		if (siteCopy) {
			STEPS.add(() -> {
				if (mc.gui.screen() instanceof JavaHostScreen screen) {
					screen.switchTo(JavaHostScreen.Tab.SETTINGS);
					mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40);
				}
			});
			STEPS.add(() -> {
				String status = com.javahost.client.gui.pages.SettingsPage.updatesStatus(false);
				check(status.equals("JavaHost " + JavaHost.VERSION + " · from " + com.javahost.util.Distribution.siteName()),
						"Settings → Updates says where this copy came from (\"" + status + "\")");
				shot("site-settings-updates");
			});
			STEPS.add(() -> {
				if (mc.gui.screen() instanceof JavaHostScreen screen) {
					screen.switchTo(JavaHostScreen.Tab.NOTIFICATIONS);
				}
			});
			STEPS.add(() -> {
				check(mc.gui.screen() instanceof JavaHostScreen, "the bell opens");
				shot("site-bell");
			});
		}
		//? if <26.1 {
		/*STEPS.add(() -> {
			mc.gui.setScreen(null);
			createWorld(mc);
			wait = 400;
		});
		*///?} else {
		// (26.x isn't obfuscated, so the client game tests already cover the shipped code; a site copy still goes in.)
		if (siteCopy) {
			STEPS.add(() -> {
				mc.gui.setScreen(null);
				createWorld(mc);
				wait = 400;
			});
		} else {
			STEPS.add(() -> {
				JavaHost.LOGGER.info("[Smoke] ALL STEPS DONE (menus only)");
				mc.stop();
			});
		}
		//?}
		STEPS.add(() -> check(mc.level != null && mc.getSingleplayerServer() != null, "a singleplayer world opened"));
		STEPS.add(() -> shot("in-world"));
		STEPS.add(() -> check(com.javahost.client.host.HostController.start(mc.getSingleplayerServer()) == null, "start hosting"));
		STEPS.add(() -> check(com.javahost.client.host.HostController.isHosting(), "the world is open to other players"));
		STEPS.add(() -> Toasts.notify("Smoke test", "Toasts show on this version"));
		STEPS.add(() -> shot("hud-and-toast"));
		STEPS.add(() -> mc.gui.setScreen(new JavaHostScreen(null, JavaHostScreen.Tab.HOST)));
		STEPS.add(() -> shot("host-page"));
		STEPS.add(() -> {
			com.javahost.client.host.HostController.stop();
			check(!com.javahost.client.host.HostController.isHosting(), "stop hosting closes the world to LAN");
		});
		STEPS.add(() -> {
			JavaHost.LOGGER.info("[Smoke] ALL STEPS DONE");
			mc.stop();
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	private static void tick() {
		if (step >= STEPS.size() || --wait > 0) {
			return;
		}
		wait = 20;
		Runnable next = STEPS.get(step++);
		try {
			next.run();
		} catch (RuntimeException e) {
			JavaHost.LOGGER.error("[Smoke] FAILED at step {}", step, e);
			Minecraft.getInstance().stop();
		}
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new IllegalStateException(what);
		}
		JavaHost.LOGGER.info("[Smoke] ok: {}", what);
	}

	private static void shot(String name) {
		com.javahost.client.compat.ClientCompat.screenshot("smoke-" + name + ".png", message -> {
		});
		JavaHost.LOGGER.info("[Smoke] screenshot {}", name);
	}

	private static void createWorld(Minecraft mc) {
		//? if <26.1 {
		/*var settings = new net.minecraft.world.level.LevelSettings("Smoke", net.minecraft.world.level.GameType.CREATIVE, false,
				net.minecraft.world.Difficulty.EASY, true, newRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel("smoke-" + System.currentTimeMillis(), settings,
				new net.minecraft.world.level.levelgen.WorldOptions(42, false, false),
				net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, null);
		*///?} else if >=26.3 {
		var settings = new net.minecraft.world.level.LevelSettings("Smoke", net.minecraft.world.level.GameType.CREATIVE,
				net.minecraft.world.level.LevelSettings.DifficultySettings.DEFAULT, true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel("smoke-" + System.currentTimeMillis(), settings,
				new net.minecraft.world.level.levelgen.WorldOptions(42, false, false),
				net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, null);
		//?} else {
		/*throw new UnsupportedOperationException("use the client game tests on this version");
		*///?}
	}

	/**
	 * Loads and links every JavaHost class in this jar. A class that still named a piece taken out of the jar (the
	 * updater, in a CurseForge/Modrinth copy) would fail here. Mixin classes can't be loaded directly and are skipped,
	 * as are classes that only work with another mod installed (e.g. a compatibility class for an optional mod).
	 */
	private static void loadEveryClass() {
		var container = FabricLoader.getInstance().getModContainer(JavaHost.MOD_ID).orElseThrow();
		ClassLoader loader = DevSmoke.class.getClassLoader();
		List<String> broken = new ArrayList<>();
		List<String> otherMods = new ArrayList<>();
		int loaded = 0;
		for (java.nio.file.Path root : container.getRootPaths()) {
			List<java.nio.file.Path> classes;
			try (var walk = java.nio.file.Files.walk(root)) {
				classes = walk.filter(p -> p.toString().endsWith(".class")).toList();
			} catch (java.io.IOException e) {
				throw new IllegalStateException("can't list the jar", e);
			}
			for (java.nio.file.Path file : classes) {
				String path = root.relativize(file).toString().replace('\\', '/');
				String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
				if (!name.startsWith("com.javahost.") || name.contains(".mixin.") || name.endsWith("package-info")) {
					continue;
				}
				try {
					Class<?> type = Class.forName(name, false, loader);
					type.getDeclaredMethods(); // links the class and resolves every type in its signatures
					type.getDeclaredFields();
					type.getDeclaredConstructors();
					loaded++;
				} catch (ClassNotFoundException | LinkageError e) {
					String missing = String.valueOf(e.getMessage()).replace('.', '/');
					if (missing.contains("com/javahost/")) {
						broken.add(name + " (needs " + e.getMessage() + ")");
					} else {
						otherMods.add(name);
					}
				}
			}
		}
		if (!otherMods.isEmpty()) {
			JavaHost.LOGGER.info("[Smoke] {} classes are for mods that aren't installed: {}", otherMods.size(), otherMods);
		}
		check(broken.isEmpty() && loaded > 100, "all " + loaded + " JavaHost classes load and link" + (broken.isEmpty() ? "" : ": " + broken));
	}

	//? if >=1.21.11 && <26.1 {
	/*private static net.minecraft.world.level.gamerules.GameRules newRules() {
		return new net.minecraft.world.level.gamerules.GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
	}
	*///?} else if >=1.21.2 && <1.21.11 {
	/*private static net.minecraft.world.level.GameRules newRules() {
		return new net.minecraft.world.level.GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
	}
	*///?} else if <1.21.2 {
	/*private static net.minecraft.world.level.GameRules newRules() {
		return new net.minecraft.world.level.GameRules();
	}
	*///?}
}

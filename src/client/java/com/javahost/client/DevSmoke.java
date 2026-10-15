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
		STEPS.add(() -> mc.gui.setScreen(new JavaHostScreen(mc.gui.screen(), JavaHostScreen.Tab.HOME)));
		for (JavaHostScreen.Tab tab : JavaHostScreen.Tab.values()) {
			STEPS.add(() -> {
				if (mc.gui.screen() instanceof JavaHostScreen screen) {
					screen.switchTo(tab);
				}
			});
			STEPS.add(() -> shot("tab-" + tab.name().toLowerCase(java.util.Locale.ROOT)));
		}
		//? if <26.1 {
		/*STEPS.add(() -> {
			mc.gui.setScreen(null);
			createWorld(mc);
			wait = 400;
		});
		*///?} else {
		// (26.x isn't obfuscated, so the client game tests already cover the shipped code.)
		STEPS.add(() -> {
			JavaHost.LOGGER.info("[Smoke] ALL STEPS DONE (menus only)");
			mc.stop();
		});
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
		*///?} else
		throw new UnsupportedOperationException("use the client game tests on this version");
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

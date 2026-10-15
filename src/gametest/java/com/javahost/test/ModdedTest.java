package com.javahost.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.gui.ModsNeededScreen;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.HostController;
import com.javahost.client.mods.ModSets;
import com.javahost.mods.ModScanner;
import com.javahost.util.ModManifest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Modded worlds with two real games ({@code sh scripts/modded-test.sh}): the host plays with a real content mod
 * (Naturalist, from Modrinth); the guest doesn't have it. The guest is told before Minecraft's registry check, downloads
 * the very same jar, restarts, and is taken straight back into the world. Coordinated through files in
 * {@code build/run/mod-shared}.
 */
final class ModdedTest {
	private static final Path SHARED = Path.of(System.getProperty("javahost.modDir", "build/run/mod-shared"));
	private static final int MINUTE = 20 * 60;
	private static final String MOD = "naturalist";

	private ModdedTest() {
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			result("FAILED: " + what);
			throw new AssertionError("[JavaHost mods] FAILED: " + what);
		}
		JavaHost.LOGGER.info("[JavaHost mods] ok: {}", what);
		result("ok: " + what);
	}

	private static void result(String line) {
		try {
			Files.createDirectories(SHARED);
			Files.writeString(SHARED.resolve(System.getProperty("javahost.testRole") + "-results.txt"), line + "\n", StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Also in the log.
		}
	}

	private static void write(String name, String content) {
		try {
			Files.createDirectories(SHARED);
			Files.writeString(SHARED.resolve(name), content);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static String read(String name) {
		try {
			Path file = SHARED.resolve(name);
			return Files.exists(file) ? Files.readString(file).trim() : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static void waitForFile(ClientGameTestContext context, String name, int ticks) {
		for (int i = 0; i < ticks && read(name) == null; i += 10) {
			context.waitTicks(10);
		}
		check(read(name) != null, "the other game got to \"" + name + "\"");
	}

	private static void quiet() {
		Config config = Config.get();
		config.tourDone = true;
		config.useUpnp = false;
		config.relayMode = "NEVER";
		config.friendsOnly = false;
		config.voiceEnabled = false;
		config.autoAcceptHostPacks = true;
	}

	// ================================================================ host

	static void host(ClientGameTestContext context) {
		quiet();
		check(FabricLoader.getInstance().isModLoaded(MOD), "the host plays with Naturalist");
		ModManifest mods;
		try {
			mods = ModScanner.manifest().get(2, TimeUnit.MINUTES);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		var naturalist = mods.mods().stream().filter(m -> m.id().equals(MOD)).findFirst();
		check(naturalist.isPresent() && naturalist.get().content(), "Naturalist adds content, so joining players need it");
		check(naturalist.get().onModrinth(), "Naturalist's exact file was found on Modrinth by its hash (" + naturalist.get().modrinthVersion() + ")");
		check(mods.mods().stream().noneMatch(m -> m.id().equals("sodium") || m.id().equals("iris")),
				"client-only mods (Sodium, Iris) aren't asked of friends: " + mods.mods().stream().map(ModManifest.Entry::id).toList());

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestCompat.waitForChunks(world);
			// Test accounts are offline, so the guest can't be checked with Mojang.
			context.runOnClient(mc -> mc.getSingleplayerServer().setUsesAuthentication(false));
			context.runOnClient(mc -> check(HostController.start(mc.getSingleplayerServer()) == null, "start hosting"));
			context.waitFor(mc -> !Connectivity.isProbing(), 600);
			write("address", "127.0.0.1:" + HostController.port());
			waitForFile(context, "guest-turned-away", 5 * MINUTE);
			waitForFile(context, "guest-joined", 15 * MINUTE);
			context.waitTicks(40);
			int guests = context.computeOnClient(mc -> HostController.guests().size());
			check(guests == 1, "the guest is in the world, with Naturalist like the host");
			context.takeScreenshot("modded-host-with-guest");
			write("host-done", "yes");
			waitForFile(context, "guest-left", 5 * MINUTE);
			context.runOnClient(mc -> HostController.stop());
		}
	}

	// ================================================================ guest

	static void guest(ClientGameTestContext context) {
		quiet();
		if (!FabricLoader.getInstance().isModLoaded(MOD)) {
			guestFirstVisit(context);
		} else {
			guestAfterRestart(context);
		}
	}

	/** No Naturalist yet: turned away with a list, downloads it, and gets ready to restart. */
	private static void guestFirstVisit(ClientGameTestContext context) {
		waitForFile(context, "address", 5 * MINUTE);
		String address = read("address");
		context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
				new ServerData("Host's world", address, ServerData.Type.OTHER), false, null));
		context.waitFor(mc -> mc.gui.screen() instanceof ModsNeededScreen || mc.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen,
				2 * MINUTE);
		context.waitTicks(5);
		context.takeScreenshot("modded-guest-after-connecting");
		String screen = context.computeOnClient(mc -> mc.gui.screen().getClass().getSimpleName() + ": " + mc.gui.screen().getNarrationMessage().getString());
		check(screen.startsWith("ModsNeededScreen"), "the guest sees JavaHost's mod list (" + screen + ")");
		check(true, "turned away before the registry check, with JavaHost's list instead of \"registry mismatch\"");
		ModSets.Needed needed = context.computeOnClient(mc -> ModSets.needed());
		check(needed.missing().stream().anyMatch(m -> m.id().equals(MOD)), "the list says Naturalist is missing");
		write("guest-turned-away", "yes");
		context.waitTicks(10);
		context.takeScreenshot("modded-guest-mods-needed");
		clickButton(context, "Download & restart");
		context.waitFor(mc -> hasButton(mc, "Close Minecraft"), 3 * MINUTE);
		check(context.computeOnClient(mc -> ModSets.staged()), "Naturalist downloaded (hash checked) and the switch waits for Minecraft to close");
		check(Files.exists(ModSets.dir().resolve("pending-join.properties")), "where to rejoin is remembered");
		context.takeScreenshot("modded-guest-ready");
		clickButton(context, "Later"); // back to the title screen; closing the game starts the switch
		// The test ends here; closing the game starts the switch, and scripts/modded-test.sh starts the guest again.
	}

	/** Started again with Naturalist switched in: straight back into the host's world. */
	private static void guestAfterRestart(ClientGameTestContext context) {
		check(context.computeOnClient(mc -> ModSets.activeLabel()) != null, "the guest's game knows it's on the host's mod set");
		context.setScreen(TitleScreen::new);
		context.waitFor(mc -> mc.level != null && mc.player != null, 3 * MINUTE);
		check(true, "rejoined the host's world automatically after restarting");
		context.waitTicks(60);
		context.takeScreenshot("modded-guest-in-world");
		write("guest-joined", "yes");
		waitForFile(context, "host-done", 5 * MINUTE);
		context.runOnClient(mc -> com.javahost.client.compat.ClientCompat.leaveWorld(net.minecraft.network.chat.Component.literal("Test done")));
		context.waitTicks(20);
		try {
			ModSets.stageRestore();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		check(ModSets.staged(), "\"Back to my mods\" waits for Minecraft to close");
		context.setScreen(() -> new com.javahost.client.gui.JavaHostScreen(null, com.javahost.client.gui.JavaHostScreen.Tab.MODS));
		context.waitTicks(30);
		context.takeScreenshot("modded-guest-mods-tab");
		context.setScreen(TitleScreen::new);
		write("guest-left", "yes");
	}

	private static boolean hasButton(net.minecraft.client.Minecraft mc, String label) {
		return mc.gui.screen() != null && mc.gui.screen().children().stream().anyMatch(c ->
				c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.getMessage().getString().equals(label));
	}

	private static void clickButton(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.active
						&& w.getMessage().getString().equals(label)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("no button " + label);
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}
}

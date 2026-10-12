package com.javahost.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;

import com.javahost.HostState;
import com.javahost.HostState.SharedPack;
import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.PackOfferScreen;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.E4mcCompat;
import com.javahost.client.host.HostController;
import com.javahost.client.pack.PackFiles;
import com.javahost.client.voice.VoiceClient;
import com.javahost.net.InviteCode;
import com.javahost.net.Payloads.PackInfo;
import com.javahost.net.Payloads.PackKind;
import com.javahost.net.Payloads.VoiceUp;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;

/**
 * End-to-end checks run inside the real game: {@code ./gradlew runClientGameTest}.
 * Screenshots land in build/run/clientGameTest/screenshots.
 */
public class JavaHostClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		String role = System.getProperty("javahost.testRole");
		if ("host".equals(role)) {
			MultiplayerTest.host(context);
			return;
		}
		if ("guest".equals(role)) {
			MultiplayerTest.guest(context);
			return;
		}
		inviteCodes();
		opusRoundTrip();

		// Keep the test off the user's router and microphone.
		Config config = Config.get();
		config.useUpnp = false;
		config.relayMode = "NEVER";
		E4mcCompat.setRelayEnabled(false);
		config.voiceEnabled = false;
		config.autoAcceptHostPacks = true;

		guestReceivesHostPacks(context);
		hostingASingleplayerWorld(context);
		JavaHost.LOGGER.info("[JavaHost test] ALL CHECKS PASSED");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("[JavaHost test] FAILED: " + what);
		}
		JavaHost.LOGGER.info("[JavaHost test] ok: {}", what);
	}

	private static void inviteCodes() {
		String code = InviteCode.encode("203.0.113.42", 25565);
		check(code != null && code.matches("JH-[0-9A-Z]{5}-[0-9A-Z]{5}"), "invite code format (" + code + ")");
		check("203.0.113.42:25565".equals(InviteCode.decode(code)), "invite code round trip");
		check("203.0.113.42:25565".equals(InviteCode.decode(code.toLowerCase().replace("-", " "))), "invite code is forgiving about case/spacing");
		check("play.example.net".equals(InviteCode.decode("play.example.net")), "plain address passes through");
		check("jhserver.net".equals(InviteCode.decode("jhserver.net")), "hostname starting with jh passes through");
	}

	private static void opusRoundTrip() {
		try {
			OpusEncoder encoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
			encoder.setBitrate(24000);
			OpusDecoder decoder = new OpusDecoder(48000, 1);
			short[] tone = new short[960];
			short[] out = new short[960];
			byte[] packet = new byte[1024];
			double energyIn = 0;
			double energyOut = 0;
			for (int frame = 0; frame < 10; frame++) {
				for (int i = 0; i < 960; i++) {
					tone[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * (frame * 960 + i) / 48000.0));
				}
				int length = encoder.encode(tone, 0, 960, packet, 0, packet.length);
				check(length > 0 && length < 200, "opus frame size " + length + " bytes");
				int samples = decoder.decode(packet, 0, length, out, 0, 960, false);
				check(samples == 960, "opus decoded a full 20 ms frame");
				if (frame >= 5) {
					for (int i = 0; i < 960; i++) {
						energyIn += tone[i] * (double) tone[i];
						energyOut += out[i] * (double) out[i];
					}
				}
			}
			double ratio = energyOut / energyIn;
			check(ratio > 0.5 && ratio < 1.5, "opus keeps the voice level (ratio " + String.format("%.2f", ratio) + ")");
		} catch (Exception e) {
			throw new AssertionError("Opus round trip failed", e);
		}
	}

	/** A pack big enough to need several chunks, so flow control and reassembly are exercised. */
	private static Path writeTestPack(Path file) throws IOException {
		var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
		Files.createDirectories(file.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("pack.mcmeta"));
			zip.write(("{\"pack\":{\"description\":\"JavaHost test pack\",\"min_format\":" + format.major()
					+ ",\"max_format\":" + format.major() + "}}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("assets/javahost_test/filler.bin"));
			zip.setLevel(0);
			byte[] filler = new byte[1024 * 1024];
			new java.util.Random(42).nextBytes(filler);
			for (int i = 0; i < 3; i++) {
				zip.write(filler); // ~3 MB of incompressible data -> 12 chunks
			}
			zip.closeEntry();
		}
		return file;
	}

	private static void guestReceivesHostPacks(ClientGameTestContext context) {
		Path pack;
		String sha1;
		Path cache = context.computeOnClient(mc -> PackFiles.cache());
		try {
			pack = writeTestPack(context.computeOnClient(mc -> PackFiles.root()).resolve("test/test-pack.zip"));
			sha1 = PackFiles.sha1(pack);
			// Clear any cached copy from an earlier run so the download really happens.
			Files.deleteIfExists(cache.resolve(sha1 + ".zip"));
			HostState.setSharedPacks(List.of(new SharedPack(
					new PackInfo(sha1, PackKind.RESOURCE_PACK, "Test Pack", Files.size(pack)), pack)), "");
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();

			context.waitFor(mc -> VoiceClient.isAvailable(), 200);
			check(true, "server announced voice chat to the joining player");

			context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().anyMatch(id -> id.startsWith("server/")), 1200);
			check(Files.exists(cache.resolve(sha1 + ".zip")), "pack streamed in chunks, checksum verified and cached");
			check(true, "host pack applied as a server resource pack");
			context.takeScreenshot("javahost-guest-with-host-pack");

			// Voice frames go through the relay without errors even with nobody else to hear them.
			context.runOnClient(mc -> ClientPlayNetworking.send(new VoiceUp(0, new byte[] {1, 2, 3})));
			connection.waitForServerboundPackets();
			check(true, "voice frame accepted by the server relay");
		}
		context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().noneMatch(id -> id.startsWith("server/")), 600);
		check(true, "host pack removed again after leaving");
		HostState.clear();
	}

	private static void hostingASingleplayerWorld(ClientGameTestContext context) {
		// Enable a user resource pack so the host has something to share.
		context.runOnClient(mc -> {
			Path userPack;
			try {
				userPack = writeTestPack(mc.getResourcePackDirectory().resolve("my-pack.zip"));
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			mc.getResourcePackRepository().reload();
			List<String> selected = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
			selected.add("file/" + userPack.getFileName());
			mc.getResourcePackRepository().setSelected(selected);
		});

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// Every tab before hosting.
			for (Tab tab : Tab.values()) {
				context.setScreen(() -> new JavaHostScreen(null, tab));
				context.waitTicks(4);
				context.takeScreenshot("ui-" + tab.name().toLowerCase() + (tab == Tab.HOST ? "-setup" : ""));
			}
			// The Host tab scrolled down, to see the bottom of the settings.
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
			context.waitTicks(10);
			context.takeScreenshot("ui-host-setup-scrolled");

			context.setScreen(() -> new PackOfferScreen(List.of(
					new PackInfo("0".repeat(40), PackKind.RESOURCE_PACK, "Faithful 32x", 18_400_000L),
					new PackInfo("1".repeat(40), PackKind.SHADER_PACK, "Complementary Reimagined r5.5.zip", 6_100_000L)),
					24_500_000L, yes -> {
					}));
			context.waitTicks(4);
			context.takeScreenshot("ui-pack-offer");
			context.setScreen(() -> null);

			context.runOnClient(mc -> check(HostController.start(mc.getSingleplayerServer()) == null, "start hosting"));
			context.runOnClient(mc -> check(mc.getSingleplayerServer().isPublished(), "world is open to other players"));
			context.waitFor(mc -> !HostState.sharedPacks().isEmpty(), 600);
			check(HostState.sharedPacks().getFirst().info().size() > 1_000_000, "host's resource pack zipped and hashed for sharing");
			context.waitFor(mc -> !Connectivity.isProbing(), 600);
			check(Connectivity.lanIp() != null, "found LAN address " + Connectivity.lanIp());

			context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
			context.waitTicks(10);
			context.takeScreenshot("ui-host-hosting");
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
			context.waitTicks(4);
			context.takeScreenshot("ui-home-hosting");

			context.setScreen(() -> null);
			newFeatures(context);
			context.runOnClient(mc -> HostController.stop());
			context.runOnClient(mc -> check(!mc.getSingleplayerServer().isPublished(), "stop hosting closes the world again"));
		}
		restoreBackup(context);
		// The title screen with the friends panel, with an invite waiting.
		java.util.UUID alex = java.util.UUID.nameUUIDFromBytes("Alex".getBytes());
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.INVITE, alex, "Alex",
				"Alex invited you to Survival Island", new com.javahost.client.friends.Friends.Invite(alex, "Alex", "Survival Island", List.of(), "",
						System.currentTimeMillis() + 600_000)));
		// The title screen on a big window (like a laptop's 2000x1250) at each common GUI scale.
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.getInput().resizeWindow(2000, 1250);
		for (int guiScale : new int[] {4, 3, 2}) {
			context.runOnClient(mc -> {
				mc.options.guiScale().set(guiScale);
				mc.resizeGui();
			});
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			context.waitTicks(10);
			context.takeScreenshot("ui-title-scale" + guiScale);
			hoverDock(context, "Friends");
			context.waitTicks(8);
			context.takeScreenshot("ui-title-scale" + guiScale + "-hover");
			context.getInput().setCursorPos(0, 0);
			// The screenshot preview keeps its buttons on screen at every scale.
			context.setScreen(() -> new JavaHostScreen(null, Tab.SCREENSHOTS));
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(com.javahost.client.friends.Screenshots.list(false).getFirst()));
			context.waitTicks(8);
			context.takeScreenshot("ui-preview-scale" + guiScale);
		}
		// Menu icons at GUI scale 3 (the in-between size).
		context.runOnClient(mc -> {
			mc.options.guiScale().set(3);
			mc.resizeGui();
		});
		for (Tab tab : List.of(Tab.FRIENDS, Tab.SETTINGS, Tab.ACCOUNT)) {
			context.setScreen(() -> new JavaHostScreen(null, tab));
			context.waitTicks(6);
			context.takeScreenshot("ui-scale3-" + tab.name().toLowerCase());
		}
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			mc.resizeGui();
		});
		context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		context.waitTicks(10);
		context.takeScreenshot("ui-title-small-window");
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.items().forEach(com.javahost.client.friends.Notifications::remove));
	}

	/** A worst-case screenshot (random noise doesn't compress): written as a PNG of the given size. */
	static Path writeNoisePng(Path file, int width, int height) {
		try (com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(width, height, false)) {
			java.util.Random random = new java.util.Random(42);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setPixel(x, y, 0xFF000000 | random.nextInt(0xFFFFFF));
				}
			}
			java.nio.file.Files.createDirectories(file.getParent());
			image.writeToFile(file);
			return file;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** UI review at the player's real setup: a 2000x1250 window at GUI scale 4 (and 3), every screen captured. */
	private static void reviewAtScale(ClientGameTestContext context, int guiScale) {
		reviewAtScale(context, guiScale, 2000, 1250);
	}

	private static void reviewAtScale(ClientGameTestContext context, int guiScale, int windowW, int windowH) {
		int previous = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.getInput().resizeWindow(windowW, windowH);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		Config.get().fpsOverlay = true;
		context.setScreen(() -> null);
		context.waitTicks(10);
		context.takeScreenshot("review-" + windowW + "-s" + guiScale + "-hud");
		Config.get().fpsOverlay = false;
		for (Tab tab : Tab.values()) {
			if (tab == Tab.PROFILE || tab == Tab.CHAT) {
				continue; // need a friend; covered by the two-game test
			}
			context.setScreen(() -> new JavaHostScreen(null, tab));
			context.waitTicks(8);
			context.takeScreenshot("review-" + windowW + "-s" + guiScale + "-" + tab.name().toLowerCase());
		}
		context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
		context.waitTicks(8);
		context.takeScreenshot("review-" + windowW + "-s" + guiScale + "-pause");
		context.setScreen(() -> null);
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(previous);
			mc.resizeGui();
		});
		context.waitTicks(4);
	}

	/** 1.4: big screenshots fit the relay, the video settings switch, and world backups. */
	private static void version14(ClientGameTestContext context) {
		// Screenshot size: ntfy.sh takes at most 2 MB per file.
		Path big = context.computeOnClient(mc -> writeNoisePng(com.javahost.client.friends.Screenshots.dir().resolve("zz-noise-test.png"), 2560, 1440));
		try {
			long original = java.nio.file.Files.size(big);
			byte[] sendable = com.javahost.client.friends.Screenshots.prepareForSending(big);
			check(sendable.length <= 1_900_000 && com.javahost.client.friends.Screenshots.isPng(sendable),
					"a " + original / 1024 + " KB screenshot is scaled to " + sendable.length / 1024 + " KB for the relay (limit 2 MB)");
			byte[] forGame = com.javahost.client.friends.Screenshots.prepareForGame(big);
			check(forGame.length > 2_000_000 && forGame.length <= 3_800_000, "the in-game fast lane keeps it bigger (" + forGame.length / 1024 + " KB)");
			java.nio.file.Files.delete(big);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		// Video settings: Simple (Minecraft's screen) or Sodium, with a button on each to switch.
		if (com.javahost.client.perf.VideoMenu.sodiumInstalled()) {
			Config config = Config.get();
			config.videoMenu = "SIMPLE";
			context.runOnClient(mc -> com.javahost.client.perf.VideoMenu.openSodium(mc, null));
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen),
					"with Simple chosen, Video Settings opens Minecraft's own screen");
			context.takeScreenshot("video-simple");
			clickMenu(context, "More settings (Sodium)");
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen().getClass().getName().contains("sodium")) && "SODIUM".equals(config.videoMenu),
					"\"More settings (Sodium)\" opens Sodium's screen and remembers it");
			context.takeScreenshot("video-sodium");
			clickMenu(context, "Simple");
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen)
					&& "SIMPLE".equals(config.videoMenu), "\"Simple\" switches back");
			context.setScreen(() -> null);
		}

		// World backup while playing.
		java.util.concurrent.CompletableFuture<Path> backup = context.computeOnClient(mc -> {
			java.util.concurrent.CompletableFuture<Path> done = new java.util.concurrent.CompletableFuture<>();
			mc.getSingleplayerServer().execute(() -> com.javahost.client.host.WorldBackups.backUp(mc.getSingleplayerServer(), "test")
					.whenComplete((file, error) -> {
						if (error != null) {
							done.completeExceptionally(error);
						} else {
							done.complete(file);
						}
					}));
			return done;
		});
		context.waitFor(mc -> backup.isDone(), 60 * 20);
		Path zip = backup.join();
		check(java.nio.file.Files.exists(zip), "world backed up while playing: " + zip.getFileName());
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
				mc.getWindow().getGuiScaledHeight() / 2.0, 0, -20));
		context.waitTicks(10);
		context.takeScreenshot("ui-host-backups");
		context.setScreen(() -> null);
	}

	/** After leaving the world: restoring a backup keeps the current world as a copy. */
	private static void restoreBackup(ClientGameTestContext context) {
		var backups = context.computeOnClient(mc -> com.javahost.client.host.WorldBackups.listAll());
		check(!backups.isEmpty(), "backups are listed after leaving the world");
		var newest = backups.getFirst();
		String aside = context.computeOnClient(mc -> {
			try {
				return com.javahost.client.host.WorldBackups.restore(newest);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		Path saves = context.computeOnClient(mc -> mc.getLevelSource().getBaseDir());
		check(java.nio.file.Files.exists(saves.resolve(newest.world()).resolve("level.dat")), "restore puts the backup back as " + newest.world());
		check(aside == null || java.nio.file.Files.exists(saves.resolve(aside)), "the world it replaced is kept as \"" + aside + "\"");
		context.setScreen(() -> new JavaHostScreen(new net.minecraft.client.gui.screens.TitleScreen(), Tab.HOST));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
				mc.getWindow().getGuiScaledHeight() / 2.0, 0, -30));
		context.waitTicks(12);
		context.takeScreenshot("ui-backups-no-world");
	}

	/** Everything in the menu can be reached and left with the mouse: ← goes back, ✕ closes. */
	private static void navigation(ClientGameTestContext context, Path file) {
		context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
		context.waitTicks(4);
		clickMenu(context, "Join with a code");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.JOIN, "clicking opens the Join page");
		context.takeScreenshot("ui-join-with-back");
		clickMenu(context, "Back");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.FRIENDS, "← on Join goes back to Friends");

		context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
		context.waitTicks(4);
		clickMenu(context, "Notifications");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.NOTIFICATIONS, "the bell opens notifications");
		clickMenu(context, "Back");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.HOME, "← on notifications goes back to Home");

		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(file));
		context.waitTicks(6);
		clickMenu(context, "Send to a friend");
		context.waitTicks(4);
		context.takeScreenshot("ui-screenshot-send-picker");
		clickMenu(context, "Back");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).selectedScreenshot()) == null, "← on a screenshot goes back to the grid");
		context.waitTicks(4);
		context.takeScreenshot("ui-screenshots-grid-back");
		// Hovering a thumbnail shows its send shortcut; clicking it opens the preview with the friend list open.
		double[] corner = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof com.javahost.client.gui.ui.ImageButton w) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getRight() - 10) * scale, (w.getY() + 9) * scale};
				}
			}
			throw new AssertionError("no thumbnails");
		});
		context.getInput().setCursorPos(corner[0], corner[1]);
		context.waitTicks(3);
		context.takeScreenshot("ui-screenshots-hover-send");
		context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(4);
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).selectedScreenshot()) != null,
				"a thumbnail's send shortcut opens that screenshot");
		context.takeScreenshot("ui-screenshots-shortcut-picker");
		clickMenu(context, "Back");

		clickMenu(context, "Close");
		check(context.computeOnClient(mc -> mc.gui.screen() == null), "✕ closes the menu");
	}

	/** Clicks a visible button in the current screen with the real mouse. */
	private static void clickMenu(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.active
						&& w.getMessage().getString().equals(label)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("no button \"" + label + "\" on " + mc.gui.screen());
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(4);
	}

	/** Moves the mouse over a dock button so its label slides out. */
	private static void hoverDock(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getClass().getName().contains("MenuDock")
						&& w.getMessage().getString().equals(label)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("no dock button " + label);
		});
		context.getInput().setCursorPos(at[0], at[1]);
	}

	/** Typing in a menu text box and pressing Enter submits it (like clicking the button next to it). */
	private static void enterSubmits(ClientGameTestContext context) {
		context.setScreen(() -> new JavaHostScreen(null, Tab.JOIN));
		context.waitTicks(4);
		context.getInput().typeChars(" "); // blank: submitting shows a message without connecting anywhere
		context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
		context.waitTicks(4);
		String message = context.computeOnClient(mc -> {
			try {
				Object page = field(mc.gui.screen(), "page");
				return (String) field(page, "error");
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		context.takeScreenshot("ui-join-enter");
		check(message != null, "Enter in a menu text box submits it (" + message + ")");
		context.setScreen(() -> null);
	}

	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(owner);
	}

	/** 1.1: FPS overlay, benchmark, screenshots page, notification center and accent themes. */
	private static void newFeatures(ClientGameTestContext context) {
		enterSubmits(context);
		context.setScreen(() -> new JavaHostScreen(null, Tab.ACCOUNT));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
				mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
		context.waitTicks(10);
		context.takeScreenshot("ui-account-scrolled");
		context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
		context.waitTicks(6);
		hoverDock(context, "Host");
		context.waitTicks(8);
		context.takeScreenshot("ui-pause-dock");
		context.getInput().setCursorPos(0, 0);
		Config config = Config.get();
		config.fpsOverlay = true;
		long started = System.currentTimeMillis();
		context.runOnClient(mc -> com.javahost.client.perf.Benchmark.start("Test run"));
		context.waitTicks(60);
		context.takeScreenshot("hud-fps-and-benchmark");
		context.waitFor(mc -> !com.javahost.client.perf.Benchmark.running(), 40 * 20);
		check(!config.benchmarks.isEmpty() && config.benchmarks.getLast().time() >= started && config.benchmarks.getLast().averageFps() > 0,
				"benchmark recorded " + Math.round(config.benchmarks.getLast().averageFps()) + " FPS average, "
						+ Math.round(config.benchmarks.getLast().onePercentLowFps()) + " FPS 1% low");
		config.fpsOverlay = false;
		context.setScreen(() -> new JavaHostScreen(null, Tab.PERFORMANCE));
		context.waitTicks(6);
		context.takeScreenshot("ui-performance-benchmark");

		context.setScreen(() -> null);
		context.waitTicks(3);
		java.util.concurrent.CompletableFuture<Path> shot = new java.util.concurrent.CompletableFuture<>();
		context.runOnClient(mc -> com.javahost.client.friends.Screenshots.take(shot::complete));
		context.waitFor(mc -> shot.isDone(), 200);
		Path file = shot.join();
		try {
			check(com.javahost.client.friends.Screenshots.isPng(java.nio.file.Files.readAllBytes(file)), "screenshot key saves a PNG: " + file.getFileName());
			byte[] scaled = com.javahost.client.friends.Screenshots.prepareForSending(file);
			check(com.javahost.client.friends.Screenshots.isPng(scaled) && scaled.length < 14_000_000, "screenshot prepared for sending (" + scaled.length / 1024 + " KB)");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		context.setScreen(() -> new JavaHostScreen(null, Tab.SCREENSHOTS));
		context.waitTicks(10);
		context.takeScreenshot("ui-screenshots-grid");
		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(file));
		context.waitTicks(10);
		context.takeScreenshot("ui-screenshots-preview");
		navigation(context, file);
		version14(context);
		reviewAtScale(context, 4);
		reviewAtScale(context, 3);
		// A laptop-sized window, where the menu switches to its compact icon sidebar.
		reviewAtScale(context, 4, 1710, 1070);
		reviewAtScale(context, 3, 1280, 800);

		java.util.UUID alex = java.util.UUID.nameUUIDFromBytes("Alex".getBytes());
		context.runOnClient(mc -> {
			com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.ACCEPTED, alex, "Alex", "Alex accepted your request", null);
			com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.MESSAGE, alex, "Alex", "want to play later?", null);
		});
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
		context.waitTicks(6);
		context.takeScreenshot("ui-home-unread-bell");
		context.setScreen(() -> new JavaHostScreen(null, Tab.NOTIFICATIONS));
		context.waitTicks(6);
		context.takeScreenshot("ui-notifications");
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.items().forEach(com.javahost.client.friends.Notifications::remove));

		for (String accent : List.of("Blue", "Purple", "Orange", "Pink", "Green")) {
			config.accent = accent;
			context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
			context.waitTicks(6);
			if (!accent.equals("Green")) {
				context.takeScreenshot("ui-settings-" + accent.toLowerCase());
			}
		}
		check(com.javahost.client.gui.ui.Theme.ACCENT == com.javahost.client.gui.ui.Theme.ACCENTS.get("Green"), "accent colours switch and switch back");
		context.setScreen(() -> null);
	}
}

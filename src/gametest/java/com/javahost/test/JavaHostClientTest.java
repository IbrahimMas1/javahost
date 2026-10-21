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
		if ("modhost".equals(role)) {
			ModdedTest.host(context);
			return;
		}
		if ("modguest".equals(role)) {
			ModdedTest.guest(context);
			return;
		}
		//? if >=26.3 {
		if ("showcase".equals(role)) {
			Showcase.run(context); // website screenshots are only taken on 26.3
			return;
		}
		//?}
		Config.get().tourDone = true; // the welcome tour is tested on its own below
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
		Files.createDirectories(file.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("pack.mcmeta"));
			zip.write(TestCompat.packMcmeta("JavaHost test pack").getBytes(StandardCharsets.UTF_8));
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
				var connection = server.connect()) {
			//? if >=26.2 {
			connection.waitForChunksRender();
			//?} else if >=26.1 {
			/*connection.getClientLevel().waitForChunksRender();
			*///?} else
			/*connection.getClientWorld().waitForChunksRender();*/

			context.waitFor(mc -> VoiceClient.isAvailable(), 200);
			check(true, "server announced voice chat to the joining player");

			context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().anyMatch(id -> id.startsWith("server/")), 1200);
			check(Files.exists(cache.resolve(sha1 + ".zip")), "pack streamed in chunks, checksum verified and cached");
			check(true, "host pack applied as a server resource pack");
			context.takeScreenshot("javahost-guest-with-host-pack");

			// Voice frames go through the relay without errors even with nobody else to hear them.
			context.runOnClient(mc -> ClientPlayNetworking.send(new VoiceUp(0, new byte[] {1, 2, 3})));
			//? if >=26.2 {
			connection.waitForServerboundPackets();
			//?} else
			/*context.waitTicks(20);*/
			check(true, "voice frame accepted by the server relay");
		}
		context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().noneMatch(id -> id.startsWith("server/")), 600);
		check(true, "host pack removed again after leaving");
		// Removing the pack reloads resources; let that finish before the next world opens.
		context.waitFor(TestCompat::noOverlay, 1200);
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
			TestCompat.waitForChunks(world);

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
						System.currentTimeMillis() + 600_000, com.javahost.client.compat.ClientCompat.minecraftVersion())));
		// The title screen on a big window (like a laptop's 2000x1250) at each common GUI scale.
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.getInput().resizeWindow(2000, 1250);
		for (int guiScale : new int[] {4, 3, 2}) {
			context.runOnClient(mc -> {
				mc.options.guiScale().set(guiScale);
				TestCompat.resizeGui(mc);
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
			TestCompat.resizeGui(mc);
		});
		for (Tab tab : List.of(Tab.FRIENDS, Tab.SETTINGS, Tab.ACCOUNT)) {
			context.setScreen(() -> new JavaHostScreen(null, tab));
			context.waitTicks(6);
			context.takeScreenshot("ui-scale3-" + tab.name().toLowerCase());
		}
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			TestCompat.resizeGui(mc);
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
			TestCompat.resizeGui(mc);
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
			TestCompat.resizeGui(mc);
		});
		context.waitTicks(4);
	}

	// ================================================================ 1.5

	private static String sha256(byte[] data) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
	}

	/** A pretend GitHub release on localhost, signed with a throwaway key the game is told to trust. */
	private static com.sun.net.httpserver.HttpServer fakeRelease(String version, int protocol, boolean tamper) throws Exception {
		return fakeRelease(version, protocol, tamper, "0");
	}

	private static com.sun.net.httpserver.HttpServer fakeRelease(String version, int protocol, boolean tamper, String minimum) throws Exception {
		java.security.KeyPair keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		byte[] jar = ("not really a jar " + version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
		String base = "http://localhost:" + server.getAddress().getPort();
		String json = "{\"version\": \"" + version + "\", \"protocol\": " + protocol + ", \"minecraft\": \"" + com.javahost.client.update.UpdateScreen.minecraftVersion() + "\", "
				+ "\"notes\": \"Faster joining, world rules and a welcome tour.\", \"releasePage\": \"" + base + "\", \"minimum\": \"" + minimum + "\", "
				+ "\"files\": [{\"name\": \"javahost-" + version + ".jar\", \"url\": \"" + base + "/javahost-" + version + ".jar\", "
				+ "\"sha256\": \"" + sha256(jar) + "\", \"size\": " + jar.length + ", \"replaces\": \"javahost-[0-9].*\\\\.jar\"}]}";
		byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(jsonBytes);
		byte[] sig = java.util.Base64.getEncoder().encode(signer.sign());
		byte[] served = tamper ? ("tampered " + version).getBytes(java.nio.charset.StandardCharsets.UTF_8) : jar;
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			byte[] body = path.endsWith("update.json") ? jsonBytes : path.endsWith(".sig") ? sig : path.endsWith(".jar") ? served : new byte[0];
			exchange.sendResponseHeaders(body.length == 0 ? 404 : 200, body.length == 0 ? -1 : body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		System.setProperty("javahost.updateUrl", base);
		System.setProperty("javahost.updateKey", java.util.Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
		return server;
	}

	private static void updateFlow(ClientGameTestContext context, String version, int protocol, boolean tamper, String shot) throws Exception {
		com.sun.net.httpserver.HttpServer server = fakeRelease(version, protocol, tamper);
		try {
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(null));
			context.waitFor(mc -> mc.gui.screen() instanceof com.javahost.client.update.UpdateScreen, 400);
			check(true, "update " + version + " offered on the title screen");
			context.waitTicks(4);
			context.takeScreenshot(shot + "-offer");
			clickMenu(context, "Not now");
			context.waitTicks(4);
			context.takeScreenshot(shot + "-not-now");
			clickMenu(context, "Update now");
			context.waitFor(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
					&& (w.getMessage().getString().equals("Close Minecraft") || w.getMessage().getString().equals("Try again"))), 400);
			context.takeScreenshot(shot + "-result");
			boolean staged = context.computeOnClient(mc -> com.javahost.client.update.Updater.staged());
			if (tamper) {
				check(!staged, "a damaged or tampered download is refused (nothing staged)");
			} else {
				check(staged, "the update is downloaded, verified and waiting for the game to close");
				java.nio.file.Path plan = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("javahost/updates/plan.properties"));
				check(java.nio.file.Files.readString(plan).contains("javahost-" + version + ".jar"), "the swap plan names the new jar");
				java.nio.file.Files.delete(plan); // don't actually swap jars when this test game closes
			}
			context.setScreen(() -> null);
		} finally {
			server.stop(0);
			System.clearProperty("javahost.updateUrl");
			System.clearProperty("javahost.updateKey");
		}
	}

	private static FlatButtonFinder settingsButton(ClientGameTestContext context, String label) {
		return new FlatButtonFinder(context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.getMessage().getString().equals(label))
				.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c).findFirst().orElse(null)));
	}

	private record FlatButtonFinder(net.minecraft.client.gui.components.AbstractWidget widget) {
	}

	/** 1.5.2: a rollback can be cancelled; important releases can't be undone or skipped. */
	private static void rollbackAndImportantUpdates(ClientGameTestContext context) {
		java.nio.file.Path previous = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("javahost/updates/previous"));
		try {
			java.nio.file.Files.createDirectories(previous);
			java.nio.file.Files.writeString(previous.resolve("javahost-1.0.0.jar"), "pretend old version");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		// Restore, then change your mind.
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40));
		context.waitTicks(10);
		clickMenu(context, "Restore 1.0.0");
		clickMenu(context, "Click again to confirm");
		check("rollback".equals(context.computeOnClient(mc -> com.javahost.client.update.Updater.stagedKind())), "Restore (confirmed) waits for Minecraft to close");
		context.waitTicks(6);
		context.takeScreenshot("ui-settings-rollback-pending");
		clickMenu(context, "Cancel going back");
		check(!context.computeOnClient(mc -> com.javahost.client.update.Updater.staged()), "a pending rollback can be cancelled");

		// An important release that 1.0.0 is older than: restoring it is blocked, and a pending rollback is called off.
		context.runOnClient(mc -> {
			try {
				com.javahost.client.update.Updater.stageRollBack();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		try {
			com.sun.net.httpserver.HttpServer server = fakeRelease(com.javahost.JavaHost.VERSION, com.javahost.JavaHost.PROTOCOL, false, com.javahost.JavaHost.VERSION);
			try {
				java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
				context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(() -> done.complete(null)));
				context.waitFor(mc -> done.isDone(), 400);
			} finally {
				server.stop(0);
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		check(!context.computeOnClient(mc -> com.javahost.client.update.Updater.staged()),
				"a pending rollback below an important release is cancelled automatically");
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40));
		context.waitTicks(10);
		var restore = settingsButton(context, "Restore 1.0.0").widget();
		check(restore != null && !restore.active, "Restore is blocked when the previous version is older than an important update");
		context.takeScreenshot("ui-settings-rollback-blocked");

		// An important update for us: no "Not now" / "Keep", only "Remind me soon".
		try {
			com.sun.net.httpserver.HttpServer server = fakeRelease("9.9.11", com.javahost.JavaHost.PROTOCOL, false, "9.9.11");
			try {
				context.setScreen(() -> null);
				java.util.concurrent.CompletableFuture<Void> found = new java.util.concurrent.CompletableFuture<>();
				context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(() -> found.complete(null)));
				context.waitFor(mc -> found.isDone(), 400);
				context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
				context.waitFor(mc -> mc.gui.screen() instanceof com.javahost.client.update.UpdateScreen, 400);
				context.waitTicks(4);
				check(settingsButton(context, "Remind me soon").widget() != null && settingsButton(context, "Not now").widget() == null,
						"an important update can't be skipped, only put off for a few minutes");
				context.takeScreenshot("update-important");
				clickMenu(context, "Remind me soon");
			} finally {
				server.stop(0);
				System.clearProperty("javahost.updateUrl");
				System.clearProperty("javahost.updateKey");
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		try (var files = java.nio.file.Files.walk(previous)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		context.setScreen(() -> null);
	}

	/** 1.13: installed by a launcher (CurseForge, Modrinth App): say what's new and to update there, never swap jars. */
	private static void launcherManagedUpdate(ClientGameTestContext context) {
		System.setProperty("javahost.test.launcherManaged", "true");
		try {
			com.sun.net.httpserver.HttpServer server = fakeRelease("9.9.12", com.javahost.JavaHost.PROTOCOL, false);
			try {
				context.setScreen(() -> null);
				java.util.concurrent.CompletableFuture<Void> found = new java.util.concurrent.CompletableFuture<>();
				context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(() -> found.complete(null)));
				context.waitFor(mc -> found.isDone(), 400);
				context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
				context.waitFor(mc -> mc.gui.screen() instanceof com.javahost.client.update.UpdateScreen, 400);
				context.waitTicks(4);
				check(settingsButton(context, "What's new").widget() != null && settingsButton(context, "Update now").widget() == null,
						"a launcher-installed JavaHost points to the launcher instead of updating itself");
				context.takeScreenshot("update-launcher");
				clickMenu(context, "OK");
				check(!context.computeOnClient(mc -> com.javahost.client.update.Updater.staged()), "nothing is downloaded for a launcher install");
			} finally {
				server.stop(0);
				System.clearProperty("javahost.updateUrl");
				System.clearProperty("javahost.updateKey");
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		} finally {
			System.clearProperty("javahost.test.launcherManaged");
		}
		context.setScreen(() -> null);
	}

	/** 1.5: updater, world rules, welcome tour, screenshot tools, debug report. */
	private static void version15(ClientGameTestContext context) {
		try {
			updateFlow(context, "9.9.9", com.javahost.JavaHost.PROTOCOL, false, "update-same-protocol");
			updateFlow(context, "9.9.10", com.javahost.JavaHost.PROTOCOL + 1, true, "update-new-protocol");
		} catch (Exception e) {
			throw new AssertionError(e);
		}

		rollbackAndImportantUpdates(context);
		launcherManagedUpdate(context);

		// World rules: the host changes a rule; locking stops changes, even by command.
		context.setScreen(() -> new JavaHostScreen(null, Tab.RULES));
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state() != null, 200);
		check(context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.state().host()), "the host sees world rules and can change them");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("DIFFICULTY", "easy"));
		context.waitFor(mc -> "easy".equals(com.javahost.client.rules.RulesClient.state().values().get("DIFFICULTY")), 100);
		check(context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty() == net.minecraft.world.Difficulty.EASY),
				"changing Difficulty on the page changes the world");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "true"));
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state().locked(), 100);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("DIFFICULTY", "hard"));
		context.runOnClient(mc -> {
			var server = mc.getSingleplayerServer();
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "difficulty hard"));
		});
		context.waitTicks(50);
		check(context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty() == net.minecraft.world.Difficulty.EASY),
				"while locked, neither the page nor /difficulty can change the rules");
		context.setScreen(() -> new JavaHostScreen(null, Tab.RULES));
		context.waitTicks(8);
		context.takeScreenshot("ui-world-rules-locked");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "false"));
		context.waitFor(mc -> !com.javahost.client.rules.RulesClient.state().locked(), 100);
		check(context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.state().history().size() >= 3), "changes are kept in the history");

		// Voice reach is one world rule for everyone (1.11): the host switches the whole world to party voice.
		check("nearby".equals(context.computeOnClient(mc -> com.javahost.client.voice.VoiceClient.worldRule())), "a world starts on nearby voice");
		context.runOnClient(mc -> com.javahost.client.JavaHostClient.toggleParty());
		context.waitFor(mc -> "party".equals(com.javahost.client.voice.VoiceClient.worldRule()), 100);
		check(context.computeOnClient(mc -> com.javahost.rules.WorldRules.partyVoice()), "the host's B key switches the whole world to party voice");
		context.setScreen(() -> new JavaHostScreen(null, Tab.VOICE));
		context.waitTicks(8);
		context.takeScreenshot("ui-voice-world-party");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "true"));
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state().locked(), 100);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("VOICE", "nearby"));
		context.waitTicks(20);
		check(context.computeOnClient(mc -> com.javahost.rules.WorldRules.partyVoice()), "while the rules are locked, voice reach stays as it is");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "false"));
		context.waitFor(mc -> !com.javahost.client.rules.RulesClient.state().locked(), 100);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("VOICE", "nearby"));
		context.waitFor(mc -> "nearby".equals(com.javahost.client.voice.VoiceClient.worldRule()), 100);
		check(!context.computeOnClient(mc -> com.javahost.rules.WorldRules.partyVoice()), "back to nearby voice for everyone");
		context.setScreen(() -> new JavaHostScreen(null, Tab.RULES));
		context.waitTicks(8);
		context.takeScreenshot("ui-world-rules");

		// Welcome tour: shown the first time, Next through it, Done.
		Config.get().tourDone = false;
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field offered = JavaHostScreen.class.getDeclaredField("tourOffered");
				offered.setAccessible(true);
				offered.set(null, false);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		context.setScreen(() -> new JavaHostScreen(null));
		context.waitTicks(6);
		for (int step = 1; step <= 5; step++) {
			context.takeScreenshot("ui-tour-" + step);
			clickMenu(context, step == 5 ? "Done" : "Next");
		}
		check(Config.get().tourDone, "the welcome tour runs through and is remembered");

		// Screenshot tools: favourite, rename, delete.
		java.nio.file.Path shot = context.computeOnClient(mc -> com.javahost.client.friends.Screenshots.list(false).getFirst());
		context.runOnClient(mc -> com.javahost.client.friends.Screenshots.setFavourite(shot, true));
		check(context.computeOnClient(mc -> com.javahost.client.friends.Screenshots.favourites().contains(shot)), "a starred screenshot shows in Favourites");
		java.nio.file.Path renamed = context.computeOnClient(mc -> {
			try {
				return com.javahost.client.friends.Screenshots.rename(shot, "My best build");
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		check(renamed.getFileName().toString().equals("My best build.png")
				&& context.computeOnClient(mc -> com.javahost.client.friends.Screenshots.isFavourite(renamed)), "renaming keeps it starred");
		context.setScreen(() -> new JavaHostScreen(null, Tab.SCREENSHOTS));
		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(renamed));
		context.waitTicks(8);
		context.takeScreenshot("ui-screenshot-tools");
		clickMenu(context, "Delete");
		clickMenu(context, "Click again");
		check(!java.nio.file.Files.exists(renamed), "Delete (clicked twice) removes the screenshot");

		// Debug report.
		String report = context.computeOnClient(mc -> com.javahost.client.DebugReport.build());
		check(report.startsWith("JavaHost debug report") && report.contains("Minecraft " + com.javahost.client.update.UpdateScreen.minecraftVersion())
				&& !java.util.regex.Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b").matcher(report).find(), "debug report has versions and no IP addresses");
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40));
		context.waitTicks(10);
		context.takeScreenshot("ui-settings-updates-help");
		context.setScreen(() -> null);
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
		// Automatic backups wait 30 minutes after the newest one, even across restarts.
		int before = context.computeOnClient(mc -> com.javahost.client.host.WorldBackups.listAll().size());
		var again = context.computeOnClient(mc -> {
			var result = new java.util.concurrent.CompletableFuture<Path>();
			mc.getSingleplayerServer().execute(() -> com.javahost.client.host.WorldBackups.backUpIfDue(mc.getSingleplayerServer(), "test again")
					.whenComplete((file, error) -> result.complete(file)));
			return result;
		});
		context.waitFor(mc -> again.isDone(), 60 * 20);
		check(context.computeOnClient(mc -> com.javahost.client.host.WorldBackups.listAll().size()) == before && zip.equals(again.join()),
				"a second automatic backup within 30 minutes is skipped");
		com.javahost.client.gui.pages.HostPage.showSection("backups");
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.waitTicks(10);
		context.takeScreenshot("ui-host-backups");
		com.javahost.client.gui.pages.HostPage.showSection("settings");
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
		com.javahost.client.gui.pages.HostPage.showSection("backups");
		context.setScreen(() -> new JavaHostScreen(new net.minecraft.client.gui.screens.TitleScreen(), Tab.HOST));
		context.waitTicks(12);
		context.takeScreenshot("ui-backups-no-world");
		com.javahost.client.gui.pages.HostPage.showSection("settings");
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
	// ================================================================ 1.8: public worlds

	static com.google.gson.JsonObject call(String base, String method, String path, com.google.gson.JsonObject body, String token)
			throws Exception {
		var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path)).header("content-type", "application/json")
				.method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody() : java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()));
		if (token != null) {
			request.header("authorization", "Bearer " + token);
		}
		String text = java.net.http.HttpClient.newHttpClient().send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
		return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
	}

	/** Lists a made-up public world on the local list service, as account {@code name}. */
	static void seed(String base, String name, String title, String description, String relay, List<String> tags, int max,
			int players, boolean voice) throws Exception {
		var challenge = call(base, "POST", "/auth/challenge", new com.google.gson.JsonObject(), null);
		var verify = new com.google.gson.JsonObject();
		verify.addProperty("name", name);
		verify.addProperty("uuid", net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name).toString());
		verify.addProperty("serverId", challenge.get("serverId").getAsString());
		verify.addProperty("expires", challenge.get("expires").getAsLong());
		String token = call(base, "POST", "/auth/verify", verify, null).get("token").getAsString();
		var listing = new com.google.gson.JsonObject();
		listing.addProperty("title", title);
		listing.addProperty("description", description);
		var tagArray = new com.google.gson.JsonArray();
		tags.forEach(tagArray::add);
		listing.add("tags", tagArray);
		listing.addProperty("mode", "survival");
		listing.addProperty("difficulty", "normal");
		listing.addProperty("max", max);
		listing.addProperty("relay", relay);
		listing.addProperty("mc", net.minecraft.SharedConstants.getCurrentVersion().name());
		listing.addProperty("jv", JavaHost.VERSION);
		listing.addProperty("voice", voice);
		listing.addProperty("signkey", com.javahost.util.FriendCrypto.encodePublic(com.javahost.util.FriendCrypto.newSigningKeyPair().getPublic()));
		check(call(base, "PUT", "/listing", listing, token).has("ok"), "listed \"" + title + "\"");
		var beat = new com.google.gson.JsonObject();
		beat.addProperty("players", players);
		call(base, "POST", "/listing/heartbeat", beat, token);
	}

	/** Discover with a few public worlds, an opened row, the Open-to-the-public form and Report, at real sizes. */
	private static void publicWorldScreens(ClientGameTestContext context) {
		// JavaHost's background threads must load classes like the game does, or pinging a server from one breaks
		// Minecraft's address checks for the rest of the session (every join then hangs on "Connecting").
		for (var pool : List.of(com.javahost.util.IoExecutor.POOL, com.javahost.util.IoExecutor.NET)) {
			try {
				ClassLoader loader = java.util.concurrent.CompletableFuture.supplyAsync(() -> Thread.currentThread().getContextClassLoader(), pool)
						.get(5, java.util.concurrent.TimeUnit.SECONDS);
				check(loader == JavaHost.class.getClassLoader(), "background threads use the game's class loader");
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
		String serverJs = System.getProperty("javahost.directoryServer");
		if (serverJs == null) {
			JavaHost.LOGGER.info("[JavaHost test] skipping public worlds (no local list service)");
			return;
		}
		int port = 8796;
		String base = "http://127.0.0.1:" + port;
		Process node = null;
		try {
			node = new ProcessBuilder("node", serverJs, String.valueOf(port)).redirectErrorStream(true)
					.redirectOutput(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "javahost-directory-test.log").toFile()).start();
			long deadline = System.currentTimeMillis() + 15_000;
			while (true) {
				try {
					call(base, "GET", "/listings", null, null);
					break;
				} catch (Exception notYet) {
					if (System.currentTimeMillis() > deadline) {
						throw new AssertionError("the local list service didn't start");
					}
					Thread.sleep(200);
				}
			}
			System.setProperty("javahost.directoryUrl", base);
			seed(base, "Alex_TheBuilder", "Help me build a giant castle", "Bring stone and good vibes. Starting the north tower tonight.",
					"brave-otter.eu.e4mc.link", List.of("help", "building"), 8, 3, false);
			seed(base, "Sam", "Hardcore-ish survival", "Normal difficulty, no griefing please.", "calm-heron.us.e4mc.link",
					List.of("survival"), 4, 4, true);
			seed(base, "Kim_Crafts", "Chill creative flatland", "", "quiet-maple.as.e4mc.link", List.of("creative", "chill"), 16, 1, false);
			seed(base, "Lee", "Mining trip to the deep dark", "Need two people with good armour.", "swift-lynx.eu.e4mc.link",
					List.of("help", "survival"), 4, 2, false);
			var found = com.javahost.client.discover.Directory.list().get(10, java.util.concurrent.TimeUnit.SECONDS);
			check(found.size() == 4, "the public list shows the 4 worlds (" + found.size() + ")");
			check(found.stream().allMatch(w -> com.javahost.util.ListingText.isRelayAddress(w.relay())), "public worlds only carry relay addresses");
			// (Only when the relay mod is missing; some versions' tests load it everywhere.)
			check(com.javahost.client.host.E4mcCompat.isInstalled()
					|| context.computeOnClient(mc -> com.javahost.client.discover.PublicHosting.cannotOpen()).contains("relay"),
					"opening to the public needs the relay");
		} catch (Exception e) {
			if (node != null) {
				node.destroy();
			}
			throw new AssertionError(e);
		}
		try {
			// The last size is as short as the user's own window (about 247 GUI pixels tall).
			for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 4}, {1320, 988, 4}}) {
				context.getInput().resizeWindow(size[0], size[1]);
				context.runOnClient(mc -> {
					mc.options.guiScale().set(size[2]);
					TestCompat.resizeGui(mc);
				});
				// The title screen dock: every button inside the window, above the copyright line.
				context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
				context.waitTicks(4);
				boolean dockFits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
						.filter(c -> c.getClass().getSimpleName().equals("DockButton"))
						.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
						.allMatch(w -> w.getBottom() <= mc.getWindow().getGuiScaledHeight() - 12));
				check(dockFits, "the title screen dock fits at " + size[0] + "x" + size[1] + " scale " + size[2]);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-title-dock");
				setDiscoverSelection(context, null);
				context.setScreen(() -> new JavaHostScreen(null, Tab.DISCOVER));
				context.waitTicks(60);
				boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
						.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
						.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
						.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
				check(fits, "Discover fits at " + size[0] + "x" + size[1] + " scale " + size[2]);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-discover");
				java.util.UUID first = context.computeOnClient(mc -> {
					try {
						return com.javahost.client.discover.Directory.list().get(5, java.util.concurrent.TimeUnit.SECONDS).stream()
								.filter(w -> w.title().startsWith("Help me build")).findFirst().orElseThrow().host();
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				});
				setDiscoverSelection(context, first);
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).refresh());
				context.waitTicks(4);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-discover-open");
				context.setScreen(() -> new com.javahost.client.gui.PublicWorldScreen(null));
				context.waitTicks(4);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-public-form");
				context.setScreen(() -> new com.javahost.client.gui.ReportScreen(null, first, "Alex_TheBuilder"));
				context.waitTicks(4);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-report");
			}
		} finally {
			setDiscoverSelection(context, null);
			context.setScreen(() -> null);
			System.clearProperty("javahost.directoryUrl");
			node.destroy();
		}
	}

	/** 1.9: a world that needs mods (some on Modrinth, one not), at the laptop sizes. */
	private static void modsScreens(ClientGameTestContext context) {
		String hash = "a".repeat(128);
		var manifest = new com.javahost.util.ModManifest("Alex_TheBuilder", "", List.of(
				new com.javahost.util.ModManifest.Entry("naturalist", "Naturalist", "2.0.6+26.3-fabric", true, "naturalist.jar", 11_400_000, hash, "35ZJ5hlX", ""),
				new com.javahost.util.ModManifest.Entry("geckolib", "GeckoLib", "5.2.1", false, "geckolib.jar", 1_200_000, hash, "AbCd1234", ""),
				new com.javahost.util.ModManifest.Entry("yungsapi", "YUNG's API", "26.1.2-Fabric-6.1.3", true, "yungsapi.jar", 900_000, hash, "EfGh5678", ""),
				new com.javahost.util.ModManifest.Entry("castlemod", "Castle Builder Extreme With A Long Name", "1.0", true, "castle.jar", 4_000_000, hash, "",
						"https://example.com/castle")));
		var needed = new com.javahost.client.mods.ModSets.Needed(manifest, manifest.mods(), "203.0.113.42:25565", null);
		for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 4}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				TestCompat.resizeGui(mc);
			});
			context.setScreen(() -> new com.javahost.client.gui.ModsNeededScreen(needed));
			context.waitTicks(4);
			boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
					.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
					.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8 && w.getBottom() <= mc.getWindow().getGuiScaledHeight()));
			check(fits, "the mods-needed screen fits at " + size[0] + "x" + size[1] + " scale " + size[2]);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-mods-needed");
			com.javahost.client.gui.pages.ModsPage.showMine(false);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(80); // the search answers from Modrinth and icons arrive
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-mods-tab");
			com.javahost.client.gui.pages.ModsPage.showMine(true);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(8);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-mods-mine");
			com.javahost.client.gui.pages.ModsPage.showMine(false);
		}
		// Browse finds mods for every version; ones that can't run here say where they run, with no Install.
		context.getInput().resizeWindow(1710, 1070);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			TestCompat.resizeGui(mc);
		});
		com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "aether");
		context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
		context.waitFor(mc -> !com.javahost.client.gui.pages.ModsPage.searching() && !com.javahost.client.gui.pages.ModsPage.results().isEmpty()
				&& mc.gui.screen().children().stream().anyMatch(c -> c instanceof com.javahost.client.gui.ui.RowButton), 400);
		context.waitTicks(60); // icons
		long cards = context.computeOnClient(mc -> mc.gui.screen().children().stream().filter(c -> c instanceof com.javahost.client.gui.ui.RowButton).count());
		long installs = context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof com.javahost.client.gui.ui.FlatButton b && b.getMessage().getString().equals("Install")).count());
		check(cards > 0 && installs < cards, "Browse shows mods for other versions without Install (" + cards + " found, " + installs + " installable)");
		context.takeScreenshot("review-1710-s4-mods-search-other-versions");
		com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "");
		context.setScreen(() -> null);
	}

	private static void size(ClientGameTestContext context, int w, int h, int scale) {
		context.getInput().resizeWindow(w, h);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			TestCompat.resizeGui(mc);
		});
	}

	private static boolean widgetsFit(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
				.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
				.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
	}

	private static boolean hasButton(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
				&& w.visible && w.getMessage().getString().equals(label)));
	}

	/** 1.12: Discover by category, a project's page, and installs with progress in the menu's download list. */
	private static void storeAndDownloads(ClientGameTestContext context) {
		Path mods = context.computeOnClient(mc -> net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods"));
		java.util.Set<String> before = fileNames(mods);
		String minecraft = context.computeOnClient(mc -> com.javahost.client.mods.Modpacks.gameVersion());
		size(context, 1710, 1070, 4);
		try {
			// A category.
			com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "");
			com.javahost.client.gui.pages.ModsPage.showCategory("magic");
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitFor(mc -> !com.javahost.client.gui.pages.ModsPage.searching() && !com.javahost.client.gui.pages.ModsPage.results().isEmpty(), 400);
			context.waitTicks(60); // icons
			check(com.javahost.client.gui.pages.ModsPage.results().stream().allMatch(p -> p.categories().contains("magic")),
					"the Magic chip shows only magic mods");
			check(widgetsFit(context), "Discover fits at 1710x1070 scale 4");
			context.takeScreenshot("review-1710-s4-mods-category");

			// Only ones that run here.
			com.javahost.client.gui.pages.ModsPage.showCategory("");
			com.javahost.client.gui.pages.ModsPage.showRunsHere(true);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitFor(mc -> !com.javahost.client.gui.pages.ModsPage.searching() && !com.javahost.client.gui.pages.ModsPage.results().isEmpty(), 400);
			check(com.javahost.client.gui.pages.ModsPage.results().stream().allMatch(p -> com.javahost.util.Availability.installable(p, minecraft)),
					"\"Only ones that run here\" shows only mods for this version");
			List<com.javahost.util.Modrinth.Project> picks = context.computeOnClient(mc -> com.javahost.client.gui.pages.ModsPage.results().stream()
					.filter(p -> !com.javahost.client.perf.BoostMods.installedSlug(p.slug())).limit(3).toList());
			check(picks.size() == 3, "found mods to install");

			// A project's page, then Install from it.
			for (int[] s : new int[][] {{1280, 800, 3}, {1710, 1070, 4}}) {
				size(context, s[0], s[1], s[2]);
				com.javahost.client.gui.pages.ModsPage.showDetail(picks.get(0));
				context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
				context.waitTicks(30);
				check(hasButton(context, "Install") && hasButton(context, "Modrinth"), "a mod's page has Install and Modrinth");
				check(widgetsFit(context), "a mod's page fits at " + s[0] + "x" + s[1]);
				context.takeScreenshot("review-" + s[0] + "-s" + s[2] + "-mods-detail");
			}
			clickMenu(context, "Install");
			check(hasButton(context, "Downloads"), "the download icon shows in the menu header");
			// Back to the list, and queue a second one from its card.
			clickMenu(context, "Back");
			check(com.javahost.client.gui.pages.ModsPage.results().size() > 0 && hasButton(context, "Install"), "back to the list");
			context.runOnClient(mc -> com.javahost.client.mods.Downloads.installMod(picks.get(1).id(), picks.get(1).slug(), picks.get(1).title(),
					picks.get(1).iconUrl()));
			context.waitTicks(6);
			context.takeScreenshot("review-1710-s4-downloads-ring");
			clickMenu(context, "Downloads");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()), "the download list opens");
			context.takeScreenshot("review-1710-s4-downloads-popover");
			context.waitFor(mc -> !com.javahost.client.mods.Downloads.active(), 20 * 180);
			for (int i = 0; i < 2; i++) {
				var job = com.javahost.client.mods.Downloads.job(picks.get(i).id());
				check(job != null && job.stage() == com.javahost.client.mods.Downloads.Stage.DONE && job.needsRestart(),
						picks.get(i).title() + " downloaded in turn and waits for a restart");
			}
			context.waitTicks(10);
			context.takeScreenshot("review-1710-s4-downloads-done");
			check(context.computeOnClient(mc -> com.javahost.client.friends.Notifications.items().stream()
					.anyMatch(n -> n.payload() instanceof com.javahost.client.friends.Notifications.Action a && a.label().equals("Close game"))),
					"a notification says it's ready, with Close game");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(10);
			check(hasButton(context, "Restart") && hasButton(context, "Close Minecraft"), "the card says Restart, with Close Minecraft above the list");
			context.takeScreenshot("review-1710-s4-mods-restart");

			// Cancelling one that's waiting its turn.
			var third = context.computeOnClient(mc -> {
				var job = com.javahost.client.mods.Downloads.installMod(picks.get(2).id(), picks.get(2).slug(), picks.get(2).title(), picks.get(2).iconUrl());
				com.javahost.client.mods.Downloads.cancel(job);
				return job;
			});
			context.waitFor(mc -> !third.active(), 20 * 60);
			check(third.stage() == com.javahost.client.mods.Downloads.Stage.CANCELLED, "a cancelled download stops");

			// Installed: the downloads and the mods JavaHost put in.
			for (int[] s : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 3}}) {
				size(context, s[0], s[1], s[2]);
				com.javahost.client.gui.pages.ModsPage.showMine(true);
				context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
				context.waitTicks(10);
				check(widgetsFit(context), "Installed fits at " + s[0] + "x" + s[1]);
				context.takeScreenshot("review-" + s[0] + "-s" + s[2] + "-mods-installed");
			}

			// Removing one: it's marked to go when the game closes, and Keep takes that back.
			size(context, 1710, 1070, 4);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -6));
			context.waitTicks(12);
			clickMenu(context, "Remove");
			check(!com.javahost.client.mods.Removals.pendingJars().isEmpty() && hasButton(context, "Keep"), "Remove marks a mod to go when the game closes");
			context.takeScreenshot("review-1710-s4-mods-removing");
			clickMenu(context, "Keep");
			check(com.javahost.client.mods.Removals.pendingJars().isEmpty(), "Keep takes it off the list");
		} finally {
			context.runOnClient(mc -> {
				try {
					java.nio.file.Files.deleteIfExists(mc.gameDirectory.toPath().resolve("javahost").resolve("remove-on-exit.txt"));
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			});
			// Leave the test game's mods folder as it was.
			for (String name : fileNames(mods)) {
				if (!before.contains(name)) {
					try {
						java.nio.file.Files.deleteIfExists(mods.resolve(name));
					} catch (IOException e) {
						JavaHost.LOGGER.warn("[JavaHost test] couldn't remove {}", name, e);
					}
				}
			}
			context.runOnClient(mc -> {
				com.javahost.client.mods.Downloads.clearFinished();
				com.javahost.client.friends.Notifications.clear();
			});
			com.javahost.client.gui.pages.ModsPage.showRunsHere(false);
			com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "");
			context.setScreen(() -> null);
		}
	}

	/** 1.12.3: a big modpack (thousands of config files) must not slow the Mods page down (it used to re-count every tick). */
	private static void bigModSetStaysFast(ClientGameTestContext context) {
		Path set = context.computeOnClient(mc -> com.javahost.client.mods.ModSets.dir().resolve("pack-showcasetest"));
		try {
			Files.createDirectories(set.resolve("overrides/config"));
			for (int i = 0; i < 3000; i++) {
				Files.writeString(set.resolve("overrides/config/c" + i + ".json"), "{}");
			}
			Files.writeString(set.resolve("set.properties"), "label=Big Test Pack 1.0\nkind=pack\ncreated=1\n");
			context.runOnClient(mc -> com.javahost.client.mods.ModSets.saved()); // a new set: counted once
			context.runOnClient(mc -> {
				try {
					var write = com.javahost.client.mods.ModSets.class.getDeclaredMethod("changed");
					write.setAccessible(true);
					write.invoke(null);
				} catch (ReflectiveOperationException e) {
					throw new AssertionError(e);
				}
			});
			check(context.computeOnClient(mc -> com.javahost.client.mods.ModSets.saved().stream().anyMatch(x -> x.label().equals("Big Test Pack 1.0"))),
					"a new mod set shows up");
			long took = context.computeOnClient(mc -> {
				long start = System.nanoTime();
				for (int i = 0; i < 200; i++) {
					com.javahost.client.mods.ModSets.saved();
					com.javahost.client.mods.ModSets.staged();
					com.javahost.client.mods.ModSets.stagedLabel();
					com.javahost.client.mods.ModSets.activeLabel();
				}
				return (System.nanoTime() - start) / 1_000_000;
			});
			check(took < 50, "200 checks of the mod sets take " + took + " ms (no files read each tick)");
			com.javahost.client.gui.pages.ModsPage.showMine(true);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(10);
			check(context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
					&& w.getMessage().getString().equals("Use"))), "the big set is listed in Installed");
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			context.runOnClient(mc -> {
				try {
					com.javahost.client.mods.ModSets.delete(com.javahost.client.mods.ModSets.saved().stream()
							.filter(x -> x.folder().equals("pack-showcasetest")).findFirst().orElseThrow());
				} catch (IOException | RuntimeException e) {
					JavaHost.LOGGER.warn("[JavaHost test] couldn't remove the test set", e);
				}
			});
			com.javahost.client.gui.pages.ModsPage.showMine(false);
			context.setScreen(() -> null);
		}
	}

	private static java.util.Set<String> fileNames(Path folder) {
		try (var files = java.nio.file.Files.list(folder)) {
			return files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
		} catch (IOException e) {
			return java.util.Set.of();
		}
	}

	/** 1.12: the Host tab's sub-tabs while hosting, with sample players, at the review sizes; and a player's menus. */
	private static void hostTabs(ClientGameTestContext context) {
		context.runOnClient(mc -> HostController.previewGuests(List.of(
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("Steve123".getBytes()), "Steve123", 42, net.minecraft.world.level.GameType.SURVIVAL, false, false),
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("AlexPlays".getBytes()), "AlexPlays", 180, net.minecraft.world.level.GameType.CREATIVE, true, false),
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("Builder".getBytes()), "Builder_With_A_Long_Name", 0,
						net.minecraft.world.level.GameType.SURVIVAL, false, true))));
		try {
			for (int[] s : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 3}}) {
				size(context, s[0], s[1], s[2]);
				for (String section : List.of("players", "settings", "backups")) {
					com.javahost.client.gui.pages.HostPage.showSection(section);
					context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
					context.waitTicks(8);
					check(hasButton(context, "Invite friends") && hasButton(context, "Stop hosting"), "Invite and Stop stay on top (" + section + ")");
					check(widgetsFit(context), "Host " + section + " fits at " + s[0] + "x" + s[1] + " scale " + s[2]);
					context.takeScreenshot("review-" + s[0] + "-s" + s[2] + "-host-" + section);
				}
			}
			size(context, 1710, 1070, 4);
			com.javahost.client.gui.pages.HostPage.showSection("players");
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
			context.waitTicks(8);
			clickMenu(context, "More");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()), "a player's More menu opens");
			context.takeScreenshot("review-1710-s4-host-player-menu");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
			clickMenu(context, "Survival");
			context.takeScreenshot("review-1710-s4-host-game-mode");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
		} finally {
			context.runOnClient(mc -> HostController.previewGuests(null));
			size(context, 1710, 1070, 4);
		}
	}

	private static void setDiscoverSelection(ClientGameTestContext context, java.util.UUID host) {
		context.runOnClient(mc -> {
			try {
				var field = com.javahost.client.gui.pages.DiscoverPage.class.getDeclaredField("selected");
				field.setAccessible(true);
				field.set(null, host);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	// ================================================================ 1.6

	/** A transfer in a given state, for screenshots (the real flow is covered by the two-game test). */
	private static com.javahost.client.share.WorldShare.Transfer fakeTransfer(java.util.UUID peer, boolean outgoing, String world,
			com.javahost.client.share.WorldShare.Stage stage, long size, long done, String message, Path result) {
		try {
			Class<com.javahost.client.share.WorldShare.Transfer> type = com.javahost.client.share.WorldShare.Transfer.class;
			var constructor = type.getDeclaredConstructor(long.class, boolean.class, java.util.UUID.class, String.class, String.class,
					com.javahost.client.share.WorldShare.Stage.class);
			constructor.setAccessible(true);
			var transfer = constructor.newInstance(new java.util.Random().nextLong(), outgoing, peer, "Alex_TheBuilder", world, stage);
			for (var entry : java.util.Map.<String, Object>of("size", size, "done", done, "speed", 1.6 * 1024 * 1024).entrySet()) {
				var field = type.getDeclaredField(entry.getKey());
				field.setAccessible(true);
				field.set(transfer, entry.getValue());
			}
			for (var entry : new String[][] {{"message", message}}) {
				var field = type.getDeclaredField(entry[0]);
				field.setAccessible(true);
				field.set(transfer, entry[1]);
			}
			var resultField = type.getDeclaredField("result");
			resultField.setAccessible(true);
			resultField.set(transfer, result);
			return transfer;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static java.util.List<com.javahost.client.share.WorldShare.Transfer> transferList() {
		try {
			var field = com.javahost.client.share.WorldShare.class.getDeclaredField("TRANSFERS");
			field.setAccessible(true);
			return (java.util.List<com.javahost.client.share.WorldShare.Transfer>) field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** The world picker, the transfers card in every state, and the offer popup, at laptop sizes. */
	private static void worldShareScreens(ClientGameTestContext context, java.util.UUID alexId) {
		check(context.computeOnClient(mc -> com.javahost.client.share.WorldShare.cannotSendTo(java.util.UUID.randomUUID()))
				.startsWith("Only friends"), "worlds can only be sent to friends");
		check(context.computeOnClient(mc -> com.javahost.client.share.WorldShare.cannotSendTo(alexId)).startsWith("Join the same world"),
				"a friend who isn't in this world can't be sent a world");
		long mb = 1024 * 1024;
		context.runOnClient(mc -> {
			com.javahost.client.share.WorldShare.pauseForTests(true);
			var list = transferList();
			list.clear();
			list.add(fakeTransfer(alexId, false, "Hardcore Island", com.javahost.client.share.WorldShare.Stage.OFFERED, 412 * mb, 0, null, null));
			list.add(fakeTransfer(alexId, true, "Survival with the boys and a very long name", com.javahost.client.share.WorldShare.Stage.SENDING,
					184 * mb, 77 * mb, null, null));
			list.add(fakeTransfer(alexId, false, "Skyblock", com.javahost.client.share.WorldShare.Stage.DONE, 52 * mb, 52 * mb, null,
					mc.getLevelSource().getBaseDir().resolve("Skyblock (from Alex_TheBuilder)")));
			list.add(fakeTransfer(alexId, true, "Creative", com.javahost.client.share.WorldShare.Stage.FAILED, 30 * mb, 4 * mb,
					"Alex_TheBuilder left the world", null));
		});
		for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 4}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				TestCompat.resizeGui(mc);
			});
			context.setScreen(() -> new JavaHostScreen(null, Tab.NOTIFICATIONS));
			context.waitTicks(8);
			boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
					.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
					.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
			check(fits, "transfer buttons fit at " + size[0] + "x" + size[1] + " scale " + size[2]);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-world-transfers");
			context.setScreen(() -> new com.javahost.client.gui.WorldPickerScreen(null,
					com.javahost.client.friends.Friends.friend(alexId).orElseThrow(), null, null));
			context.waitTicks(30); // folder sizes are measured in the background
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-world-picker");
			// The real popup: offers wait for a menu, then ask.
			context.runOnClient(mc -> com.javahost.client.Popups.offerWorld(transferList().getFirst()));
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
			context.waitTicks(6);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof com.javahost.client.gui.FriendPopup), "a world offer pops up on a menu");
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-world-offer");
		}
		context.runOnClient(mc -> {
			transferList().clear();
			com.javahost.client.share.WorldShare.pauseForTests(false);
		});
		context.setScreen(() -> null);
	}

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
		context.waitTicks(20);
		context.takeScreenshot("hud-fps");
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
		version15(context);
		// Sample notifications: a long JavaHost notice (wraps in full) and a friend's message.
		context.runOnClient(mc -> {
			com.javahost.client.Toasts.notify("Installed 4 mods", "Restart Minecraft to turn them on. Sodium, Lithium, FerriteCore and ImmediatelyFast are ready and will load next time.");
			com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.MESSAGE,
					java.util.UUID.nameUUIDFromBytes("Alex".getBytes()), "Alex", "Are you on tonight? I found a village with a blacksmith near spawn!", null);
		});
		context.waitTicks(4);
		context.takeScreenshot("toast-two-lines");
		reviewAtScale(context, 4);
		reviewAtScale(context, 3);
		// A laptop-sized window, where the menu switches to its compact icon sidebar.
		reviewAtScale(context, 4, 1710, 1070);
		reviewAtScale(context, 3, 1280, 800);
		// A friend's profile on the laptop-sized window: every button must fit inside the window.
		java.util.UUID alexId = java.util.UUID.nameUUIDFromBytes("Alex".getBytes());
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field dataField = com.javahost.client.friends.Friends.class.getDeclaredField("data");
				dataField.setAccessible(true);
				Object data = dataField.get(null);
				java.lang.reflect.Field friendsField = data.getClass().getDeclaredField("friends");
				friendsField.setAccessible(true);
				@SuppressWarnings("unchecked")
				java.util.List<com.javahost.client.friends.Friends.Friend> friends = (java.util.List<com.javahost.client.friends.Friends.Friend>) friendsField.get(data);
				friends.add(new com.javahost.client.friends.Friends.Friend(alexId, "Alex_TheBuilder", "", System.currentTimeMillis(), null, null));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				TestCompat.resizeGui(mc);
			});
			context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openProfile(alexId));
			context.waitTicks(8);
			boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
					.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
					.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
			check(fits, "profile buttons fit inside the window at " + size[0] + "x" + size[1] + " scale " + size[2]);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-profile");
			// Rest the mouse on a cut-off label for over a second: the full text appears.
		}
		worldShareScreens(context, alexId);
		publicWorldScreens(context);
		modsScreens(context);
		storeAndDownloads(context);
		bigModSetStaysFast(context);
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field dataField = com.javahost.client.friends.Friends.class.getDeclaredField("data");
				dataField.setAccessible(true);
				Object data = dataField.get(null);
				java.lang.reflect.Field friendsField = data.getClass().getDeclaredField("friends");
				friendsField.setAccessible(true);
				((java.util.List<?>) friendsField.get(data)).removeIf(f -> ((com.javahost.client.friends.Friends.Friend) f).id().equals(alexId));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		// Hover text: on the Host tab before hosting at the small size, the subtitle is cut short; resting on it shows it all.
		// Repeated toasts with the same key update in place instead of piling up.
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			for (int i = 0; i < 12; i++) {
				com.javahost.client.Toasts.show("voice-mode", i % 2 == 0 ? "Party voice" : "Nearby voice", "Spam test " + i);
			}
		});
		context.waitTicks(4);
		int toastCount = context.computeOnClient(mc -> {
			try {
				java.lang.reflect.Field visible = mc.gui.toastManager().getClass().getDeclaredField("visibleToasts");
				visible.setAccessible(true);
				java.lang.reflect.Field queued = mc.gui.toastManager().getClass().getDeclaredField("queued");
				queued.setAccessible(true);
				int count = 0;
				for (Object instance : (java.util.Collection<?>) visible.get(mc.gui.toastManager())) {
					java.lang.reflect.Method getToast = instance.getClass().getDeclaredMethod("getToast");
					getToast.setAccessible(true);
					Object toast = getToast.invoke(instance);
					if ("voice-mode".equals(((net.minecraft.client.gui.components.toasts.Toast) toast).getToken())) {
						count++;
					}
				}
				for (Object toast : (java.util.Collection<?>) queued.get(mc.gui.toastManager())) {
					if ("voice-mode".equals(((net.minecraft.client.gui.components.toasts.Toast) toast).getToken())) {
						count++;
					}
				}
				return count;
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		check(toastCount == 1, "pressing B 12 times leaves one voice toast, not a queue (" + toastCount + ")");
		context.takeScreenshot("toast-after-spam");
		context.runOnClient(mc -> {
			com.javahost.client.Toasts.show("Microphone on", "Press M to toggle");
			com.javahost.client.Toasts.show("World backed up", "Restore it any time from the Host tab, even after friends leave.");
		});
		context.waitTicks(30);
		context.takeScreenshot("toast-stack");
		// A narrow window cuts the Host tab's subtitle; resting the mouse on it shows the full text after a second.
		context.getInput().resizeWindow(900, 560);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(3);
			TestCompat.resizeGui(mc);
		});
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.waitTicks(8);
		double[] spot = context.computeOnClient(mc -> {
			// The hint under "Always use the host's packs" is cut short at this size.
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals("Always use the host's packs")) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + 40) * scale, (w.getY() + 15) * scale};
				}
			}
			throw new AssertionError("no packs toggle");
		});
		context.getInput().setCursorPos(spot[0], spot[1]);
		context.waitTicks(30);
		context.takeScreenshot("hover-full-text");
		context.getInput().setCursorPos(0, 0);
		context.setScreen(() -> null);
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(0);
			TestCompat.resizeGui(mc);
		});
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.clear());
		// The Host tab before hosting, at the laptop size: Start hosting must be on screen without scrolling.
		context.runOnClient(mc -> HostController.stop());
		com.javahost.client.gui.pages.HostPage.showSection("settings");
		context.getInput().resizeWindow(1710, 1070);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			TestCompat.resizeGui(mc);
		});
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.waitTicks(8);
		check(context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
				&& w.visible && w.getMessage().getString().equals("Start hosting"))), "Start hosting is visible without scrolling");
		context.takeScreenshot("review-1710-s4-host-setup");
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
		context.waitTicks(10);
		context.takeScreenshot("review-1710-s4-host-setup-scrolled");
		clickMenu(context, "Advanced settings");
		context.waitTicks(6);
		context.takeScreenshot("review-1710-s4-host-advanced");
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, 30));
		context.waitTicks(10);
		clickMenu(context, "Start hosting");
		context.waitFor(mc -> HostController.isHosting(), 200);
		check(true, "Start hosting from the top of the Host tab works");
		context.waitTicks(20);
		context.takeScreenshot("review-1710-s4-host-hosting");
		hostTabs(context);
		context.setScreen(() -> null);
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(0);
			TestCompat.resizeGui(mc);
		});

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

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
			context.runOnClient(mc -> HostController.stop());
			context.runOnClient(mc -> check(!mc.getSingleplayerServer().isPublished(), "stop hosting closes the world again"));
		}
	}
}

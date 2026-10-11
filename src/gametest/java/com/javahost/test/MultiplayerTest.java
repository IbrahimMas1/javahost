package com.javahost.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.gui.FriendPopup;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.util.Mailbox;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.HostController;
import com.javahost.client.pack.IrisCompat;
import com.javahost.client.voice.VoiceClient;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Real multiplayer through the host's PC, with two separate game processes:
 * {@code ./gradlew runMpHost runMpGuest}. The host opens its world; the guest joins it through
 * each connection route (same Wi-Fi, IPv6, the internet relay) and both sides check that
 * joining, pack + shader sharing and voice (both directions) work. The two games coordinate
 * through small files in {@code build/run/mp-shared}.
 */
final class MultiplayerTest {
	private static final Path SHARED = Path.of(System.getProperty("javahost.mpDir", "build/run/mp-shared"));
	private static final String HOST_SHADER = "host-shader.zip";
	private static final String GUEST_SHADER = "guest-shader.zip";
	private static final String SHADER_SETTINGS = "JAVAHOST_TEST_OPTION=true";
	/** A tiny but real shader pack with one option, so Iris keeps the option in its settings file. */
	private static final Map<String, String> SHADER_FILES = Map.of(
			"shaders/shaders.properties", "# JavaHost test shader\n",
			"shaders/final.vsh", """
					#version 120
					varying vec2 texcoord;
					void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.st; }
					""",
			"shaders/final.fsh", """
					#version 120
					//#define JAVAHOST_TEST_OPTION
					uniform sampler2D colortex0;
					varying vec2 texcoord;
					void main() {
					#ifdef JAVAHOST_TEST_OPTION
						gl_FragColor = texture2D(colortex0, texcoord) * vec4(0.85, 1.0, 0.85, 1.0);
					#else
						gl_FragColor = texture2D(colortex0, texcoord);
					#endif
					}
					""");
	private static final int ONE_MINUTE = 20 * 60;

	private MultiplayerTest() {
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("[JavaHost MP] FAILED: " + what);
		}
		JavaHost.LOGGER.info("[JavaHost MP] ok: {}", what);
		appendResult("ok: " + what);
	}

	private static void appendResult(String line) {
		try {
			Files.createDirectories(SHARED);
			Files.writeString(SHARED.resolve(System.getProperty("javahost.testRole") + "-results.txt"), line + "\n",
					StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Results also go to the log.
		}
	}

	private static void writeShared(String name, String content) {
		try {
			Files.createDirectories(SHARED);
			Path temp = SHARED.resolve(name + ".tmp");
			Files.writeString(temp, content);
			Files.move(temp, SHARED.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static String readShared(String name) {
		try {
			Path file = SHARED.resolve(name);
			return Files.exists(file) ? Files.readString(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static void waitForShared(ClientGameTestContext context, String name, int timeoutTicks) {
		context.waitFor(mc -> readShared(name) != null, timeoutTicks);
	}

	private static void writeZip(Path file, Map<String, String> entries) {
		try {
			Files.createDirectories(file.getParent());
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
				for (Map.Entry<String, String> entry : entries.entrySet()) {
					zip.putNextEntry(new ZipEntry(entry.getKey()));
					zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
					zip.closeEntry();
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void prepareShader(ClientGameTestContext context, String name, String settings) {
		context.runOnClient(mc -> {
			Path dir = IrisCompat.shaderpacksDirectory();
			writeZip(dir.resolve(name), SHADER_FILES);
			if (settings != null) {
				try {
					Files.writeString(dir.resolve(name + ".txt"), settings);
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			}
			check(IrisCompat.apply(name), "switched own shader to " + name);
		});
	}

	private static Optional<String> activeShader(ClientGameTestContext context) {
		return context.computeOnClient(mc -> IrisCompat.activeShaderPack());
	}

	// ================================================================ host

	static void host(ClientGameTestContext context) {
		Config config = Config.get();
		config.useUpnp = Boolean.getBoolean("javahost.testUpnp"); // touching the real router is opt-in
		config.useIpv6 = true;
		config.relayMode = "ALWAYS"; // so the relay route is always exercised
		config.shareResourcePacks = true;
		config.shareShaders = true;
		config.voiceEnabled = true;
		config.outputVolume = 0; // decode everything, play nothing
		config.port = 25599;

		context.runOnClient(mc -> {
			var format = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
			writeZip(mc.getResourcePackDirectory().resolve("host-pack.zip"), Map.of("pack.mcmeta",
					"{\"pack\":{\"description\":\"Host's pack\",\"min_format\":" + format.major() + ",\"max_format\":" + format.major() + "}}"));
			mc.getResourcePackRepository().reload();
			List<String> selected = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
			selected.add("file/host-pack.zip");
			mc.getResourcePackRepository().setSelected(selected);
		});
		prepareShader(context, HOST_SHADER, SHADER_SETTINGS);

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			// Test accounts are offline accounts; real players have Microsoft accounts and keep verification on.
			context.runOnClient(mc -> mc.getSingleplayerServer().setUsesAuthentication(false));

			// ---- Friends: add by name, the guest accepts, then one click to invite. No codes anywhere.
			waitForShared(context, "guest-ready", 5 * ONE_MINUTE);
			String added = join(context.computeOnClient(mc -> Friends.add("Guest")));
			check(added.startsWith("Friend request sent"), "host sent a friend request to Guest (" + added + ")");
			context.waitFor(mc -> Friends.friends().stream().anyMatch(f -> f.name().equals("Guest")), 3 * ONE_MINUTE);
			check(true, "Guest accepted; they're on the host's friends list");
			Friends.Friend guestFriend = context.computeOnClient(mc -> Friends.friends().stream().filter(f -> f.name().equals("Guest")).findFirst().orElseThrow());
			context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.FRIENDS));
			context.waitTicks(5);
			context.takeScreenshot("mp-host-friends");
			context.runOnClient(mc -> check(!HostController.isHosting(), "world not open yet before inviting"));
			context.runOnClient(mc -> InviteFlow.invite(guestFriend));
			context.waitFor(mc -> "Invited".equals(InviteFlow.status(guestFriend.id())), 2 * ONE_MINUTE);
			context.runOnClient(mc -> check(HostController.isHosting(), "inviting opened the world automatically and sent the invite"));
			context.waitTicks(5);
			context.takeScreenshot("mp-host-friends-invited");
			context.setScreen(() -> null);
			context.waitFor(mc -> HostController.guests().stream().anyMatch(g -> g.name().equals("Guest")), 3 * ONE_MINUTE);
			check(true, "Guest joined the world from the invite's Join button");
			waitForShared(context, "guest-invite-done", 3 * ONE_MINUTE);

			context.waitFor(mc -> !Connectivity.isProbing(), ONE_MINUTE);
			context.waitFor(mc -> Connectivity.relayDomain() != null, 2 * ONE_MINUTE);
			check(true, "relay is up at " + Connectivity.relayDomain());
			for (Connectivity.Route route : Connectivity.routes()) {
				appendResult("route " + route.name() + ": " + route.state() + " " + route.invite() + " | " + route.detail());
			}

			Properties addresses = new Properties();
			addresses.setProperty("lan", Connectivity.lanIp() + ":" + HostController.port());
			addresses.setProperty("relay", Connectivity.relayDomain());
			Connectivity.routes().stream().filter(r -> r.name().equals("IPv6") && r.invite() != null).findFirst()
					.ifPresent(r -> addresses.setProperty("ipv6", com.javahost.net.InviteCode.decode(r.invite())));
			StringBuilder text = new StringBuilder();
			addresses.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
			writeShared("host-addresses", text.toString());

			// The host "talks" the whole time with a generated tone.
			VoiceClient.setTestTone(true);

			int heardBefore = 0;
			while (readShared("guest-done") == null) {
				context.waitFor(mc -> readShared("guest-done") != null || pendingRoute() != null || unfriendedNotAcked(), 15 * ONE_MINUTE);
				if (unfriendedNotAcked()) {
					writeShared("host-saw-remove", "ok");
					check(true, "host got the guest's unfriend message");
					continue;
				}
				String route = pendingRoute();
				if (route == null) {
					continue;
				}
				int baseline = heardBefore;
				context.waitFor(mc -> HostController.guests().stream().anyMatch(g -> g.name().equals("Guest")), ONE_MINUTE);
				UUID guestId = HostController.guests().stream().filter(g -> g.name().equals("Guest")).findFirst().orElseThrow().id();
				check(true, route + ": host sees Guest in the world (ping " + HostController.guests().getFirst().pingMs() + " ms)");
				context.waitFor(mc -> VoiceClient.audibleFramesFrom(guestId) > baseline + 25, ONE_MINUTE);
				heardBefore = VoiceClient.audibleFramesFrom(guestId);
				check(true, route + ": host hears the guest's voice");
				context.takeScreenshot("mp-host-" + route);

				if (route.equals("relay")) {
					context.runOnClient(mc -> HostController.kick(guestId));
					context.waitFor(mc -> HostController.guests().isEmpty(), ONE_MINUTE);
					check(true, route + ": kick removes the guest");
				}
				writeShared("host-ack-" + route, "ok");
			}
			VoiceClient.setTestTone(false);
			context.runOnClient(mc -> HostController.stop());
			context.runOnClient(mc -> check(!mc.getSingleplayerServer().isPublished(), "host stopped hosting"));
		}
		JavaHost.LOGGER.info("[JavaHost MP] HOST DONE: {}", readShared("guest-done"));
		check(readShared("guest-done").startsWith("PASS"), "guest reported success");
	}

	private static boolean unfriendedNotAcked() {
		return readShared("guest-removed") != null && readShared("host-saw-remove") == null
				&& Friends.friends().stream().noneMatch(f -> f.name().equals("Guest"));
	}

	private static <T> T join(java.util.concurrent.CompletableFuture<T> future) {
		try {
			return future.get(2, java.util.concurrent.TimeUnit.MINUTES);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static String pendingRoute() {
		String route = readShared("guest-route");
		if (route == null || readShared("host-ack-" + route.trim()) != null) {
			return null;
		}
		return route.trim();
	}

	// ================================================================ guest

	static void guest(ClientGameTestContext context) {
		Config config = Config.get();
		config.voiceEnabled = true;
		config.outputVolume = 0;
		config.autoAcceptHostPacks = true;
		prepareShader(context, GUEST_SHADER, null);

		// ---- Friends: accept the host's request from the popup, then join from the invite popup.
		context.waitFor(mc -> Friends.status() == Mailbox.Status.ONLINE, 2 * ONE_MINUTE);
		writeShared("guest-ready", "ok");
		context.waitFor(mc -> mc.gui.screen() instanceof FriendPopup, 3 * ONE_MINUTE);
		context.takeScreenshot("mp-guest-friend-request");
		context.clickScreenButton("Accept");
		context.waitFor(mc -> Friends.friends().stream().anyMatch(f -> f.name().equals("Host")), ONE_MINUTE);
		check(true, "guest accepted the friend request from the popup");
		context.waitFor(mc -> mc.gui.screen() instanceof FriendPopup, 3 * ONE_MINUTE);
		context.takeScreenshot("mp-guest-invite");
		context.clickScreenButton("Join");
		context.waitFor(mc -> (mc.player != null && mc.level != null && mc.gui.screen() == null)
				|| mc.gui.screen() instanceof DisconnectedScreen, 3 * ONE_MINUTE);
		String refusedInvite = context.computeOnClient(mc -> mc.gui.screen() instanceof DisconnectedScreen d ? d.getNarrationMessage().getString() : null);
		check(refusedInvite == null, "guest joined the host's world by clicking Join" + (refusedInvite == null ? "" : " (refused: " + refusedInvite + ")"));
		context.waitTicks(60);
		context.takeScreenshot("mp-guest-joined-from-invite");
		context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
		context.waitFor(mc -> mc.level == null, ONE_MINUTE);
		writeShared("guest-invite-done", "ok");

		waitForShared(context, "host-addresses", 5 * ONE_MINUTE);
		Properties addresses = new Properties();
		try {
			addresses.load(new java.io.StringReader(readShared("host-addresses")));
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		Map<String, String> routes = new LinkedHashMap<>();
		routes.put("lan", addresses.getProperty("lan"));
		if (addresses.getProperty("ipv6") != null) {
			routes.put("ipv6", addresses.getProperty("ipv6"));
		}
		routes.put("relay", addresses.getProperty("relay"));

		VoiceClient.setTestTone(true);
		try {
			for (Map.Entry<String, String> route : routes.entrySet()) {
				joinThrough(context, route.getKey(), route.getValue());
			}

			// ---- Friends-only: after unfriending, the host's world turns us away.
			Friends.Friend hostFriend = context.computeOnClient(mc -> Friends.friends().stream().filter(f -> f.name().equals("Host")).findFirst().orElseThrow());
			context.runOnClient(mc -> Friends.remove(hostFriend));
			writeShared("guest-removed", "ok");
			waitForShared(context, "host-saw-remove", 2 * ONE_MINUTE);
			String lan = routes.get("lan");
			context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(lan),
					new ServerData("JavaHost test host", lan, ServerData.Type.OTHER), false, null));
			context.waitFor(mc -> mc.gui.screen() instanceof DisconnectedScreen || (mc.player != null && mc.gui.screen() == null), 2 * ONE_MINUTE);
			String turnedAway = context.computeOnClient(mc -> mc.gui.screen() instanceof DisconnectedScreen d ? d.getNarrationMessage().getString() : "");
			check(turnedAway.contains("just for the host's friends"), "a non-friend is turned away from a friends-only world");
			context.takeScreenshot("mp-guest-turned-away");
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			writeShared("guest-done", "PASS " + String.join(",", routes.keySet()));
		} catch (Throwable t) {
			writeShared("guest-done", "FAIL " + t.getMessage());
			throw t;
		}
	}

	private static void joinThrough(ClientGameTestContext context, String route, String address) {
		JavaHost.LOGGER.info("[JavaHost MP] guest joining via {} at {}", route, address);
		context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
				new ServerData("JavaHost test host", address, ServerData.Type.OTHER), false, null));
		context.waitFor(mc -> (mc.player != null && mc.level != null && mc.gui.screen() == null)
				|| mc.gui.screen() instanceof DisconnectedScreen, 3 * ONE_MINUTE);
		String refused = context.computeOnClient(mc -> mc.gui.screen() instanceof DisconnectedScreen screen
				? screen.getNarrationMessage().getString() : null);
		check(refused == null, route + ": guest joined the host's world at " + address + (refused == null ? "" : " (refused: " + refused + ")"));

		context.waitFor(mc -> VoiceClient.isAvailable(), ONE_MINUTE);
		check(true, route + ": voice chat available");

		context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().anyMatch(id -> id.startsWith("server/")), 2 * ONE_MINUTE);
		check(true, route + ": host's resource pack applied");

		context.waitFor(mc -> IrisCompat.activeShaderPack().map(name -> name.equals("JavaHost - " + HOST_SHADER)).orElse(false), 2 * ONE_MINUTE);
		Path settingsFile = context.computeOnClient(mc -> IrisCompat.shaderpacksDirectory().resolve("JavaHost - " + HOST_SHADER + ".txt"));
		String settings;
		try {
			settings = Files.readString(settingsFile);
		} catch (IOException e) {
			throw new AssertionError("shader settings were not copied", e);
		}
		check(settings.contains(SHADER_SETTINGS), route + ": host's shader and its settings are active");

		UUID hostId = context.computeOnClient(mc -> mc.level.players().stream()
				.filter(p -> p.getPlainTextName().equals("Host")).map(AbstractClientPlayer::getUUID).findFirst().orElse(null));
		check(hostId != null, route + ": guest can see the host player");
		int before = VoiceClient.audibleFramesFrom(hostId);
		context.waitFor(mc -> VoiceClient.audibleFramesFrom(hostId) > before + 25, ONE_MINUTE);
		check(true, route + ": guest hears the host's voice");
		context.takeScreenshot("mp-guest-" + route);

		writeShared("guest-route", route);
		waitForShared(context, "host-ack-" + route, 3 * ONE_MINUTE);

		if (route.equals("relay")) {
			// The host kicks us on this route.
			context.waitFor(mc -> mc.gui.screen() instanceof DisconnectedScreen, ONE_MINUTE);
			check(true, route + ": guest was kicked by the host");
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		} else {
			context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
		}
		context.waitFor(mc -> mc.level == null, ONE_MINUTE);
		context.waitFor(mc -> IrisCompat.activeShaderPack().map(GUEST_SHADER::equals).orElse(false), ONE_MINUTE);
		check(true, route + ": guest's own shader is back after leaving");
		context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().noneMatch(id -> id.startsWith("server/")), ONE_MINUTE);
		check(true, route + ": host's resource pack removed after leaving");
	}
}

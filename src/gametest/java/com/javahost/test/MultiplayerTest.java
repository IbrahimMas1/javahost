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
import com.javahost.HostState;
import com.javahost.client.friends.Chats;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Presence;
import com.javahost.client.friends.Screenshots;
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
import net.minecraft.world.level.GameType;

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

			// ---- Chat both ways.
			Mailbox.Sent hello = await(context, context.computeOnClient(mc -> Friends.sendChat(guestFriend, "hello from host")));
			check(hello.ok(), "host sent a chat message (HTTP " + hello.status() + ")");
			context.waitFor(mc -> Chats.with(guestFriend.id()).stream().anyMatch(l -> !l.mine() && l.text().equals("hi host")), 2 * ONE_MINUTE);
			check(true, "host got the guest's chat reply");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openChat(guestFriend.id()));
			context.waitTicks(10);
			context.takeScreenshot("mp-host-chat");
			context.setScreen(() -> null);

			// ---- A screenshot, end-to-end encrypted, sent as an attachment.
			java.util.concurrent.CompletableFuture<Path> shot = new java.util.concurrent.CompletableFuture<>();
			context.runOnClient(mc -> Screenshots.take(shot::complete));
			Path shotFile = await(context, shot);
			byte[] png;
			try {
				png = Screenshots.prepareForSending(shotFile);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			writeShared("host-shot-sha", sha256(png));
			Mailbox.Sent sentShot = await(context, context.computeOnClient(mc -> Friends.sendScreenshot(guestFriend, png, shotFile.getFileName().toString())));
			check(sentShot.ok(), "host sent a " + png.length / 1024 + " KB screenshot to Guest (HTTP " + sentShot.status() + ")");
			waitForShared(context, "guest-social-done", 3 * ONE_MINUTE);

			// ---- One click invite; the guest's game confirms delivery.
			context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.FRIENDS));
			context.runOnClient(mc -> check(!HostController.isHosting(), "world not open yet before inviting"));
			context.runOnClient(mc -> InviteFlow.invite(guestFriend));
			context.waitFor(mc -> Friends.inviteStatus(guestFriend.id()).map(st -> st.state() == Friends.InviteState.DELIVERED).orElse(false), 2 * ONE_MINUTE);
			context.runOnClient(mc -> check(HostController.isHosting(), "inviting opened the world automatically and sent the invite"));
			check(true, "the invite shows Delivered (the guest's game confirmed it)");
			context.waitTicks(5);
			context.takeScreenshot("mp-host-friends-invited");
			context.setScreen(() -> null);
			context.waitFor(mc -> HostController.guests().stream().anyMatch(g -> g.name().equals("Guest")), 3 * ONE_MINUTE);
			check(true, "Guest joined the world from the invite's Join button");
			context.waitFor(mc -> "In your world".equals(InviteFlow.status(guestFriend.id())), ONE_MINUTE);
			check(true, "the friends list shows Guest as \"In your world\"");
			UUID guestPlayer = HostController.guests().stream().filter(g -> g.name().equals("Guest")).findFirst().orElseThrow().id();

			// ---- Host controls: game mode, and muting one player's voice.
			context.runOnClient(mc -> HostController.setGameMode(guestPlayer, GameType.CREATIVE));
			writeShared("host-set-creative", "ok");
			waitForShared(context, "guest-saw-creative", ONE_MINUTE);
			check(true, "host switched Guest to Creative and the guest's game shows it");
			context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.HOST));
			context.waitTicks(10);
			context.takeScreenshot("mp-host-controls");
			context.setScreen(() -> null);

			context.waitFor(mc -> VoiceClient.audibleFramesFrom(guestPlayer) > 25, ONE_MINUTE);
			context.runOnClient(mc -> Config.get().mutedPlayers.add(guestPlayer.toString()));
			context.waitTicks(20);
			int mutedAt = VoiceClient.audibleFramesFrom(guestPlayer);
			context.waitTicks(60);
			int afterMute = VoiceClient.audibleFramesFrom(guestPlayer);
			check(afterMute - mutedAt <= 2, "muting Guest silences them (" + (afterMute - mutedAt) + " frames in 3 s)");
			context.runOnClient(mc -> Config.get().mutedPlayers.remove(guestPlayer.toString()));
			context.waitFor(mc -> VoiceClient.audibleFramesFrom(guestPlayer) > afterMute + 25, ONE_MINUTE);
			check(true, "unmuting brings Guest's voice back");

			// ---- 1.4: a backup when a friend joins.
			String worldFolder = context.computeOnClient(mc -> mc.getSingleplayerServer()
					.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
			context.waitFor(mc -> !com.javahost.client.host.WorldBackups.list(worldFolder).isEmpty(), ONE_MINUTE);
			check(true, "the world was backed up when Guest joined");

			// ---- 1.4: version check.
			context.waitFor(mc -> Presence.versionOf(guestFriend.id()).isPresent(), 3 * ONE_MINUTE);
			check(JavaHost.VERSION.equals(Presence.versionOf(guestFriend.id()).get()), "host knows Guest's JavaHost version (" + JavaHost.VERSION + ")");

			// ---- 1.4: same-world fast lane: chat and a big screenshot go through the game, not the relay.
			int usageBefore = context.computeOnClient(mc -> com.javahost.client.friends.MessageUsage.sentToday());
			Mailbox.Sent fastChat = await(context, context.computeOnClient(mc -> Friends.sendChat(guestFriend, "fast hello")));
			check(fastChat.ok(), "chat to Guest sent while in the same world");
			Path noise = context.computeOnClient(mc -> JavaHostClientTest.writeNoisePng(Screenshots.dir().resolve("zz-fast-lane.png"), 1920, 1080));
			byte[] full;
			try {
				full = Screenshots.prepareForGame(noise);
				Files.delete(noise);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			writeShared("host-fast-sha", sha256(full));
			Mailbox.Sent fastShot = await(context, context.computeOnClient(mc -> Friends.sendScreenshot(guestFriend, full, "fast.png")));
			check(fastShot.ok() && full.length > 2_100_000, "a " + full.length / 1024 + " KB screenshot (too big for the relay) sent to Guest");
			check(context.computeOnClient(mc -> com.javahost.client.friends.MessageUsage.sentToday()) == usageBefore,
					"both went through the game connection: no relay messages used");
			waitForShared(context, "guest-fast-ok", 2 * ONE_MINUTE);
			check(true, "Guest got the chat and the full-size screenshot through the game");

			// ---- 1.4: party voice reaches a friend far away.
			runCommand(context, "tp Guest 600 120 600");
			context.waitTicks(60);
			int far = VoiceClient.audibleFramesFrom(guestPlayer);
			context.waitTicks(60);
			check(VoiceClient.audibleFramesFrom(guestPlayer) - far <= 2, "Guest 600 blocks away is out of nearby voice range");
			writeShared("host-wants-party", "ok");
			waitForShared(context, "guest-party-on", ONE_MINUTE);
			int beforeParty = VoiceClient.audibleFramesFrom(guestPlayer);
			context.waitFor(mc -> VoiceClient.audibleFramesFrom(guestPlayer) > beforeParty + 25, ONE_MINUTE);
			check(true, "party voice: the host hears Guest from 600 blocks away");
			writeShared("host-party-ok", "ok");
			waitForShared(context, "guest-party-off", ONE_MINUTE);
			runCommand(context, "tp Guest Host");
			context.runOnClient(mc -> HostController.setGameMode(guestPlayer, GameType.SURVIVAL));
			writeShared("host-controls-done", "ok");

			// ---- The guest leaves, sees us hosting in their friends list and joins with no invite.
			context.waitFor(mc -> HostController.guests().isEmpty(), ONE_MINUTE);
			context.waitFor(mc -> HostController.guests().stream().anyMatch(g -> g.name().equals("Guest")), 5 * ONE_MINUTE);
			check(true, "Guest joined again from Join on their friends list (online status, no invite)");
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
				context.waitFor(mc -> readShared("guest-done") != null || pendingRoute() != null || unfriendedNotAcked()
						|| waiting("guest-wants-ban", "host-banned") || waiting("guest-saw-ban", "host-full") || waiting("guest-saw-full", "host-open"), 15 * ONE_MINUTE);
				if (waiting("guest-wants-ban", "host-banned")) {
					context.runOnClient(mc -> HostController.ban(guestPlayer, "Guest"));
					writeShared("host-banned", "ok");
					continue;
				}
				if (waiting("guest-saw-ban", "host-full")) {
					check(true, "a banned player is turned away");
					context.runOnClient(mc -> {
						HostController.unban(guestPlayer);
						HostState.setMaxPlayers(1);
					});
					writeShared("host-full", "ok");
					continue;
				}
				if (waiting("guest-saw-full", "host-open")) {
					check(true, "the player limit turns people away when the world is full");
					context.runOnClient(mc -> HostController.applyHostRules(Config.get()));
					writeShared("host-open", "ok");
					continue;
				}
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

	/** Presses a button inside the JavaHost menu (it draws its own widgets, so the stock helper can't see them). */
	private static void clickMenuButton(ClientGameTestContext context, String label) {
		boolean pressed = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractButton button && button.visible && button.active
						&& button.getMessage().getString().equals(label)) {
					button.onPress(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
					return true;
				}
			}
			return false;
		});
		check(pressed, "pressed \"" + label + "\" in the JavaHost menu");
	}

	/** Runs a command on the host's server, as the server. */
	private static void runCommand(ClientGameTestContext context, String command) {
		context.runOnClient(mc -> {
			var server = mc.getSingleplayerServer();
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		});
		context.waitTicks(5);
	}

	/** The other game wrote {@code asked} and we haven't answered with {@code answer} yet. */
	private static boolean waiting(String asked, String answer) {
		return readShared(asked) != null && readShared(answer) == null;
	}

	private static String sha256(byte[] data) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	/** Waits for a future while letting the game keep ticking (callbacks often hop to the client thread). */
	private static <T> T await(ClientGameTestContext context, java.util.concurrent.CompletableFuture<T> future) {
		context.waitFor(mc -> future.isDone(), 3 * ONE_MINUTE);
		return future.join();
	}

	/** Tries to join; returns why we were turned away, or null if we got in (then leaves again). */
	private static String tryJoin(ClientGameTestContext context, String address) {
		context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
				new ServerData("JavaHost test host", address, ServerData.Type.OTHER), false, null));
		context.waitFor(mc -> mc.gui.screen() instanceof DisconnectedScreen || (mc.player != null && mc.gui.screen() == null), 2 * ONE_MINUTE);
		String refused = context.computeOnClient(mc -> mc.gui.screen() instanceof DisconnectedScreen d ? d.getNarrationMessage().getString() : null);
		if (refused == null) {
			context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
			context.waitFor(mc -> mc.level == null, ONE_MINUTE);
		} else {
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		}
		return refused;
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
		Friends.Friend host = context.computeOnClient(mc -> Friends.friends().stream().filter(f -> f.name().equals("Host")).findFirst().orElseThrow());

		// ---- Chat and a screenshot from the host.
		context.waitFor(mc -> Chats.with(host.id()).stream().anyMatch(l -> !l.mine() && l.text().equals("hello from host")), 2 * ONE_MINUTE);
		check(true, "guest got the host's chat message");
		Mailbox.Sent reply = await(context, context.computeOnClient(mc -> Friends.sendChat(host, "hi host")));
		check(reply.ok(), "guest replied in chat");
		waitForShared(context, "host-shot-sha", 3 * ONE_MINUTE);
		context.waitFor(mc -> !Screenshots.list(true).isEmpty(), 3 * ONE_MINUTE);
		String receivedSha;
		try {
			receivedSha = sha256(Files.readAllBytes(context.computeOnClient(mc -> Screenshots.list(true).getFirst())));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		check(receivedSha.equals(readShared("host-shot-sha")), "guest received the host's screenshot, decrypted and byte-for-byte identical");
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.NOTIFICATIONS));
		context.waitTicks(10);
		context.takeScreenshot("mp-guest-notifications");
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.SCREENSHOTS));
		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(Screenshots.list(true).getFirst()));
		context.waitTicks(10);
		context.takeScreenshot("mp-guest-screenshot-received");
		context.setScreen(TitleScreen::new);
		writeShared("guest-social-done", "ok");

		// ---- The invite popup's Join button.
		context.waitFor(mc -> mc.gui.screen() instanceof FriendPopup, 3 * ONE_MINUTE);
		context.takeScreenshot("mp-guest-invite");
		context.clickScreenButton("Join");
		context.waitFor(mc -> (mc.player != null && mc.level != null && mc.gui.screen() == null)
				|| mc.gui.screen() instanceof DisconnectedScreen, 3 * ONE_MINUTE);
		String refusedInvite = context.computeOnClient(mc -> mc.gui.screen() instanceof DisconnectedScreen d ? d.getNarrationMessage().getString() : null);
		check(refusedInvite == null, "guest joined the host's world by clicking Join" + (refusedInvite == null ? "" : " (refused: " + refusedInvite + ")"));
		VoiceClient.setTestTone(true);
		waitForShared(context, "host-set-creative", 2 * ONE_MINUTE);
		context.waitFor(mc -> mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE, ONE_MINUTE);
		writeShared("guest-saw-creative", "ok");
		check(true, "guest's game mode changed to Creative by the host");
		context.waitFor(mc -> InviteFlow.playingTogether(host.id()), ONE_MINUTE);
		check(!context.computeOnClient(mc -> InviteFlow.joinable(host.id())), "the host shows as \"Playing with you\", not Join, while we're in their world");
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.FRIENDS));
		context.waitTicks(10);
		context.takeScreenshot("mp-guest-friends-together");
		context.setScreen(() -> null);

		// ---- 1.4: version check, fast lane, party voice.
		check(JavaHost.VERSION.equals(com.javahost.client.JavaHostClient.hostVersion), "guest knows the host's JavaHost version");
		waitForShared(context, "host-fast-sha", 3 * ONE_MINUTE);
		context.waitFor(mc -> Chats.with(host.id()).stream().anyMatch(l -> !l.mine() && l.text().equals("fast hello")), ONE_MINUTE);
		String wantSha = readShared("host-fast-sha");
		context.waitFor(mc -> {
			List<Path> received = Screenshots.list(true);
			try {
				return !received.isEmpty() && sha256(Files.readAllBytes(received.getFirst())).equals(wantSha);
			} catch (IOException e) {
				return false;
			}
		}, ONE_MINUTE);
		check(true, "guest got the fast-lane chat and the full-size screenshot, byte-for-byte identical");
		writeShared("guest-fast-ok", "ok");
		waitForShared(context, "host-wants-party", 2 * ONE_MINUTE);
		context.runOnClient(mc -> Config.get().voiceMode = "PARTY");
		check(context.computeOnClient(mc -> VoiceClient.partyMode()), "guest switched to party voice");
		writeShared("guest-party-on", "ok");
		waitForShared(context, "host-party-ok", ONE_MINUTE);
		context.runOnClient(mc -> Config.get().voiceMode = "NEARBY");
		writeShared("guest-party-off", "ok");
		context.takeScreenshot("mp-guest-joined-from-invite");
		waitForShared(context, "host-controls-done", 3 * ONE_MINUTE);
		VoiceClient.setTestTone(false);
		context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
		context.waitFor(mc -> mc.level == null, ONE_MINUTE);

		// ---- Online status: the host shows as Hosting; Join straight from the list, no invite needed.
		context.waitFor(mc -> Presence.of(host.id()).map(Presence.Status::hosting).orElse(false), 3 * ONE_MINUTE);
		check(true, "guest sees the host as Hosting (online status)");
		context.setScreen(TitleScreen::new);
		context.waitTicks(10);
		context.takeScreenshot("mp-guest-title-friends");
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.FRIENDS));
		context.waitTicks(10);
		context.takeScreenshot("mp-guest-friends-hosting");
		clickMenuButton(context, "Join");
		context.waitFor(mc -> (mc.player != null && mc.level != null && mc.gui.screen() == null)
				|| mc.gui.screen() instanceof DisconnectedScreen, 3 * ONE_MINUTE);
		String refusedPresence = context.computeOnClient(mc -> mc.gui.screen() instanceof DisconnectedScreen d ? d.getNarrationMessage().getString() : null);
		check(refusedPresence == null, "guest joined from Join on the friends list" + (refusedPresence == null ? "" : " (refused: " + refusedPresence + ")"));
		context.waitTicks(40);
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

			// ---- Ban and player limit.
			String lanAddress = routes.get("lan");
			writeShared("guest-wants-ban", "ok");
			waitForShared(context, "host-banned", ONE_MINUTE);
			String banned = tryJoin(context, lanAddress);
			check(banned != null && banned.contains("banned"), "guest is turned away after being banned (" + banned + ")");
			writeShared("guest-saw-ban", "ok");
			waitForShared(context, "host-full", ONE_MINUTE);
			String full = tryJoin(context, lanAddress);
			check(full != null && full.contains("full"), "guest is turned away from a full world (" + full + ")");
			writeShared("guest-saw-full", "ok");
			waitForShared(context, "host-open", ONE_MINUTE);

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

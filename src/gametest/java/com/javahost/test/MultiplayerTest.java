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
import com.javahost.client.gui.WorldPickerScreen;
import com.javahost.client.share.WorldShare;
import com.javahost.util.Mailbox;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.HostController;
import com.javahost.client.pack.IrisCompat;
import com.javahost.client.voice.VoiceClient;
import com.javahost.net.Payloads.VoiceUp2;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
		config.tourDone = true;
		config.outputVolume = 0; // decode everything, play nothing
		config.port = 25599;

		context.runOnClient(mc -> {
			writeZip(mc.getResourcePackDirectory().resolve("host-pack.zip"), Map.of("pack.mcmeta",
					TestCompat.packMcmeta("Host's pack")));
			mc.getResourcePackRepository().reload();
			List<String> selected = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
			selected.add("file/host-pack.zip");
			mc.getResourcePackRepository().setSelected(selected);
		});
		prepareShader(context, HOST_SHADER, SHADER_SETTINGS);

		// Counts real joins by Guest, to prove refused players never get into the world at all.
		java.util.concurrent.atomic.AtomicInteger guestJoins = new java.util.concurrent.atomic.AtomicInteger();
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (com.javahost.compat.Compat.name(handler.player).equals("Guest")) {
				guestJoins.incrementAndGet();
			}
		});
		int joinsBeforeRefusal = -1;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestCompat.waitForChunks(world);
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
			// As if Minecraft's own "Open to LAN" had been used earlier with "Allow commands" on.
			context.runOnClient(mc -> TestCompat.setGuestCommands(mc.getSingleplayerServer(), true));
			context.runOnClient(mc -> InviteFlow.invite(guestFriend));
			context.waitFor(mc -> Friends.inviteStatus(guestFriend.id()).map(st -> st.state() == Friends.InviteState.DELIVERED).orElse(false), 2 * ONE_MINUTE);
			context.runOnClient(mc -> check(HostController.isHosting(), "inviting opened the world automatically and sent the invite"));
			context.runOnClient(mc -> check(!TestCompat.guestCommands(mc.getSingleplayerServer()), "guests get no commands, even after a vanilla Open to LAN with commands on"));
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
			// 1.11: voice reach is one world rule; a guest can't switch it on their own.
			writeShared("host-wants-party", "ok");
			waitForShared(context, "guest-tried-party", ONE_MINUTE);
			int stillFar = VoiceClient.audibleFramesFrom(guestPlayer);
			context.waitTicks(60);
			check(VoiceClient.audibleFramesFrom(guestPlayer) - stillFar <= 2, "a guest can't switch the world to party voice on their own");
			context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("VOICE", "party"));
			context.waitFor(mc -> com.javahost.rules.WorldRules.partyVoice(), ONE_MINUTE);
			writeShared("host-party-on", "ok");
			waitForShared(context, "guest-party-on", ONE_MINUTE);
			int beforeParty = VoiceClient.audibleFramesFrom(guestPlayer);
			context.waitFor(mc -> VoiceClient.audibleFramesFrom(guestPlayer) > beforeParty + 25, ONE_MINUTE);
			check(true, "party voice for the world: the host hears Guest from 600 blocks away");
			context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("VOICE", "nearby"));
			context.waitFor(mc -> !com.javahost.rules.WorldRules.partyVoice(), ONE_MINUTE);
			writeShared("host-party-ok", "ok");
			waitForShared(context, "guest-party-off", ONE_MINUTE);
			runCommand(context, "tp Guest Host");
			context.runOnClient(mc -> HostController.setGameMode(guestPlayer, GameType.SURVIVAL));

			// ---- 1.5: world rules are fair: only the host and co-hosts change them, and a lock stops everyone.
			net.minecraft.world.Difficulty startDifficulty = context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty());
			writeShared("host-rules-ready", "ok");
			waitForShared(context, "guest-tried-rules", ONE_MINUTE);
			context.waitTicks(20);
			check(context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty()) == startDifficulty,
					"a guest who isn't a co-host can't change world rules");
			context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__cohost:" + guestPlayer, "true"));
			writeShared("host-made-cohost", "ok");
			waitForShared(context, "guest-changed-rules", ONE_MINUTE);
			context.waitFor(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty() == net.minecraft.world.Difficulty.HARD, ONE_MINUTE);
			check(true, "after the host makes Guest a co-host, Guest can change Difficulty (and everyone is told)");
			context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "true"));
			context.waitFor(mc -> com.javahost.client.rules.RulesClient.state() != null && com.javahost.client.rules.RulesClient.state().locked(), ONE_MINUTE);
			writeShared("host-locked", "ok");
			waitForShared(context, "guest-tried-locked", ONE_MINUTE);
			context.waitTicks(20);
			check(context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty()) == net.minecraft.world.Difficulty.HARD,
					"locked rules can't be changed, even by a co-host");
			context.runOnClient(mc -> {
				com.javahost.client.rules.RulesClient.request("__lock", "false");
				com.javahost.client.rules.RulesClient.request("__cohost:" + guestPlayer, "false");
			});
			sendWorlds(context, guestPlayer);
			survivesAbuse(context);
			writeShared("host-controls-done", "ok");

			// ---- The guest leaves, sees us hosting in their friends list and joins with no invite.
			context.waitFor(mc -> HostController.guests().isEmpty(), ONE_MINUTE);
			context.waitFor(mc -> HostController.guests().stream().anyMatch(g -> g.name().equals("Guest")), 5 * ONE_MINUTE);
			check(true, "Guest joined again from Join on their friends list (online status, no invite)");
			waitForShared(context, "guest-invite-done", 3 * ONE_MINUTE);

			context.waitFor(mc -> !Connectivity.isProbing(), ONE_MINUTE);
			// The relay mod only loads in these tests on 26.1+; older versions test the direct routes.
			boolean relay = com.javahost.client.host.E4mcCompat.isInstalled();
			if (relay) {
				context.waitFor(mc -> Connectivity.relayDomain() != null, 2 * ONE_MINUTE);
				check(true, "relay is up at " + Connectivity.relayDomain());
			}
			for (Connectivity.Route route : Connectivity.routes()) {
				appendResult("route " + route.name() + ": " + route.state() + " " + route.invite() + " | " + route.detail());
			}

			Properties addresses = new Properties();
			addresses.setProperty("lan", Connectivity.lanIp() + ":" + HostController.port());
			if (relay) {
				addresses.setProperty("relay", Connectivity.relayDomain());
			}
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
						|| waiting("guest-wants-ban", "host-banned") || waiting("guest-saw-ban", "host-full") || waiting("guest-saw-full", "host-open") || waiting("guest-wants-public", "host-public-done"), 15 * ONE_MINUTE);
				if (waiting("guest-wants-public", "host-public-done")) {
					// Checked before the public world opens, where the (now stranger) guest is welcome as a visitor.
					check(joinsBeforeRefusal >= 0 && guestJoins.get() == joinsBeforeRefusal,
							"a non-friend is refused at login: they never joined the world (no 'joined' message, nothing sent to them)");
					hostPublicWorld(context);
					continue;
				}
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
					joinsBeforeRefusal = guestJoins.get();
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
					WorldShare.Transfer overRelay = context.computeOnClient(mc -> WorldShare.send(Friends.friend(guestId).orElseThrow(),
							WorldShare.sources().stream().filter(src -> src.kind() == WorldShare.Source.Kind.BACKUP).findFirst().orElseThrow()));
					context.waitFor(mc -> !overRelay.active(), 3 * ONE_MINUTE);
					check(overRelay.stage() == WorldShare.Stage.DONE, "relay: a backup sent through the relay arrived (" + overRelay.status() + ")");
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

	// ================================================================ 1.8: public worlds

	private static final String DIRECTORY = "http://127.0.0.1:8795";

	/** Host side: open to the public, see the stranger arrive as a visitor, trust them, then close again. */
	private static void hostPublicWorld(ClientGameTestContext context) {
		String problem = context.computeOnClient(mc -> com.javahost.client.discover.PublicHosting.open(new com.javahost.client.discover.PublicHosting.Settings(
				"Help me build a test castle", "Bring stone. Be nice.", List.of("help", "building"), 8, false)));
		check(problem == null, "the host opened the world to the public" + (problem == null ? "" : " (" + problem + ")"));
		context.waitFor(mc -> com.javahost.client.discover.PublicHosting.listed(), 3 * ONE_MINUTE);
		check(true, "the world is on the public list");
		boolean noDirect = context.computeOnClient(mc -> Connectivity.routes().stream()
				.filter(r -> r.name().equals("Internet") || r.name().equals("IPv6")).noneMatch(r -> r.state() == Connectivity.State.WORKING));
		check(noDirect, "a public world opens no direct way in (no router port, no IPv6): strangers only reach it through the relay");
		writeShared("host-public", "ok");

		context.waitFor(mc -> HostController.guests().stream().anyMatch(g -> g.name().equals("Guest")), 3 * ONE_MINUTE);
		context.waitTicks(20);
		HostController.Guest visitor = context.computeOnClient(mc -> HostController.guests().stream().filter(g -> g.name().equals("Guest")).findFirst().orElseThrow());
		check(visitor.visitor() && visitor.gameMode() == GameType.ADVENTURE, "a stranger joins as a visitor, in Adventure (can't break or place)");
		int heard = VoiceClient.audibleFramesFrom(visitor.id());
		context.waitTicks(60);
		check(VoiceClient.audibleFramesFrom(visitor.id()) == heard, "with voice for visitors off, nobody hears the visitor");
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.HOST));
		context.waitTicks(10);
		context.takeScreenshot("mp-host-public-visitor");
		context.setScreen(() -> null);

		context.runOnClient(mc -> HostController.trust(visitor.id(), visitor.name(), true));
		context.waitFor(mc -> HostController.guests().stream().anyMatch(g -> g.name().equals("Guest") && g.trusted() && g.gameMode() == GameType.SURVIVAL),
				ONE_MINUTE);
		check(true, "after Trust, the visitor can build (Survival)");
		writeShared("host-trusted", "ok");

		waitForShared(context, "guest-reported", 2 * ONE_MINUTE);
		try {
			var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(DIRECTORY + "/admin/reports"))
					.header("authorization", "Bearer dev-admin").build();
			String reports = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
			check(reports.contains("\"reports\":1"), "the guest's report reached the list's moderators (" + reports + ")");
		} catch (Exception e) {
			throw new AssertionError(e);
		}

		context.runOnClient(mc -> com.javahost.client.discover.PublicHosting.close());
		context.waitFor(mc -> HostController.guests().isEmpty(), ONE_MINUTE);
		check(true, "closing the world to the public sends strangers away again (friends only)");
		boolean gone = false;
		for (int i = 0; i < 20 && !gone; i++) {
			context.waitTicks(10);
			gone = context.computeOnClient(mc -> {
				try {
					return com.javahost.client.discover.Directory.list().get(5, java.util.concurrent.TimeUnit.SECONDS).stream().noneMatch(w -> w.name().equals("Host"));
				} catch (Exception e) {
					return false;
				}
			});
		}
		check(gone, "and it's off the public list");
		writeShared("host-public-done", "ok");
	}

	/** Guest side: a stranger now (we unfriended), finds the public world, visits, gets trusted, reports. */
	private static void visitPublicWorld(ClientGameTestContext context, UUID hostId) {
		writeShared("guest-wants-public", "ok");
		waitForShared(context, "host-public", 4 * ONE_MINUTE);
		com.javahost.client.discover.Directory.PublicWorld world = null;
		for (int i = 0; i < 30 && world == null; i++) {
			context.waitTicks(20);
			world = context.computeOnClient(mc -> {
				try {
					return com.javahost.client.discover.Directory.list().get(5, java.util.concurrent.TimeUnit.SECONDS).stream()
							.filter(w -> w.host().equals(hostId)).findFirst().orElse(null);
				} catch (Exception e) {
					return null;
				}
			});
		}
		check(world != null && world.title().equals("Help me build a test castle"), "the stranger finds the host's world on the public list");
		check(com.javahost.util.ListingText.isRelayAddress(world.relay()), "the listing gives only a relay address, never the host's home address (" + world.relay() + ")");
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.DISCOVER));
		context.waitTicks(200); // pings go through the relay
		context.takeScreenshot("mp-guest-discover");
		com.javahost.client.discover.Directory.PublicWorld found = world;
		context.runOnClient(mc -> InviteFlow.joinPublic(found));
		context.waitFor(mc -> (mc.player != null && mc.level != null) || mc.gui.screen() instanceof DisconnectedScreen, 3 * ONE_MINUTE);
		context.waitFor(mc -> !InviteFlow.verifying(), ONE_MINUTE);
		check(context.computeOnClient(mc -> mc.level != null), "joined the public world, and the host proved it's theirs (with the listed key)");
		// "Always use the host's packs" is on in this test, but a stranger's packs are always asked about.
		context.waitFor(mc -> mc.gui.screen() instanceof com.javahost.client.gui.PackOfferScreen, ONE_MINUTE);
		check(true, "a stranger's packs are asked about, never applied automatically");
		context.setScreen(() -> null);
		context.waitFor(mc -> mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.ADVENTURE, ONE_MINUTE);
		check(true, "the stranger is a visitor: Adventure until trusted");
		context.takeScreenshot("mp-guest-visiting");
		waitForShared(context, "host-trusted", 2 * ONE_MINUTE);
		context.waitFor(mc -> mc.gameMode.getPlayerMode() == GameType.SURVIVAL, ONE_MINUTE);
		check(true, "the host trusted the visitor: now they can build");
		var reported = await(context, com.javahost.client.discover.Directory.report(hostId, "other"));
		check(reported.ok(), "reporting a public world works (" + reported.message() + ")");
		writeShared("guest-reported", "ok");
		context.waitFor(mc -> mc.gui.screen() instanceof DisconnectedScreen, 2 * ONE_MINUTE);
		String reason = context.computeOnClient(mc -> ((DisconnectedScreen) mc.gui.screen()).getNarrationMessage().getString());
		check(reason.contains("just for the host's friends"), "when the host closes it to the public, strangers are sent away (" + reason + ")");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	// ================================================================ 1.7: safety

	/** Host side: a hacked guest floods JavaHost traffic; the world keeps running and the flooder is removed. */
	private static void survivesAbuse(ClientGameTestContext context) {
		String sha = context.computeOnClient(mc -> HostState.sharedPacks().isEmpty() ? "" : HostState.sharedPacks().getFirst().info().sha1());
		writeShared("host-ready-for-abuse", sha);
		context.waitFor(mc -> readShared("guest-abuse-done") != null || HostController.guests().isEmpty(), 3 * ONE_MINUTE);
		long tickMs = context.computeOnClient(mc -> mc.getSingleplayerServer().getAverageTickTimeNanos() / 1_000_000);
		check(tickMs < 50, "the world kept running smoothly through the flood (average tick " + tickMs + " ms)");
		context.waitFor(mc -> HostController.guests().isEmpty(), ONE_MINUTE);
		check(true, "a game that kept flooding was removed from the world");
		waitForShared(context, "guest-abuse-done", ONE_MINUTE);
	}

	/** Guest side: acts like a hacked client. Short bursts are just dropped; a 12-second flood gets us removed. */
	private static void abuseHost(ClientGameTestContext context, Friends.Friend host) {
		waitForShared(context, "host-ready-for-abuse", 2 * ONE_MINUTE);
		String sha = readShared("host-ready-for-abuse").trim();
		UUID hostId = host.id();
		context.runOnClient(mc -> {
			// Fake acknowledgements for a pack we never received, and the same pack asked for again and again.
			for (int i = 0; i < 20 && !sha.isEmpty(); i++) {
				ClientPlayNetworking.send(new com.javahost.net.Payloads.PackRequest(List.of(sha)));
				ClientPlayNetworking.send(new com.javahost.net.Payloads.PackAck(sha, 100_000));
			}
			// Relayed "messages" claiming billions of pieces.
			for (int i = 0; i < 200; i++) {
				ClientPlayNetworking.send(new com.javahost.net.Payloads.RelayUp(hostId, i, 0, Integer.MAX_VALUE, new byte[30 * 1024]));
			}
			// Rule-change spam.
			for (int i = 0; i < 200; i++) {
				ClientPlayNetworking.send(new com.javahost.net.Payloads.RulesChange("DIFFICULTY", i % 2 == 0 ? "hard" : "easy"));
			}
		});
		context.waitTicks(40);
		check(context.computeOnClient(mc -> mc.level != null), "short bursts are dropped, not punished (still in the world)");
		// Now a sustained party-voice flood: 200 frames a second for 13 seconds.
		for (int second = 0; second < 13 && context.computeOnClient(mc -> mc.level != null); second++) {
			int base = second * 200;
			context.runOnClient(mc -> {
				for (int i = 0; i < 200 && ClientPlayNetworking.canSend(VoiceUp2.TYPE); i++) {
					ClientPlayNetworking.send(new VoiceUp2(base + i, true, new byte[100]));
				}
			});
			context.waitTicks(20);
		}
		context.waitFor(mc -> mc.gui.screen() instanceof DisconnectedScreen, ONE_MINUTE);
		String reason = context.computeOnClient(mc -> ((DisconnectedScreen) mc.gui.screen()).getNarrationMessage().getString());
		check(reason.contains("Too much traffic"), "a client that keeps flooding is removed (" + reason + ")");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		writeShared("guest-abuse-done", "ok");
	}

	/**
	 * Guest side: with the wrong signing key on file for the host (as if a fake server answered), joining from the
	 * friends list must not stay in that world: each way in fails the proof, and we're told why.
	 */
	private static void joinsAFakeWorldAndLeaves(ClientGameTestContext context, Friends.Friend host) {
		String realKey = host.signKey();
		check(realKey != null, "the host's signing key arrived with their accept");
		String wrongKey = com.javahost.util.FriendCrypto.encodePublic(com.javahost.util.FriendCrypto.newSigningKeyPair().getPublic());
		setSignKey(context, host.id(), wrongKey);
		Presence.Status status = context.computeOnClient(mc -> Presence.of(host.id()).orElseThrow());
		context.runOnClient(mc -> InviteFlow.join(status.asInvite(host.name())));
		context.waitFor(mc -> com.javahost.client.friends.Notifications.items().stream().anyMatch(i -> i.text().contains("Couldn't confirm")),
				3 * ONE_MINUTE);
		context.waitFor(mc -> mc.level == null && !(mc.gui.screen() instanceof ConnectScreen), ONE_MINUTE);
		check(true, "a world that can't prove it's the friend's is left at once, by every route, with a clear message");
		context.takeScreenshot("mp-guest-unconfirmed-world");
		setSignKey(context, host.id(), realKey);
	}

	private static void setSignKey(ClientGameTestContext context, UUID friend, String key) {
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field dataField = Friends.class.getDeclaredField("data");
				dataField.setAccessible(true);
				Object data = dataField.get(null);
				java.lang.reflect.Field friendsField = data.getClass().getDeclaredField("friends");
				friendsField.setAccessible(true);
				@SuppressWarnings("unchecked")
				List<Friends.Friend> friends = (List<Friends.Friend>) friendsField.get(data);
				friends.replaceAll(f -> f.id().equals(friend) ? new Friends.Friend(f.id(), f.name(), f.box(), f.since(), f.statusKey(), key) : f);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	// ================================================================ 1.6: sending a world copy

	private static WorldShare.Source liveWorld() {
		return WorldShare.sources().stream().filter(src -> src.kind() == WorldShare.Source.Kind.LIVE).findFirst().orElseThrow();
	}

	/** Host side: send the live world, have one declined, cancel one halfway, then receive one from the guest. */
	private static void sendWorlds(ClientGameTestContext context, UUID guestPlayer) {
		Friends.Friend guest = context.computeOnClient(mc -> Friends.friend(guestPlayer).orElseThrow());
		String problem = context.computeOnClient(mc -> WorldShare.cannotSendTo(guestPlayer));
		check(problem == null, "the host can send Guest a world" + (problem == null ? "" : " (" + problem + ")"));
		context.setScreen(() -> new WorldPickerScreen(null, guest, null, null));
		context.waitTicks(10);
		context.takeScreenshot("mp-host-world-picker");
		context.setScreen(() -> null);

		WorldShare.Transfer live = context.computeOnClient(mc -> WorldShare.send(guest, liveWorld()));
		writeShared("host-sending-world", "ok");
		context.waitFor(mc -> live.stage() == WorldShare.Stage.SENDING || !live.active(), 2 * ONE_MINUTE);
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.NOTIFICATIONS));
		context.waitTicks(2);
		context.takeScreenshot("mp-host-world-sending");
		context.waitFor(mc -> !live.active(), 3 * ONE_MINUTE);
		check(live.stage() == WorldShare.Stage.DONE, "the live world reached Guest, unpacked (" + live.status() + ")");
		context.waitTicks(2);
		context.takeScreenshot("mp-host-world-sent");
		context.setScreen(() -> null);
		waitForShared(context, "guest-got-world", ONE_MINUTE);

		WorldShare.Transfer declined = context.computeOnClient(mc -> WorldShare.send(guest,
				WorldShare.sources().stream().filter(src -> src.kind() == WorldShare.Source.Kind.BACKUP).findFirst().orElseThrow()));
		context.waitFor(mc -> !declined.active(), 2 * ONE_MINUTE);
		check(declined.stage() == WorldShare.Stage.FAILED && declined.status().contains("no thanks"),
				"a declined offer is reported to the sender (" + declined.status() + ")");

		// The test world is tiny, so sending is held still (no chunks go out) to cancel it mid-way for sure.
		context.runOnClient(mc -> WorldShare.throttleForTests(0));
		WorldShare.Transfer halfway = context.computeOnClient(mc -> WorldShare.send(guest, liveWorld()));
		context.waitFor(mc -> halfway.stage() == WorldShare.Stage.SENDING || !halfway.active(), 3 * ONE_MINUTE);
		context.waitTicks(10);
		check(halfway.stage() == WorldShare.Stage.SENDING, "a transfer is still going when cancelled (" + halfway.status() + ")");
		context.runOnClient(mc -> WorldShare.cancel(halfway));
		context.runOnClient(mc -> WorldShare.throttleForTests(32));
		waitForShared(context, "guest-saw-cancel", ONE_MINUTE);
		boolean noZip = context.computeOnClient(mc -> {
			try (var files = Files.list(WorldShare.outgoingDir())) {
				return files.findAny().isEmpty();
			} catch (IOException e) {
				return true;
			}
		});
		check(noZip, "cancelling removes the sender's temporary copy");

		writeShared("host-ready-for-world", "ok");
		WorldShare.Transfer incoming = awaitOffer(context, guestPlayer);
		context.runOnClient(mc -> WorldShare.accept(incoming));
		context.waitFor(mc -> !incoming.active(), 3 * ONE_MINUTE);
		check(incoming.stage() == WorldShare.Stage.DONE, "the host received a world from Guest (" + incoming.status() + ")");
		checkReceived(context, incoming);
		writeShared("host-got-world", "ok");
	}

	/** Guest side of {@link #sendWorlds}. */
	private static void receiveWorlds(ClientGameTestContext context, Friends.Friend host) {
		waitForShared(context, "host-sending-world", 2 * ONE_MINUTE);
		WorldShare.Transfer offer = awaitOffer(context, host.id());
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.NOTIFICATIONS));
		context.waitTicks(4);
		context.takeScreenshot("mp-guest-world-offer");
		context.runOnClient(mc -> WorldShare.accept(offer));
		context.waitFor(mc -> !offer.active(), 3 * ONE_MINUTE);
		check(offer.stage() == WorldShare.Stage.DONE, "Guest received the host's live world (" + offer.status() + ")");
		context.waitTicks(2);
		context.takeScreenshot("mp-guest-world-received");
		context.setScreen(() -> null);
		checkReceived(context, offer);
		// players/data/ on 26.x, playerdata/ before.
		check(Files.exists(offer.result().resolve("players").resolve("data").resolve(host.id() + ".dat"))
				|| Files.exists(offer.result().resolve("playerdata").resolve(host.id() + ".dat")), "the host's own player is kept in the copy");
		writeShared("guest-got-world", "ok");

		WorldShare.Transfer unwanted = awaitOffer(context, host.id());
		context.runOnClient(mc -> WorldShare.decline(unwanted));

		WorldShare.Transfer halfway = awaitOffer(context, host.id());
		long saves = countSaves(context);
		context.runOnClient(mc -> WorldShare.accept(halfway));
		context.waitFor(mc -> !halfway.active(), 3 * ONE_MINUTE);
		check(halfway.stage() == WorldShare.Stage.FAILED, "a transfer the host cancels stops on the guest too (" + halfway.status() + ")");
		boolean noPart = context.computeOnClient(mc -> {
			try (var files = Files.list(WorldShare.incomingDir())) {
				return files.findAny().isEmpty();
			} catch (IOException e) {
				return true;
			}
		});
		check(noPart && countSaves(context) == saves, "a cancelled transfer leaves no partial files or worlds");
		writeShared("guest-saw-cancel", "ok");

		waitForShared(context, "host-ready-for-world", ONE_MINUTE);
		WorldShare.Source received = context.computeOnClient(mc -> WorldShare.sources().stream()
				.filter(src -> src.path().toAbsolutePath().normalize().equals(offer.result().toAbsolutePath().normalize())).findFirst().orElseThrow());
		WorldShare.Transfer back = context.computeOnClient(mc -> WorldShare.send(host, received));
		context.waitFor(mc -> !back.active(), 3 * ONE_MINUTE);
		check(back.stage() == WorldShare.Stage.DONE, "Guest sent a saved world to the host (" + back.status() + ")");
		waitForShared(context, "host-got-world", ONE_MINUTE);
	}

	private static WorldShare.Transfer awaitOffer(ClientGameTestContext context, UUID from) {
		context.waitFor(mc -> WorldShare.transfers().stream().anyMatch(t -> !t.outgoing && t.peer.equals(from) && t.stage() == WorldShare.Stage.OFFERED),
				2 * ONE_MINUTE);
		return context.computeOnClient(mc -> WorldShare.transfers().stream()
				.filter(t -> !t.outgoing && t.peer.equals(from) && t.stage() == WorldShare.Stage.OFFERED).findFirst().orElseThrow());
	}

	private static void checkReceived(ClientGameTestContext context, WorldShare.Transfer transfer) {
		Path world = transfer.result();
		check(world != null && Files.isRegularFile(world.resolve("level.dat")), "the received world is in saves/ (" + world + ")");
		UUID me = context.computeOnClient(mc -> com.javahost.client.friends.Identity.id());
		try {
			check(com.javahost.share.WorldOwner.ownerOf(world).orElse(null).equals(me), "the receiver owns the copy, so they play as themselves");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static long countSaves(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			try (var dirs = Files.list(mc.getLevelSource().getBaseDir())) {
				return dirs.count();
			} catch (IOException e) {
				return -1L;
			}
		});
	}

	/** Presses a button inside the JavaHost menu (it draws its own widgets, so the stock helper can't see them). */
	private static void clickMenuButton(ClientGameTestContext context, String label) {
		boolean pressed = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractButton button && button.visible && button.active
						&& button.getMessage().getString().equals(label)) {
					TestCompat.press(button);
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
		config.tourDone = true;
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
		// The guest tries every way to switch to party voice: their own setting, B, and asking the server.
		context.runOnClient(mc -> {
			Config.get().voiceMode = "PARTY";
			com.javahost.client.JavaHostClient.toggleParty();
			com.javahost.client.rules.RulesClient.request("VOICE", "party");
		});
		context.waitTicks(20);
		check(!context.computeOnClient(mc -> VoiceClient.partyMode()), "a guest's own choice doesn't change the world's voice reach");
		writeShared("guest-tried-party", "ok");
		waitForShared(context, "host-party-on", ONE_MINUTE);
		context.waitFor(mc -> VoiceClient.partyMode(), ONE_MINUTE);
		check(true, "when the host switches the world to party voice, the guest is on it too");
		writeShared("guest-party-on", "ok");
		waitForShared(context, "host-party-ok", ONE_MINUTE);
		context.waitFor(mc -> !VoiceClient.partyMode(), ONE_MINUTE);
		check(true, "back to nearby voice for everyone when the host switches back");
		context.runOnClient(mc -> Config.get().voiceMode = "NEARBY");
		writeShared("guest-party-off", "ok");
		// ---- 1.5: world rules from the guest's side.
		waitForShared(context, "host-rules-ready", 2 * ONE_MINUTE);
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state() != null, ONE_MINUTE);
		check(!context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.state().canEdit()), "guest sees the world rules read-only");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("DIFFICULTY", "peaceful"));
		writeShared("guest-tried-rules", "ok");
		waitForShared(context, "host-made-cohost", ONE_MINUTE);
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state().canEdit(), ONE_MINUTE);
		check(true, "guest became a co-host");
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.RULES));
		context.waitTicks(10);
		context.takeScreenshot("mp-guest-world-rules-cohost");
		context.setScreen(() -> null);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("DIFFICULTY", "hard"));
		writeShared("guest-changed-rules", "ok");
		waitForShared(context, "host-locked", ONE_MINUTE);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("DIFFICULTY", "easy"));
		writeShared("guest-tried-locked", "ok");
		context.takeScreenshot("mp-guest-joined-from-invite");
		receiveWorlds(context, host);
		abuseHost(context, host);
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
		joinsAFakeWorldAndLeaves(context, host);
		context.setScreen(() -> new JavaHostScreen(null, JavaHostScreen.Tab.FRIENDS));
		context.waitTicks(10);
		clickMenuButton(context, "Join");
		context.waitFor(mc -> (mc.player != null && mc.level != null && mc.gui.screen() == null)
				|| mc.gui.screen() instanceof DisconnectedScreen, 3 * ONE_MINUTE);
		context.waitFor(mc -> !InviteFlow.verifying(), ONE_MINUTE);
		check(context.computeOnClient(mc -> mc.level != null), "joining from the friends list: the host proved it's their world");
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
		if (addresses.getProperty("relay") != null) {
			routes.put("relay", addresses.getProperty("relay"));
		}

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
			if (routes.containsKey("relay")) {
				visitPublicWorld(context, hostFriend.id()); // public worlds are relay-only
			}
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
				.filter(p -> com.javahost.compat.Compat.name(p).equals("Host")).map(AbstractClientPlayer::getUUID).findFirst().orElse(null));
		check(hostId != null, route + ": guest can see the host player");
		int before = VoiceClient.audibleFramesFrom(hostId);
		context.waitFor(mc -> VoiceClient.audibleFramesFrom(hostId) > before + 25, ONE_MINUTE);
		check(true, route + ": guest hears the host's voice");
		context.takeScreenshot("mp-guest-" + route);

		writeShared("guest-route", route);
		if (route.equals("relay")) {
			WorldShare.Transfer offer = awaitOffer(context, hostId);
			context.runOnClient(mc -> WorldShare.accept(offer));
			context.waitFor(mc -> !offer.active(), 3 * ONE_MINUTE);
			check(offer.stage() == WorldShare.Stage.DONE, "relay: guest received a world through the relay (" + offer.status() + ")");
		}
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

package com.javahost.test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.friends.Chats;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.host.E4mcCompat;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;

/**
 * Website screenshots, not a test: a game session driven line by line from {@code commands.txt} in the showcase
 * folder, so shots can be set up, checked and retaken without restarting. Each finished line is reported in
 * {@code done.txt}. Run with {@code sh scripts/showcase.sh}. Everything shown is made up: fake players, friends and
 * documentation addresses.
 */
final class Showcase {
	private static Path dir;
	private static TestSingleplayerContext world;
	/** Made-up people: name to UUID, chosen so each gets the default skin asked for. */
	private static final Map<String, UUID> PEOPLE = new LinkedHashMap<>();
	private static final Map<String, ServerPlayer> FAKES = new LinkedHashMap<>();
	private static final Set<UUID> TALKING = ConcurrentHashMap.newKeySet();
	/** The frame rate shown in the menu and HUD (the test renderer is slower than a real game), or 0 for the real one. */
	private static volatile int shownFps;
	private static Process directory;

	private Showcase() {
	}

	static void run(ClientGameTestContext context) {
		dir = Path.of(System.getProperty("javahost.showcaseDir"));
		Path commands = dir.resolve("commands.txt");
		Path done = dir.resolve("done.txt");
		try {
			Files.createDirectories(dir.resolve("shots"));
			Files.writeString(commands, "");
			Files.writeString(done, "ready\n");
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		Config config = Config.get();
		config.tourDone = true;
		config.useUpnp = false; // never touch the router
		config.relayMode = "NEVER";
		config.friendsOnly = false;
		config.autoAcceptHostPacks = true;
		E4mcCompat.setRelayEnabled(false);
		// Voice pills without a microphone: the audio threads are marked started so they never open one.
		setStatic(com.javahost.client.voice.VoiceClient.class, "started", true);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> keepTalking());

		int handled = 0;
		while (true) {
			List<String> lines;
			try {
				lines = Files.readAllLines(commands);
			} catch (Exception e) {
				lines = List.of();
			}
			if (lines.size() <= handled) {
				context.waitTick();
				continue;
			}
			String line = lines.get(handled++).trim();
			String result;
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.equals("quit")) {
				break;
			}
			try {
				result = "ok " + handled + " " + execute(context, line);
			} catch (Throwable e) {
				JavaHost.LOGGER.error("[Showcase] {} failed", line, e);
				result = "error " + handled + " " + line + " -> " + e;
			}
			try {
				Files.writeString(done, result + "\n", StandardOpenOption.APPEND);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
		if (world != null) {
			world.close();
		}
		if (directory != null) {
			directory.destroy();
		}
	}

	private static String execute(ClientGameTestContext context, String line) throws Exception {
		String[] a = line.split("\\s+");
		String rest = line.substring(a[0].length()).trim();
		switch (a[0]) {
			case "world" -> {
				if (world != null) {
					FAKES.clear();
					world.close();
				}
				world = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> {
					s.setSeed(a[1]);
					s.setAllowCommands(true);
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				}).create();
				try {
					TestCompat.waitForChunks(world);
				} catch (AssertionError slow) {
					// far render distances take longer than the default wait; carry on
				}
				world.getServer().runCommand("gamerule advance_time false");
				world.getServer().runCommand("gamerule advance_weather false");
				world.getServer().runCommand("gamerule spawn_mobs false");
				return "world " + a[1];
			}
			case "mc" -> world.getServer().runCommand(rest);
			case "me" -> world.getServer().runCommand("execute as " + self(context) + " at @s run " + rest);
			case "wait" -> context.waitTicks(Integer.parseInt(a[1]));
			case "chunks" -> TestCompat.waitForChunks(world);
			case "window" -> {
				context.getInput().resizeWindow(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
				int scale = Integer.parseInt(a[3]);
				context.runOnClient(mc -> {
					mc.options.guiScale().set(scale);
					TestCompat.resizeGui(mc);
				});
				context.waitTicks(2);
			}
			case "hud" -> context.runOnClient(mc -> {
				if (mc.gui.hud.isHidden() == a[1].equals("on")) {
					mc.gui.hud.toggle();
				}
			});
			case "render" -> {
				int distance = Integer.parseInt(a[1]);
				context.runOnClient(mc -> mc.options.renderDistance().set(distance));
				if (world != null) {
					world.getServer().runOnServer(server -> server.getPlayerList().setViewDistance(distance));
				}
			}
			case "fov" -> context.runOnClient(mc -> mc.options.fov().set(Integer.parseInt(a[1])));
			case "clouds" -> context.runOnClient(mc -> mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.valueOf(a[1].toUpperCase())));
			case "graphics" -> context.runOnClient(mc -> {
				mc.options.graphicsPreset().set(net.minecraft.client.GraphicsPreset.valueOf(a[1].toUpperCase()));
				mc.options.graphicsPreset().get().apply(mc);
			});
			case "blur" -> context.runOnClient(mc -> mc.options.menuBackgroundBlurriness().set(Integer.parseInt(a[1])));
			case "fps" -> shownFps = Integer.parseInt(a[1]);
			case "chatclear" -> context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			case "shot" -> {
				// Nothing focused: keyboard focus draws like a hover, and a focused text field shows its cursor.
				context.runOnClient(mc -> {
					if (mc.gui.screen() != null) {
						mc.gui.screen().setFocused(null);
						for (var child : mc.gui.screen().children()) {
							if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
								w.setFocused(false);
							}
						}
					}
				});
				context.waitTicks(15); // the hover highlight fades out
				Path file = context.takeScreenshot(TestScreenshotOptions.of(a[1]).disableCounterPrefix().withDestinationDir(dir.resolve("shots")));
				return file.toString();
			}
			case "person" -> PEOPLE.put(a[1], context.computeOnClient(mc -> idWithSkin(a[1], a[2])));
			case "fake" -> spawnFake(a);
			case "fakepose" -> onFake(a[1], p -> {
				boolean crouch = a[2].equals("crouch");
				p.setShiftKeyDown(crouch);
				p.setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
			});
			case "fakelook" -> onFake(a[1], p -> {
				float yaw = Float.parseFloat(a[2]);
				p.snapTo(p.getX(), p.getY(), p.getZ(), yaw, Float.parseFloat(a[3]));
				p.setYHeadRot(yaw);
				p.setYBodyRot(yaw);
			});
			case "fakemove" -> onFake(a[1], p -> {
				float yaw = Float.parseFloat(a[5]);
				p.snapTo(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]), yaw, Float.parseFloat(a[6]));
				p.setYHeadRot(yaw);
				p.setYBodyRot(yaw);
			});
			case "fakeitem" -> onFake(a[1], p -> {
				EquipmentSlot slot = EquipmentSlot.valueOf(a[2].toUpperCase());
				p.setItemSlot(slot, a[3].equals("none") ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(a[3]))));
				p.level().getChunkSource().sendToTrackingPlayers(p, new ClientboundSetEquipmentPacket(p.getId(),
						List.of(com.mojang.datafixers.util.Pair.of(slot, p.getItemBySlot(slot).copy()))));
			});
			case "fakegone" -> world.getServer().runOnServer(server -> {
				for (String name : a[1].equals("all") ? new ArrayList<>(FAKES.keySet()) : List.of(a[1])) {
					ServerPlayer p = FAKES.remove(name);
					if (p != null) {
						server.getPlayerList().remove(p);
					}
				}
			});
			case "talk" -> {
				TALKING.clear();
				if (!a[1].equals("none")) {
					for (String name : a[1].split(",")) {
						TALKING.add(PEOPLE.get(name));
					}
				}
			}
			case "voice" -> context.runOnClient(mc -> {
				setStatic(com.javahost.client.voice.VoiceClient.class, "serverHasVoice", !a[1].equals("off"));
				setStatic(com.javahost.client.voice.VoiceClient.class, "transmitting", a[1].equals("talking"));
				Config.get().voiceEnabled = !a[1].equals("off");
			});
			case "friends" -> context.runOnClient(Showcase::seedFriends);
			case "screen" -> openScreen(context, rest);
			case "cursor" -> context.getInput().setCursorPos(Double.parseDouble(a[1]), Double.parseDouble(a[2]));
			case "click" -> click(context, rest);
			case "scroll" -> context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, Double.parseDouble(a[1])));
			case "toast" -> {
				String[] parts = rest.split("\\|");
				context.runOnClient(mc -> com.javahost.client.Toasts.player(PEOPLE.get(parts[0]), parts[1], parts[2]));
			}
			case "event" -> context.runOnClient(mc -> event(rest));
			case "host" -> host(context, a[1]);
			case "transfers" -> context.runOnClient(mc -> transfers(mc));
			case "worldoffer" -> context.runOnClient(mc -> com.javahost.client.Popups.offerWorld(transfer(PEOPLE.get(a[1]), a[1], false,
					rest.substring(rest.indexOf(' ') + 1), com.javahost.client.share.WorldShare.Stage.OFFERED, 286L << 20, 0, null)));
			case "discover" -> discover(context);
			case "accent" -> context.runOnClient(mc -> {
				Config.get().accent = a[1];
				com.javahost.client.gui.ui.Theme.apply(a[1]);
			});
			default -> throw new IllegalArgumentException("unknown command " + a[0]);
		}
		return line;
	}

	private static String self(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.getUser().getName());
	}

	// ================================================================ made-up players

	/** A UUID for {@code name} whose default skin is {@code skin} (e.g. "wide/alex", "slim/zuri"). */
	private static UUID idWithSkin(String name, String skin) {
		for (int i = 0; i < 100_000; i++) {
			UUID id = UUID.nameUUIDFromBytes(("javahost-showcase:" + name + ":" + i).getBytes(StandardCharsets.UTF_8));
			if (DefaultPlayerSkin.get(id).toString().contains("/" + skin)) {
				return id;
			}
		}
		throw new IllegalArgumentException("no default skin " + skin + " (" + DefaultPlayerSkin.getDefaultSkin() + ")");
	}

	/** fake NAME X Y Z YAW PITCH [mainhand item]: a player in the world, as the server and everyone sees it. */
	private static void spawnFake(String[] a) {
		String name = a[1];
		UUID id = PEOPLE.get(name);
		double x = Double.parseDouble(a[2]);
		double y = Double.parseDouble(a[3]);
		double z = Double.parseDouble(a[4]);
		float yaw = Float.parseFloat(a[5]);
		float pitch = Float.parseFloat(a[6]);
		world.getServer().runOnServer(server -> {
			GameProfile profile = new GameProfile(id, name);
			ClientInformation info = new ClientInformation("en_us", 8, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true,
					ParticleStatus.ALL);
			ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, info);
			player.snapTo(x, y, z, yaw, pitch);
			if (a.length > 7) {
				player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(a[7]))));
			}
			server.getPlayerList().placeNewPlayer(new FakeConnection(), player, new CommonListenerCookie(profile, 0, info, false));
			player.snapTo(x, y, z, yaw, pitch);
			player.setYHeadRot(yaw);
			player.setYBodyRot(yaw);
			setField(net.minecraft.server.network.ServerCommonPacketListenerImpl.class, player.connection, "latency", 20 + Math.floorMod(name.hashCode(), 40));
			FAKES.put(name, player);
		});
	}

	private interface FakeAction {
		void run(ServerPlayer player) throws Exception;
	}

	private static void onFake(String name, FakeAction action) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = FAKES.get(name);
			if (player == null) {
				throw new IllegalArgumentException("no fake player " + name);
			}
			try {
				action.run(player);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/** A connection to nowhere: everything sent is dropped and nothing can disconnect it. */
	private static final class FakeConnection extends Connection {
		FakeConnection() {
			super(PacketFlow.SERVERBOUND);
			setField(Connection.class, this, "channel", new EmbeddedChannel());
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
			setField(Connection.class, this, "packetListener", listener);
			setField(Connection.class, this, "disconnectListener", listener);
		}

		@Override
		public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
		}

		@Override
		public void send(Packet<?> packet) {
		}

		@Override
		public void send(Packet<?> packet, ChannelFutureListener listener) {
		}

		@Override
		public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
		}

		@Override
		public void disconnect(Component reason) {
		}

		@Override
		public void disconnect(DisconnectionDetails details) {
		}

		@Override
		public void setReadOnly() {
		}

		@Override
		public void handleDisconnection() {
		}
	}

	/** Keeps the chosen players "heard just now", so the voice pills and speaker icons stay on. */
	private static void keepTalking() {
		if (shownFps > 0) {
			setStatic(Minecraft.class, "fps", shownFps);
		}
		if (TALKING.isEmpty()) {
			return;
		}
		try {
			Field speakersField = com.javahost.client.voice.VoiceClient.class.getDeclaredField("SPEAKERS");
			speakersField.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<UUID, Object> speakers = (Map<UUID, Object>) speakersField.get(null);
			Class<?> type = Class.forName("com.javahost.client.voice.VoiceClient$Speaker");
			for (UUID id : TALKING) {
				Object speaker = speakers.get(id);
				if (speaker == null) {
					var constructor = type.getDeclaredConstructor();
					constructor.setAccessible(true);
					speaker = constructor.newInstance();
					speakers.put(id, speaker);
				}
				setField(type, speaker, "lastHeard", System.currentTimeMillis() + 60_000);
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	// ================================================================ friends

	private record Pal(String name, Presence.State state, String world, int players, int max, long since) {
	}

	/** Friends with statuses, a chat and a few notifications, as if they'd been friends for a while. */
	private static void seedFriends(Minecraft mc) throws Exception {
		long now = System.currentTimeMillis();
		long day = 24 * 60 * 60 * 1000L;
		List<Pal> pals = List.of(
				new Pal("AlexPlays", Presence.State.HOSTING, "Cherry Valley", 3, 8, now - 40 * day),
				new Pal("LunaBuilds", Presence.State.PLAYING, "Cherry Valley", 3, 8, now - 31 * day),
				new Pal("MossyMiner", Presence.State.PLAYING, "Deep Dark Trip", 2, 4, now - 12 * day),
				new Pal("RedstoneRay", Presence.State.MENU, "", 0, 0, now - 9 * day),
				new Pal("PixelPip", Presence.State.OFFLINE, "", 0, 0, now - 3 * day));
		Object data = getStatic(Friends.class, "data");
		@SuppressWarnings("unchecked")
		List<Friends.Friend> friends = (List<Friends.Friend>) getField(data.getClass(), data, "friends");
		@SuppressWarnings("unchecked")
		Map<UUID, Presence.Status> latest = (Map<UUID, Presence.Status>) getStatic(Presence.class, "LATEST");
		@SuppressWarnings("unchecked")
		Map<UUID, String> versions = (Map<UUID, String>) getStatic(Presence.class, "VERSIONS");
		String signKey = com.javahost.util.FriendCrypto.encodePublic(com.javahost.util.FriendCrypto.newSigningKeyPair().getPublic());
		for (Pal pal : pals) {
			UUID id = PEOPLE.get(pal.name());
			friends.removeIf(f -> f.id().equals(id));
			friends.add(new Friends.Friend(id, pal.name(), "", pal.since(), "", signKey));
			versions.put(id, JavaHost.VERSION);
			if (pal.state() != Presence.State.OFFLINE) {
				List<Friends.RouteInfo> routes = pal.state() == Presence.State.HOSTING
						? List.of(new Friends.RouteInfo("Relay", "WORKING", "brave-otter.eu.e4mc.link"))
						: List.of();
				latest.put(id, new Presence.Status(id, pal.state(), pal.world(), pal.players(), pal.max(), routes, "", now));
			}
		}
		UUID alex = PEOPLE.get("AlexPlays");
		Chats.forget(alex);
		Chats.add(alex, new Chats.Line(false, "yo are you on tonight?", now - 50 * 60_000));
		Chats.add(alex, new Chats.Line(true, "yeah! after dinner", now - 48 * 60_000));
		Chats.add(alex, new Chats.Line(false, "found a cherry grove next to a village, it's unreal", now - 47 * 60_000));
		Chats.add(alex, new Chats.Line(false, "bring your shaders, I'll share mine when you join", now - 46 * 60_000));
		Chats.add(alex, new Chats.Line(true, "on my way, send the invite", now - 2 * 60_000));
		Chats.add(alex, new Chats.Line(false, "sent! click Join", now - 60_000));
	}

	private static void event(String spec) throws Exception {
		String[] p = spec.split("\\|");
		UUID id = PEOPLE.get(p[1]);
		Friends.Event event = switch (p[0]) {
			case "invite" -> new Friends.Event.InviteReceived(new Friends.Invite(id, p[1], p[2],
					List.of(new Friends.RouteInfo("Relay", "WORKING", "brave-otter.eu.e4mc.link")), "", System.currentTimeMillis() + 600_000, "26.3"));
			case "request" -> new Friends.Event.RequestReceived(new Friends.Request(id, p[1], "", System.currentTimeMillis(), null, ""));
			case "chat" -> new Friends.Event.ChatReceived(Friends.friend(id).orElseThrow(), p[2]);
			default -> throw new IllegalArgumentException(p[0]);
		};
		var method = com.javahost.client.Popups.class.getDeclaredMethod("onEvent", Friends.Event.class);
		method.setAccessible(true);
		method.invoke(null, event);
	}

	// ================================================================ screens

	private static void openScreen(ClientGameTestContext context, String spec) throws Exception {
		String[] p = spec.split(":");
		switch (p[0]) {
			case "none" -> context.setScreen(() -> null);
			case "title" -> context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			case "pause" -> context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
			case "tab" -> context.setScreen(() -> new JavaHostScreen(null, Tab.valueOf(p[1].toUpperCase())));
			case "profile" -> {
				context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openProfile(PEOPLE.get(p[1])));
			}
			case "chat" -> {
				context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openChat(PEOPLE.get(p[1])));
			}
			case "picker" -> context.setScreen(() -> new com.javahost.client.gui.WorldPickerScreen(null,
					Friends.friend(PEOPLE.get(p[1])).orElseThrow(), null, null));
			case "public" -> context.setScreen(() -> new com.javahost.client.gui.PublicWorldScreen(null));
			case "packs" -> context.setScreen(() -> new com.javahost.client.gui.PackOfferScreen(List.of(
					new com.javahost.net.Payloads.PackInfo("0".repeat(40), com.javahost.net.Payloads.PackKind.RESOURCE_PACK, "Faithful 32x", 18_400_000L),
					new com.javahost.net.Payloads.PackInfo("1".repeat(40), com.javahost.net.Payloads.PackKind.SHADER_PACK,
							"Complementary Reimagined r5.5", 6_100_000L)), 24_500_000L, yes -> {
					}));
			default -> throw new IllegalArgumentException(spec);
		}
	}

	private static void click(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.active
						&& w.getMessage().getString().equals(label)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
				}
			}
			throw new IllegalArgumentException("no button " + label);
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	// ================================================================ world copies

	/** A transfer in a given state (made up: nothing is sent). */
	private static com.javahost.client.share.WorldShare.Transfer transfer(UUID peer, String peerName, boolean outgoing, String world,
			com.javahost.client.share.WorldShare.Stage stage, long size, long done, Path result) {
		try {
			Class<com.javahost.client.share.WorldShare.Transfer> type = com.javahost.client.share.WorldShare.Transfer.class;
			var constructor = type.getDeclaredConstructor(long.class, boolean.class, UUID.class, String.class, String.class,
					com.javahost.client.share.WorldShare.Stage.class);
			constructor.setAccessible(true);
			var transfer = constructor.newInstance(new java.util.Random().nextLong(), outgoing, peer, peerName, world, stage);
			setField(type, transfer, "size", size);
			setField(type, transfer, "done", done);
			setField(type, transfer, "speed", 2.4 * 1024 * 1024);
			setField(type, transfer, "result", result);
			return transfer;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	/** The notification centre's transfers card: one arriving, one going out, one done. */
	private static void transfers(Minecraft mc) {
		com.javahost.client.share.WorldShare.pauseForTests(true);
		@SuppressWarnings("unchecked")
		List<com.javahost.client.share.WorldShare.Transfer> list =
				(List<com.javahost.client.share.WorldShare.Transfer>) getStatic(com.javahost.client.share.WorldShare.class, "TRANSFERS");
		list.clear();
		long mb = 1024 * 1024;
		list.add(transfer(PEOPLE.get("LunaBuilds"), "LunaBuilds", false, "Cosy Cherry Village", com.javahost.client.share.WorldShare.Stage.RECEIVING,
				286 * mb, 171 * mb, null));
		list.add(transfer(PEOPLE.get("AlexPlays"), "AlexPlays", true, "Cherry Valley", com.javahost.client.share.WorldShare.Stage.SENDING,
				412 * mb, 98 * mb, null));
		list.add(transfer(PEOPLE.get("MossyMiner"), "MossyMiner", false, "Skyblock", com.javahost.client.share.WorldShare.Stage.DONE, 52 * mb, 52 * mb,
				mc.getLevelSource().getBaseDir().resolve("Skyblock (from MossyMiner)")));
	}

	// ================================================================ hosting and public worlds

	/** host start: hosting with every route shown as working, using documentation addresses. */
	private static void host(ClientGameTestContext context, String what) {
		if (what.equals("stop")) {
			context.runOnClient(mc -> com.javahost.client.host.HostController.stop());
			return;
		}
		context.runOnClient(mc -> com.javahost.client.host.HostController.start(mc.getSingleplayerServer()));
		context.waitFor(mc -> !com.javahost.client.host.Connectivity.isProbing(), 600);
		Class<?> c = com.javahost.client.host.Connectivity.class;
		setStatic(c, "lanIp", "192.168.1.24");
		setStatic(c, "publicIpv4", "203.0.113.42");
		setStatic(c, "portMapped", true);
		setStatic(c, "cgnat", false);
		setStatic(c, "ipv6", "2001:db8:4a1f::24");
		setStatic(c, "ipv6Pinhole", "pinhole");
	}

	private static void discover(ClientGameTestContext context) throws Exception {
		String serverJs = System.getProperty("javahost.directoryServer",
				Path.of("../../../directory/dev/server.js").toAbsolutePath().normalize().toString());
		int port = 8797;
		String base = "http://127.0.0.1:" + port;
		directory = new ProcessBuilder("node", serverJs, String.valueOf(port)).redirectErrorStream(true)
				.redirectOutput(dir.resolve("directory.log").toFile()).start();
		long deadline = System.currentTimeMillis() + 15_000;
		while (true) {
			try {
				JavaHostClientTest.call(base, "GET", "/listings", null, null);
				break;
			} catch (Exception notYet) {
				if (System.currentTimeMillis() > deadline) {
					throw new AssertionError("the local list service didn't start");
				}
				Thread.sleep(200);
			}
		}
		System.setProperty("javahost.directoryUrl", base);
		JavaHostClientTest.seed(base, "AlexPlays", "Help me build a giant castle", "Bring stone and good vibes. Starting the north tower tonight.",
				"brave-otter.eu.e4mc.link", List.of("help", "building"), 8, 5, true);
		JavaHostClientTest.seed(base, "MossyMiner", "Mining trip to the deep dark", "Need two people with good armour. Loot is shared.",
				"swift-lynx.eu.e4mc.link", List.of("help", "survival"), 4, 2, false);
		JavaHostClientTest.seed(base, "LunaBuilds", "Cosy cherry grove village", "Building a little village by the river. All builders welcome.",
				"calm-heron.us.e4mc.link", List.of("building", "chill"), 10, 6, true);
		JavaHostClientTest.seed(base, "Kai_Crafts", "Chill creative flatland", "Creative mode, no rules except be nice.",
				"quiet-maple.as.e4mc.link", List.of("creative", "chill"), 16, 9, false);
		JavaHostClientTest.seed(base, "Noor", "Hardcore-ish survival", "Normal difficulty, no griefing please.",
				"bright-fern.us.e4mc.link", List.of("survival"), 6, 3, true);
		JavaHostClientTest.seed(base, "Efe_Builds", "Speedrun practice", "Trying to beat the dragon in under an hour.",
				"red-panda.eu.e4mc.link", List.of("adventure"), 4, 4, false);
		context.setScreen(() -> new JavaHostScreen(null, Tab.DISCOVER));
		context.waitFor(mc -> !((List<?>) getStatic(com.javahost.client.gui.pages.DiscoverPage.class, "worlds")).isEmpty(), 400);
		// Pings as they'd look on a good connection (the relay addresses here are made up, so they can't be pinged).
		context.runOnClient(mc -> {
			@SuppressWarnings("unchecked")
			Map<UUID, net.minecraft.client.multiplayer.ServerData> pings =
					(Map<UUID, net.minecraft.client.multiplayer.ServerData>) getStatic(com.javahost.client.gui.pages.DiscoverPage.class, "PINGS");
			@SuppressWarnings("unchecked")
			List<com.javahost.client.discover.Directory.PublicWorld> worlds =
					(List<com.javahost.client.discover.Directory.PublicWorld>) getStatic(com.javahost.client.gui.pages.DiscoverPage.class, "worlds");
			int i = 0;
			for (var w : worlds) {
				var data = new net.minecraft.client.multiplayer.ServerData(w.title(), w.relay(), net.minecraft.client.multiplayer.ServerData.Type.OTHER);
				data.setState(net.minecraft.client.multiplayer.ServerData.State.SUCCESSFUL);
				data.ping = new long[] {38, 64, 112, 47, 171, 29}[i++ % 6];
				pings.put(w.host(), data);
			}
			setStatic(com.javahost.client.gui.pages.DiscoverPage.class, "fetchedAt", System.currentTimeMillis() + 3_600_000L);
			((JavaHostScreen) mc.gui.screen()).refresh();
		});
	}

	// ================================================================ reflection

	static Object getStatic(Class<?> type, String name) {
		return getField(type, null, name);
	}

	static void setStatic(Class<?> type, String name, Object value) {
		setField(type, null, name, value);
	}

	static Object getField(Class<?> type, Object target, String name) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	static void setField(Class<?> type, Object target, String name, Object value) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	static MinecraftServer server() {
		return Minecraft.getInstance().getSingleplayerServer();
	}
}

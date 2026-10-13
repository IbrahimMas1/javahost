package com.javahost.client.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.javahost.HostState;
import com.javahost.HostState.SharedPack;
import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.pack.IrisCompat;
import com.javahost.client.pack.PackFiles;
import com.javahost.net.Payloads.PackInfo;
import com.javahost.net.Payloads.PackKind;
import com.javahost.pack.PackServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer.MultiplayerScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;

/**
 * Turns the open singleplayer world into a multiplayer world. The host's PC runs the server; this publishes
 * it, starts the connection checks in {@link Connectivity}, and prepares the packs to share.
 */
public final class HostController {
	/** A connected friend, for the host's player list. */
	public record Guest(UUID id, String name, int pingMs, net.minecraft.world.level.GameType gameMode) {
	}

	private static boolean hosting;
	private static int snapshotTicks;
	private static int port;
	private static volatile String packStatus = "";
	private static volatile List<Guest> guests = List.of();

	private HostController() {
	}

	public static boolean isHosting() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return hosting && server != null && server.isPublished();
	}

	public static int port() {
		return port;
	}

	public static String packStatus() {
		return packStatus;
	}

	/** Starts hosting. Must be called on the render thread. Returns an error message, or {@code null} on success. */
	public static String start(IntegratedServer server) {
		Config config = Config.get();
		if (server.isPublished()) {
			server.unpublishServer();
		}
		HostState.setVoice(config.voiceEnabledWhenHosting, config.voiceRange);
		applyHostRules(config);
		// Guests never get commands through JavaHost, even if Minecraft's own "Open to LAN" allowed them earlier.
		server.setGuestCommandAccess(false);
		boolean relayNow = Connectivity.RelayMode.valueOf(config.relayMode) == Connectivity.RelayMode.ALWAYS
				&& E4mcCompat.isInstalled();
		// e4mc would otherwise start its relay on every publish; we decide when it's needed.
		E4mcCompat.setRelayEnabled(relayNow);
		if (!server.publishServer(MultiplayerScope.LAN, config.port)) {
			return "Port " + config.port + " is already in use. Pick another port and try again.";
		}
		hosting = true;
		port = config.port;
		Connectivity.start(port, relayNow);
		preparePacks(server, config);
		return null;
	}

	/**
	 * Direct connections didn't work out, so reopen the world with e4mc's relay on. Runs a few seconds after
	 * hosting starts, before anyone could have joined through an address we showed.
	 */
	static void restartWithRelay() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (!hosting || server == null) {
			return;
		}
		E4mcCompat.setRelayEnabled(true);
		server.unpublishServer();
		server.setGuestCommandAccess(false);
		if (!server.publishServer(MultiplayerScope.LAN, port)) {
			JavaHost.LOGGER.warn("Could not reopen the world for the relay");
		}
	}

	public static void stop() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null && server.isPublished()) {
			server.unpublishServer();
		}
		cleanup();
	}

	/** Undoes router changes, stops the relay and forgets shared packs. Safe to call more than once. */
	public static void cleanup() {
		if (hosting) {
			Connectivity.stop();
		}
		hosting = false;
		HostState.clear();
		// Keep the join rules in force: a world opened later with Minecraft's own "Open to LAN" stays protected.
		applyHostRules(Config.get());
		packStatus = "";
	}

	public static void tick() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (!hosting || server == null) {
			guests = List.of();
			return;
		}
		Connectivity.tick();
		// The player list belongs to the server thread, so snapshot it there; twice a second is plenty (pings
		// only update every few seconds anyway).
		if (snapshotTicks++ % 10 != 0) {
			return;
		}
		server.execute(() -> {
			List<Guest> snapshot = new ArrayList<>();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!server.isSingleplayerOwner(player.nameAndId())) {
					snapshot.add(new Guest(player.getUUID(), player.getPlainTextName(), player.connection.latency(), player.gameMode()));
				}
			}
			guests = List.copyOf(snapshot);
		});
	}

	/** Everyone connected except the host (refreshed every tick). */
	public static List<Guest> guests() {
		return guests;
	}

	/**
	 * Pushes friends-only, bans, blocks and the player limit to the server's join checks, and removes anyone already
	 * in the world who no longer passes them (e.g. right after you ban, block or unfriend them).
	 */
	public static void applyHostRules(Config config) {
		HostState.setFriendsOnly(config.friendsOnly);
		HostState.setBannedPlayers(uuids(config.bannedPlayers.keySet()));
		HostState.setBlockedPlayers(uuids(config.blockedPlayers.keySet()));
		HostState.setMaxPlayers(config.maxPlayers);
		enforce();
	}

	private static java.util.Set<UUID> uuids(java.util.Collection<String> ids) {
		java.util.Set<UUID> set = new java.util.HashSet<>();
		for (String id : ids) {
			try {
				set.add(UUID.fromString(id));
			} catch (IllegalArgumentException ignored) {
				// A hand-edited config; skip the bad entry.
			}
		}
		return set;
	}

	/** Disconnects players in the open world who wouldn't be let in now. */
	public static void enforce() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || !server.isPublished()) {
			return;
		}
		server.execute(() -> {
			for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
				String refusal = com.javahost.JoinGate.refusal(server, player.nameAndId());
				// The full-world rule only applies to new arrivals.
				if (refusal != null && !refusal.startsWith("This world is full")) {
					player.connection.disconnect(Component.literal(refusal));
				}
			}
		});
	}

	/** Changes a guest's game mode (survival, creative, adventure, spectator). */
	public static void setGameMode(UUID id, net.minecraft.world.level.GameType mode) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player != null) {
					player.setGameMode(mode);
					JavaHost.LOGGER.info("Set {}'s game mode to {}", player.getPlainTextName(), mode);
				}
			});
		}
	}

	/** Bans a guest from the host's worlds and removes them now. */
	public static void ban(UUID id, String name) {
		Config config = Config.get();
		config.bannedPlayers.put(id.toString(), name);
		config.save();
		applyHostRules(config);
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player != null) {
					player.connection.disconnect(Component.literal("The host has banned you from this world."));
				}
			});
		}
	}

	public static void unban(UUID id) {
		Config config = Config.get();
		config.bannedPlayers.remove(id.toString());
		config.save();
		applyHostRules(config);
	}

	/** A guest's current game mode, from the server (null if not found). */
	public static net.minecraft.world.level.GameType gameModeOf(UUID id) {
		// From the snapshot: the player list belongs to the server thread.
		return guests.stream().filter(g -> g.id().equals(id)).findFirst().map(Guest::gameMode).orElse(null);
	}

	public static void kick(UUID id) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player != null) {
					player.connection.disconnect(Component.literal("The host removed you from the world."));
				}
			});
		}
	}

	// ---------------------------------------------------------------- packs

	private record PackSource(PackKind kind, String name, Path path) {
	}

	private static void preparePacks(IntegratedServer server, Config config) {
		Minecraft minecraft = Minecraft.getInstance();
		List<PackSource> sources = new ArrayList<>();

		if (config.shareResourcePacks) {
			Path packDir = minecraft.getResourcePackDirectory();
			for (Pack pack : minecraft.getResourcePackRepository().getSelectedPacks()) {
				// Only packs the player added themselves live in resourcepacks/ with a "file/" id.
				String id = pack.getId();
				if (id.startsWith("file/")) {
					Path path = packDir.resolve(id.substring("file/".length()));
					if (Files.exists(path)) {
						sources.add(new PackSource(PackKind.RESOURCE_PACK, pack.getTitle().getString(), path));
					}
				}
			}
		}

		String shaderSettings = "";
		if (config.shareShaders) {
			Optional<String> shader = IrisCompat.activeShaderPack();
			Path shaderDir = IrisCompat.shaderpacksDirectory();
			if (shader.isPresent() && shaderDir != null) {
				Path path = shaderDir.resolve(shader.get());
				if (Files.exists(path)) {
					sources.add(new PackSource(PackKind.SHADER_PACK, shader.get(), path));
					Path settings = shaderDir.resolve(shader.get() + ".txt");
					try {
						if (Files.isRegularFile(settings) && Files.size(settings) < 60_000) {
							shaderSettings = Files.readString(settings);
						}
					} catch (IOException ignored) {
						// Sharing the pack with default settings is still useful.
					}
				}
			}
		}

		if (sources.isEmpty()) {
			packStatus = "";
			HostState.clear();
			return;
		}

		packStatus = "Preparing " + com.javahost.client.gui.ui.Draw.count(sources.size(), "pack") + " to share...";
		String settings = shaderSettings;
		CompletableFuture.supplyAsync(() -> buildSharedPacks(sources), com.javahost.util.IoExecutor.POOL).whenComplete((packs, error) -> {
			if (error != null || packs == null) {
				JavaHost.LOGGER.warn("Could not prepare packs for sharing", error);
				packStatus = "Could not prepare packs to share.";
				return;
			}
			if (!hosting) {
				return;
			}
			HostState.setSharedPacks(packs, settings);
			long totalMb = packs.stream().mapToLong(p -> p.info().size()).sum() / (1024 * 1024);
			packStatus = "Sharing " + com.javahost.client.gui.ui.Draw.count(packs.size(), "pack") + " (" + totalMb + " MB) with everyone who joins.";
			// Anyone who joined while we were zipping gets the offer now.
			server.execute(() -> PackServer.offerToAll(server));
		});
	}

	private static List<SharedPack> buildSharedPacks(List<PackSource> sources) {
		Path outgoing = PackFiles.outgoing();
		try {
			if (Files.isDirectory(outgoing)) {
				try (Stream<Path> old = Files.list(outgoing)) {
					for (Path file : old.toList()) {
						Files.deleteIfExists(file);
					}
				}
			}
			Files.createDirectories(outgoing);

			List<SharedPack> packs = new ArrayList<>();
			int index = 0;
			for (PackSource source : sources) {
				Path zip = PackFiles.asZip(source.path(), outgoing.resolve((index++) + "-" + PackFiles.safeName(source.name()) + ".zip"));
				String sha1 = PackFiles.sha1(zip);
				String name = source.kind() == PackKind.SHADER_PACK ? source.path().getFileName().toString() : source.name();
				packs.add(new SharedPack(new PackInfo(sha1, source.kind(), name, Files.size(zip)), zip));
			}
			return packs;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}

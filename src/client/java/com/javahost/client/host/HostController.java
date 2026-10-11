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
	public record Guest(UUID id, String name, int pingMs) {
	}

	private static boolean hosting;
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
		HostState.setFriendsOnly(config.friendsOnly);
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
		HostState.setFriendsOnly(false);
		packStatus = "";
	}

	public static void tick() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (!hosting || server == null) {
			guests = List.of();
			return;
		}
		Connectivity.tick();
		// The player list belongs to the server thread, so snapshot it there.
		server.execute(() -> {
			List<Guest> snapshot = new ArrayList<>();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!server.isSingleplayerOwner(player.nameAndId())) {
					snapshot.add(new Guest(player.getUUID(), player.getPlainTextName(), player.connection.latency()));
				}
			}
			guests = List.copyOf(snapshot);
		});
	}

	/** Everyone connected except the host (refreshed every tick). */
	public static List<Guest> guests() {
		return guests;
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

		packStatus = "Preparing " + sources.size() + " pack(s) to share...";
		String settings = shaderSettings;
		CompletableFuture.supplyAsync(() -> buildSharedPacks(sources)).whenComplete((packs, error) -> {
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
			packStatus = "Sharing " + packs.size() + " pack(s) (" + totalMb + " MB) with everyone who joins.";
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

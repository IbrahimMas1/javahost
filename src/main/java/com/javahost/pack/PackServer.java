package com.javahost.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.javahost.HostState;
import com.javahost.HostState.SharedPack;
import com.javahost.JavaHost;
import com.javahost.net.Payloads;
import com.javahost.net.Payloads.PackAck;
import com.javahost.net.Payloads.PackChunk;
import com.javahost.net.Payloads.PackInfo;
import com.javahost.net.Payloads.PackOffer;
import com.javahost.net.Payloads.PackRequest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Streams the host's resource packs and shader pack to players who join.
 *
 * <p>Files are sent in {@link Payloads#PACK_CHUNK_SIZE} pieces over the game connection. The client
 * acknowledges every chunk and at most {@link #WINDOW} chunks are in flight per player, so a big pack
 * never floods the connection or the host's memory, and gameplay packets keep flowing alongside it.
 */
public final class PackServer {
	private static final int WINDOW = 8;
	/** Chunks sent per player per tick at most (~1 MB), so a big pack never stalls the world. */
	private static final int PER_TICK = 4;
	/** How many times one player may download the same pack in a session (a retry after a hiccup is fine). */
	private static final int MAX_SENDS = 2;

	private static final class Transfer {
		final SharedPack pack;
		final int total;
		int nextToSend;
		int acked = -1;

		Transfer(SharedPack pack) {
			this.pack = pack;
			this.total = (int) Math.max(1, (pack.info().size() + Payloads.PACK_CHUNK_SIZE - 1) / Payloads.PACK_CHUNK_SIZE);
		}
	}

	/** Queued transfers per player; only the head of each queue is actively sending. */
	private static final Map<UUID, ArrayDeque<Transfer>> TRANSFERS = new HashMap<>();
	/** How often each player has been sent each pack this session (player -> sha1 -> count). */
	private static final Map<UUID, Map<String, Integer>> SENDS = new HashMap<>();

	private PackServer() {
	}

	/** Sends the current pack list to everyone except the host (used when packs finish preparing). */
	public static void offerToAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			offerTo(server, player);
		}
	}

	private static void offerTo(MinecraftServer server, ServerPlayer player) {
		List<SharedPack> packs = HostState.sharedPacks();
		if (packs.isEmpty() || com.javahost.compat.Compat.isOwner(server, player)) {
			return;
		}
		if (!ServerPlayNetworking.canSend(player, PackOffer.TYPE)) {
			return;
		}
		List<PackInfo> infos = new ArrayList<>();
		for (SharedPack pack : packs) {
			infos.add(pack.info());
		}
		ServerPlayNetworking.send(player, new PackOffer(infos, HostState.shaderSettings()));
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> offerTo(server, handler.player));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TRANSFERS.remove(handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TRANSFERS.clear();
			SENDS.clear();
		});

		ServerPlayNetworking.registerGlobalReceiver(PackRequest.TYPE, (payload, context) -> {
			ArrayDeque<Transfer> queue = TRANSFERS.computeIfAbsent(context.player().getUUID(), id -> new ArrayDeque<>());
			Map<String, Integer> sent = SENDS.computeIfAbsent(context.player().getUUID(), id -> new HashMap<>());
			for (String sha1 : payload.sha1s()) {
				SharedPack pack = HostState.findPack(sha1);
				if (pack != null && queue.stream().noneMatch(t -> t.pack == pack) && sent.getOrDefault(sha1, 0) < MAX_SENDS) {
					sent.merge(sha1, 1, Integer::sum);
					queue.add(new Transfer(pack));
				}
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(PackAck.TYPE, (payload, context) -> {
			ArrayDeque<Transfer> queue = TRANSFERS.get(context.player().getUUID());
			Transfer head = queue == null ? null : queue.peek();
			// Only chunks we've actually sent can be acknowledged (a made-up ack would make us send everything at once).
			if (head != null && head.pack.info().sha1().equals(payload.sha1()) && payload.index() < head.nextToSend) {
				head.acked = Math.max(head.acked, payload.index());
				if (head.acked >= head.total - 1) {
					queue.poll();
				}
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(PackServer::tick);
	}

	private static void tick(MinecraftServer server) {
		if (TRANSFERS.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, ArrayDeque<Transfer>>> it = TRANSFERS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, ArrayDeque<Transfer>> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Transfer transfer = entry.getValue().peek();
			if (player == null || transfer == null) {
				it.remove();
				continue;
			}
			try {
				pump(player, transfer);
			} catch (IOException e) {
				JavaHost.LOGGER.warn("Stopped sending {} to {}", transfer.pack.info().name(), com.javahost.compat.Compat.name(player), e);
				entry.getValue().poll();
			}
		}
	}

	private static void pump(ServerPlayer player, Transfer transfer) throws IOException {
		if (transfer.nextToSend >= transfer.total || transfer.nextToSend - transfer.acked > WINDOW
				|| !((com.javahost.mixin.ConnectionAccess) ((com.javahost.mixin.ServerCommonListenerAccess) player.connection).javahost$connection())
						.javahost$channel().isWritable()) {
			return; // nothing due, or the player isn't reading what we've already sent
		}
		try (FileChannel channel = FileChannel.open(transfer.pack.file(), StandardOpenOption.READ)) {
			for (int sent = 0; sent < PER_TICK && transfer.nextToSend < transfer.total && transfer.nextToSend - transfer.acked <= WINDOW; sent++) {
				long offset = (long) transfer.nextToSend * Payloads.PACK_CHUNK_SIZE;
				int length = (int) Math.min(Payloads.PACK_CHUNK_SIZE, transfer.pack.info().size() - offset);
				ByteBuffer buffer = ByteBuffer.allocate(Math.max(length, 0));
				while (buffer.hasRemaining()) {
					if (channel.read(buffer, offset + buffer.position()) < 0) {
						throw new IOException("Pack file shrank while sending");
					}
				}
				ServerPlayNetworking.send(player,
						new PackChunk(transfer.pack.info().sha1(), transfer.nextToSend, transfer.total, buffer.array()));
				transfer.nextToSend++;
			}
		}
	}
}

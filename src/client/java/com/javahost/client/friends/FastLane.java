package com.javahost.client.friends;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.javahost.JavaHost;
import com.javahost.net.Payloads;
import com.javahost.net.Payloads.RelayDown;
import com.javahost.net.Payloads.RelayUp;
import com.javahost.util.Chunks;
import com.javahost.util.Mailbox.Sent;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * The same-world fast lane: when a friend is in the same world (and the host runs JavaHost 1.4+), friend
 * messages travel over the game connection instead of the internet relay. Instant, full-size screenshots, and
 * none of the relay's daily allowance used. Messages are already end-to-end encrypted, so the host only
 * forwards bytes it can't read.
 */
public final class FastLane {
	private static final SecureRandom RANDOM = new SecureRandom();
	/** Transfers we're receiving, by sender and transfer id. Only touched on the client thread. */
	private static final Map<String, Chunks.Assembly> INCOMING = new HashMap<>();

	private FastLane() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RelayDown.TYPE, (piece, context) -> receive(piece));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(INCOMING::clear));
	}

	/** True when this friend can be reached through the game connection right now. */
	public static boolean canReach(UUID friend) {
		return ClientPlayNetworking.canSend(RelayUp.TYPE) && InviteFlow.playingTogether(friend);
	}

	/** Sends a message (and optional file) to a friend in this world. Completes with 200 once handed to the connection. */
	public static CompletableFuture<Sent> send(UUID friend, String text, byte[] file) {
		byte[] packed = Chunks.pack(text, file);
		if (packed.length > Payloads.RELAY_MAX_BYTES) {
			return CompletableFuture.completedFuture(new Sent(413));
		}
		List<byte[]> pieces = Chunks.split(packed, Payloads.RELAY_CHUNK_SIZE);
		long id = RANDOM.nextLong();
		CompletableFuture<Sent> done = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			if (!canReach(friend)) {
				done.complete(new Sent(-1));
				return;
			}
			for (int i = 0; i < pieces.size(); i++) {
				ClientPlayNetworking.send(new RelayUp(friend, id, i, pieces.size(), pieces.get(i)));
			}
			done.complete(new Sent(200));
		});
		return done;
	}

	private static void receive(RelayDown piece) {
		INCOMING.values().removeIf(assembly -> assembly.olderThan(120_000));
		String key = piece.from() + "/" + piece.id();
		Chunks.Assembly assembly = INCOMING.computeIfAbsent(key, k -> new Chunks.Assembly(piece.total(), Payloads.RELAY_MAX_BYTES));
		try {
			byte[] whole = assembly.add(piece.index(), piece.data());
			if (whole == null) {
				return;
			}
			INCOMING.remove(key);
			Chunks.Unpacked message = Chunks.unpack(whole);
			Friends.receiveDirect(piece.from(), message.text(), message.file());
		} catch (RuntimeException e) {
			INCOMING.remove(key);
			JavaHost.LOGGER.warn("[Friends] dropped a fast-lane message from {}", piece.from(), e);
		}
	}
}

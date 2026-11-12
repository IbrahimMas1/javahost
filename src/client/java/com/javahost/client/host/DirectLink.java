package com.javahost.client.host;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.util.DirectTunnel;

/**
 * Direct player-to-player joins: a friend's game connects straight to the host's through both routers, with no relay
 * in between, so the ping is whatever the internet between the two of them is (see {@link DirectTunnel}).
 *
 * <p>The two games arrange it over the friends' sealed messages: the guest sends "punch" with a fresh session
 * number, a secret token and the addresses its socket can be reached at; the host answers "punch-ok" with its own;
 * both then send to each other until they hear one another. If that doesn't happen within a few seconds the join
 * carries on with the other routes (the router's open port, the relay).
 */
public final class DirectLink {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int MAX_ADDRESSES = 8;

	private record Waiting(UUID host, CompletableFuture<List<InetSocketAddress>> answer) {
	}

	private static volatile DirectTunnel hostTunnel;
	private static volatile DirectTunnel guestTunnel;
	private static volatile int guestSession;
	private static volatile long guestIdleSince;
	private static final Map<Integer, Waiting> WAITING = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_PUNCH = new ConcurrentHashMap<>();

	private DirectLink() {
	}

	// ---------------------------------------------------------------- hosting

	/** Hosting started: open the socket friends will come through, and find out how it looks from outside. */
	public static void hostStart(int gamePort) {
		hostStop();
		if (!Config.get().directLink) {
			return;
		}
		try {
			DirectTunnel tunnel = DirectTunnel.host(gamePort);
			hostTunnel = tunnel;
			tunnel.findPublicAddress().thenAccept(address -> JavaHost.LOGGER.info("[Direct] ready for friends ({})",
					address == null ? "no public address found: same network and IPv6 only"
							: tunnel.portVaries() ? "this router changes ports per destination: may not work for everyone" : "public address found"));
		} catch (IOException | RuntimeException e) {
			JavaHost.LOGGER.warn("[Direct] couldn't open a socket for direct joins: {}", e.toString());
		}
	}

	public static void hostStop() {
		DirectTunnel tunnel = hostTunnel;
		hostTunnel = null;
		if (tunnel != null) {
			tunnel.close();
		}
	}

	/** Whether friends can try a direct join right now. */
	public static boolean hosting() {
		DirectTunnel tunnel = hostTunnel;
		return tunnel != null && !tunnel.isClosed();
	}

	/** One line for the Host tab about the direct route. */
	public static String hostNote() {
		DirectTunnel tunnel = hostTunnel;
		if (tunnel == null) {
			return "";
		}
		int players = tunnel.connected();
		if (players > 0) {
			return players == 1 ? "1 friend is connected straight to you." : players + " friends are connected straight to you.";
		}
		return tunnel.portVaries() ? "Friends connect straight to you when their router allows it (yours is strict)."
				: "Friends with JavaHost connect straight to you: no relay, lowest ping.";
	}

	/** A friend's game asks to come through ("punch"). Answers with our addresses and starts sending toward theirs. */
	public static void onPunch(Friend friend, JsonObject body) {
		DirectTunnel tunnel = hostTunnel;
		if (tunnel == null || tunnel.isClosed() || !HostController.isHosting()) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = LAST_PUNCH.put(friend.id(), now);
		if (last != null && now - last < 1000) {
			return; // one attempt a second is plenty
		}
		try {
			int session = body.get("session").getAsInt();
			byte[] token = Base64.getDecoder().decode(body.get("token").getAsString());
			List<InetSocketAddress> theirs = addresses(body.getAsJsonArray("addresses"));
			if (token.length != 16 || theirs.isEmpty()) {
				return;
			}
			tunnel.findPublicAddress().thenAccept(address -> {
				tunnel.expect(session, token, theirs);
				JsonObject answer = new JsonObject();
				answer.addProperty("session", session);
				JsonArray ours = new JsonArray();
				tunnel.candidates().forEach(ours::add);
				answer.add("addresses", ours);
				Friends.sendPunch(friend, "punch-ok", answer);
				JavaHost.LOGGER.info("[Direct] {} is coming through directly", friend.name());
			});
		} catch (RuntimeException e) {
			JavaHost.LOGGER.debug("[Direct] ignored a punch from {}: {}", friend.name(), e.toString());
		}
	}

	// ---------------------------------------------------------------- joining

	/**
	 * Tries to reach {@code host}'s world directly. Completes with a port on this computer to connect the game to,
	 * or fails within about ten seconds if there's no direct way through.
	 */
	public static CompletableFuture<Integer> open(Friend host) {
		closeGuest();
		DirectTunnel tunnel;
		try {
			tunnel = DirectTunnel.guest();
		} catch (IOException | RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		guestTunnel = tunnel;
		int session = RANDOM.nextInt();
		guestSession = session;
		byte[] token = new byte[16];
		RANDOM.nextBytes(token);
		CompletableFuture<List<InetSocketAddress>> answer = new CompletableFuture<>();
		WAITING.put(session, new Waiting(host.id(), answer));
		return tunnel.findPublicAddress().thenCompose(address -> {
			JsonObject body = new JsonObject();
			body.addProperty("session", session);
			body.addProperty("token", Base64.getEncoder().encodeToString(token));
			JsonArray ours = new JsonArray();
			tunnel.candidates().forEach(ours::add);
			body.add("addresses", ours);
			Friends.sendPunch(host, "punch", body);
			return answer.orTimeout(6, TimeUnit.SECONDS);
		}).thenCompose(theirs -> tunnel.connect(session, token, theirs).orTimeout(8, TimeUnit.SECONDS)).whenComplete((port, error) -> {
			WAITING.remove(session);
			if (error != null) {
				tunnel.close();
			}
		});
	}

	/** The host's answer to our "punch". */
	public static void onPunchOk(Friend friend, JsonObject body) {
		try {
			Waiting waiting = WAITING.get(body.get("session").getAsInt());
			if (waiting != null && waiting.host().equals(friend.id())) {
				List<InetSocketAddress> theirs = addresses(body.getAsJsonArray("addresses"));
				if (!theirs.isEmpty()) {
					waiting.answer().complete(theirs);
				}
			}
		} catch (RuntimeException e) {
			JavaHost.LOGGER.debug("[Direct] ignored a punch-ok from {}: {}", friend.name(), e.toString());
		}
	}

	private static List<InetSocketAddress> addresses(JsonArray array) {
		List<InetSocketAddress> out = new ArrayList<>();
		if (array != null) {
			for (JsonElement element : array) {
				InetSocketAddress address = out.size() < MAX_ADDRESSES ? DirectTunnel.parse(element.getAsString()) : null;
				if (address != null) {
					out.add(address);
				}
			}
		}
		return out;
	}

	/** Round trip to the host over the direct link, in milliseconds; -1 when we aren't on one. */
	public static int guestPing() {
		DirectTunnel tunnel = guestTunnel;
		return tunnel == null || tunnel.isClosed() || tunnel.connected() == 0 ? -1 : tunnel.roundTrip(guestSession);
	}

	public static void closeGuest() {
		DirectTunnel tunnel = guestTunnel;
		guestTunnel = null;
		if (tunnel != null) {
			tunnel.close();
		}
	}

	/** Each tick: a guest link nobody is using any more is closed. */
	public static void tick(boolean joiningOrPlaying) {
		DirectTunnel tunnel = guestTunnel;
		if (tunnel == null) {
			return;
		}
		if (joiningOrPlaying || tunnel.connected() > 0) {
			guestIdleSince = 0;
		} else if (guestIdleSince == 0) {
			guestIdleSince = System.currentTimeMillis();
		} else if (System.currentTimeMillis() - guestIdleSince > 30_000) {
			closeGuest();
		}
	}
}

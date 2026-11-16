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

	/** The host's answer: where it can be reached, and the relay to meet at if that fails (or null). */
	private record Answer(List<InetSocketAddress> addresses, InetSocketAddress relay) {
	}

	private record Waiting(UUID host, CompletableFuture<Answer> answer) {
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
			Relays.refresh();
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
		int relayed = tunnel.connectedViaRelay();
		if (players > 0) {
			String direct = players - relayed == 0 ? "" : players - relayed == 1 ? "1 friend is connected straight to you." : (players - relayed) + " friends are connected straight to you.";
			String through = relayed == 0 ? "" : relayed == 1 ? "1 through a JavaHost relay." : relayed + " through a JavaHost relay.";
			return (direct + " " + through).trim();
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
			// Their round trip to each relay they can reach; with ours, that picks the relay nearest to both of us.
			java.util.Map<InetSocketAddress, Integer> theirRelays = new java.util.HashMap<>();
			if (body.has("relays")) {
				for (java.util.Map.Entry<String, JsonElement> entry : body.getAsJsonObject("relays").entrySet()) {
					InetSocketAddress relay = theirRelays.size() < MAX_ADDRESSES ? DirectTunnel.parse(entry.getKey()) : null;
					if (relay != null && Relays.addresses().contains(relay)) {
						theirRelays.put(relay, Math.max(0, Math.min(5000, entry.getValue().getAsInt())));
					}
				}
			}
			tunnel.findPublicAddress().thenCompose(address -> tunnel.pingRelays(List.copyOf(theirRelays.keySet()))).thenAccept(ourRelays -> {
				InetSocketAddress relay = null;
				int best = Integer.MAX_VALUE;
				for (java.util.Map.Entry<InetSocketAddress, Integer> entry : ourRelays.entrySet()) {
					int total = entry.getValue() + theirRelays.get(entry.getKey());
					if (total < best) {
						best = total;
						relay = entry.getKey();
					}
				}
				tunnel.expect(session, token, theirs, relay);
				JsonObject answer = new JsonObject();
				answer.addProperty("session", session);
				JsonArray ours = new JsonArray();
				tunnel.candidates().forEach(ours::add);
				answer.add("addresses", ours);
				if (relay != null) {
					answer.addProperty("relay", text(relay));
				}
				Friends.sendPunch(friend, "punch-ok", answer);
				JavaHost.LOGGER.info("[Direct] {} is coming through ({})", friend.name(),
						relay == null ? "directly, no relay to fall back on" : "directly if possible, else by the " + Relays.name(relay) + " relay, about " + best + " ms");
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
		CompletableFuture<Answer> answer = new CompletableFuture<>();
		WAITING.put(session, new Waiting(host.id(), answer));
		// The relay list is read alongside the address lookup (it's no longer fetched at startup); a slow list doesn't hold
		// the join up for more than a couple of seconds.
		CompletableFuture<List<InetSocketAddress>> relayList = Relays.whenLoaded(2500);
		return tunnel.findPublicAddress().thenCompose(address -> relayList).thenCompose(tunnel::pingRelays).thenCompose(relayTimes -> {
			JsonObject body = new JsonObject();
			body.addProperty("session", session);
			body.addProperty("token", Base64.getEncoder().encodeToString(token));
			JsonArray ours = new JsonArray();
			(noDirect() ? List.of("192.0.2.2:9") : tunnel.candidates()).forEach(ours::add);
			body.add("addresses", ours);
			if (!relayTimes.isEmpty()) {
				JsonObject relays = new JsonObject();
				relayTimes.forEach((relay, ms) -> relays.addProperty(text(relay), ms));
				body.add("relays", relays);
			}
			Friends.sendPunch(host, "punch", body);
			return answer.orTimeout(6, TimeUnit.SECONDS);
		}).thenCompose(theirs -> tunnel.connect(session, token, theirs.addresses(), theirs.relay()).orTimeout(9, TimeUnit.SECONDS)).whenComplete((port, error) -> {
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
				// Only a relay from our own list: the host can pick among them, not send us somewhere else.
				InetSocketAddress relay = body.has("relay") ? DirectTunnel.parse(body.get("relay").getAsString()) : null;
				if (relay != null && !Relays.addresses().contains(relay)) {
					relay = null;
				}
				if (!theirs.isEmpty() || relay != null) {
					waiting.answer().complete(new Answer(theirs, relay));
				}
			}
		} catch (RuntimeException e) {
			JavaHost.LOGGER.debug("[Direct] ignored a punch-ok from {}: {}", friend.name(), e.toString());
		}
	}

	/** Development only: pretend there's no direct way (so the automated tests can exercise the relay). */
	private static boolean noDirect() {
		return System.getProperty("javahost.test.noDirect") != null && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	private static List<InetSocketAddress> addresses(JsonArray array) {
		List<InetSocketAddress> out = new ArrayList<>();
		if (noDirect()) {
			return List.of(new InetSocketAddress("192.0.2.1", 9)); // an address nothing answers at
		}
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

	private static String text(InetSocketAddress address) {
		String host = address.getAddress().getHostAddress();
		return (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
	}

	/** How we're connected to the host: "direct", the relay's name ("UAE relay"), or null when not on a JavaHost link. */
	public static String guestRoute() {
		DirectTunnel tunnel = guestTunnel;
		if (tunnel == null || tunnel.isClosed() || tunnel.connected() == 0) {
			return null;
		}
		return tunnel.viaRelay(guestSession) ? Relays.name(tunnel.relayOf(guestSession)) + " relay" : "direct";
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

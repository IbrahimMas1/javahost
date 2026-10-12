package com.javahost.client.friends;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Friends.RouteInfo;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.HostController;
import com.javahost.util.FriendCrypto;
import com.javahost.util.Mailbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * Online status. Each player publishes what they're doing (in the menus, playing, or hosting a world and how to
 * reach it) to their own status topic, encrypted with a status key only their friends have. Statuses go out only
 * when something changes, plus a heartbeat every 15 minutes and "offline" on quit, which keeps well inside the
 * free relay's daily message limit. All friends' statuses arrive over one shared connection.
 */
public final class Presence {
	public enum State { MENU, PLAYING, HOSTING, OFFLINE }

	public record Status(UUID player, State state, String world, int players, int max, List<RouteInfo> routes, String hostIp, long time) {
		public boolean online() {
			return state != State.OFFLINE && System.currentTimeMillis() - time < STALE_MS;
		}

		public boolean hosting() {
			return online() && state == State.HOSTING && !routes.isEmpty();
		}

		/** The same shape as an invite, so joining a hosting friend reuses the invite join flow. */
		public Friends.Invite asInvite(String name) {
			return new Friends.Invite(player, name, world, routes, hostIp, System.currentTimeMillis() + 60_000);
		}
	}

	private static final long HEARTBEAT_MS = 15 * 60 * 1000L;
	private static final long STALE_MS = 20 * 60 * 1000L;
	private static final long MIN_GAP_MS = 20_000L;

	private static final Map<UUID, Status> LATEST = new HashMap<>();
	private static Mailbox stream;
	private static String lastPublished = "";
	private static long lastPublishTime;

	private Presence() {
	}

	static String topicFor(UUID player) {
		return FriendCrypto.mailboxFor(player, Friends.NAMESPACE + ":status");
	}

	public static void start() {
		stream = new Mailbox("status", topics(), "30m");
		stream.start("30m", message -> Minecraft.getInstance().execute(() -> receive(message)), status -> {
		});
	}

	private static List<String> topics() {
		List<String> topics = new ArrayList<>();
		for (Friend friend : Friends.friends()) {
			if (friend.statusKey() != null && !friend.statusKey().isEmpty()) {
				topics.add(topicFor(friend.id()));
			}
		}
		return topics;
	}

	/** The friend list or someone's status key changed: listen to the right topics. */
	static void friendsChanged() {
		if (stream != null) {
			stream.setTopics(topics());
		}
	}

	public static Optional<Status> of(UUID friend) {
		Status status = LATEST.get(friend);
		return status == null || !status.online() ? Optional.empty() : Optional.of(status);
	}

	public static List<Friend> onlineFriends() {
		return Friends.friends().stream().filter(f -> of(f.id()).isPresent()).toList();
	}

	// ================================================================ publishing

	/** Called every client tick: publishes when our state changes (rate-limited) or the heartbeat is due. */
	public static void tick(Minecraft minecraft) {
		if (Friends.status() == Mailbox.Status.CONNECTING && lastPublishTime == 0) {
			return;
		}
		JsonObject now = currentState(minecraft);
		String key = now.toString();
		long since = System.currentTimeMillis() - lastPublishTime;
		boolean stateChanged = !key.equals(lastPublished);
		// A change inside the minimum gap goes out on a later tick, once the gap has passed.
		if ((stateChanged && since > MIN_GAP_MS) || since > HEARTBEAT_MS) {
			publish(now);
		}
	}

	/** Publishes our status again right away (e.g. after our status key changed). */
	static void republish() {
		lastPublished = "";
		lastPublishTime = 0;
	}

	/** Tells friends we're offline; called when the game closes (waits briefly so it actually goes out). */
	public static void goOffline() {
		JsonObject state = new JsonObject();
		state.addProperty("state", State.OFFLINE.name());
		try {
			send(state).get(2, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// Friends will see us as offline after the heartbeat expires anyway.
		}
	}

	private static JsonObject currentState(Minecraft minecraft) {
		JsonObject state = new JsonObject();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (HostController.isHosting() && server != null && !Connectivity.isProbing()) {
			List<RouteInfo> routes = InviteFlow.shareableRoutes();
			state.addProperty("state", State.HOSTING.name());
			state.addProperty("world", server.getWorldData().getLevelName());
			state.addProperty("players", HostController.guests().size() + 1);
			state.addProperty("max", Config.get().maxPlayers);
			state.add("routes", Friends.routesJson(routes));
			state.addProperty("hostIp", Connectivity.publicIpv4() == null ? "" : Connectivity.publicIpv4());
		} else if (minecraft.level != null) {
			state.addProperty("state", State.PLAYING.name());
		} else {
			state.addProperty("state", State.MENU.name());
		}
		return state;
	}

	private static void publish(JsonObject state) {
		lastPublished = state.toString();
		lastPublishTime = System.currentTimeMillis();
		if (Friends.friends().isEmpty()) {
			return; // nobody to tell
		}
		send(state);
	}

	private static java.util.concurrent.CompletableFuture<Mailbox.Sent> send(JsonObject state) {
		try {
			JsonObject message = new JsonObject();
			long ts = System.currentTimeMillis();
			message.addProperty("v", 1);
			message.addProperty("type", "status");
			message.addProperty("from", Identity.id().toString());
			message.addProperty("ts", ts);
			message.addProperty("data", FriendCrypto.seal(Friends.myStatusKey(), state.toString(), "status|" + Identity.id() + "|" + ts));
			String summary = state.get("state").getAsString();
			return Mailbox.post(topicFor(Identity.id()), message.toString()).thenApply(sent -> {
				JavaHost.LOGGER.info("[Friends] published status {}: HTTP {}", summary, sent.status());
				return sent;
			});
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not publish status", e);
			return java.util.concurrent.CompletableFuture.completedFuture(new Mailbox.Sent(-1));
		}
	}

	// ================================================================ receiving

	private static void receive(Mailbox.Message envelope) {
		try {
			JsonObject message = JsonParser.parseString(envelope.text()).getAsJsonObject();
			UUID from = UUID.fromString(message.get("from").getAsString());
			long ts = message.get("ts").getAsLong();
			Optional<Friend> friend = Friends.friend(from);
			if (friend.isEmpty() || friend.get().statusKey() == null || !envelope.topic().equals(topicFor(from))) {
				return;
			}
			Status previous = LATEST.get(from);
			if (previous != null && previous.time() >= ts) {
				return; // older than what we have
			}
			byte[] key = Base64.getDecoder().decode(friend.get().statusKey());
			JsonObject body = JsonParser.parseString(FriendCrypto.open(key, message.get("data").getAsString(), "status|" + from + "|" + ts)).getAsJsonObject();
			State state = State.valueOf(body.get("state").getAsString());
			Status status = new Status(from, state, Friends.str(body, "world"),
					body.has("players") ? body.get("players").getAsInt() : 0, body.has("max") ? body.get("max").getAsInt() : 0,
					body.has("routes") ? Friends.routesFrom(body.getAsJsonArray("routes")) : List.of(), Friends.str(body, "hostIp"), ts);
			LATEST.put(from, status);
			boolean fresh = System.currentTimeMillis() - ts < 2 * 60 * 1000L;
			boolean startedHosting = status.hosting() && (previous == null || !previous.hosting());
			if (fresh && startedHosting) {
				Notifications.add(Notifications.Kind.HOSTING, from, friend.get().name(),
						friend.get().name() + " is hosting " + status.world(), status);
			}
			Friends.changed();
		} catch (Exception e) {
			JavaHost.LOGGER.debug("Ignored a status message", e);
		}
	}
}

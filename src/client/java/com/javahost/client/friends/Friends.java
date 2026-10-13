package com.javahost.client.friends;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.HostState;
import com.javahost.JavaHost;
import com.javahost.util.FriendCrypto;
import com.javahost.util.Mailbox;
import com.javahost.util.Mailbox.Sent;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Friends, friend requests, invites, chat and screenshots. Messages travel through each player's {@link Mailbox}:
 * <ul>
 * <li><b>request</b>/<b>accept</b>: signed with the sender's Mojang-certified key (proves who sent it) and carrying
 * their X25519 key, so both sides can derive a shared secret.</li>
 * <li>everything else (<b>invite</b>, <b>invite-seen</b>, <b>chat</b>, <b>screenshot</b>, <b>statuskey</b>,
 * <b>remove</b>): encrypted and authenticated with that shared secret.</li>
 * </ul>
 * Every message sent or received is logged, so problems can be diagnosed from the game log.
 */
public final class Friends {
	/**
	 * {@code signKey}: their Ed25519 public key, which proves their online status and that a world is theirs. Null
	 * for friends still on JavaHost 1.6 or older (they can't be joined until they update).
	 */
	public record Friend(UUID id, String name, String box, long since, String statusKey, String signKey) {
		Friend withStatusKey(String key) {
			return new Friend(id, name, box, since, key, signKey);
		}

		Friend withSignKey(String key) {
			return new Friend(id, name, box, since, statusKey, key);
		}
	}

	/** {@code note}: a warning to show with it (e.g. their name matches a friend's old name), or empty. */
	public record Request(UUID id, String name, String box, long time, String signKey, String note) {
	}

	public record Outgoing(UUID id, String name, long time) {
	}

	/** A world invite received from a friend. Routes are in the host's order of preference. */
	public record Invite(UUID from, String name, String world, List<RouteInfo> routes, String hostIp, long expires) {
		public boolean expired() {
			return System.currentTimeMillis() > expires;
		}
	}

	public record RouteInfo(String name, String state, String address) {
	}

	public enum InviteState { SENDING, SENT, DELIVERED, FAILED }

	public record InviteStatus(InviteState state, String messageId, long time, String detail) {
	}

	/** Things that happened, for popups, toasts and the notification center. */
	public sealed interface Event {
		record InviteReceived(Invite invite) implements Event {
		}

		record RequestReceived(Request request) implements Event {
		}

		record Accepted(Friend friend) implements Event {
		}

		record ChatReceived(Friend friend, String text) implements Event {
		}

		record ScreenshotReceived(Friend friend, Path file, String caption) implements Event {
		}
	}

	private static final class Data {
		List<Friend> friends = new ArrayList<>();
		List<Request> incoming = new ArrayList<>();
		List<Outgoing> outgoing = new ArrayList<>();
		String lastMessageId = "";
		/** Our status key (base64); friends use it to read our online status. */
		String myStatusKey = "";
		/** Friends we've already sent the current status key to. */
		Set<String> statusKeySentTo = new HashSet<>();
		/** Friends we've already sent our signing key to. */
		Set<String> signKeySentTo = new HashSet<>();
		/** When we last asked each friend to resend their keys (so we don't keep asking). */
		Map<String, Long> askedForKeys = new HashMap<>();
		/** Message ids already handled, with when they were sent: a replayed message is ignored, even after a restart. */
		Map<String, Long> seen = new HashMap<>();
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("javahost-friends.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long MAX_AGE_MS = 12 * 60 * 60 * 1000L;
	private static final long DELIVERY_TIMEOUT_MS = 60_000L;
	static final String NAMESPACE = System.getProperty("javahost.friendsNamespace", "");

	private static Data data = new Data();
	private static Mailbox mailbox;
	private static final int MAX_SEEN = 5000;
	private static boolean dirty;
	private static long lastSaved;
	private static long lastRetry;
	private static final List<Runnable> LISTENERS = new ArrayList<>();
	private static final Map<UUID, InviteStatus> INVITES_SENT = new HashMap<>();
	private static Consumer<Event> events = event -> {
	};

	private Friends() {
	}

	// ================================================================ setup

	/** Loads saved friends and starts listening to this player's mailbox. Call once the game has started. */
	public static void start(Consumer<Event> sink) {
		events = sink;
		load();
		if (data.myStatusKey.isEmpty()) {
			rotateStatusKey(false);
		}
		pushAllowedPlayers();
		mailbox = new Mailbox("mailbox", List.of(FriendCrypto.mailboxFor(Identity.id(), NAMESPACE)), "12h");
		String since = data.lastMessageId.isEmpty() ? "12h" : data.lastMessageId;
		mailbox.start(since, message -> Minecraft.getInstance().execute(() -> {
			data.lastMessageId = message.id();
			receive(message);
			save();
		}), status -> Minecraft.getInstance().execute(() -> {
			JavaHost.LOGGER.info("[Friends] mailbox {}", status);
			changed();
		}));
		lastRetry = System.currentTimeMillis();
		sendMissingKeys(Integer.MAX_VALUE);
	}

	/**
	 * Friends who don't have our status or signing key yet get them; friends whose keys we're missing are asked
	 * to resend (once a day). Covers friends added on older versions and messages lost while someone was away.
	 */
	private static void sendMissingKeys(int limit) {
		int sent = 0;
		long now = System.currentTimeMillis();
		for (Friend friend : List.copyOf(data.friends)) {
			if (sent >= limit) {
				return;
			}
			String id = friend.id().toString();
			if (!data.statusKeySentTo.contains(id)) {
				sendStatusKey(friend);
				sent++;
			}
			if (!data.signKeySentTo.contains(id)) {
				sendSignKey(friend);
				sent++;
			}
			if ((friend.statusKey() == null || friend.signKey() == null) && now - data.askedForKeys.getOrDefault(id, 0L) > 24 * 60 * 60 * 1000L) {
				data.askedForKeys.put(id, now);
				sendSealed(friend, "keys-please", new JsonObject());
				sent++;
				save();
			}
		}
	}

	/**
	 * Signs a sample request with this account's Mojang-certified key and verifies it the way a friend's game would,
	 * so a broken signing setup shows up in the log right away instead of as "my friend never got my request".
	 */
	public static void selfCheck() {
		Identity.checkCertificate().thenAccept(present -> Minecraft.getInstance().execute(() -> {
			changed();
			if (Identity.INSECURE_TEST_MODE || !present) {
				JavaHost.LOGGER.info("Friends self-check skipped: this login has no Microsoft-account certificate");
				return;
			}
			runSelfCheck();
		}));
	}

	private static void runSelfCheck() {
		JsonObject sample = header("request", Identity.id());
		sample.addProperty("box", Identity.boxPublicKey());
		Identity.sign(sample, signedPayload(sample)).whenComplete((signed, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null || !signed) {
				JavaHost.LOGGER.warn("Friends self-check FAILED: could not sign ({})", error == null ? "no key" : error.toString());
				return;
			}
			boolean valid = Identity.verify(Identity.id(), sample, signedPayload(sample));
			JsonObject tampered = sample.deepCopy();
			tampered.addProperty("name", "Someone_Else");
			boolean tamperRejected = !Identity.verify(Identity.id(), tampered, signedPayload(tampered));
			JavaHost.LOGGER.info("Friends self-check {}: signature verified={}, tampered copy rejected={}, size={} bytes",
					valid && tamperRejected ? "PASSED" : "FAILED", valid, tamperRejected, sample.toString().length());
		}));
	}

	public static Mailbox.Status status() {
		return mailbox == null ? Mailbox.Status.CONNECTING : mailbox.status();
	}

	/** Called whenever friends, requests, invites or the connection change (UI refresh). */
	public static void onChange(Runnable listener) {
		LISTENERS.add(listener);
	}

	static void changed() {
		pushAllowedPlayers();
		new ArrayList<>(LISTENERS).forEach(Runnable::run);
	}

	/** Friends may join our hosted world; everyone else is turned away when "friends only" is on. */
	private static void pushAllowedPlayers() {
		Set<UUID> allowed = new HashSet<>();
		data.friends.forEach(f -> allowed.add(f.id()));
		boolean removed = !allowed.containsAll(HostState.allowedPlayers());
		HostState.setAllowedPlayers(allowed);
		if (removed && net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null) {
			com.javahost.client.host.HostController.enforce(); // someone you unfriended leaves a friends-only world
		}
	}

	public static List<Friend> friends() {
		return List.copyOf(data.friends);
	}

	public static List<Request> incoming() {
		return List.copyOf(data.incoming);
	}

	public static List<Outgoing> outgoing() {
		return List.copyOf(data.outgoing);
	}

	public static Optional<Friend> friend(UUID id) {
		return data.friends.stream().filter(f -> f.id().equals(id)).findFirst();
	}

	public static Optional<InviteStatus> inviteStatus(UUID friend) {
		return Optional.ofNullable(INVITES_SENT.get(friend));
	}

	/** Our status key, for {@link Presence}. */
	static byte[] myStatusKey() {
		return Base64.getDecoder().decode(data.myStatusKey);
	}

	/** Gives up on invites that were sent but never acknowledged; saves changes; retries key exchanges. */
	public static void tick() {
		long now = System.currentTimeMillis();
		if (dirty && now - lastSaved > 1000) {
			flush();
		}
		if (mailbox != null && now - lastRetry > 10 * 60 * 1000L) {
			lastRetry = now;
			sendMissingKeys(5);
		}
		INVITES_SENT.replaceAll((id, status) -> status.state() == InviteState.SENT && now - status.time() > DELIVERY_TIMEOUT_MS
				? new InviteStatus(InviteState.FAILED, status.messageId(), now, "Not delivered (they may be offline)")
				: status);
	}

	// ================================================================ actions

	/** Sends a friend request. Completes with a message for the player. */
	public static CompletableFuture<String> add(String rawName) {
		String name = rawName.trim();
		if (!name.matches("[A-Za-z0-9_]{3,16}")) {
			return CompletableFuture.completedFuture("That isn't a Minecraft username.");
		}
		if (name.equalsIgnoreCase(Identity.name())) {
			return CompletableFuture.completedFuture("That's you!");
		}
		if (!Identity.canSign()) {
			return CompletableFuture.completedFuture("Friends need you to be signed in with a Microsoft account.");
		}
		return Identity.lookUp(name).thenCompose(found -> {
			if (found.isEmpty()) {
				return CompletableFuture.completedFuture("No Minecraft player is called " + name + ".");
			}
			UUID id = found.get();
			return onGameThread(() -> {
				if (friend(id).isPresent()) {
					return CompletableFuture.completedFuture("You're already friends with " + name + ".");
				}
				Optional<Request> theirs = data.incoming.stream().filter(r -> r.id().equals(id)).findFirst();
				if (theirs.isPresent()) {
					return accept(theirs.get()).thenApply(ok -> ok ? "You're now friends with " + name + "!" : "Couldn't reach the friends service.");
				}
				return sendSigned("request", id, name).thenApply(sent -> {
					if (!sent.ok()) {
						return describeFailure(sent);
					}
					Minecraft.getInstance().execute(() -> {
						data.outgoing.removeIf(o -> o.id().equals(id));
						data.outgoing.add(new Outgoing(id, name, System.currentTimeMillis()));
						save();
						changed();
					});
					return "Friend request sent to " + name + ".";
				});
			});
		});
	}

	/** Plain words for why something wasn't sent. */
	public static String describeFailure(Sent sent) {
		if (sent.status() == 401) {
			return "Friends need you to be signed in with a Microsoft account.";
		}
		if (sent.rateLimited()) {
			return "You've hit the daily message limit. Try again later.";
		}
		if (sent.status() == 413) {
			return "That's too big to send.";
		}
		return "Couldn't reach the friends service. Check your internet.";
	}

	public static CompletableFuture<Boolean> accept(Request request) {
		return sendSigned("accept", request.id(), request.name()).thenApply(sent -> {
			if (sent.ok()) {
				Minecraft.getInstance().execute(() -> {
					Friend friend = becomeFriends(request.id(), request.name(), request.box(), request.signKey());
					data.incoming.removeIf(r -> r.id().equals(request.id()));
					save();
					changed();
					if (friend != null) {
						sendStatusKey(friend);
						sendSignKey(friend);
					}
				});
			}
			return sent.ok();
		});
	}

	public static void decline(Request request) {
		data.incoming.removeIf(r -> r.id().equals(request.id()));
		save();
		changed();
	}

	public static void cancel(Outgoing outgoing) {
		data.outgoing.removeIf(o -> o.id().equals(outgoing.id()));
		save();
		changed();
	}

	/** Blocks a player: their messages are dropped unread, they can't join your worlds, and they stop being a friend. */
	public static void block(UUID id, String name) {
		com.javahost.client.Config config = com.javahost.client.Config.get();
		config.blockedPlayers.put(id.toString(), name);
		config.save();
		data.incoming.removeIf(r -> r.id().equals(id));
		data.outgoing.removeIf(o -> o.id().equals(id));
		friend(id).ifPresent(Friends::remove);
		com.javahost.client.host.HostController.applyHostRules(config);
		save();
		changed();
		JavaHost.LOGGER.info("[Friends] blocked {}", name);
	}

	public static void unblock(UUID id) {
		com.javahost.client.Config config = com.javahost.client.Config.get();
		config.blockedPlayers.remove(id.toString());
		config.save();
		com.javahost.client.host.HostController.applyHostRules(config);
		changed();
	}

	public static boolean isBlocked(UUID id) {
		return com.javahost.client.Config.get().blockedPlayers.containsKey(id.toString());
	}

	public static void remove(Friend friend) {
		sendSealed(friend, "remove", new JsonObject());
		data.friends.removeIf(f -> f.id().equals(friend.id()));
		INVITES_SENT.remove(friend.id());
		Chats.forget(friend.id());
		// They could still read our status with the old key; switch to a new one.
		rotateStatusKey(true);
		save();
		changed();
	}

	/** Sends an encrypted invite with the ways to reach our world, and tracks whether it arrives. */
	public static CompletableFuture<Boolean> sendInvite(Friend friend, String world, List<RouteInfo> routes, String hostIp) {
		JsonObject body = new JsonObject();
		body.addProperty("world", world);
		body.addProperty("hostIp", hostIp == null ? "" : hostIp);
		body.addProperty("expires", System.currentTimeMillis() + 15 * 60 * 1000L);
		body.add("routes", routesJson(routes));
		INVITES_SENT.put(friend.id(), new InviteStatus(InviteState.SENDING, "", System.currentTimeMillis(), "Sending..."));
		changed();
		JsonObject message = header("invite", friend.id());
		return sendSealed(friend, message, body).thenApply(sent -> {
			Minecraft.getInstance().execute(() -> {
				INVITES_SENT.put(friend.id(), sent.ok()
						? new InviteStatus(InviteState.SENT, str(message, "id"), System.currentTimeMillis(), "Sent, waiting for them")
						: new InviteStatus(InviteState.FAILED, "", System.currentTimeMillis(), describeFailure(sent)));
				changed();
			});
			return sent.ok();
		});
	}

	public static CompletableFuture<Sent> sendChat(Friend friend, String text) {
		String clean = text.strip();
		if (clean.length() > Chats.MAX_LENGTH) {
			clean = clean.substring(0, Chats.MAX_LENGTH);
		}
		JsonObject body = new JsonObject();
		body.addProperty("text", clean);
		String line = clean;
		return sendSealed(friend, header("chat", friend.id()), body).thenApply(sent -> {
			if (sent.ok()) {
				Chats.add(friend.id(), new Chats.Line(true, line, System.currentTimeMillis()));
				Minecraft.getInstance().execute(Friends::changed);
			}
			return sent;
		});
	}

	/** Encrypts an image and uploads it as an attachment to the friend's mailbox. */
	public static CompletableFuture<Sent> sendScreenshot(Friend friend, byte[] png, String caption) {
		try {
			JsonObject message = header("screenshot", friend.id());
			byte[] key = Identity.sharedKeyWith(friend.id(), friend.box());
			JsonObject meta = new JsonObject();
			meta.addProperty("caption", caption == null ? "" : caption);
			message.addProperty("data", FriendCrypto.seal(key, meta.toString(), context(message)));
			byte[] sealed = FriendCrypto.sealBytes(key, png, context(message) + "|file");
			return deliver(friend, "screenshot (" + png.length / 1024 + " KB)", message.toString(), sealed,
					() -> Mailbox.postAttachment(FriendCrypto.mailboxFor(friend.id(), NAMESPACE), sealed, message.toString()));
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not send screenshot to {}", friend.name(), e);
			return CompletableFuture.completedFuture(new Sent(-1));
		}
	}

	/**
	 * Sends a screenshot file: the full-size original over the fast lane when the friend is in this world,
	 * otherwise scaled to fit the relay's 2 MB limit. Call off the render thread.
	 */
	public static CompletableFuture<Sent> sendScreenshotFile(Friend friend, java.nio.file.Path file) {
		try {
			byte[] png = FastLane.canReach(friend.id()) ? Screenshots.prepareForGame(file) : Screenshots.prepareForSending(file);
			return sendScreenshot(friend, png, file.getFileName().toString());
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not prepare screenshot {} for {}", file.getFileName(), friend.name(), e);
			return CompletableFuture.completedFuture(new Sent(413));
		}
	}

	/**
	 * Sends a sealed message (and file): over the game connection when the friend is in this world, otherwise
	 * (or if that fails) through the internet relay. Logs which way it went.
	 */
	private static CompletableFuture<Sent> deliver(Friend friend, String what, String message, byte[] file,
			java.util.function.Supplier<CompletableFuture<Sent>> relay) {
		CompletableFuture<Sent> first = FastLane.canReach(friend.id())
				? FastLane.send(friend.id(), message, file).thenApply(sent -> {
					if (sent.ok()) {
						JavaHost.LOGGER.info("[Friends] sent {} to {} via game", what, friend.name());
					}
					return sent;
				})
				: CompletableFuture.completedFuture(null);
		return first.thenCompose(sent -> sent != null && sent.ok() ? CompletableFuture.completedFuture(sent)
				: relay.get().thenApply(viaRelay -> {
					JavaHost.LOGGER.info("[Friends] sent {} to {}: HTTP {}", what, friend.name(), viaRelay.status());
					return viaRelay;
				}));
	}

	static JsonArray routesJson(List<RouteInfo> routes) {
		JsonArray list = new JsonArray();
		for (RouteInfo route : routes) {
			JsonObject r = new JsonObject();
			r.addProperty("name", route.name());
			r.addProperty("state", route.state());
			r.addProperty("address", route.address());
			list.add(r);
		}
		return list;
	}

	static List<RouteInfo> routesFrom(JsonArray array) {
		List<RouteInfo> routes = new ArrayList<>();
		for (JsonElement element : array) {
			JsonObject r = element.getAsJsonObject();
			routes.add(new RouteInfo(str(r, "name"), str(r, "state"), str(r, "address")));
		}
		return routes;
	}

	// ================================================================ status keys (for Presence)

	private static void rotateStatusKey(boolean announce) {
		data.myStatusKey = Base64.getEncoder().encodeToString(FriendCrypto.randomKey());
		data.statusKeySentTo.clear();
		save();
		if (announce) {
			data.friends.forEach(Friends::sendStatusKey);
			Presence.republish();
		}
	}

	private static void sendStatusKey(Friend friend) {
		JsonObject body = new JsonObject();
		body.addProperty("key", data.myStatusKey);
		sendSealed(friend, "statuskey", body).thenAccept(sent -> {
			if (sent.ok()) {
				Minecraft.getInstance().execute(() -> {
					data.statusKeySentTo.add(friend.id().toString());
					save();
				});
			}
		});
	}

	private static void sendSignKey(Friend friend) {
		JsonObject body = new JsonObject();
		body.addProperty("key", Identity.signPublicKey());
		sendSealed(friend, "keys", body).thenAccept(sent -> {
			if (sent.ok()) {
				Minecraft.getInstance().execute(() -> {
					data.signKeySentTo.add(friend.id().toString());
					save();
				});
			}
		});
	}

	// ================================================================ sending

	private static <T> CompletableFuture<T> onGameThread(java.util.function.Supplier<CompletableFuture<T>> work) {
		CompletableFuture<T> result = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> work.get().whenComplete((value, error) -> {
			if (error != null) {
				result.completeExceptionally(error);
			} else {
				result.complete(value);
			}
		}));
		return result;
	}

	private static JsonObject header(String type, UUID to) {
		JsonObject message = new JsonObject();
		message.addProperty("v", 1);
		message.addProperty("type", type);
		message.addProperty("id", FriendCrypto.randomId());
		message.addProperty("from", Identity.id().toString());
		message.addProperty("name", Identity.name());
		message.addProperty("to", to.toString());
		message.addProperty("ts", System.currentTimeMillis());
		return message;
	}

	/** The exact bytes a request/accept signature covers. */
	private static byte[] signedPayload(JsonObject m) {
		String base = String.join("\n", "javahost-v1", str(m, "type"), str(m, "id"), str(m, "from"), str(m, "name"), str(m, "to"),
				String.valueOf(m.get("ts").getAsLong()), str(m, "box"));
		// 1.7+: the signing key is covered by the Mojang signature too (older requests don't carry one).
		return (m.has("signkey") ? base + "\n" + str(m, "signkey") : base).getBytes(StandardCharsets.UTF_8);
	}

	private static String context(JsonObject m) {
		return String.join("|", str(m, "type"), str(m, "id"), str(m, "from"), str(m, "to"), String.valueOf(m.get("ts").getAsLong()));
	}

	private static CompletableFuture<Sent> sendSigned(String type, UUID to, String toName) {
		JsonObject message = header(type, to);
		message.addProperty("box", Identity.boxPublicKey());
		message.addProperty("signkey", Identity.signPublicKey());
		return Identity.sign(message, signedPayload(message)).thenCompose(signed -> {
			if (!signed) {
				Identity.checkCertificate();
				return CompletableFuture.completedFuture(new Sent(401));
			}
			return Mailbox.post(FriendCrypto.mailboxFor(to, NAMESPACE), message.toString());
		}).exceptionally(e -> new Sent(-1)).thenApply(sent -> {
			JavaHost.LOGGER.info("[Friends] sent {} to {}: HTTP {}", type, toName, sent.status());
			return sent;
		});
	}

	private static CompletableFuture<Sent> sendSealed(Friend friend, String type, JsonObject body) {
		return sendSealed(friend, header(type, friend.id()), body);
	}

	private static CompletableFuture<Sent> sendSealed(Friend friend, JsonObject message, JsonObject body) {
		try {
			byte[] key = Identity.sharedKeyWith(friend.id(), friend.box());
			message.addProperty("data", FriendCrypto.seal(key, body.toString(), context(message)));
			String type = str(message, "type");
			return deliver(friend, type, message.toString(), null, () -> Mailbox.post(FriendCrypto.mailboxFor(friend.id(), NAMESPACE), message.toString()));
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not send {} to {}", str(message, "type"), friend.name(), e);
			return CompletableFuture.completedFuture(new Sent(-1));
		}
	}

	// ================================================================ receiving

	private static void receive(Mailbox.Message envelope) {
		String url = envelope.attachmentUrl();
		handle(envelope.text(), url == null ? null : () -> Mailbox.download(url), null);
	}

	/** A message that came over the same-world fast lane; {@code sender} is who the host says sent it. */
	static void receiveDirect(UUID sender, String text, byte[] file) {
		handle(text, file == null ? null : () -> CompletableFuture.completedFuture(file), sender);
	}

	/** Fetches a message's attached file (downloaded from the relay, or already here from the fast lane). */
	private interface Attachment {
		CompletableFuture<byte[]> fetch();
	}

	private static void handle(String text, Attachment attachment, UUID sender) {
		String type = "?";
		try {
			JsonObject m = JsonParser.parseString(text).getAsJsonObject();
			type = str(m, "type");
			if (m.get("v").getAsInt() != 1 || data.seen.containsKey(str(m, "id"))) {
				return;
			}
			UUID from = UUID.fromString(str(m, "from"));
			if (isBlocked(from)) {
				return; // dropped unread
			}
			UUID to = UUID.fromString(str(m, "to"));
			if (sender != null && !sender.equals(from)) {
				return; // the fast lane says someone else sent it
			}
			long ts = m.get("ts").getAsLong();
			long now = System.currentTimeMillis();
			if (!to.equals(Identity.id()) || from.equals(Identity.id()) || ts < now - MAX_AGE_MS || ts > now + 10 * 60 * 1000L) {
				return;
			}
			String name = str(m, "name");
			String id = str(m, "id");
			if (!name.matches("[A-Za-z0-9_]{1,16}") || !id.matches("[A-Za-z0-9_-]{1,64}")) {
				return;
			}
			remember(id, ts);
			JavaHost.LOGGER.info("[Friends] received {} from {}{}", type, name, sender != null ? " via game" : "");
			switch (type) {
				case "request" -> {
					if (Identity.verify(from, m, signedPayload(m))) {
						onRequest(from, name, str(m, "box"), str(m, "signkey"));
					} else {
						JavaHost.LOGGER.warn("[Friends] rejected request from {}: bad signature", name);
					}
				}
				case "accept" -> {
					if (Identity.verify(from, m, signedPayload(m))) {
						onAccept(from, name, str(m, "box"), str(m, "signkey"));
					} else {
						JavaHost.LOGGER.warn("[Friends] rejected accept from {}: bad signature", name);
					}
				}
				case "invite", "invite-seen", "remove", "statuskey", "keys", "keys-please", "chat", "screenshot" -> {
					Optional<Friend> friend = friend(from);
					if (friend.isEmpty()) {
						JavaHost.LOGGER.info("[Friends] ignored {} from {}: not a friend", type, name);
						return;
					}
					// Decrypting also proves it came from this friend (only the two of us have the key).
					String plain = FriendCrypto.open(Identity.sharedKeyWith(from, friend.get().box()), str(m, "data"), context(m));
					onSealed(type, friend.get(), m, JsonParser.parseString(plain).getAsJsonObject(), attachment);
				}
				default -> {
				}
			}
		} catch (Exception e) {
			JavaHost.LOGGER.warn("[Friends] could not read a {} message", type, e);
		}
	}

	private static void onSealed(String type, Friend friend, JsonObject message, JsonObject body, Attachment attachment) {
		switch (type) {
			case "remove" -> {
				data.friends.removeIf(f -> f.id().equals(friend.id()));
				rotateStatusKey(true);
				changed();
			}
			case "statuskey" -> {
				data.friends.replaceAll(f -> f.id().equals(friend.id()) ? f.withStatusKey(str(body, "key")) : f);
				save();
				Presence.friendsChanged();
				changed();
			}
			case "keys" -> {
				String key = str(body, "key");
				if (!FriendCrypto.isSigningKey(key)) {
					return; // not a key
				}
				data.friends.replaceAll(f -> f.id().equals(friend.id()) ? f.withSignKey(key) : f);
				save();
				Presence.friendsChanged();
				changed();
			}
			case "keys-please" -> {
				// They lost (or never got) our keys: send them again, at most once an hour each.
				String id = friend.id().toString();
				long last = data.askedForKeys.getOrDefault("answered:" + id, 0L);
				if (System.currentTimeMillis() - last > 60 * 60 * 1000L) {
					data.askedForKeys.put("answered:" + id, System.currentTimeMillis());
					sendStatusKey(friend);
					sendSignKey(friend);
					save();
				}
			}
			case "invite-seen" -> {
				InviteStatus status = INVITES_SENT.get(friend.id());
				if (status != null && status.messageId().equals(str(body, "ref"))) {
					INVITES_SENT.put(friend.id(), new InviteStatus(InviteState.DELIVERED, status.messageId(), System.currentTimeMillis(), "Delivered"));
					changed();
				}
			}
			case "invite" -> {
				// However long the sender says, an invite is good for 15 minutes from when it was sent.
				long expires = Math.min(body.get("expires").getAsLong(), message.get("ts").getAsLong() + 15 * 60 * 1000L);
				Invite invite = new Invite(friend.id(), friend.name(), com.javahost.util.Text.clean(str(body, "world"), 64),
						routesFrom(body.getAsJsonArray("routes")), str(body, "hostIp"), expires);
				// Let the host know it arrived, even if we don't join.
				JsonObject seen = new JsonObject();
				seen.addProperty("ref", str(message, "id"));
				sendSealed(friend, "invite-seen", seen);
				if (!invite.expired() && !invite.routes().isEmpty()) {
					events.accept(new Event.InviteReceived(invite));
				}
			}
			case "chat" -> {
				String text = com.javahost.util.Text.clean(str(body, "text"), Chats.MAX_LENGTH);
				if (text.isEmpty()) {
					return;
				}
				Chats.add(friend.id(), new Chats.Line(false, text, System.currentTimeMillis()));
				events.accept(new Event.ChatReceived(friend, text));
				changed();
			}
			case "screenshot" -> receiveScreenshot(friend, message, body, attachment);
			default -> {
			}
		}
	}

	private static void receiveScreenshot(Friend friend, JsonObject message, JsonObject meta, Attachment attachment) {
		if (attachment == null) {
			return;
		}
		String caption = com.javahost.util.Text.clean(str(meta, "caption"), 128);
		String context = context(message) + "|file";
		attachment.fetch().thenAccept(sealed -> {
			try {
				byte[] png = FriendCrypto.openBytes(Identity.sharedKeyWith(friend.id(), friend.box()), sealed, context);
				Path file = Screenshots.saveFromFriend(friend.name(), png);
				JavaHost.LOGGER.info("[Friends] saved screenshot from {} to {}", friend.name(), file.getFileName());
				Minecraft.getInstance().execute(() -> events.accept(new Event.ScreenshotReceived(friend, file, caption)));
			} catch (Exception e) {
				JavaHost.LOGGER.warn("[Friends] could not open screenshot from {}", friend.name(), e);
			}
		}).exceptionally(e -> {
			JavaHost.LOGGER.warn("[Friends] could not download screenshot from {}", friend.name(), e);
			return null;
		});
	}

	/**
	 * Gets the sender's real name from Mojang before showing anything: the name in a request is whatever the sender
	 * typed, so without this check someone could pose as one of your friends. Falls back to the claimed name only
	 * when Mojang can't be reached (and in offline test mode).
	 */
	private static void withRealName(UUID from, String claimed, java.util.function.Consumer<String> then) {
		Identity.nameOf(from).thenAccept(real -> Minecraft.getInstance().execute(() -> {
			if (real.isPresent() && !real.get().equals(claimed)) {
				JavaHost.LOGGER.warn("[Friends] {} claimed to be called {}; using their real name", real.get(), claimed);
			}
			then.accept(real.orElse(claimed));
		}));
	}

	private static void onRequest(UUID from, String claimedName, String box, String signKey) {
		withRealName(from, claimedName, name -> {
			if (isBlocked(from)) {
				return;
			}
			if (friend(from).isPresent()) {
				// They probably reinstalled; confirm again with our key.
				Friend friend = becomeFriends(from, name, box, signKey);
				sendSigned("accept", from, name);
				if (friend != null) {
					sendStatusKey(friend);
					sendSignKey(friend);
				}
				return;
			}
			if (data.outgoing.stream().anyMatch(o -> o.id().equals(from))) {
				// We both asked: that's a yes.
				accept(new Request(from, name, box, System.currentTimeMillis(), signKey, ""));
				return;
			}
			// A different account using a name one of your friends had (names can change hands): say so.
			String note = data.friends.stream().anyMatch(f -> !f.id().equals(from) && f.name().equalsIgnoreCase(name))
					? "Not your friend " + name + ": a different account with the same name" : "";
			boolean known = data.incoming.removeIf(r -> r.id().equals(from));
			Request request = new Request(from, name, box, System.currentTimeMillis(), signKey, note);
			data.incoming.add(request);
			while (data.incoming.size() > 50) {
				data.incoming.removeFirst(); // a flood of requests can't grow the list forever
			}
			save();
			changed();
			if (!known) {
				events.accept(new Event.RequestReceived(request));
			}
		});
	}

	private static void onAccept(UUID from, String claimedName, String box, String signKey) {
		boolean wanted = data.outgoing.stream().anyMatch(o -> o.id().equals(from)) || friend(from).isPresent();
		if (!wanted) {
			return;
		}
		withRealName(from, claimedName, name -> {
			boolean isNew = friend(from).isEmpty();
			Friend friend = becomeFriends(from, name, box, signKey);
			data.outgoing.removeIf(o -> o.id().equals(from));
			changed();
			if (friend != null) {
				sendStatusKey(friend);
				sendSignKey(friend);
				if (isNew) {
					events.accept(new Event.Accepted(friend));
				}
			}
		});
	}

	private static Friend becomeFriends(UUID id, String name, String box, String signKey) {
		try {
			FriendCrypto.decodePublic(box); // reject garbage keys up front
		} catch (Exception e) {
			return null;
		}
		Optional<Friend> existing = friend(id);
		String sign = signKey != null && !signKey.isEmpty() ? signKey : existing.map(Friend::signKey).orElse(null);
		Friend friend = new Friend(id, name, box, existing.map(Friend::since).orElse(System.currentTimeMillis()),
				existing.map(Friend::statusKey).orElse(null), sign);
		data.friends.removeIf(f -> f.id().equals(id));
		data.friends.add(friend);
		data.friends.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		data.statusKeySentTo.remove(id.toString());
		data.signKeySentTo.remove(id.toString());
		save();
		Presence.friendsChanged();
		return friend;
	}

	/** Marks a message as handled, forgetting the oldest once there are too many (and any older than 12 hours). */
	private static void remember(String id, long ts) {
		data.seen.put(id, ts);
		if (data.seen.size() > MAX_SEEN) {
			long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
			data.seen.values().removeIf(t -> t < cutoff);
			while (data.seen.size() > MAX_SEEN) {
				String oldest = data.seen.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
				data.seen.remove(oldest);
			}
		}
		save();
	}

	static String str(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? "" : value.getAsString();
	}

	// ================================================================ storage

	private static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			Data loaded = GSON.fromJson(reader, Data.class);
			if (loaded != null) {
				data = loaded;
				if (data.friends == null) {
					data.friends = new ArrayList<>();
				}
				if (data.incoming == null) {
					data.incoming = new ArrayList<>();
				}
				if (data.outgoing == null) {
					data.outgoing = new ArrayList<>();
				}
				if (data.lastMessageId == null) {
					data.lastMessageId = "";
				}
				if (data.myStatusKey == null) {
					data.myStatusKey = "";
				}
				if (data.statusKeySentTo == null) {
					data.statusKeySentTo = new HashSet<>();
				}
				if (data.signKeySentTo == null) {
					data.signKeySentTo = new HashSet<>();
				}
				if (data.askedForKeys == null) {
					data.askedForKeys = new HashMap<>();
				}
				if (data.seen == null) {
					data.seen = new HashMap<>();
				}
				// Requests saved by 1.6 have no signing key or note.
				data.incoming.replaceAll(r -> r.note() == null ? new Request(r.id(), r.name(), r.box(), r.time(), r.signKey(), "") : r);
			}
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not read friends list", e);
		}
	}

	/** Saves soon (at most once a second, in the background), so a burst of messages doesn't write the file each time. */
	static void save() {
		dirty = true;
	}

	/** Writes the friends file now. The JSON is made here (game thread); the file is written in the background. */
	public static void flush() {
		if (!dirty) {
			return;
		}
		dirty = false;
		lastSaved = System.currentTimeMillis();
		String json = GSON.toJson(data);
		com.javahost.util.IoExecutor.run(() -> write(json));
	}

	/** Writes any unsaved changes before the game closes. */
	public static void flushNow() {
		if (dirty) {
			dirty = false;
			write(GSON.toJson(data));
		}
	}

	private static synchronized void write(String json) {
		try {
			Files.createDirectories(FILE.getParent());
			Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			Files.writeString(temp, json);
			Files.move(temp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not save friends list", e);
		}
	}
}

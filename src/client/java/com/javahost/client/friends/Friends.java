package com.javahost.client.friends;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import com.javahost.client.Toasts;
import com.javahost.util.FriendCrypto;
import com.javahost.util.Mailbox;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Friends, friend requests and invites. Messages travel through each player's {@link Mailbox}:
 * <ul>
 * <li><b>request</b>/<b>accept</b>: signed with the sender's Mojang-certified key (proves who sent it) and carrying
 * their X25519 key, so both sides can derive a shared secret.</li>
 * <li><b>invite</b>/<b>remove</b>: encrypted and authenticated with that shared secret.</li>
 * </ul>
 */
public final class Friends {
	public record Friend(UUID id, String name, String box, long since) {
	}

	public record Request(UUID id, String name, String box, long time) {
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

	private static final class Data {
		List<Friend> friends = new ArrayList<>();
		List<Request> incoming = new ArrayList<>();
		List<Outgoing> outgoing = new ArrayList<>();
		String lastMessageId = "";
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("javahost-friends.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long MAX_AGE_MS = 12 * 60 * 60 * 1000L;
	private static final String NAMESPACE = System.getProperty("javahost.friendsNamespace", "");

	private static Data data = new Data();
	private static Mailbox mailbox;
	private static final Set<String> SEEN = new HashSet<>();
	private static final List<Runnable> LISTENERS = new ArrayList<>();
	private static Consumer<Invite> inviteHandler = invite -> {
	};
	private static Consumer<Request> requestHandler = request -> {
	};

	private Friends() {
	}

	// ================================================================ setup

	/** Loads saved friends and starts listening to this player's mailbox. Call once the game has started. */
	public static void start(Consumer<Invite> onInvite, Consumer<Request> onRequest) {
		inviteHandler = onInvite;
		requestHandler = onRequest;
		load();
		pushAllowedPlayers();
		mailbox = new Mailbox(FriendCrypto.mailboxFor(Identity.id(), NAMESPACE));
		String since = data.lastMessageId.isEmpty() ? "12h" : data.lastMessageId;
		mailbox.start(since, (id, text) -> Minecraft.getInstance().execute(() -> {
			data.lastMessageId = id;
			receive(text);
			save();
		}), status -> Minecraft.getInstance().execute(Friends::changed));
	}

	public static Mailbox.Status status() {
		return mailbox == null ? Mailbox.Status.CONNECTING : mailbox.status();
	}

	/** Called whenever the friend list, requests or mailbox status change (UI refresh). */
	public static void onChange(Runnable listener) {
		LISTENERS.add(listener);
	}

	public static void removeListener(Runnable listener) {
		LISTENERS.remove(listener);
	}

	private static void changed() {
		pushAllowedPlayers();
		new ArrayList<>(LISTENERS).forEach(Runnable::run);
	}

	/** Friends may join our hosted world; everyone else is turned away when "friends only" is on. */
	private static void pushAllowedPlayers() {
		Set<UUID> allowed = new HashSet<>();
		data.friends.forEach(f -> allowed.add(f.id()));
		HostState.setAllowedPlayers(allowed);
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
				return sendSigned("request", id, name).thenApply(ok -> {
					if (!ok) {
						return "Couldn't reach the friends service. Check your internet.";
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

	public static CompletableFuture<Boolean> accept(Request request) {
		return sendSigned("accept", request.id(), request.name()).thenApply(ok -> {
			if (ok) {
				Minecraft.getInstance().execute(() -> {
					becomeFriends(request.id(), request.name(), request.box());
					data.incoming.removeIf(r -> r.id().equals(request.id()));
					save();
					changed();
				});
			}
			return ok;
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

	public static void remove(Friend friend) {
		sendSealed(friend, "remove", new JsonObject());
		data.friends.removeIf(f -> f.id().equals(friend.id()));
		save();
		changed();
	}

	/** Sends an encrypted invite with the ways to reach our world. */
	public static CompletableFuture<Boolean> sendInvite(Friend friend, String world, List<RouteInfo> routes, String hostIp) {
		JsonObject body = new JsonObject();
		body.addProperty("world", world);
		body.addProperty("hostIp", hostIp == null ? "" : hostIp);
		body.addProperty("expires", System.currentTimeMillis() + 15 * 60 * 1000L);
		JsonArray list = new JsonArray();
		for (RouteInfo route : routes) {
			JsonObject r = new JsonObject();
			r.addProperty("name", route.name());
			r.addProperty("state", route.state());
			r.addProperty("address", route.address());
			list.add(r);
		}
		body.add("routes", list);
		return sendSealed(friend, "invite", body);
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
		return String.join("\n", "javahost-v1", str(m, "type"), str(m, "id"), str(m, "from"), str(m, "name"), str(m, "to"),
				String.valueOf(m.get("ts").getAsLong()), str(m, "box")).getBytes(StandardCharsets.UTF_8);
	}

	private static String context(JsonObject m) {
		return String.join("|", str(m, "type"), str(m, "id"), str(m, "from"), str(m, "to"), String.valueOf(m.get("ts").getAsLong()));
	}

	private static CompletableFuture<Boolean> sendSigned(String type, UUID to, String toName) {
		JsonObject message = header(type, to);
		message.addProperty("box", Identity.boxPublicKey());
		return Identity.sign(message, signedPayload(message)).thenCompose(signed -> signed
				? Mailbox.post(FriendCrypto.mailboxFor(to, NAMESPACE), message.toString())
				: CompletableFuture.completedFuture(false))
				.exceptionally(e -> false);
	}

	private static CompletableFuture<Boolean> sendSealed(Friend friend, String type, JsonObject body) {
		try {
			JsonObject message = header(type, friend.id());
			byte[] key = Identity.sharedKeyWith(friend.id(), friend.box());
			message.addProperty("data", FriendCrypto.seal(key, body.toString(), context(message)));
			return Mailbox.post(FriendCrypto.mailboxFor(friend.id(), NAMESPACE), message.toString()).exceptionally(e -> false);
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not send {} to {}", type, friend.name(), e);
			return CompletableFuture.completedFuture(false);
		}
	}

	// ================================================================ receiving

	private static void receive(String text) {
		try {
			JsonObject m = JsonParser.parseString(text).getAsJsonObject();
			if (m.get("v").getAsInt() != 1 || !SEEN.add(str(m, "id"))) {
				return;
			}
			UUID from = UUID.fromString(str(m, "from"));
			UUID to = UUID.fromString(str(m, "to"));
			long ts = m.get("ts").getAsLong();
			long now = System.currentTimeMillis();
			if (!to.equals(Identity.id()) || from.equals(Identity.id()) || ts < now - MAX_AGE_MS || ts > now + 10 * 60 * 1000L) {
				return;
			}
			String name = str(m, "name");
			if (!name.matches("[A-Za-z0-9_]{1,16}")) {
				return;
			}
			switch (str(m, "type")) {
				case "request" -> {
					if (!Identity.verify(from, m, signedPayload(m))) {
						return;
					}
					onRequest(from, name, str(m, "box"));
				}
				case "accept" -> {
					if (!Identity.verify(from, m, signedPayload(m))) {
						return;
					}
					onAccept(from, name, str(m, "box"));
				}
				case "invite", "remove" -> {
					Optional<Friend> friend = friend(from);
					if (friend.isEmpty()) {
						return;
					}
					// Decrypting also proves it came from this friend (only the two of us have the key).
					String plain = FriendCrypto.open(Identity.sharedKeyWith(from, friend.get().box()), str(m, "data"), context(m));
					JsonObject body = JsonParser.parseString(plain).getAsJsonObject();
					if (str(m, "type").equals("remove")) {
						data.friends.removeIf(f -> f.id().equals(from));
						changed();
					} else {
						onInvite(friend.get(), body);
					}
				}
				default -> {
				}
			}
		} catch (Exception e) {
			JavaHost.LOGGER.debug("Ignored a mailbox message", e);
		}
	}

	private static void onRequest(UUID from, String name, String box) {
		if (friend(from).isPresent()) {
			// They probably reinstalled; confirm again with our key.
			becomeFriends(from, name, box);
			sendSigned("accept", from, name);
			return;
		}
		if (data.outgoing.stream().anyMatch(o -> o.id().equals(from))) {
			// We both asked: that's a yes.
			Request mutual = new Request(from, name, box, System.currentTimeMillis());
			accept(mutual);
			return;
		}
		data.incoming.removeIf(r -> r.id().equals(from));
		Request request = new Request(from, name, box, System.currentTimeMillis());
		data.incoming.add(request);
		changed();
		requestHandler.accept(request);
	}

	private static void onAccept(UUID from, String name, String box) {
		boolean wanted = data.outgoing.stream().anyMatch(o -> o.id().equals(from)) || friend(from).isPresent();
		if (!wanted) {
			return;
		}
		boolean isNew = friend(from).isEmpty();
		becomeFriends(from, name, box);
		data.outgoing.removeIf(o -> o.id().equals(from));
		changed();
		if (isNew) {
			Toasts.player(from, name + " accepted!", "You're friends now");
		}
	}

	private static void onInvite(Friend friend, JsonObject body) {
		List<RouteInfo> routes = new ArrayList<>();
		for (JsonElement element : body.getAsJsonArray("routes")) {
			JsonObject r = element.getAsJsonObject();
			routes.add(new RouteInfo(str(r, "name"), str(r, "state"), str(r, "address")));
		}
		Invite invite = new Invite(friend.id(), friend.name(), str(body, "world"), routes, str(body, "hostIp"), body.get("expires").getAsLong());
		if (!invite.expired() && !routes.isEmpty()) {
			inviteHandler.accept(invite);
		}
	}

	private static void becomeFriends(UUID id, String name, String box) {
		try {
			FriendCrypto.decodePublic(box); // reject garbage keys up front
		} catch (Exception e) {
			return;
		}
		data.friends.removeIf(f -> f.id().equals(id));
		data.friends.add(new Friend(id, name, box, System.currentTimeMillis()));
		data.friends.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		save();
	}

	private static String str(JsonObject object, String key) {
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
			}
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not read friends list", e);
		}
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(data, writer);
			}
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not save friends list", e);
		}
	}
}

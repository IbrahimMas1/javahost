package com.javahost.client.discover;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.JavaHost;
import com.javahost.client.friends.Identity;
import com.javahost.util.IoExecutor;
import com.javahost.util.ListingText;
import com.javahost.util.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * The JavaHost public world list (a small service: directory/ in this repo). Signing in: the list gives a one-time
 * code, and the game signs it with its Mojang-certified chat key (the one Minecraft signs chat with); the list checks
 * Mojang's certificate. So nobody can list a world or report from someone else's account.
 */
public final class Directory {
	/** Where the list lives (the Worker in directory/, on Cloudflare). */
	static final String SERVICE = "https://javahost-directory.javahost-ibrahim.workers.dev";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).executor(IoExecutor.NET).build();

	/** A world someone opened to the public. */
	public record PublicWorld(UUID host, String name, String title, String description, List<String> tags, String mode, String difficulty,
			int max, int players, String relay, String mc, String jv, String region, boolean voice, String signKey, int mods) {
		public boolean full() {
			return players >= max;
		}
	}

	/** What the list service said: ok, or why not (shown to the player). */
	public record Result(boolean ok, String message, JsonObject body) {
	}

	/** Hosts' real names, looked up with Mojang from this computer (a listing's name is what the host's game said). */
	private static final java.util.Map<UUID, String> REAL_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

	/** The host's name as Mojang knows it, once looked up; until then, the name in the listing. */
	public static String nameOf(PublicWorld world) {
		return REAL_NAMES.getOrDefault(world.host(), world.name());
	}

	/** Looks up the real names of hosts we haven't checked yet. */
	public static void checkNames(List<PublicWorld> worlds) {
		for (PublicWorld world : worlds) {
			if (REAL_NAMES.putIfAbsent(world.host(), world.name()) == null) {
				Identity.nameOf(world.host()).thenAccept(real -> real.ifPresent(name -> REAL_NAMES.put(world.host(), name)));
			}
		}
	}

	private static String token;
	private static long tokenUntil;

	private Directory() {
	}

	/** The service's address: the real one, or (development only) a local one for the automated tests. */
	static String base() {
		String test = System.getProperty("javahost.directoryUrl");
		if (test != null && FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return test;
		}
		return SERVICE;
	}

	public static boolean available() {
		return !base().isEmpty();
	}

	/** Parental controls or a Mojang multiplayer ban: then public worlds stay off for this account. */
	public static String blockedReason() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.allowsMultiplayer() || minecraft.multiplayerBan() != null) {
			return "Online play is turned off for this account, so public worlds aren't available.";
		}
		return null;
	}

	// ---------------------------------------------------------------- reading

	public static CompletableFuture<List<PublicWorld>> list() {
		return send("GET", "/listings", null, false).thenApply(result -> {
			List<PublicWorld> worlds = new ArrayList<>();
			if (!result.ok() || !result.body().has("worlds")) {
				return worlds;
			}
			for (JsonElement element : result.body().getAsJsonArray("worlds")) {
				try {
					worlds.add(parse(element.getAsJsonObject()));
				} catch (RuntimeException e) {
					JavaHost.LOGGER.debug("Skipped a malformed public world", e);
				}
			}
			return worlds;
		});
	}

	/** One listing, checked again here: only relay addresses, cleaned text, sane numbers. */
	static PublicWorld parse(JsonObject o) {
		String relay = o.get("relay").getAsString();
		if (!ListingText.isRelayAddress(relay)) {
			throw new IllegalArgumentException("not a relay address");
		}
		List<String> tags = new ArrayList<>();
		if (o.has("tags")) {
			for (JsonElement tag : o.getAsJsonArray("tags")) {
				if (ListingText.TAGS.contains(tag.getAsString())) {
					tags.add(tag.getAsString());
				}
			}
		}
		int max = Math.max(1, Math.min(16, o.get("max").getAsInt()));
		return new PublicWorld(UUID.fromString(o.get("uuid").getAsString()), Text.clean(o.get("name").getAsString(), 16),
				ListingText.clean(o.get("title").getAsString(), ListingText.TITLE_MAX),
				ListingText.clean(o.get("description").getAsString(), ListingText.DESCRIPTION_MAX), List.copyOf(tags),
				o.get("mode").getAsString(), o.get("difficulty").getAsString(), max,
				Math.max(0, Math.min(max, o.get("players").getAsInt())), relay, Text.clean(o.get("mc").getAsString(), 16),
				Text.clean(o.get("jv").getAsString(), 16), ListingText.region(relay), o.get("voice").getAsBoolean(),
				o.get("signkey").getAsString(), o.has("mods") && !o.get("mods").isJsonNull() ? Math.max(0, Math.min(1000, o.get("mods").getAsInt())) : 0);
	}

	// ---------------------------------------------------------------- signed-in calls

	public static CompletableFuture<Result> publish(JsonObject listing) {
		return signedIn().thenCompose(ok -> ok.ok() ? send("PUT", "/listing", listing, true) : CompletableFuture.completedFuture(ok));
	}

	public static CompletableFuture<Result> heartbeat(int players) {
		JsonObject body = new JsonObject();
		body.addProperty("players", players);
		return signedIn().thenCompose(ok -> ok.ok() ? send("POST", "/listing/heartbeat", body, true) : CompletableFuture.completedFuture(ok));
	}

	public static CompletableFuture<Result> unpublish() {
		return signedIn().thenCompose(ok -> ok.ok() ? send("DELETE", "/listing", null, true) : CompletableFuture.completedFuture(ok));
	}

	public static CompletableFuture<Result> report(UUID host, String reason) {
		JsonObject body = new JsonObject();
		body.addProperty("host", host.toString());
		body.addProperty("reason", reason);
		return signedIn().thenCompose(ok -> ok.ok() ? send("POST", "/report", body, true) : CompletableFuture.completedFuture(ok));
	}

	/** Signs in once (the token lasts 12 hours). */
	private static synchronized CompletableFuture<Result> signedIn() {
		if (token != null && System.currentTimeMillis() < tokenUntil) {
			return CompletableFuture.completedFuture(new Result(true, "", new JsonObject()));
		}
		return send("POST", "/auth/challenge", new JsonObject(), false).thenCompose(challenge -> {
			if (!challenge.ok()) {
				return CompletableFuture.completedFuture(challenge);
			}
			String serverId = challenge.body().get("serverId").getAsString();
			JsonObject verify = new JsonObject();
			verify.addProperty("name", Identity.name());
			verify.addProperty("uuid", Identity.id().toString());
			verify.addProperty("serverId", serverId);
			verify.addProperty("expires", challenge.body().get("expires").getAsLong());
			// Sign the one-time code with our Mojang-certified chat key; the list checks Mojang's certificate.
			byte[] payload = String.join("\n", "javahost-directory-v1", serverId, Identity.id().toString(), Identity.name())
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			return Identity.sign(verify, payload).thenCompose(signed -> {
				if (!signed && !FabricLoader.getInstance().isDevelopmentEnvironment()) {
					return CompletableFuture.completedFuture(new Result(false,
							"Public worlds need you signed in to Minecraft with a Microsoft account.", new JsonObject()));
				}
				return signIn(verify);
			});
		});
	}

	private static CompletableFuture<Result> signIn(JsonObject verify) {
		return send("POST", "/auth/verify", verify, false).thenApply(result -> {
			if (result.ok()) {
				synchronized (Directory.class) {
					token = result.body().get("token").getAsString();
					tokenUntil = System.currentTimeMillis() + 11 * 60 * 60 * 1000L;
				}
			}
			return result;
		});
	}

	private static CompletableFuture<Result> send(String method, String path, JsonObject body, boolean signed) {
		if (!available()) {
			return CompletableFuture.completedFuture(new Result(false, "Public worlds aren't available yet.", new JsonObject()));
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15))
				.header("content-type", "application/json").header("user-agent", "JavaHost/" + JavaHost.VERSION)
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
		if (signed) {
			synchronized (Directory.class) {
				request.header("authorization", "Bearer " + token);
			}
		}
		return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			JsonObject json;
			try {
				JsonElement parsed = JsonParser.parseString(response.body());
				json = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
			} catch (RuntimeException e) {
				json = new JsonObject();
			}
			if (response.statusCode() == 401 && signed) {
				synchronized (Directory.class) {
					token = null; // signed out (e.g. the token expired): next call signs in again
				}
			}
			boolean ok = response.statusCode() / 100 == 2;
			String message = ok ? "" : json.has("error") ? capitalise(json.get("error").getAsString()) : "The public list didn't answer (" + response.statusCode() + ").";
			return new Result(ok, message, json);
		}).exceptionally(e -> new Result(false, "Couldn't reach the public list. Check your internet.", new JsonObject()));
	}

	private static String capitalise(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1) + (text.endsWith(".") ? "" : ".");
	}

	/** For the tests: forget the sign-in. */
	static synchronized void signOut() {
		token = null;
	}

	static JsonArray tagsJson(List<String> tags) {
		JsonArray array = new JsonArray();
		tags.forEach(array::add);
		return array;
	}
}

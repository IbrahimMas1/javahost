package com.javahost.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minecraft's own profile service, as the launcher uses it (see minecraft.wiki, "Mojang API"):
 * <ul>
 * <li>{@code GET /minecraft/profile}: the account's skins (url, variant, ACTIVE) and capes (id, alias, url, ACTIVE)</li>
 * <li>{@code POST /minecraft/profile/skins}: multipart, {@code variant} = classic|slim and {@code file} = the PNG</li>
 * <li>{@code DELETE /minecraft/profile/skins/active}: back to the default skin</li>
 * <li>{@code PUT /minecraft/profile/capes/active} {"capeId"}, {@code DELETE /minecraft/profile/capes/active}</li>
 * <li>{@code GET /minecraft/profile/lookup/name/<name>}: a player's id (public)</li>
 * <li>{@code GET sessionserver.mojang.com/session/minecraft/profile/<id>}: their textures (public)</li>
 * </ul>
 * <p>
 * The access token is a secret. It is only ever put on requests to {@code https://api.minecraftservices.com}
 * ({@link #mayCarryToken}), redirects are never followed, and it never appears in messages or exceptions. Skin
 * pictures are only downloaded from {@code textures.minecraft.net}, over HTTPS, size-capped. In development a test
 * server can stand in for all of it ({@link #dev}); then a fixed dummy token is sent instead of the real one.
 */
public final class SkinApi {
	public static final String SERVICES = "https://api.minecraftservices.com";
	public static final String SESSION = "https://sessionserver.mojang.com";
	public static final String TOKEN_HOST = "api.minecraftservices.com";
	public static final String TEXTURE_HOST = "textures.minecraft.net";
	/** What a test server gets instead of a token. */
	public static final String DEV_TOKEN = "javahost-test-token";
	private static final int MAX_JSON = 256 * 1024;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final SecureRandom RANDOM = new SecureRandom();

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.executor(IoExecutor.NET)
			.build();

	/** What a call was for, so a status code can be put into words that fit. */
	public enum Op { PROFILE, UPLOAD, RESET, CAPE, LOOKUP, TEXTURE }

	/** A call that didn't work; the message is for the player (never contains the token). */
	public static final class Failure extends Exception {
		private final int status;

		public Failure(String message, int status) {
			super(message, null, false, false);
			this.status = status;
		}

		/** The HTTP status, or 0 when the service couldn't be reached. */
		public int status() {
			return status;
		}
	}

	public record Cape(String id, String alias, String url, boolean active) {
	}

	/** The account's profile: its active skin (null url = the default skin) and capes. */
	public record Profile(String id, String name, String skinUrl, boolean slim, List<Cape> capes) {
		public Cape activeCape() {
			return capes.stream().filter(Cape::active).findFirst().orElse(null);
		}
	}

	/** A player's skin, from their public profile (null url: the default skin). */
	public record Textures(String skinUrl, boolean slim) {
	}

	private final String services;
	private final String session;
	private final String token;
	/** Set for a development test server: everything goes there, with {@link #DEV_TOKEN}. */
	private final URI dev;

	SkinApi(String services, String session, String token, URI dev) {
		this.services = services;
		this.session = session;
		this.token = token;
		this.dev = dev;
	}

	/** The real services, signed in with the game's access token (may be null when signed out). */
	public static SkinApi real(String token) {
		return new SkinApi(SERVICES, SESSION, token, null);
	}

	/** A development test server at {@code base} standing in for every Mojang address. */
	public static SkinApi dev(String base) {
		String clean = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		return new SkinApi(clean, clean, null, URI.create(clean));
	}

	public boolean isDev() {
		return dev != null;
	}

	/** Whether {@code token} looks like a real Microsoft-account session (a JWT), not an offline or dev account's filler. */
	public static boolean looksLikeSession(String token) {
		return token != null && token.length() > 100 && token.chars().filter(c -> c == '.').count() == 2 && !token.contains(" ");
	}

	/** The only place a real token may go: Minecraft's profile service, over HTTPS, on its normal port. */
	public static boolean mayCarryToken(URI uri) {
		return uri != null && "https".equals(uri.getScheme()) && TOKEN_HOST.equalsIgnoreCase(uri.getHost())
				&& (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
	}

	/** The header value for a request to {@code uri}, or null when it must go without one. */
	String authorization(URI uri) {
		if (dev != null) {
			return sameOrigin(uri, dev) ? "Bearer " + DEV_TOKEN : null;
		}
		return token != null && !token.isEmpty() && mayCarryToken(uri) ? "Bearer " + token : null;
	}

	private static boolean sameOrigin(URI a, URI b) {
		return a.getScheme() != null && a.getScheme().equals(b.getScheme()) && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
				&& a.getPort() == b.getPort();
	}

	// ---------------------------------------------------------------- the account (needs the token)

	public Profile profile() throws Failure {
		return parseProfile(call(Op.PROFILE, request(services + "/minecraft/profile", true).GET()));
	}

	/** Wears {@code png} (a 64x64 skin) with classic or slim arms. */
	public Profile uploadSkin(byte[] png, boolean slim) throws Failure {
		Multipart body = multipart(slim ? "slim" : "classic", png);
		return parseProfile(call(Op.UPLOAD, request(services + "/minecraft/profile/skins", true)
				.timeout(Duration.ofSeconds(40))
				.header("Content-Type", body.contentType())
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.body()))));
	}

	/** Back to Minecraft's default skin. */
	public Profile resetSkin() throws Failure {
		return parseProfile(call(Op.RESET, request(services + "/minecraft/profile/skins/active", true).DELETE()));
	}

	public Profile showCape(String capeId) throws Failure {
		JsonObject body = new JsonObject();
		body.addProperty("capeId", capeId);
		return parseProfile(call(Op.CAPE, request(services + "/minecraft/profile/capes/active", true)
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body.toString()))));
	}

	public Profile hideCape() throws Failure {
		return parseProfile(call(Op.CAPE, request(services + "/minecraft/profile/capes/active", true).DELETE()));
	}

	// ---------------------------------------------------------------- anyone's skin (public, no token)

	/** A Minecraft name as typed, if it could be one (letters, digits, underscore; 1-16), else null. */
	public static String validName(String name) {
		String trimmed = name == null ? "" : name.strip();
		return NAME.matcher(trimmed).matches() ? trimmed : null;
	}

	/** The id of the player called {@code name}. */
	public UUID lookup(String name) throws Failure {
		String valid = validName(name);
		if (valid == null) {
			throw new Failure("That isn't a Minecraft name.", 400);
		}
		JsonObject o = call(Op.LOOKUP, request(services + "/minecraft/profile/lookup/name/" + URLEncoder.encode(valid, StandardCharsets.UTF_8), false).GET());
		try {
			return parseUuid(o.get("id").getAsString());
		} catch (RuntimeException e) {
			throw new Failure("Minecraft's answer didn't make sense. Try again later.", 200);
		}
	}

	/** A player's skin address and arms, from their public profile. */
	public Textures textures(UUID id) throws Failure {
		String plain = id.toString().replace("-", "");
		JsonObject o = call(Op.LOOKUP, request(session + "/session/minecraft/profile/" + plain, false).GET());
		return parseTextures(o);
	}

	/** Downloads a skin picture from Minecraft's texture server (or the test server). */
	public byte[] download(String url) throws Failure {
		URI uri = textureUri(url);
		if (uri == null) {
			throw new Failure("That skin isn't on Minecraft's skin server.", 0);
		}
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", "JavaHost").GET().build();
		try {
			HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = response.body()) {
				if (response.statusCode() != 200) {
					throw new Failure(message(Op.TEXTURE, response.statusCode()), response.statusCode());
				}
				byte[] data = in.readNBytes(SkinImage.MAX_BYTES + 1);
				if (data.length > SkinImage.MAX_BYTES) {
					throw new Failure("That skin file is too big.", 200);
				}
				return data;
			}
		} catch (IOException e) {
			throw new Failure(message(Op.TEXTURE, 0), 0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Failure(message(Op.TEXTURE, 0), 0);
		}
	}

	/**
	 * Where a skin picture may be fetched from: Minecraft's texture server (an http address from Mojang is
	 * upgraded to https), or in development the test server. Null for anywhere else.
	 */
	URI textureUri(String url) {
		if (url == null || url.length() > 300) {
			return null;
		}
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawPath() == null) {
			return null;
		}
		if (dev != null) {
			return sameOrigin(uri, dev) ? uri : null;
		}
		if (!TEXTURE_HOST.equalsIgnoreCase(uri.getHost()) || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
				|| (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) || !uri.getRawPath().startsWith("/texture/")
				|| uri.getRawQuery() != null) {
			return null;
		}
		return URI.create("https://" + TEXTURE_HOST + uri.getRawPath());
	}

	// ---------------------------------------------------------------- plumbing

	private HttpRequest.Builder request(String url, boolean auth) throws Failure {
		URI uri = URI.create(url);
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", "JavaHost")
				.header("Accept", "application/json");
		if (auth) {
			String header = authorization(uri);
			if (header == null) {
				throw new Failure("Sign in with Microsoft to wear skins.", 401);
			}
			builder.header("Authorization", header);
		}
		return builder;
	}

	private static JsonObject call(Op op, HttpRequest.Builder builder) throws Failure {
		HttpResponse<InputStream> response;
		try {
			response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			throw new Failure(message(op, 0), 0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Failure(message(op, 0), 0);
		}
		int status = response.statusCode();
		byte[] data;
		try (InputStream in = response.body()) {
			data = in.readNBytes(MAX_JSON + 1);
		} catch (IOException e) {
			throw new Failure(message(op, 0), 0);
		}
		if (status == 204 && op == Op.LOOKUP) {
			throw new Failure(message(op, 404), 404); // the session server's "no such player"
		}
		if (status < 200 || status >= 300) {
			throw new Failure(message(op, status), status);
		}
		if (data.length > MAX_JSON) {
			throw new Failure("Minecraft's answer was too long. Try again later.", status);
		}
		try {
			JsonElement parsed = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				throw new IllegalStateException();
			}
			return parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			throw new Failure("Minecraft's answer didn't make sense. Try again later.", status);
		}
	}

	/** A status code in the player's words. 0 = couldn't reach the service at all. */
	public static String message(Op op, int status) {
		boolean change = op == Op.UPLOAD || op == Op.RESET || op == Op.CAPE;
		if (status == 0) {
			return "Can't reach Minecraft's skin service. Check your internet connection.";
		}
		if (status == 401) {
			return "Your Minecraft session has expired. Restart the game.";
		}
		if (status == 429) {
			return change ? "Minecraft allows only a few skin changes a minute. Try again shortly."
					: "Too many lookups at once. Try again in a minute.";
		}
		if (status >= 500) {
			return "Minecraft's skin service isn't answering right now. Try again later.";
		}
		if (status >= 300 && status < 400) {
			return "Minecraft's skin service answered oddly. Try again later.";
		}
		return switch (op) {
			case UPLOAD -> status == 400 ? "Minecraft didn't accept this skin. It must be a 64×64 PNG."
					: status == 403 ? "This account can't change its skin right now." : "Minecraft didn't accept the change (" + status + ").";
			case CAPE -> status == 400 ? "That cape isn't on your account." : "Minecraft didn't accept the change (" + status + ").";
			case RESET -> "Minecraft didn't accept the change (" + status + ").";
			case LOOKUP -> status == 404 || status == 400 ? "There's no Minecraft player with that name." : "Couldn't look that player up (" + status + ").";
			case TEXTURE -> "Couldn't download that skin (" + status + ").";
			case PROFILE -> status == 404 ? "This account doesn't own Minecraft." : status == 403 ? "Minecraft won't show this account's skins right now."
					: "Couldn't load your skins from Minecraft (" + status + ").";
		};
	}

	/** A multipart/form-data body. */
	public record Multipart(byte[] body, String contentType) {
	}

	/** The upload body: {@code variant} then {@code file} (the PNG), as Minecraft's skin service expects. */
	public static Multipart multipart(String variant, byte[] png) {
		byte[] random = new byte[12];
		RANDOM.nextBytes(random);
		String boundary = "JavaHostBoundary" + HexFormat.of().formatHex(random);
		ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
		String crlf = "\r\n";
		write(out, "--" + boundary + crlf + "Content-Disposition: form-data; name=\"variant\"" + crlf + crlf + variant + crlf);
		write(out, "--" + boundary + crlf + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"" + crlf
				+ "Content-Type: image/png" + crlf + crlf);
		out.writeBytes(png);
		write(out, crlf + "--" + boundary + "--" + crlf);
		return new Multipart(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
	}

	private static void write(ByteArrayOutputStream out, String text) {
		out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
	}

	static Profile parseProfile(JsonObject o) throws Failure {
		try {
			String skinUrl = null;
			boolean slim = false;
			JsonElement skins = o.get("skins");
			if (skins != null && skins.isJsonArray()) {
				for (JsonElement element : skins.getAsJsonArray()) {
					JsonObject skin = element.getAsJsonObject();
					if ("ACTIVE".equalsIgnoreCase(string(skin, "state"))) {
						skinUrl = string(skin, "url");
						slim = "slim".equalsIgnoreCase(string(skin, "variant"));
					}
				}
			}
			List<Cape> capes = new ArrayList<>();
			JsonElement list = o.get("capes");
			if (list != null && list.isJsonArray()) {
				for (JsonElement element : list.getAsJsonArray()) {
					if (capes.size() >= 64) {
						break;
					}
					JsonObject cape = element.getAsJsonObject();
					String id = string(cape, "id");
					if (id.isEmpty() || id.length() > 64 || !id.matches("[0-9A-Za-z-]+")) {
						continue;
					}
					String alias = Text.clean(string(cape, "alias"), 32);
					capes.add(new Cape(id, alias.isEmpty() ? "Cape" : prettyAlias(alias), string(cape, "url"), "ACTIVE".equalsIgnoreCase(string(cape, "state"))));
				}
			}
			return new Profile(Text.clean(string(o, "id"), 40), Text.clean(string(o, "name"), 16), skinUrl == null || skinUrl.isEmpty() ? null : skinUrl,
					slim, List.copyOf(capes));
		} catch (RuntimeException e) {
			throw new Failure("Minecraft's answer didn't make sense. Try again later.", 200);
		}
	}

	/** "MIGRATOR" or "migrator" → "Migrator"; "pan" → "Pan". */
	static String prettyAlias(String alias) {
		String lower = alias.replace('_', ' ').toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder();
		boolean start = true;
		for (char c : lower.toCharArray()) {
			out.append(start ? Character.toUpperCase(c) : c);
			start = c == ' ';
		}
		return out.toString();
	}

	static Textures parseTextures(JsonObject o) throws Failure {
		try {
			JsonElement properties = o.get("properties");
			if (properties != null && properties.isJsonArray()) {
				for (JsonElement element : properties.getAsJsonArray()) {
					JsonObject p = element.getAsJsonObject();
					if (!"textures".equals(string(p, "name"))) {
						continue;
					}
					String value = string(p, "value");
					if (value.length() > 16 * 1024) {
						break;
					}
					JsonObject decoded = JsonParser.parseString(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).getAsJsonObject();
					JsonObject textures = decoded.getAsJsonObject("textures");
					JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
					if (skin == null) {
						return new Textures(null, false);
					}
					JsonObject metadata = skin.has("metadata") && skin.get("metadata").isJsonObject() ? skin.getAsJsonObject("metadata") : null;
					return new Textures(string(skin, "url"), metadata != null && "slim".equals(string(metadata, "model")));
				}
			}
			return new Textures(null, false);
		} catch (RuntimeException e) {
			throw new Failure("Minecraft's answer didn't make sense. Try again later.", 200);
		}
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	static UUID parseUuid(String text) {
		String plain = text.replace("-", "");
		if (!plain.matches("[0-9a-fA-F]{32}")) {
			throw new IllegalArgumentException("not a uuid");
		}
		return new UUID(Long.parseUnsignedLong(plain.substring(0, 16), 16), Long.parseUnsignedLong(plain.substring(16), 16));
	}
}

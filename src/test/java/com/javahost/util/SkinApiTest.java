package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

/** Minecraft's skin service: the upload body, errors in words, and where the access token may (never) go. */
class SkinApiTest {
	/** Made up: shaped like a session token, never a real one. */
	private static final String TOKEN = "eyJ" + "a".repeat(60) + ".eyJ" + "b".repeat(60) + "." + "c".repeat(40);

	@Test
	void theUploadBodyHasTheVariantAndTheFile() {
		byte[] png = SkinLibraryTest.png(1);
		SkinApi.Multipart body = SkinApi.multipart("slim", png);
		assertTrue(body.contentType().startsWith("multipart/form-data; boundary="));
		String boundary = body.contentType().substring(body.contentType().indexOf('=') + 1);
		String text = new String(body.body(), StandardCharsets.ISO_8859_1);
		assertTrue(text.startsWith("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\nslim\r\n"));
		assertTrue(text.contains("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n"));
		assertTrue(text.endsWith("\r\n--" + boundary + "--\r\n"));
		int start = text.indexOf("Content-Type: image/png\r\n\r\n") + "Content-Type: image/png\r\n\r\n".length();
		byte[] file = java.util.Arrays.copyOfRange(body.body(), start, start + png.length);
		assertArrayEquals(png, file);
		assertFalse(new String(png, StandardCharsets.ISO_8859_1).contains(boundary));
	}

	@Test
	void statusCodesBecomePlainWords() {
		assertEquals("Your Minecraft session has expired. Restart the game.", SkinApi.message(SkinApi.Op.UPLOAD, 401));
		assertEquals("Minecraft allows only a few skin changes a minute. Try again shortly.", SkinApi.message(SkinApi.Op.UPLOAD, 429));
		assertTrue(SkinApi.message(SkinApi.Op.UPLOAD, 400).contains("64×64"));
		assertTrue(SkinApi.message(SkinApi.Op.UPLOAD, 0).contains("internet"));
		assertTrue(SkinApi.message(SkinApi.Op.LOOKUP, 404).contains("no Minecraft player"));
		assertTrue(SkinApi.message(SkinApi.Op.CAPE, 400).contains("cape"));
		assertTrue(SkinApi.message(SkinApi.Op.PROFILE, 503).contains("isn't answering"));
		for (SkinApi.Op op : SkinApi.Op.values()) {
			for (int status : new int[] {0, 301, 400, 401, 403, 404, 405, 429, 500}) {
				assertFalse(SkinApi.message(op, status).isBlank());
			}
		}
	}

	@Test
	void theTokenOnlyGoesToMinecraftsProfileService() {
		assertTrue(SkinApi.mayCarryToken(URI.create("https://api.minecraftservices.com/minecraft/profile")));
		assertTrue(SkinApi.mayCarryToken(URI.create("https://api.minecraftservices.com:443/minecraft/profile/skins")));
		for (String other : List.of("http://api.minecraftservices.com/minecraft/profile", "https://api.minecraftservices.com.example.com/x",
				"https://evil.example/api.minecraftservices.com", "https://user@api.minecraftservices.com/minecraft/profile",
				"https://api.minecraftservices.com:8443/minecraft/profile", "https://textures.minecraft.net/texture/abc",
				"https://sessionserver.mojang.com/session/minecraft/profile/x", "http://127.0.0.1:8080/minecraft/profile")) {
			assertFalse(SkinApi.mayCarryToken(URI.create(other)), other);
		}
		SkinApi real = SkinApi.real(TOKEN);
		assertEquals("Bearer " + TOKEN, real.authorization(URI.create("https://api.minecraftservices.com/minecraft/profile")));
		assertNull(real.authorization(URI.create("http://203.0.113.5/minecraft/profile")));
		assertNull(SkinApi.dev("http://127.0.0.1:1").authorization(URI.create("https://api.minecraftservices.com/minecraft/profile")),
				"the test server mode never sends anything to the real service");
	}

	@Test
	void sessionsAreToldFromOfflineAccounts() {
		assertTrue(SkinApi.looksLikeSession(TOKEN));
		assertFalse(SkinApi.looksLikeSession("0"));
		assertFalse(SkinApi.looksLikeSession("FabricMC"));
		assertFalse(SkinApi.looksLikeSession(null));
	}

	@Test
	void picturesComeOnlyFromTheTextureServer() {
		SkinApi real = SkinApi.real(TOKEN);
		assertEquals(URI.create("https://textures.minecraft.net/texture/0123abcd"), real.textureUri("http://textures.minecraft.net/texture/0123abcd"));
		assertEquals(URI.create("https://textures.minecraft.net/texture/0123abcd"), real.textureUri("https://textures.minecraft.net/texture/0123abcd"));
		for (String other : List.of("https://textures.minecraft.net.example.com/texture/x", "https://example.com/texture/x",
				"https://textures.minecraft.net/other/x", "file:///etc/passwd", "https://u@textures.minecraft.net/texture/x",
				"https://textures.minecraft.net:8443/texture/x", "https://textures.minecraft.net/texture/x?y=1", "not a url at all ::")) {
			assertNull(real.textureUri(other), other);
		}
		SkinApi dev = SkinApi.dev("http://127.0.0.1:4567");
		assertEquals(URI.create("http://127.0.0.1:4567/texture/a"), dev.textureUri("http://127.0.0.1:4567/texture/a"));
		assertNull(dev.textureUri("https://textures.minecraft.net/texture/a"));
	}

	@Test
	void redirectsAreNotFollowedAndTheTokenStaysHome() throws Exception {
		List<String> seenElsewhere = new CopyOnWriteArrayList<>();
		HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		elsewhere.createContext("/", exchange -> {
			seenElsewhere.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		elsewhere.start();
		List<String> seen = new CopyOnWriteArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/steal");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.start();
		try {
			SkinApi api = SkinApi.dev("http://127.0.0.1:" + server.getAddress().getPort());
			SkinApi.Failure failure = assertThrows(SkinApi.Failure.class, api::profile);
			assertEquals(302, failure.status());
			assertEquals(List.of("Bearer " + SkinApi.DEV_TOKEN), seen);
			assertTrue(seenElsewhere.isEmpty(), "the redirect was not followed");
			// Pictures aren't fetched through redirects either.
			assertThrows(SkinApi.Failure.class, () -> api.download("http://127.0.0.1:" + server.getAddress().getPort() + "/texture/x"));
			assertTrue(seenElsewhere.isEmpty());
		} finally {
			server.stop(0);
			elsewhere.stop(0);
		}
	}

	@Test
	void aRealTokenIsNeverSentToAnotherHost() throws Exception {
		List<String> seen = new CopyOnWriteArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			SkinApi misdirected = new SkinApi(base, base, TOKEN, null);
			SkinApi.Failure failure = assertThrows(SkinApi.Failure.class, () -> misdirected.uploadSkin(SkinLibraryTest.png(1), false));
			assertFalse(failure.getMessage().contains(TOKEN));
			assertTrue(seen.isEmpty(), "nothing was sent at all");
			misdirected.lookup("Steve123").toString(); // public calls go without a token
		} catch (SkinApi.Failure expected) {
			// the empty answer isn't a profile; what matters is the header
		} finally {
			server.stop(0);
		}
		assertTrue(seen.stream().allMatch("null"::equals), "no Authorization header: " + seen);
	}

	@Test
	void profilesAndTexturesAreRead() throws Exception {
		SkinApi.Profile profile = SkinApi.parseProfile(JsonParser.parseString("""
				{"id": "0123", "name": "Steve123",
				 "skins": [{"id": "s1", "state": "INACTIVE", "url": "http://textures.minecraft.net/texture/old", "variant": "CLASSIC"},
				           {"id": "s2", "state": "ACTIVE", "url": "http://textures.minecraft.net/texture/new", "variant": "SLIM"}],
				 "capes": [{"id": "c-1", "state": "ACTIVE", "url": "http://textures.minecraft.net/texture/cape", "alias": "MIGRATOR"},
				           {"id": "../bad", "state": "INACTIVE", "alias": "x"},
				           {"id": "c-2", "state": "INACTIVE", "url": "", "alias": "§cpan"}]}""").getAsJsonObject());
		assertEquals("http://textures.minecraft.net/texture/new", profile.skinUrl());
		assertTrue(profile.slim());
		assertEquals(2, profile.capes().size());
		assertEquals("Migrator", profile.activeCape().alias());
		assertEquals("Pan", profile.capes().get(1).alias());

		String textures = Base64.getEncoder().encodeToString("""
				{"timestamp": 1, "profileId": "x", "profileName": "AlexPlays",
				 "textures": {"SKIN": {"url": "http://textures.minecraft.net/texture/abc", "metadata": {"model": "slim"}}}}"""
				.getBytes(StandardCharsets.UTF_8));
		SkinApi.Textures parsed = SkinApi.parseTextures(JsonParser.parseString(
				"{\"id\": \"x\", \"name\": \"AlexPlays\", \"properties\": [{\"name\": \"textures\", \"value\": \"" + textures + "\"}]}").getAsJsonObject());
		assertEquals("http://textures.minecraft.net/texture/abc", parsed.skinUrl());
		assertTrue(parsed.slim());
		assertNull(SkinApi.parseTextures(JsonParser.parseString("{\"properties\": []}").getAsJsonObject()).skinUrl());
		assertThrows(SkinApi.Failure.class, () -> SkinApi.parseTextures(JsonParser.parseString(
				"{\"properties\": [{\"name\": \"textures\", \"value\": \"!!!not base64\"}]}").getAsJsonObject()));
	}

	@Test
	void namesAndIdsAreChecked() {
		assertEquals("Steve123", SkinApi.validName(" Steve123 "));
		assertNull(SkinApi.validName("../profile"));
		assertNull(SkinApi.validName("a".repeat(17)));
		assertNull(SkinApi.validName(""));
		assertEquals(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), SkinApi.parseUuid("0123456789abcdef0123456789abcdef"));
		assertThrows(IllegalArgumentException.class, () -> SkinApi.parseUuid("nope"));
	}
}

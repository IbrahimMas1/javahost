package com.javahost.client.friends;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.JavaHost;
import com.javahost.util.FriendCrypto;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.SignatureValidator;
import net.minecraft.util.Signer;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;

/**
 * Who this player is, provably. Friend requests are signed with the player's Mojang-issued chat key (the
 * same certificate Minecraft uses to sign chat), so nobody can send a request pretending to be someone else.
 * Also holds this player's long-lived X25519 key for encrypting messages between friends.
 */
public final class Identity {
	/** Development/test clients have no Microsoft account; this switch (tests only) accepts unsigned requests. */
	public static final boolean INSECURE_TEST_MODE = Boolean.getBoolean("javahost.insecureFriends");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("javahost-identity.json");
	private static KeyPair boxKeys;

	private Identity() {
	}

	public static UUID id() {
		return Minecraft.getInstance().getUser().getProfileId();
	}

	public static String name() {
		return Minecraft.getInstance().getUser().getName();
	}

	/** This player's X25519 key pair, created on first use and kept in the config folder. */
	public static synchronized KeyPair boxKeys() {
		if (boxKeys != null) {
			return boxKeys;
		}
		try {
			if (Files.exists(FILE)) {
				JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
				boxKeys = FriendCrypto.decodeKeyPair(json.get("public").getAsString(), json.get("private").getAsString());
				return boxKeys;
			}
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Friend key unreadable, making a new one", e);
		}
		boxKeys = FriendCrypto.newKeyPair();
		JsonObject json = new JsonObject();
		json.addProperty("public", FriendCrypto.encodePublic(boxKeys.getPublic()));
		json.addProperty("private", FriendCrypto.encodePrivate(boxKeys.getPrivate()));
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, json.toString());
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not save friend key", e);
		}
		return boxKeys;
	}

	public static String boxPublicKey() {
		return FriendCrypto.encodePublic(boxKeys().getPublic());
	}

	/** An account id for a username: Mojang's lookup for real accounts, the offline id in test mode. */
	public static CompletableFuture<Optional<UUID>> lookUp(String username) {
		if (INSECURE_TEST_MODE) {
			return CompletableFuture.completedFuture(Optional.of(UUIDUtil.createOfflinePlayerUUID(username)));
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonElement json = com.javahost.util.Modrinth.getJson("https://api.mojang.com/users/profiles/minecraft/"
						+ java.net.URLEncoder.encode(username, StandardCharsets.UTF_8));
				String hex = json.getAsJsonObject().get("id").getAsString();
				return Optional.of(UUID.fromString(hex.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")));
			} catch (Exception e) {
				return Optional.empty();
			}
		});
	}

	/** Whether this player can send signed friend requests (needs a Microsoft-account login). */
	public static boolean canSign() {
		// Offline (non-Microsoft) logins get Minecraft's empty key manager, which never has a certificate.
		return INSECURE_TEST_MODE
				|| Minecraft.getInstance().getProfileKeyPairManager() != net.minecraft.client.multiplayer.ProfileKeyPairManager.EMPTY_KEY_MANAGER;
	}

	/**
	 * Signs {@code payload} with this player's Mojang-certified key. Fills in "key" (the certificate) and "sig"
	 * on {@code message}. Completes with false if the player has no certificate (offline account).
	 */
	public static CompletableFuture<Boolean> sign(JsonObject message, byte[] payload) {
		if (INSECURE_TEST_MODE) {
			return CompletableFuture.completedFuture(true);
		}
		return Minecraft.getInstance().getProfileKeyPairManager().prepareKeyPair().thenApply(optional -> {
			if (optional.isEmpty()) {
				return false;
			}
			ProfileKeyPair pair = optional.get();
			JsonElement keyJson = ProfilePublicKey.Data.CODEC.encodeStart(JsonOps.INSTANCE, pair.publicKey().data()).getOrThrow();
			message.add("key", keyJson);
			byte[] signature = Signer.from(pair.privateKey(), "SHA256withRSA").sign(payload);
			message.addProperty("sig", Base64.getEncoder().encodeToString(signature));
			return true;
		});
	}

	/** Checks that {@code sender} (a Minecraft account) really signed {@code payload}. */
	public static boolean verify(UUID sender, JsonObject message, byte[] payload) {
		if (INSECURE_TEST_MODE) {
			return true;
		}
		try {
			SignatureValidator mojang = Minecraft.getInstance().services().profileKeySignatureValidator();
			if (mojang == null || !message.has("key") || !message.has("sig")) {
				return false;
			}
			ProfilePublicKey.Data data = ProfilePublicKey.Data.CODEC.parse(JsonOps.INSTANCE, message.get("key")).getOrThrow();
			if (data.hasExpired(ProfilePublicKey.EXPIRY_GRACE_PERIOD)) {
				return false;
			}
			// Throws unless Mojang certified this key for exactly this account.
			ProfilePublicKey key = ProfilePublicKey.createValidated(mojang, sender, data);
			return key.createSignatureValidator().validate(payload, Base64.getDecoder().decode(message.get("sig").getAsString()));
		} catch (Exception e) {
			JavaHost.LOGGER.debug("Rejected friend message from {}", sender, e);
			return false;
		}
	}

	public static byte[] sharedKeyWith(UUID friend, String friendBoxKey) throws GeneralSecurityException {
		return FriendCrypto.sharedKey(boxKeys().getPrivate(), FriendCrypto.decodePublic(friendBoxKey), id(), friend);
	}
}

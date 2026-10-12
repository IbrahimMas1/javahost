package com.javahost.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptography for the friends system, using only the JDK.
 * <ul>
 * <li>Every player has a long-lived X25519 key pair. When two players become friends they exchange public keys
 * (inside messages signed with their Mojang-certified chat keys) and each derives the same shared secret.</li>
 * <li>Everything sent between friends afterwards (world invites and their addresses) is encrypted and
 * authenticated with AES-256-GCM under that secret, so only the two friends can read or forge it.</li>
 * </ul>
 */
public final class FriendCrypto {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int NONCE_BYTES = 12;

	private FriendCrypto() {
	}

	public static KeyPair newKeyPair() {
		try {
			return KeyPairGenerator.getInstance("X25519").generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("X25519 unavailable", e);
		}
	}

	public static String encodePublic(PublicKey key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	public static String encodePrivate(PrivateKey key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	public static KeyPair decodeKeyPair(String publicB64, String privateB64) throws GeneralSecurityException {
		KeyFactory factory = KeyFactory.getInstance("XDH");
		return new KeyPair(
				factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64))),
				factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64))));
	}

	public static PublicKey decodePublic(String publicB64) throws GeneralSecurityException {
		return KeyFactory.getInstance("XDH").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
	}

	/**
	 * The 256-bit key two friends share. Both sides get the same value: the X25519 secret is symmetric and the
	 * two account ids are mixed in in a fixed order.
	 */
	public static byte[] sharedKey(PrivateKey mine, PublicKey theirs, UUID me, UUID them) throws GeneralSecurityException {
		KeyAgreement agreement = KeyAgreement.getInstance("X25519");
		agreement.init(mine);
		agreement.doPhase(theirs, true);
		byte[] secret = agreement.generateSecret();
		UUID low = me.compareTo(them) < 0 ? me : them;
		UUID high = low == me ? them : me;
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		sha.update("javahost-friend-v1".getBytes(StandardCharsets.UTF_8));
		sha.update(secret);
		sha.update(uuidBytes(low));
		sha.update(uuidBytes(high));
		Arrays.fill(secret, (byte) 0);
		return sha.digest();
	}

	/** Encrypts; returns base64 of nonce + ciphertext. {@code context} is authenticated but not encrypted. */
	public static String seal(byte[] key, String plaintext, String context) throws GeneralSecurityException {
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
		cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
		byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
		return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
	}

	/** Decrypts what {@link #seal} produced; throws if it was tampered with or the key/context is wrong. */
	public static String open(byte[] key, String sealedB64, String context) throws GeneralSecurityException {
		byte[] all = Base64.getDecoder().decode(sealedB64);
		if (all.length < NONCE_BYTES + 16) {
			throw new GeneralSecurityException("Message too short");
		}
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, NONCE_BYTES));
		cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
		return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
	}

	/** Encrypts a file (e.g. a screenshot): nonce + ciphertext, authenticated together with {@code context}. */
	public static byte[] sealBytes(byte[] key, byte[] plaintext, String context) throws GeneralSecurityException {
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
		cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
		byte[] sealed = cipher.doFinal(plaintext);
		return ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
	}

	public static byte[] openBytes(byte[] key, byte[] sealed, String context) throws GeneralSecurityException {
		if (sealed.length < NONCE_BYTES + 16) {
			throw new GeneralSecurityException("Data too short");
		}
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, NONCE_BYTES));
		cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
		return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
	}

	/** A fresh random 256-bit key (used for each player's status key). */
	public static byte[] randomKey() {
		byte[] key = new byte[32];
		RANDOM.nextBytes(key);
		return key;
	}

	/** The mailbox name for a player: derived from their account id, so friends can find it without a directory. */
	public static String mailboxFor(UUID player, String namespace) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] digest = sha.digest(("javahost-mailbox-v1:" + namespace + ":" + player).getBytes(StandardCharsets.UTF_8));
			return "jh1-" + HexFormat.of().formatHex(digest, 0, 16);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String randomId() {
		byte[] bytes = new byte[9];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static byte[] uuidBytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}
}

package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FriendCryptoTest {
	private final UUID alice = UUID.randomUUID();
	private final UUID bob = UUID.randomUUID();

	@Test
	void bothFriendsDeriveTheSameKey() throws Exception {
		KeyPair a = FriendCrypto.newKeyPair();
		KeyPair b = FriendCrypto.newKeyPair();
		byte[] fromAlice = FriendCrypto.sharedKey(a.getPrivate(), b.getPublic(), alice, bob);
		byte[] fromBob = FriendCrypto.sharedKey(b.getPrivate(), a.getPublic(), bob, alice);
		assertArrayEquals(fromAlice, fromBob);
		assertEquals(32, fromAlice.length);

		// A third player can't get the same key.
		KeyPair eve = FriendCrypto.newKeyPair();
		assertFalse(java.util.Arrays.equals(fromAlice, FriendCrypto.sharedKey(eve.getPrivate(), a.getPublic(), UUID.randomUUID(), alice)));
	}

	@Test
	void keysSurviveSaving() throws Exception {
		KeyPair a = FriendCrypto.newKeyPair();
		KeyPair restored = FriendCrypto.decodeKeyPair(FriendCrypto.encodePublic(a.getPublic()), FriendCrypto.encodePrivate(a.getPrivate()));
		KeyPair b = FriendCrypto.newKeyPair();
		assertArrayEquals(FriendCrypto.sharedKey(a.getPrivate(), b.getPublic(), alice, bob),
				FriendCrypto.sharedKey(restored.getPrivate(), b.getPublic(), alice, bob));
	}

	@Test
	void sealedInvitesOpenOnlyWithTheRightKeyAndContext() throws Exception {
		KeyPair a = FriendCrypto.newKeyPair();
		KeyPair b = FriendCrypto.newKeyPair();
		byte[] key = FriendCrypto.sharedKey(a.getPrivate(), b.getPublic(), alice, bob);
		String sealed = FriendCrypto.seal(key, "{\"world\":\"My World\"}", "invite|1");
		assertEquals("{\"world\":\"My World\"}", FriendCrypto.open(key, sealed, "invite|1"));
		assertNotEquals(sealed, FriendCrypto.seal(key, "{\"world\":\"My World\"}", "invite|1"), "fresh nonce every time");

		// Wrong context (e.g. replayed as a different message), wrong key, or tampering all fail.
		assertThrows(GeneralSecurityException.class, () -> FriendCrypto.open(key, sealed, "invite|2"));
		byte[] other = FriendCrypto.sharedKey(FriendCrypto.newKeyPair().getPrivate(), b.getPublic(), alice, bob);
		assertThrows(GeneralSecurityException.class, () -> FriendCrypto.open(other, sealed, "invite|1"));
		byte[] raw = java.util.Base64.getDecoder().decode(sealed);
		raw[raw.length - 1] ^= 1;
		assertThrows(GeneralSecurityException.class, () -> FriendCrypto.open(key, java.util.Base64.getEncoder().encodeToString(raw), "invite|1"));
	}

	@Test
	void mailboxNamesAreStableAndPrivate() {
		String box = FriendCrypto.mailboxFor(alice, "");
		assertEquals(box, FriendCrypto.mailboxFor(alice, ""));
		assertNotEquals(box, FriendCrypto.mailboxFor(bob, ""));
		assertNotEquals(box, FriendCrypto.mailboxFor(alice, "test"));
		assertTrue(box.matches("jh1-[0-9a-f]{32}"));
		assertFalse(box.contains(alice.toString()));
	}
}

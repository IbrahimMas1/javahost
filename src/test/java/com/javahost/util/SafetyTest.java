package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** The 1.7 safety checks: rate limits, image bombs, signed statuses and host proofs, local networks, clean text. */
class SafetyTest {
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-00000000000e");

	@Test
	void rateLimiterAllowsBurstsThenSpotsFloods() {
		AtomicLong now = new AtomicLong();
		RateLimiter limiter = new RateLimiter(10, 20, now::get);
		for (int i = 0; i < 20; i++) {
			assertTrue(limiter.tryTake(ALEX, 1), "a burst is fine");
		}
		assertFalse(limiter.tryTake(ALEX, 1), "then the bucket is empty");
		assertTrue(limiter.tryTake(SAM, 1), "players have their own buckets");
		now.addAndGet(1_000_000_000L);
		assertTrue(limiter.tryTake(ALEX, 10), "it refills at the rate");
		assertFalse(limiter.abusive(ALEX, 5000), "one refusal isn't abuse");
		// Keep flooding for 12 seconds: that's a hacked client.
		for (int i = 0; i < 120; i++) {
			now.addAndGet(100_000_000L);
			for (int j = 0; j < 5; j++) {
				limiter.tryTake(ALEX, 1);
			}
		}
		assertTrue(limiter.abusive(ALEX, 10_000));
		// Then behaving for a few seconds clears the record.
		now.addAndGet(5_000_000_000L);
		assertTrue(limiter.tryTake(ALEX, 1));
		assertFalse(limiter.abusive(ALEX, 10_000));
	}

	private static byte[] pngHeader(int width, int height) {
		ByteBuffer buf = ByteBuffer.allocate(33);
		buf.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
		buf.putInt(13).put(new byte[] {'I', 'H', 'D', 'R'}).putInt(width).putInt(height);
		return buf.array();
	}

	@Test
	void imageBombsAreRefusedBeforeDecoding() {
		assertTrue(PngInfo.safe(PngInfo.size(pngHeader(3840, 2160))), "a 4K screenshot is fine");
		assertTrue(PngInfo.safe(PngInfo.size(pngHeader(7680, 4320))), "so is 8K");
		assertFalse(PngInfo.safe(PngInfo.size(pngHeader(30000, 30000))), "a 900-megapixel 'screenshot' isn't");
		assertFalse(PngInfo.safe(PngInfo.size(pngHeader(1, 100_000))), "nor a silly side");
		assertFalse(PngInfo.safe(PngInfo.size(pngHeader(0, 10))));
		assertNull(PngInfo.size(new byte[] {1, 2, 3}), "not a PNG at all");
	}

	@Test
	void onlyTheFriendCanSignTheirStatus() {
		KeyPair alex = FriendCrypto.newSigningKeyPair();
		KeyPair eve = FriendCrypto.newSigningKeyPair();
		String alexPublic = FriendCrypto.encodePublic(alex.getPublic());
		byte[] status = FriendCrypto.statusBytes(ALEX, 1000, "sealed-status");
		assertTrue(FriendCrypto.verifySigned(alexPublic, status, FriendCrypto.sign(alex.getPrivate(), status)));
		// Eve (another of Alex's friends) knows the status key but not Alex's signing key: her fake is refused.
		assertFalse(FriendCrypto.verifySigned(alexPublic, status, FriendCrypto.sign(eve.getPrivate(), status)));
		// A real signature can't be moved onto a different (e.g. future-dated) status.
		byte[] signed = FriendCrypto.sign(alex.getPrivate(), status);
		assertFalse(FriendCrypto.verifySigned(alexPublic, FriendCrypto.statusBytes(ALEX, 9_999_999, "sealed-status"), signed));
		assertFalse(FriendCrypto.verifySigned(null, status, signed), "friends without a key (old versions) can't be trusted");
		assertTrue(FriendCrypto.isSigningKey(alexPublic));
		assertFalse(FriendCrypto.isSigningKey("not a key"));
	}

	@Test
	void aHostProofOnlyWorksForTheGuestItWasMadeFor() {
		KeyPair alex = FriendCrypto.newSigningKeyPair();
		String alexPublic = FriendCrypto.encodePublic(alex.getPublic());
		byte[] nonce = new byte[32];
		new java.security.SecureRandom().nextBytes(nonce);
		byte[] proof = FriendCrypto.sign(alex.getPrivate(), FriendCrypto.hostProofBytes(nonce, ALEX, SAM));
		assertTrue(FriendCrypto.verifySigned(alexPublic, FriendCrypto.hostProofBytes(nonce, ALEX, SAM), proof));
		// Eve's fake server joins Alex's world as herself and passes Alex's answer on to Sam: it names Eve, not Sam.
		byte[] relayed = FriendCrypto.sign(alex.getPrivate(), FriendCrypto.hostProofBytes(nonce, ALEX, EVE));
		assertFalse(FriendCrypto.verifySigned(alexPublic, FriendCrypto.hostProofBytes(nonce, ALEX, SAM), relayed));
		// An old answer doesn't fit a new challenge.
		byte[] fresh = nonce.clone();
		fresh[0] ^= 1;
		assertFalse(FriendCrypto.verifySigned(alexPublic, FriendCrypto.hostProofBytes(fresh, ALEX, SAM), proof));
	}

	@Test
	void sameLocalNetworkNeedsTheSameSubnet() {
		byte[] me = {(byte) 192, (byte) 168, 1, 20};
		assertTrue(Subnets.same(me, new byte[] {(byte) 192, (byte) 168, 1, 77}, 24));
		assertFalse(Subnets.same(me, new byte[] {(byte) 192, (byte) 168, 2, 77}, 24), "another network with the same kind of address");
		assertFalse(Subnets.same(me, new byte[] {10, 0, 0, 1}, 24));
		assertFalse(Subnets.same(me, new byte[] {(byte) 192, (byte) 168, 1, 77}, 0), "no prefix means we can't tell");
	}

	@Test
	void textFromOthersLosesFormattingCodes() {
		assertEquals("Survival", Text.clean("§kSur§cvival", 64));
		assertEquals("ab", Text.clean("a\u0007b", 64));
		assertEquals("abc", Text.clean("abcdef", 3));
		assertEquals("", Text.clean(null, 5));
	}

	@Test
	void fastLaneRefusesImpossiblePieceCounts() {
		assertThrows(IllegalArgumentException.class, () -> new Chunks.Assembly(Integer.MAX_VALUE, 4 * 1024 * 1024, 30 * 1024));
		assertThrows(IllegalArgumentException.class, () -> new Chunks.Assembly(-1, 4 * 1024 * 1024, 30 * 1024));
		assertThrows(IllegalArgumentException.class, () -> new Chunks.Assembly(0, 4 * 1024 * 1024, 30 * 1024));
		new Chunks.Assembly(137, 4 * 1024 * 1024, 30 * 1024); // the most a real 4 MB message needs
	}
}

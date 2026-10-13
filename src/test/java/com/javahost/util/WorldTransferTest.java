package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldTransferTest {
	private static final long ID = 42;

	@TempDir
	Path temp;

	private static byte[] world(int size) {
		byte[] data = new byte[size];
		new Random(7).nextBytes(data);
		return data;
	}

	private WorldTransfer.Header header(byte[] data) throws Exception {
		Path file = temp.resolve("w.zip");
		Files.write(file, data);
		return new WorldTransfer.Header("Survival", data.length, data.length * 2L, WorldTransfer.sha256(file), FriendCrypto.randomKey());
	}

	@Test
	void aWorldArrivesWholeThroughTheWindow() throws Exception {
		byte[] data = world(WorldTransfer.PLAIN_CHUNK * 100 + 123);
		WorldTransfer.Header header = header(data);
		assertEquals(101, header.chunks());

		WorldTransfer.Sender sender = new WorldTransfer.Sender(header.chunks());
		WorldTransfer.Receiver receiver = new WorldTransfer.Receiver(header);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int maxInFlight = 0;
		while (!sender.done()) {
			assertTrue(sender.canSend() || sender.done(), "the window never deadlocks");
			// Send everything the window allows, then deliver it (like one round trip).
			java.util.List<byte[]> inFlight = new java.util.ArrayList<>();
			java.util.List<Integer> indexes = new java.util.ArrayList<>();
			while (sender.canSend()) {
				int index = sender.take();
				byte[] plain = Arrays.copyOfRange(data, index * WorldTransfer.PLAIN_CHUNK,
						index * WorldTransfer.PLAIN_CHUNK + WorldTransfer.chunkLength(data.length, index));
				byte[] sealed = WorldTransfer.sealChunk(header.key(), ID, index, plain);
				assertTrue(sealed.length <= WorldTransfer.SEALED_CHUNK, "fits a serverbound packet");
				inFlight.add(sealed);
				indexes.add(index);
			}
			maxInFlight = Math.max(maxInFlight, inFlight.size());
			for (int i = 0; i < inFlight.size(); i++) {
				out.write(receiver.accept(ID, indexes.get(i), inFlight.get(i)));
				int ack = receiver.ackDue();
				if (ack >= 0) {
					sender.ack(ack);
				}
			}
		}
		assertTrue(maxInFlight <= WorldTransfer.WINDOW + 1, "at most a window in flight");
		assertTrue(receiver.complete());
		assertTrue(receiver.verified());
		assertArrayEquals(data, out.toByteArray());
	}

	@Test
	void tamperedReorderedOrForeignChunksAreRefused() throws Exception {
		byte[] data = world(WorldTransfer.PLAIN_CHUNK * 3);
		WorldTransfer.Header header = header(data);
		byte[] first = WorldTransfer.sealChunk(header.key(), ID, 0, Arrays.copyOfRange(data, 0, WorldTransfer.PLAIN_CHUNK));
		byte[] second = WorldTransfer.sealChunk(header.key(), ID, 1,
				Arrays.copyOfRange(data, WorldTransfer.PLAIN_CHUNK, 2 * WorldTransfer.PLAIN_CHUNK));

		assertThrows(GeneralSecurityException.class, () -> new WorldTransfer.Receiver(header).accept(ID, 1, second), "out of order");
		assertThrows(GeneralSecurityException.class, () -> new WorldTransfer.Receiver(header).accept(ID, 0, second), "swapped in");
		assertThrows(GeneralSecurityException.class, () -> new WorldTransfer.Receiver(header).accept(ID + 1, 0, first), "another transfer");
		byte[] flipped = first.clone();
		flipped[40] ^= 1;
		assertThrows(GeneralSecurityException.class, () -> new WorldTransfer.Receiver(header).accept(ID, 0, flipped), "tampered");
	}

	@Test
	void aWrongHashIsCaughtAtTheEnd() throws Exception {
		byte[] data = world(1000);
		WorldTransfer.Header real = header(data);
		WorldTransfer.Header lying = new WorldTransfer.Header("Survival", data.length, 0, "0".repeat(64), real.key());
		WorldTransfer.Receiver receiver = new WorldTransfer.Receiver(lying);
		receiver.accept(ID, 0, WorldTransfer.sealChunk(real.key(), ID, 0, data));
		assertTrue(receiver.complete());
		assertFalse(receiver.verified());
	}

	@Test
	void onlyTheFriendCanOpenTheOffer() throws Exception {
		WorldTransfer.Header header = header(world(500));
		byte[] friendKey = FriendCrypto.randomKey();
		byte[] sealed = WorldTransfer.sealHeader(friendKey, ID, header);

		WorldTransfer.Header opened = WorldTransfer.openHeader(friendKey, ID, sealed);
		assertEquals("Survival", opened.world());
		assertEquals(header.sha256(), opened.sha256());
		assertArrayEquals(header.key(), opened.key());

		assertThrows(GeneralSecurityException.class, () -> WorldTransfer.openHeader(FriendCrypto.randomKey(), ID, sealed), "wrong key");
		assertThrows(GeneralSecurityException.class, () -> WorldTransfer.openHeader(friendKey, ID + 1, sealed), "replayed as another id");
		WorldTransfer.Header huge = new WorldTransfer.Header("Big", WorldTransfer.MAX_BYTES + 1, 0, header.sha256(), header.key());
		assertThrows(GeneralSecurityException.class,
				() -> WorldTransfer.openHeader(friendKey, ID, WorldTransfer.sealHeader(friendKey, ID, huge)), "too big");
	}
}

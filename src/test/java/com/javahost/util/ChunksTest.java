package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ChunksTest {
	@Test
	void reassemblesInAnyOrder() {
		byte[] file = new byte[100_000];
		new Random(3).nextBytes(file);
		byte[] packed = Chunks.pack("{\"type\":\"screenshot\"}", file);
		List<byte[]> pieces = Chunks.split(packed, 30 * 1024);
		assertEquals(4, pieces.size());

		List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
		Collections.shuffle(order, new Random(9));
		Chunks.Assembly assembly = new Chunks.Assembly(pieces.size(), 4 * 1024 * 1024, 30 * 1024);
		byte[] whole = null;
		for (int i = 0; i < order.size(); i++) {
			whole = assembly.add(order.get(i), pieces.get(order.get(i)));
			if (i < order.size() - 1) {
				assertNull(whole);
				assertNull(assembly.add(order.get(i), pieces.get(order.get(i))), "duplicates are ignored");
			}
		}
		Chunks.Unpacked unpacked = Chunks.unpack(whole);
		assertEquals("{\"type\":\"screenshot\"}", unpacked.text());
		assertArrayEquals(file, unpacked.file());
	}

	@Test
	void textOnlyMessagesAndTheSizeCap() {
		Chunks.Unpacked chat = Chunks.unpack(Chunks.split(Chunks.pack("hi", null), 1024).getFirst());
		assertEquals("hi", chat.text());
		assertNull(chat.file());

		Chunks.Assembly assembly = new Chunks.Assembly(3, 50, 20);
		assembly.add(0, new byte[30]);
		assertThrows(IllegalStateException.class, () -> assembly.add(1, new byte[30]));
		assertNull(assembly.add(7, new byte[1]), "out of range is ignored");
	}
}

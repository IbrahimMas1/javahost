package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import org.junit.jupiter.api.Test;

class KcpTest {
	/** A pretend line between two ends: each datagram takes a while, and some are lost, repeated or late. */
	private static final class Line {
		record Packet(int arrives, long order, boolean toA, byte[] data) {
		}

		final PriorityQueue<Packet> inFlight = new PriorityQueue<>((x, y) -> x.arrives() != y.arrives() ? Integer.compare(x.arrives(), y.arrives()) : Long.compare(x.order(), y.order()));
		final Random random;
		final double loss;
		final int delay;
		final int jitter;
		int now;
		long order;
		int carried;
		Kcp a;
		Kcp b;

		Line(long seed, double loss, int delay, int jitter) {
			random = new Random(seed);
			this.loss = loss;
			this.delay = delay;
			this.jitter = jitter;
			a = new Kcp((data, length) -> post(false, data, length));
			b = new Kcp((data, length) -> post(true, data, length));
		}

		void post(boolean toA, byte[] data, int length) {
			assertTrue(length <= Kcp.MTU, "datagrams fit the size limit");
			carried++;
			if (random.nextDouble() < loss) {
				return;
			}
			int copies = random.nextDouble() < loss / 4 ? 2 : 1;
			for (int i = 0; i < copies; i++) {
				inFlight.add(new Packet(now + delay + (jitter == 0 ? 0 : random.nextInt(jitter)), order++, toA, java.util.Arrays.copyOf(data, length)));
			}
		}

		/** Ten milliseconds pass. */
		void step() {
			now += 10;
			while (!inFlight.isEmpty() && inFlight.peek().arrives() <= now) {
				Packet packet = inFlight.poll();
				(packet.toA() ? a : b).input(packet.data(), 0, packet.data().length, now);
			}
			a.flush(now);
			b.flush(now);
		}
	}

	private static byte[] drain(Kcp from, ByteArrayOutputStream into) {
		byte[] buffer = new byte[4096];
		int n;
		while ((n = from.receive(buffer, 0, buffer.length)) > 0) {
			into.write(buffer, 0, n);
		}
		return into.toByteArray();
	}

	private static void transfer(Line line, int bytesEachWay, int maxSteps) {
		byte[] fromA = new byte[bytesEachWay];
		byte[] fromB = new byte[bytesEachWay / 3];
		new Random(1).nextBytes(fromA);
		new Random(2).nextBytes(fromB);
		// Written in uneven bursts, like a game does.
		int sentA = 0;
		int sentB = 0;
		ByteArrayOutputStream atA = new ByteArrayOutputStream();
		ByteArrayOutputStream atB = new ByteArrayOutputStream();
		int steps = 0;
		while ((atB.size() < fromA.length || atA.size() < fromB.length) && steps++ < maxSteps) {
			if (sentA < fromA.length && line.a.pending() < 600) {
				int n = Math.min(fromA.length - sentA, 1 + line.random.nextInt(20_000));
				line.a.send(fromA, sentA, n);
				sentA += n;
			}
			if (sentB < fromB.length && line.b.pending() < 600) {
				int n = Math.min(fromB.length - sentB, 1 + line.random.nextInt(300));
				line.b.send(fromB, sentB, n);
				sentB += n;
			}
			line.step();
			drain(line.b, atB);
			drain(line.a, atA);
		}
		assertArrayEquals(fromA, atB.toByteArray(), "everything A sent reaches B, in order, after " + line.now + " ms");
		assertArrayEquals(fromB, atA.toByteArray(), "everything B sent reaches A, in order");
		assertFalse(line.a.dead() || line.b.dead());
	}

	@Test
	void aCleanLineCarriesEverything() {
		Line line = new Line(7, 0, 20, 0);
		transfer(line, 400_000, 5_000);
		assertTrue(line.a.roundTrip() >= 30 && line.a.roundTrip() <= 80, "round trip measured near 40 ms: " + line.a.roundTrip());
	}

	@Test
	void aBadLineStillCarriesEverythingInOrder() {
		// One datagram in ten lost, some repeated, arrival times all over the place.
		transfer(new Line(11, 0.10, 40, 60), 300_000, 40_000);
		transfer(new Line(12, 0.25, 15, 30), 120_000, 60_000);
	}

	@Test
	void aLostPieceIsResentWithoutWaitingForATimer() {
		// 5% loss on a 100 ms line: with quick resends 200 KB takes a few seconds, not the minutes timers alone would.
		Line line = new Line(21, 0.05, 50, 5);
		transfer(line, 200_000, 20_000);
		assertTrue(line.now < 15_000, "took " + line.now + " ms");
	}

	@Test
	void aSlowReaderMakesTheSenderWaitNotOverflow() {
		Line line = new Line(31, 0, 10, 0);
		byte[] data = new byte[1_500_000];
		new Random(3).nextBytes(data);
		line.a.send(data, 0, data.length);
		for (int i = 0; i < 300; i++) {
			line.step(); // B never reads
		}
		int stuck = line.carried;
		for (int i = 0; i < 300; i++) {
			line.step();
		}
		assertTrue(line.carried - stuck < 60, "a full receiver is only asked now and then: " + (line.carried - stuck) + " datagrams in 3 s");
		ByteArrayOutputStream atB = new ByteArrayOutputStream();
		for (int i = 0; i < 4000 && atB.size() < data.length; i++) {
			drain(line.b, atB);
			line.step();
		}
		assertArrayEquals(data, atB.toByteArray(), "it all arrives once the reader catches up");
	}

	@Test
	void silenceIsNoticed() {
		List<byte[]> lost = new ArrayList<>();
		Kcp alone = new Kcp((data, length) -> lost.add(data));
		alone.send(new byte[100], 0, 100);
		int now = 0;
		while (!alone.dead() && now < 600_000) {
			alone.flush(now += 10);
		}
		assertTrue(alone.dead(), "nobody answering is reported");
		assertTrue(now > 20_000, "but not hastily (" + now + " ms)");
	}

	@Test
	void rubbishIsIgnored() {
		Kcp kcp = new Kcp((data, length) -> {
		});
		Random random = new Random(5);
		for (int i = 0; i < 2000; i++) {
			byte[] junk = new byte[random.nextInt(1300)];
			random.nextBytes(junk);
			kcp.input(junk, 0, junk.length, i);
		}
		assertEquals(0, kcp.receive(new byte[10], 0, 10) > 4096 ? 1 : 0);
	}

	@Test
	void stunAnswersAreRead() {
		byte[] request = Stun.request();
		assertEquals(20, request.length);
		assertTrue(Stun.isStun(request, request.length));
		// A success response saying 192.0.2.1:32853 (the example in RFC 5769, with our transaction id).
		byte[] response = new byte[32];
		response[0] = 0x01;
		response[1] = 0x01;
		response[3] = 12;
		System.arraycopy(request, 4, response, 4, 16);
		response[21] = 0x20;
		response[23] = 8;
		response[25] = 0x01;
		response[26] = (byte) 0xA1;
		response[27] = 0x47;
		response[28] = (byte) 0xE1;
		response[29] = 0x12;
		response[30] = (byte) 0xA6;
		response[31] = 0x43;
		InetSocketAddress mapped = Stun.mapped(response, response.length, request);
		assertNotNull(mapped);
		assertEquals("192.0.2.1", mapped.getAddress().getHostAddress());
		assertEquals(32853, mapped.getPort());
		assertNull(Stun.mapped(response, response.length, Stun.request()), "an answer to someone else's question is ignored");
		assertNull(Stun.mapped(response, 24, request), "a cut-off answer is ignored");
		assertFalse(Stun.isStun(new byte[] {'J', 1, 0, 0, 0x21, 0x12, (byte) 0xA4, 0x42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 20));
	}
}

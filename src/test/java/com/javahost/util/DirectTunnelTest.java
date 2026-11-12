package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DirectTunnelTest {
	/** A stand-in for the game: sends back whatever it gets. */
	private static ServerSocket echoServer() throws IOException {
		ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
		Thread thread = new Thread(() -> {
			while (!server.isClosed()) {
				try {
					Socket socket = server.accept();
					new Thread(() -> {
						try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
							in.transferTo(out);
						} catch (IOException e) {
							// closed
						}
					}).start();
				} catch (IOException e) {
					return;
				}
			}
		});
		thread.setDaemon(true);
		thread.start();
		return server;
	}

	private static InetSocketAddress local(DirectTunnel tunnel) {
		return new InetSocketAddress(InetAddress.getLoopbackAddress(), tunnel.localPort());
	}

	@Test
	void aGuestReachesTheHostsGameThroughTheLink() throws Exception {
		byte[] token = new byte[16];
		new Random(9).nextBytes(token);
		try (ServerSocket game = echoServer(); DirectTunnel host = DirectTunnel.host(game.getLocalPort()); DirectTunnel guest = DirectTunnel.guest()) {
			host.expect(77, token, List.of(local(guest)));
			int port = guest.connect(77, token, List.of(new InetSocketAddress("192.0.2.9", 9), local(host))).get(5, TimeUnit.SECONDS);
			byte[] sent = new byte[1_200_000];
			new Random(4).nextBytes(sent);
			byte[] back = new byte[sent.length];
			try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
				Thread writer = new Thread(() -> {
					try {
						OutputStream out = socket.getOutputStream();
						for (int at = 0; at < sent.length; at += 7000) {
							out.write(sent, at, Math.min(7000, sent.length - at));
						}
						out.flush();
					} catch (IOException e) {
						// the read below will fail the test
					}
				});
				writer.start();
				socket.setSoTimeout(20_000);
				new DataInputStream(socket.getInputStream()).readFully(back);
				writer.join();
				assertEquals(1, host.connected());
				assertTrue(guest.roundTrip(77) >= 0 && guest.roundTrip(77) < 200);
			}
			assertArrayEquals(sent, back, "1.2 MB went to the host's game and came back unchanged");
		}
	}

	@Test
	void closingOneEndHangsUpTheOtherAtOnce() throws Exception {
		byte[] token = new byte[16];
		try (ServerSocket game = echoServer(); DirectTunnel host = DirectTunnel.host(game.getLocalPort())) {
			DirectTunnel guest = DirectTunnel.guest();
			host.expect(3, token, List.of(local(guest)));
			int port = guest.connect(3, token, List.of(local(host))).get(5, TimeUnit.SECONDS);
			try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
				socket.getOutputStream().write(new byte[] {1, 2, 3});
				socket.setSoTimeout(5000);
				assertEquals(1, socket.getInputStream().read());
				assertEquals(1, host.connected());
				guest.close(); // the guest's game quits without a word
				long start = System.currentTimeMillis();
				while (host.connected() > 0 && System.currentTimeMillis() - start < 5000) {
					Thread.sleep(20);
				}
				assertEquals(0, host.connected(), "the host knows within moments, not after a half-minute silence");
			}
		}
	}

	@Test
	void theWrongTokenGetsNowhere() throws Exception {
		byte[] token = new byte[16];
		byte[] wrong = new byte[16];
		wrong[3] = 1;
		try (ServerSocket game = echoServer(); DirectTunnel host = DirectTunnel.host(game.getLocalPort()); DirectTunnel guest = DirectTunnel.guest()) {
			host.expect(5, token, List.of(local(guest)));
			var attempt = guest.connect(5, wrong, List.of(local(host)));
			Thread.sleep(1500);
			assertEquals(0, host.connected(), "the host doesn't accept a session opened with another token");
			assertTrue(!attempt.isDone() || attempt.isCompletedExceptionally());
			// And a session nobody announced is ignored altogether.
			var stranger = guest.connect(6, token, List.of(local(host)));
			Thread.sleep(500);
			assertTrue(!stranger.isDone());
		}
	}

	@Test
	void givingUpIsReported() throws Exception {
		try (DirectTunnel guest = DirectTunnel.guest()) {
			var attempt = guest.connect(1, new byte[16], List.of(new InetSocketAddress("192.0.2.1", 9)));
			assertThrows(ExecutionException.class, () -> attempt.get(20, TimeUnit.SECONDS));
		}
	}

	@Test
	void addressesAreReadStrictly() {
		assertEquals(25565, DirectTunnel.parse("203.0.113.7:25565").getPort());
		assertNotNull(DirectTunnel.parse("[2001:db8::1]:4000"));
		assertNull(DirectTunnel.parse("example.com:25565"), "names are never looked up");
		assertNull(DirectTunnel.parse("203.0.113.7:0"));
		assertNull(DirectTunnel.parse("203.0.113.7:70000"));
		assertNull(DirectTunnel.parse("0.0.0.0:25565"));
		assertNull(DirectTunnel.parse("224.0.0.1:25565"));
		assertNull(DirectTunnel.parse("203.0.113.7"));
		assertNull(DirectTunnel.parse(null));
	}
}

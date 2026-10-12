package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The bug behind "the invite never arrived": a subscription whose connection died silently stayed "connected"
 * forever. This fake server delivers one message and then goes silent while keeping the connection open,
 * exactly like a dead Wi-Fi link. The mailbox must notice, reconnect, and pick up what was sent meanwhile.
 */
class MailboxTest {
	private static HttpServer server;
	private static final AtomicInteger CONNECTIONS = new AtomicInteger();
	private static final List<String> SINCE_SEEN = new CopyOnWriteArrayList<>();
	private static final List<String> POSTED = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void startFakeServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (exchange.getRequestMethod().equals("POST")) {
				POSTED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
				return;
			}
			if (path.endsWith("/json")) {
				int connection = CONNECTIONS.incrementAndGet();
				SINCE_SEEN.add(exchange.getRequestURI().getQuery());
				exchange.sendResponseHeaders(200, 0);
				OutputStream out = exchange.getResponseBody();
				out.write("{\"id\":\"open1\",\"time\":1,\"event\":\"open\",\"topic\":\"t\"}\n".getBytes(StandardCharsets.UTF_8));
				if (connection == 1) {
					out.write("{\"id\":\"m1\",\"time\":2,\"event\":\"message\",\"topic\":\"t\",\"message\":\"first\"}\n".getBytes(StandardCharsets.UTF_8));
				} else {
					out.write("{\"id\":\"m2\",\"time\":3,\"event\":\"message\",\"topic\":\"t\",\"message\":\"second\"}\n".getBytes(StandardCharsets.UTF_8));
				}
				out.flush();
				// Then hang: no keepalives, connection left open (a silently dead link).
				try {
					Thread.sleep(60_000);
				} catch (InterruptedException ignored) {
					// test over
				}
				exchange.close();
			}
		});
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
		System.setProperty("javahost.mailboxServer", "http://127.0.0.1:" + server.getAddress().getPort());
		System.setProperty("javahost.mailboxStallMs", "1500");
	}

	@AfterAll
	static void stopFakeServer() {
		server.stop(0);
	}

	@Test
	void reconnectsAfterASilentlyDeadConnection() throws Exception {
		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		Mailbox mailbox = new Mailbox("test", List.of("t"), "30m");
		mailbox.start("12h", message -> received.add(message.text() + "@" + message.id()), status -> {
		});
		try {
			assertEquals("first@m1", received.poll(5, TimeUnit.SECONDS));
			// The first connection is now silent; the watchdog must drop it and reconnect.
			String second = received.poll(10, TimeUnit.SECONDS);
			assertNotNull(second, "no reconnect after the connection went silent");
			assertEquals("second@m2", second);
			assertTrue(CONNECTIONS.get() >= 2);
			assertEquals("since=m1", SINCE_SEEN.get(1), "reconnect must resume after the last message");
		} finally {
			mailbox.stop();
		}
	}

	@Test
	void postsAreDelivered() throws Exception {
		Mailbox.Sent sent = Mailbox.post("t", "hello").get(5, TimeUnit.SECONDS);
		assertTrue(sent.ok());
		assertTrue(POSTED.contains("hello"));
	}
}

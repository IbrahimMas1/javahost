package com.javahost.util;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A player's inbox for friend requests and invites, carried by ntfy.sh, a free public message relay (no account,
 * no server of our own). Each player listens on a mailbox named after their account id; others post to it.
 * Messages stay for 12 hours, so a friend who is offline still gets them when they next start the game.
 * Anything sensitive is encrypted before it gets here (see {@link FriendCrypto}).
 */
public final class Mailbox {
	public enum Status { CONNECTING, ONLINE, OFFLINE }

	private static final String SERVER = System.getProperty("javahost.mailboxServer", "https://ntfy.sh");
	private static final int MAX_MESSAGE = 4000;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final String topic;
	private volatile Status status = Status.CONNECTING;
	private volatile boolean running;
	private Thread thread;

	public Mailbox(String topic) {
		this.topic = topic;
	}

	public Status status() {
		return status;
	}

	/** Sends a message to someone's mailbox. Completes with {@code true} once ntfy.sh accepted it. */
	public static CompletableFuture<Boolean> post(String topic, String message) {
		if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE) {
			return CompletableFuture.failedFuture(new IOException("Message too large"));
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(SERVER + "/" + topic))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "JavaHost")
				.POST(HttpRequest.BodyPublishers.ofString(message))
				.build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(r -> r.statusCode() == 200);
	}

	/**
	 * Listens in the background until {@link #stop()}. {@code onMessage} gets (message id, text) for every message
	 * newer than {@code since} (a message id, or "12h" for everything still stored); reconnects by itself.
	 */
	public void start(String since, BiConsumer<String, String> onMessage, Consumer<Status> onStatus) {
		running = true;
		thread = new Thread(() -> {
			String cursor = since;
			int backoff = 2;
			while (running) {
				try {
					HttpRequest request = HttpRequest.newBuilder(URI.create(SERVER + "/" + topic + "/json?since="
									+ URLEncoder.encode(cursor, StandardCharsets.UTF_8)))
							.header("User-Agent", "JavaHost")
							.GET()
							.build();
					HttpResponse<Stream<String>> response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
					if (response.statusCode() != 200) {
						throw new IOException("HTTP " + response.statusCode());
					}
					setStatus(Status.ONLINE, onStatus);
					backoff = 2;
					try (Stream<String> lines = response.body()) {
						for (String line : (Iterable<String>) lines::iterator) {
							if (!running) {
								break;
							}
							if (line.isBlank()) {
								continue;
							}
							JsonObject event = JsonParser.parseString(line).getAsJsonObject();
							if ("message".equals(event.get("event").getAsString()) && event.has("message")) {
								cursor = event.get("id").getAsString();
								onMessage.accept(cursor, event.get("message").getAsString());
							}
						}
					}
				} catch (Exception e) {
					if (!running) {
						break;
					}
					setStatus(Status.OFFLINE, onStatus);
					try {
						Thread.sleep(backoff * 1000L);
					} catch (InterruptedException interrupted) {
						break;
					}
					backoff = Math.min(backoff * 2, 60);
				}
			}
		}, "JavaHost Mailbox");
		thread.setDaemon(true);
		thread.start();
	}

	private void setStatus(Status next, Consumer<Status> onStatus) {
		if (status != next) {
			status = next;
			onStatus.accept(next);
		}
	}

	public void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
		}
	}
}

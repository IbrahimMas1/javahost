package com.javahost.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Inboxes for friend requests, invites, chat, status and screenshots, carried by ntfy.sh, a free public message
 * relay (no account, no server of our own). Each player listens on topics named after their account id; others
 * post to them. Messages stay for 12 hours (attachments 3 hours), so offline friends still get them later.
 * Anything sensitive is encrypted before it gets here (see {@link FriendCrypto}).
 *
 * <p>The subscription is one long-lived HTTP stream. Connections can die silently (Wi-Fi changes, sleep, router
 * timeouts) without the stream noticing, so a watchdog reconnects when nothing, not even ntfy's keepalive, has
 * arrived for {@link #STALL_MS}. Reconnecting asks for everything since the last message, so nothing is lost.
 */
public final class Mailbox {
	public enum Status { CONNECTING, ONLINE, OFFLINE }

	/** One received message; {@code attachmentUrl} is set when a file came with it. */
	public record Message(String id, String topic, long time, String text, String attachmentUrl, long attachmentSize) {
	}

	/** Result of a send. {@code 200} is success, {@code 429} means the daily/rate limit, {@code -1} a network error. */
	public record Sent(int status) {
		public boolean ok() {
			return status == 200;
		}

		public boolean rateLimited() {
			return status == 429;
		}
	}

	static final String SERVER = System.getProperty("javahost.mailboxServer", "https://ntfy.sh");
	static final long STALL_MS = Long.getLong("javahost.mailboxStallMs", 100_000L);
	private static final int MAX_MESSAGE = 4000;
	private static final int MAX_ATTACHMENT = 14 * 1024 * 1024;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final String name;
	private volatile List<String> topics;
	private final String window;
	private volatile Status status = Status.CONNECTING;
	private volatile boolean running;
	private volatile InputStream current;
	private volatile long lastActivity;
	private volatile String cursor;
	private Thread thread;

	/**
	 * @param window for multi-topic subscriptions, how far back to replay on every (re)connect, e.g. "30m".
	 *               Single-topic mailboxes resume exactly after the last message instead.
	 */
	public Mailbox(String name, List<String> topics, String window) {
		this.name = name;
		this.topics = List.copyOf(topics);
		this.window = window;
	}

	public Status status() {
		return status;
	}

	/** Changes which topics are listened to (e.g. a friend was added) and reconnects. */
	public void setTopics(List<String> newTopics) {
		if (!newTopics.equals(topics)) {
			topics = List.copyOf(newTopics);
			dropConnection();
		}
	}

	// ================================================================ sending

	/** Told about every message the relay accepted (for the "friend messages today" counter). */
	public static volatile Runnable onSent = () -> {
	};

	/** Posts a text message, retrying network errors and server errors a few times. */
	public static CompletableFuture<Sent> post(String topic, String message) {
		if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE) {
			return CompletableFuture.completedFuture(new Sent(413));
		}
		return withRetries(() -> HttpRequest.newBuilder(URI.create(SERVER + "/" + topic))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "JavaHost")
				.POST(HttpRequest.BodyPublishers.ofString(message))
				.build(), 0);
	}

	/** Uploads a file (already encrypted) with a short text message describing it. */
	public static CompletableFuture<Sent> postAttachment(String topic, byte[] data, String message) {
		if (data.length > MAX_ATTACHMENT || !message.matches("[\\x20-\\x7E]*")) {
			return CompletableFuture.completedFuture(new Sent(413));
		}
		return withRetries(() -> HttpRequest.newBuilder(URI.create(SERVER + "/" + topic))
				.timeout(Duration.ofMinutes(2))
				.header("User-Agent", "JavaHost")
				.header("Filename", "javahost.bin")
				.header("Message", message)
				.PUT(HttpRequest.BodyPublishers.ofByteArray(data))
				.build(), 0);
	}

	private static CompletableFuture<Sent> withRetries(java.util.function.Supplier<HttpRequest> request, int attempt) {
		return HTTP.sendAsync(request.get(), HttpResponse.BodyHandlers.discarding())
				.thenApply(response -> {
					Sent sent = new Sent(response.statusCode());
					if (sent.ok()) {
						onSent.run();
					}
					return sent;
				})
				.exceptionally(error -> new Sent(-1))
				.thenCompose(sent -> {
					boolean retry = (sent.status() == -1 || sent.status() >= 500) && attempt < 3;
					if (!retry) {
						return CompletableFuture.completedFuture(sent);
					}
					long delay = (long) (1000 * Math.pow(3, attempt));
					return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS))
							.thenCompose(ignored -> withRetries(request, attempt + 1));
				});
	}

	/** Downloads an attachment. Only files hosted by our message relay are fetched. */
	public static CompletableFuture<byte[]> download(String url) {
		URI uri = URI.create(url);
		if (!uri.getHost().equalsIgnoreCase(URI.create(SERVER).getHost())) {
			return CompletableFuture.failedFuture(new IOException("Refusing to download from " + uri.getHost()));
		}
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).header("User-Agent", "JavaHost").GET().build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApply(response -> {
			try (InputStream in = response.body()) {
				if (response.statusCode() != 200) {
					throw new IOException("HTTP " + response.statusCode());
				}
				byte[] data = in.readNBytes(MAX_ATTACHMENT + 1);
				if (data.length > MAX_ATTACHMENT) {
					throw new IOException("Attachment too large");
				}
				return data;
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
	}

	// ================================================================ receiving

	/**
	 * Listens in the background until {@link #stop()}. Single-topic mailboxes start after {@code since} (a message
	 * id, or a duration like "12h") and then resume after the last message they saw.
	 */
	public void start(String since, Consumer<Message> onMessage, Consumer<Status> onStatus) {
		running = true;
		cursor = since;
		thread = new Thread(() -> listen(onMessage, onStatus), "JavaHost " + name);
		thread.setDaemon(true);
		thread.start();
		Thread watchdog = new Thread(this::watch, "JavaHost " + name + " watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	private void listen(Consumer<Message> onMessage, Consumer<Status> onStatus) {
		int backoff = 2;
		while (running) {
			List<String> listening = topics;
			if (listening.isEmpty()) {
				setStatus(Status.ONLINE, onStatus);
				sleep(1000);
				continue;
			}
			try {
				String since = listening.size() == 1 ? cursor : window;
				HttpRequest request = HttpRequest.newBuilder(URI.create(SERVER + "/" + String.join(",", listening) + "/json?since="
								+ URLEncoder.encode(since, StandardCharsets.UTF_8)))
						.header("User-Agent", "JavaHost")
						.GET()
						.build();
				HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) {
					response.body().close();
					throw new IOException("HTTP " + response.statusCode());
				}
				current = response.body();
				lastActivity = System.currentTimeMillis();
				setStatus(Status.ONLINE, onStatus);
				backoff = 2;
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(current, StandardCharsets.UTF_8))) {
					String line;
					while (running && listening == topics && (line = reader.readLine()) != null) {
						lastActivity = System.currentTimeMillis();
						if (line.isBlank()) {
							continue;
						}
						JsonObject event = JsonParser.parseString(line).getAsJsonObject();
						if (!"message".equals(event.get("event").getAsString())) {
							continue; // "open" and "keepalive"
						}
						String id = event.get("id").getAsString();
						if (listening.size() == 1) {
							cursor = id;
						}
						String text = event.has("message") ? event.get("message").getAsString() : "";
						String url = null;
						long size = 0;
						if (event.has("attachment")) {
							JsonObject attachment = event.getAsJsonObject("attachment");
							url = attachment.get("url").getAsString();
							size = attachment.has("size") ? attachment.get("size").getAsLong() : 0;
						}
						onMessage.accept(new Message(id, event.get("topic").getAsString(), event.get("time").getAsLong(), text, url, size));
					}
				}
				// The server ended the stream (or we changed topics): reconnect straight away.
			} catch (Exception e) {
				if (!running) {
					break;
				}
				if (listening == topics) {
					setStatus(Status.OFFLINE, onStatus);
					sleep(backoff * 1000L);
					backoff = Math.min(backoff * 2, 60);
				}
			} finally {
				current = null;
			}
		}
	}

	/** Reconnects when the stream has gone quiet for too long (a connection that died without telling us). */
	private void watch() {
		while (running) {
			sleep(Math.min(5000, STALL_MS / 4));
			if (current != null && System.currentTimeMillis() - lastActivity > STALL_MS) {
				dropConnection();
			}
		}
	}

	private void dropConnection() {
		InputStream stream = current;
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException ignored) {
				// Closing is all we wanted.
			}
		}
	}

	private void setStatus(Status next, Consumer<Status> onStatus) {
		if (status != next) {
			status = next;
			onStatus.accept(next);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void stop() {
		running = false;
		dropConnection();
		if (thread != null) {
			thread.interrupt();
		}
	}
}

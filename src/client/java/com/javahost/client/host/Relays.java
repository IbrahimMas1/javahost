package com.javahost.client.host;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.util.IoExecutor;
import com.javahost.util.Text;
import net.fabricmc.loader.api.FabricLoader;

/**
 * JavaHost's own relays: where two friends meet when their routers won't let them connect straight to each other
 * (see {@code util/RelayServer}). The list is a small file on the JavaHost website, so relays can be added or moved
 * without a new version of the mod; your own can be added in the config ("relays": ["host:port"]).
 */
public final class Relays {
	/** A relay: what to call it ("UAE"), and where it is. */
	public record Relay(String name, InetSocketAddress address) {
	}

	private static final String LIST = "https://ibrahimmas1.github.io/javahost/relays.json";
	private static final int MAX = 12;
	private static final long REFRESH_MS = 6 * 60 * 60 * 1000L;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).executor(IoExecutor.NET).build();

	private static volatile List<Relay> relays = List.of();
	private static volatile long loadedAt;
	private static volatile boolean loading;

	private Relays() {
	}

	/** The relays known right now (empty until the list has been read). Refreshes itself in the background. */
	public static List<Relay> list() {
		refresh();
		return relays;
	}

	public static List<InetSocketAddress> addresses() {
		return list().stream().map(Relay::address).toList();
	}

	/** The name of the relay at {@code address}, or "relay". */
	public static String name(InetSocketAddress address) {
		for (Relay relay : relays) {
			if (relay.address().equals(address)) {
				return relay.name();
			}
		}
		return "relay";
	}

	/** Forgets the list and reads it again now. */
	public static void reload() {
		loadedAt = 0;
		refresh();
	}

	/** Reads the list again if it's old (off the game thread). */
	public static void refresh() {
		if (loading || (loadedAt != 0 && System.currentTimeMillis() - loadedAt < REFRESH_MS)) {
			return;
		}
		loading = true;
		IoExecutor.NET.execute(() -> {
			try {
				List<Relay> found = new ArrayList<>();
				// Development only: relays for the automated tests.
				String test = System.getProperty("javahost.relays");
				if (test != null && FabricLoader.getInstance().isDevelopmentEnvironment()) {
					for (String entry : test.split(",")) {
						add(found, "Test", entry.trim());
					}
				} else {
					for (String entry : Config.get().relays) {
						add(found, "Yours", entry);
					}
					try {
						HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(LIST)).timeout(Duration.ofSeconds(10)).GET().build(),
								HttpResponse.BodyHandlers.ofString());
						if (response.statusCode() == 200 && response.body().length() < 16_384) {
							JsonArray array = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("relays");
							for (JsonElement element : array) {
								JsonObject relay = element.getAsJsonObject();
								add(found, Text.clean(relay.get("name").getAsString(), 24), relay.get("address").getAsString());
							}
						}
					} catch (Exception e) {
						JavaHost.LOGGER.debug("[Relays] couldn't read the relay list: {}", e.toString());
					}
				}
				relays = List.copyOf(found);
				loadedAt = System.currentTimeMillis();
				JavaHost.LOGGER.info("[Relays] {} known", found.size());
			} finally {
				loading = false;
			}
		});
	}

	/** Adds "host:port" if it's well formed and its name resolves (this is our own short list, so names are looked up). */
	private static void add(List<Relay> into, String name, String entry) {
		if (entry == null || into.size() >= MAX) {
			return;
		}
		int colon = entry.lastIndexOf(':');
		if (colon <= 0 || !entry.substring(0, colon).matches("[A-Za-z0-9.-]{1,253}|\\[[0-9a-fA-F:]{2,45}]")) {
			return;
		}
		try {
			int port = Integer.parseInt(entry.substring(colon + 1));
			String host = entry.substring(0, colon).replace("[", "").replace("]", "");
			if (port < 1 || port > 65535) {
				return;
			}
			InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), port);
			if (into.stream().noneMatch(r -> r.address().equals(address))) {
				into.add(new Relay(name.isEmpty() ? "Relay" : name, address));
			}
		} catch (Exception e) {
			// a relay that can't be found right now is simply left out
		}
	}
}

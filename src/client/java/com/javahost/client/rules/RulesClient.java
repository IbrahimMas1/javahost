package com.javahost.client.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.net.Payloads.RulesChange;
import com.javahost.net.Payloads.RulesState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The world rules as the server last described them, and requests to change them (the server decides). */
public final class RulesClient {
	public record CoHost(UUID id, String name) {
	}

	public record Change(long time, String who, String what) {
	}

	public record State(Map<String, String> values, boolean locked, boolean host, boolean canEdit, List<CoHost> coHosts, List<Change> history) {
	}

	private static volatile State state;
	private static volatile int version;

	private RulesClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RulesState.TYPE, (payload, context) -> {
			state = parse(payload.json());
			version++;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> state = null);
	}

	/** Null when the world isn't run by JavaHost 1.5+. */
	public static State state() {
		return state;
	}

	/** Changes whenever new rules arrive (pages use it to know when to redraw). */
	public static int version() {
		return version;
	}

	public static void request(String rule, String value) {
		if (ClientPlayNetworking.canSend(RulesChange.TYPE)) {
			ClientPlayNetworking.send(new RulesChange(rule, value));
		}
	}

	private static State parse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		Map<String, String> values = new LinkedHashMap<>();
		root.getAsJsonObject("values").entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
		List<CoHost> coHosts = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("coHosts")) {
			JsonObject c = e.getAsJsonObject();
			coHosts.add(new CoHost(UUID.fromString(c.get("id").getAsString()), c.get("name").getAsString()));
		}
		List<Change> history = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("history")) {
			JsonObject h = e.getAsJsonObject();
			history.add(new Change(h.get("time").getAsLong(), h.get("who").getAsString(), h.get("what").getAsString()));
		}
		return new State(values, root.get("locked").getAsBoolean(), root.get("host").getAsBoolean(),
				root.get("canEdit").getAsBoolean(), coHosts, history);
	}
}

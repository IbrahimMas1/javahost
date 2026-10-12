package com.javahost.client.friends;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.javahost.JavaHost;
import net.fabricmc.loader.api.FabricLoader;

/** Chat history with each friend, kept on this computer only (the last {@link #MAX_PER_FRIEND} messages each). */
public final class Chats {
	public record Line(boolean mine, String text, long time) {
	}

	public static final int MAX_PER_FRIEND = 200;
	public static final int MAX_LENGTH = 256;
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("javahost-chats.json");
	private static final Gson GSON = new Gson();
	private static Map<String, List<Line>> history = new HashMap<>();
	private static final Map<UUID, Integer> UNREAD = new HashMap<>();
	private static boolean loaded;

	private Chats() {
	}

	public static synchronized List<Line> with(UUID friend) {
		load();
		return List.copyOf(history.getOrDefault(friend.toString(), List.of()));
	}

	public static synchronized void add(UUID friend, Line line) {
		load();
		List<Line> lines = history.computeIfAbsent(friend.toString(), k -> new ArrayList<>());
		lines.add(line);
		com.javahost.util.History.keepNewest(lines, MAX_PER_FRIEND);
		if (!line.mine()) {
			UNREAD.merge(friend, 1, Integer::sum);
		}
		save();
	}

	public static synchronized int unread(UUID friend) {
		return UNREAD.getOrDefault(friend, 0);
	}

	public static synchronized int unreadTotal() {
		return UNREAD.values().stream().mapToInt(Integer::intValue).sum();
	}

	public static synchronized void markRead(UUID friend) {
		UNREAD.remove(friend);
	}

	public static synchronized void forget(UUID friend) {
		load();
		history.remove(friend.toString());
		UNREAD.remove(friend);
		save();
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (!Files.exists(FILE)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			Map<String, List<Line>> data = GSON.fromJson(reader, new TypeToken<Map<String, List<Line>>>() {
			}.getType());
			if (data != null) {
				data.replaceAll((k, v) -> new ArrayList<>(v));
				history = data;
			}
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not read chat history", e);
		}
	}

	private static void save() {
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			GSON.toJson(history, writer);
		} catch (Exception e) {
			JavaHost.LOGGER.warn("Could not save chat history", e);
		}
	}
}

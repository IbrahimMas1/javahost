package com.javahost.client.friends;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.javahost.JavaHost;
import com.javahost.util.IoExecutor;
import com.javahost.util.Text;
import net.fabricmc.loader.api.FabricLoader;

/**
 * What you remember about your friends, kept on this computer only (config/javahost-friendlog.json): who your
 * favourites are, when each friend was last seen online, and a short list of what they've been up to.
 */
public final class FriendLog {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("javahost-friendlog.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_ACTIVITY = 40;
	private static final int MAX_FRIENDS = 2000;
	/** A friend whose status keeps arriving within this long has simply stayed online. */
	private static final long SAME_SESSION_MS = 45 * 60 * 1000L;

	/** One line of the activity list: {@code name} did {@code what} at {@code time}. */
	public static final class Entry {
		public long time;
		public String friend = "";
		public String name = "";
		public String what = "";
	}

	private static final class Data {
		Set<String> favourites = new LinkedHashSet<>();
		Map<String, Long> lastSeen = new HashMap<>();
		List<Entry> activity = new ArrayList<>();
	}

	private static Data data;
	private static boolean dirty;
	private static int ticks;

	private FriendLog() {
	}

	private static Data data() {
		if (data == null) {
			Data loaded = null;
			try {
				if (Files.isRegularFile(FILE) && Files.size(FILE) < 1_000_000) {
					loaded = GSON.fromJson(Files.readString(FILE), Data.class);
				}
			} catch (IOException | RuntimeException e) {
				JavaHost.LOGGER.warn("[Friends] couldn't read {}: {}", FILE.getFileName(), e.toString());
			}
			data = loaded != null ? loaded : new Data();
			if (data.favourites == null) {
				data.favourites = new LinkedHashSet<>();
			}
			if (data.lastSeen == null) {
				data.lastSeen = new HashMap<>();
			}
			if (data.activity == null) {
				data.activity = new ArrayList<>();
			}
			data.activity.removeIf(e -> e == null || e.name == null || e.what == null || e.friend == null);
			for (Entry entry : data.activity) {
				entry.name = Text.clean(entry.name, 32);
				entry.what = Text.clean(entry.what, 96);
			}
			while (data.activity.size() > MAX_ACTIVITY) {
				data.activity.remove(0);
			}
		}
		return data;
	}

	// ---------------------------------------------------------------- favourites

	public static synchronized boolean isFavourite(UUID friend) {
		return data().favourites.contains(friend.toString());
	}

	public static synchronized void toggleFavourite(UUID friend) {
		Set<String> favourites = data().favourites;
		if (!favourites.remove(friend.toString()) && favourites.size() < MAX_FRIENDS) {
			favourites.add(friend.toString());
		}
		dirty = true;
	}

	// ---------------------------------------------------------------- last seen and activity

	/** A friend's signed status arrived ({@code previous}: the one before it this session, or null). */
	static synchronized void seen(Presence.Status previous, Presence.Status status, String name) {
		Data d = data();
		String id = status.player().toString();
		Long before = d.lastSeen.get(id);
		if (before != null && before >= status.time()) {
			return; // already counted (statuses are replayed when the game starts)
		}
		boolean online = status.state() != Presence.State.OFFLINE;
		boolean wasOnline = previous != null ? previous.state() != Presence.State.OFFLINE
				: before != null && status.time() - before < SAME_SESSION_MS && !wentOffline(id);
		String what = null;
		if (status.hosting() && (previous == null || !previous.hosting())) {
			what = "started hosting " + status.world();
		} else if (online && !wasOnline) {
			what = "came online";
		} else if (!online && wasOnline) {
			what = "went offline";
		} else if (online && previous != null && previous.hosting() && !status.hosting()) {
			what = "stopped hosting";
		}
		if (d.lastSeen.size() < MAX_FRIENDS || before != null) {
			d.lastSeen.put(id, status.time());
		}
		if (what != null) {
			Entry entry = new Entry();
			entry.time = status.time();
			entry.friend = id;
			entry.name = Text.clean(name, 32);
			entry.what = Text.clean(what, 96);
			d.activity.add(entry);
			while (d.activity.size() > MAX_ACTIVITY) {
				d.activity.remove(0);
			}
		}
		dirty = true;
	}

	/** Whether the newest thing we wrote down about this friend is that they left. */
	private static boolean wentOffline(String id) {
		List<Entry> activity = data().activity;
		for (int i = activity.size() - 1; i >= 0; i--) {
			if (activity.get(i).friend.equals(id)) {
				return activity.get(i).what.equals("went offline");
			}
		}
		return false;
	}

	/** "Last seen 2h ago" for a friend who's offline now, or "Offline" if they were never seen. */
	public static synchronized String lastSeenText(UUID friend) {
		Long time = data().lastSeen.get(friend.toString());
		return time == null ? "Offline" : "Last seen " + ago(time);
	}

	/** The newest {@code limit} things friends did, newest first; only friends you still have. */
	public static synchronized List<Entry> activity(int limit) {
		List<Entry> out = new ArrayList<>();
		List<Entry> all = data().activity;
		for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
			Entry entry = all.get(i);
			try {
				if (Friends.friend(UUID.fromString(entry.friend)).isPresent()) {
					out.add(entry);
				}
			} catch (IllegalArgumentException e) {
				// not a player id: skip
			}
		}
		return out;
	}

	/** "just now", "5m ago", "3h ago", "yesterday", "4 days ago". */
	public static String ago(long time) {
		long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60_000L);
		return minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago" : minutes < 24 * 60 ? minutes / 60 + "h ago"
				: minutes < 48 * 60 ? "yesterday" : minutes / (24 * 60) + " days ago";
	}

	// ---------------------------------------------------------------- saving

	/** Every few seconds, writes what changed (off the game thread). */
	public static void tick() {
		if (++ticks % 100 != 0) {
			return;
		}
		String json = snapshot();
		if (json != null) {
			IoExecutor.POOL.execute(() -> write(json));
		}
	}

	public static void flushNow() {
		String json = snapshot();
		if (json != null) {
			write(json);
		}
	}

	private static synchronized String snapshot() {
		if (!dirty || data == null) {
			return null;
		}
		dirty = false;
		return GSON.toJson(data);
	}

	private static synchronized void write(String json) {
		try {
			Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			Files.writeString(temp, json);
			Files.move(temp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Friends] couldn't save {}: {}", FILE.getFileName(), e.toString());
		}
	}
}

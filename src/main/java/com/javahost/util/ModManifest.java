package com.javahost.util;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The mods a world needs: the host's content mods (anything that adds blocks, items, mobs and so on, which a
 * joining player must have too) and the libraries they depend on. Sent to joining players before Minecraft compares
 * registries, so a friend missing mods gets a clear list and a download button instead of "registry mismatch".
 *
 * <p>It arrives from another player's game, so it's untrusted: sizes are capped and text is cleaned on reading.
 * Pure Java (plus Gson), unit-tested.
 */
public record ModManifest(String host, String pack, List<Entry> mods) {
	public static final int MAX_MODS = 1000; // big modpacks run to several hundred mods
	public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

	/**
	 * One mod. {@code content}: it adds things to the game's registries, so the version must match exactly;
	 * otherwise it's a library a content mod needs, and any version will do. {@code modrinthVersion} is empty when
	 * the jar isn't on Modrinth; {@code page} is then where to get it (may be empty).
	 */
	public record Entry(String id, String name, String version, boolean content, String fileName, long size, String sha512,
			String modrinthVersion, String page) {
		public boolean onModrinth() {
			return !modrinthVersion.isEmpty();
		}
	}

	/** What a joining player has: mod id to version. */
	public interface Installed {
		String version(String modId);
	}

	/** The mods this player is missing (or has in another version) to join. */
	public List<Entry> missing(Installed installed) {
		List<Entry> missing = new ArrayList<>();
		for (Entry mod : mods) {
			String have = installed.version(mod.id());
			if (have == null || (mod.content() && !have.equals(mod.version()))) {
				missing.add(mod);
			}
		}
		return missing;
	}

	public long totalSize(List<Entry> entries) {
		return entries.stream().mapToLong(Entry::size).sum();
	}

	/** A short id for this exact set of mods (the same mods give the same id on every computer). */
	public String id() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			mods.stream().map(m -> m.id() + "@" + m.version() + "#" + m.sha512()).sorted()
					.forEach(line -> digest.update((line + "\n").getBytes(StandardCharsets.UTF_8)));
			return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public boolean empty() {
		return mods.isEmpty();
	}

	// ---------------------------------------------------------------- JSON

	public String toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("host", host);
		root.addProperty("pack", pack);
		JsonArray list = new JsonArray();
		for (Entry m : mods) {
			JsonObject o = new JsonObject();
			o.addProperty("id", m.id());
			o.addProperty("name", m.name());
			o.addProperty("version", m.version());
			o.addProperty("content", m.content());
			o.addProperty("file", m.fileName());
			o.addProperty("size", m.size());
			o.addProperty("sha512", m.sha512());
			o.addProperty("modrinth", m.modrinthVersion());
			o.addProperty("page", m.page());
			list.add(o);
		}
		root.add("mods", list);
		return root.toString();
	}

	/** Reads (and checks) a manifest from another player; throws IllegalArgumentException if it's malformed. */
	public static ModManifest fromJson(String json) {
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			JsonArray list = root.getAsJsonArray("mods");
			if (list == null || list.size() > MAX_MODS) {
				throw new IllegalArgumentException("too many mods");
			}
			List<Entry> mods = new ArrayList<>();
			long total = 0;
			for (JsonElement element : list) {
				JsonObject o = element.getAsJsonObject();
				String id = string(o, "id", 64);
				String fileName = string(o, "file", 128);
				String sha512 = string(o, "sha512", 128).toLowerCase(java.util.Locale.ROOT);
				String modrinth = string(o, "modrinth", 16);
				long size = o.has("size") ? o.get("size").getAsLong() : 0;
				if (!id.matches("[a-z][a-z0-9_-]{0,63}") || !safeJarName(fileName) || !sha512.matches("[0-9a-f]{128}")
						|| !modrinth.matches("[A-Za-z0-9]{0,16}") || size < 0) {
					throw new IllegalArgumentException("bad mod entry " + id);
				}
				total += size;
				mods.add(new Entry(id, Text.clean(string(o, "name", 64), 64), Text.clean(string(o, "version", 64), 64),
						o.has("content") && o.get("content").getAsBoolean(), fileName, size, sha512, modrinth, page(string(o, "page", 200))));
			}
			if (total > MAX_TOTAL_BYTES) {
				throw new IllegalArgumentException("mods too big");
			}
			return new ModManifest(Text.clean(string(root, "host", 32), 32), Text.clean(string(root, "pack", 64), 64), List.copyOf(mods));
		} catch (IllegalStateException | ClassCastException | UnsupportedOperationException | com.google.gson.JsonParseException e) {
			throw new IllegalArgumentException("unreadable mod list", e);
		}
	}

	/** A plain jar file name: no folders, nothing hidden. */
	public static boolean safeJarName(String name) {
		return name.matches("[A-Za-z0-9][A-Za-z0-9._+ ()\\[\\]-]{0,126}\\.jar") && !name.contains("..");
	}

	/** Only web pages, shown as a link the player chooses to open. */
	private static String page(String url) {
		return url.matches("https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~%/?#&=+:@-]*)?") ? url : "";
	}

	private static String string(JsonObject o, String key, int max) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return "";
		}
		String s = e.getAsString();
		return s.length() > max ? s.substring(0, max) : s;
	}

	/**
	 * Mod ids never required of a joining player: the game itself, the loader, and JavaHost with what it needs
	 * (every JavaHost player has Fabric API).
	 */
	public static final java.util.Set<String> ALWAYS_PRESENT = java.util.Set.of("minecraft", "java", "fabricloader", "javahost", "mixinextras",
			"fabric-api");

	/** For tests and callers with a map. */
	public static Installed of(Map<String, String> versions) {
		return versions::get;
	}
}

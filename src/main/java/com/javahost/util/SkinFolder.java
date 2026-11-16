package com.javahost.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A player's saved skins: a folder of {@code <id>.png} files (64x64, ids made up here, never taken from a file
 * name) and an {@code index.json} with each skin's name, model and history. PNGs dropped into the folder by hand
 * are taken in on {@link #load()}. The index is written atomically. Not thread-safe on its own across processes;
 * within the game every call is synchronized. Disk work: call it off the render thread.
 */
public final class SkinFolder {
	public static final int MAX_SKINS = 200;
	public static final int MAX_NAME = 32;
	public static final String CLASSIC = "classic";
	public static final String SLIM = "slim";
	/** The index can't legitimately be bigger than this (200 short entries). */
	private static final long MAX_INDEX_BYTES = 512 * 1024;
	/** Loose files looked at per load, so a folder full of junk can't stall the game. */
	private static final int MAX_LOOSE = 64;
	private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");
	private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{32}");
	private static final SecureRandom RANDOM = new SecureRandom();

	/** One saved skin. {@code source}: where it came from ("file", "folder", "account", "player:Name", "editor"). */
	public record Entry(String id, String name, String model, long added, long lastWorn, String source, String fingerprint) {
		public boolean slim() {
			return SLIM.equals(model);
		}

		Entry withName(String name) {
			return new Entry(id, name, model, added, lastWorn, source, fingerprint);
		}

		Entry withModel(String model, String fingerprint) {
			return new Entry(id, name, model, added, lastWorn, source, fingerprint);
		}

		Entry withLastWorn(long lastWorn) {
			return new Entry(id, name, model, added, lastWorn, source, fingerprint);
		}

		Entry withFingerprint(String fingerprint) {
			return new Entry(id, name, model, added, lastWorn, source, fingerprint);
		}
	}

	/** What {@link #add} did: the new entry, or the one that already had this picture ({@code existed}). */
	public record Added(Entry entry, boolean existed) {
	}

	/** The collection already holds {@link #MAX_SKINS}. */
	public static final class Full extends Exception {
		public Full() {
			super("You have " + MAX_SKINS + " skins saved, the most there's room for. Delete one first.");
		}
	}

	private final Path dir;
	private final List<Entry> entries = new ArrayList<>();
	/** The skin the account wears, as far as we know, and the address Minecraft gave for it. */
	private String worn;
	private String wornUrl;
	/** Loose files found on the last load that weren't skins. */
	private int refused;

	public SkinFolder(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	/** Reads the index (forgiving anything odd in it) and takes in PNGs dropped into the folder. */
	public synchronized void load() throws IOException {
		Files.createDirectories(dir);
		entries.clear();
		worn = null;
		wornUrl = null;
		refused = 0;
		readIndex();
		boolean changed = false;
		// Drop entries whose file is gone, and work out fingerprints the index didn't have.
		for (int i = entries.size() - 1; i >= 0; i--) {
			Entry entry = entries.get(i);
			Path file = file(entry.id());
			if (!Files.isRegularFile(file)) {
				entries.remove(i);
				changed = true;
			} else if (entry.fingerprint().isEmpty()) {
				try {
					entries.set(i, entry.withFingerprint(SkinImage.fingerprint(SkinImage.read(readCapped(file)), entry.model())));
				} catch (SkinImage.Invalid e) {
					entries.remove(i);
				}
				changed = true;
			}
		}
		if (worn != null && entries.stream().noneMatch(e -> e.id().equals(worn))) {
			worn = null;
			changed = true;
		}
		changed |= takeLooseFiles();
		if (changed) {
			save();
		}
	}

	private void readIndex() {
		Path index = dir.resolve("index.json");
		JsonObject root;
		try {
			if (!Files.isRegularFile(index) || Files.size(index) > MAX_INDEX_BYTES) {
				return;
			}
			JsonElement parsed = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				return;
			}
			root = parsed.getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			return; // unreadable: start over; the PNGs are taken in again as loose files
		}
		Set<String> seen = new HashSet<>();
		Set<String> fingerprints = new HashSet<>();
		JsonElement list = root.get("skins");
		if (list != null && list.isJsonArray()) {
			for (JsonElement element : list.getAsJsonArray()) {
				if (entries.size() >= MAX_SKINS) {
					break;
				}
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject o = element.getAsJsonObject();
				String id = string(o, "id");
				if (!ID.matcher(id).matches() || !seen.add(id)) {
					continue;
				}
				String model = SLIM.equals(string(o, "model")) ? SLIM : CLASSIC;
				String fingerprint = string(o, "fingerprint");
				if (!FINGERPRINT.matcher(fingerprint).matches()) {
					fingerprint = "";
				}
				if (!fingerprint.isEmpty() && !fingerprints.add(fingerprint)) {
					continue; // the same picture twice
				}
				String name = cleanName(string(o, "name"));
				entries.add(new Entry(id, name.isEmpty() ? "Skin" : name, model, number(o, "added"), number(o, "lastWorn"),
						Text.clean(string(o, "source"), 40), fingerprint));
			}
		}
		String w = string(root, "worn");
		worn = ID.matcher(w).matches() ? w : null;
		String url = string(root, "wornUrl");
		wornUrl = url.length() <= 300 && url.startsWith("http") ? url : null;
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
	}

	private static long number(JsonObject o, String key) {
		JsonElement e = o.get(key);
		try {
			return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? Math.max(0, e.getAsLong()) : 0;
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	/** Takes in PNG files dropped into the folder by hand: each is checked, saved under a new id, and the original removed. */
	private boolean takeLooseFiles() throws IOException {
		List<Path> loose = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{png,PNG}")) {
			for (Path file : stream) {
				String fileName = file.getFileName().toString();
				String base = fileName.substring(0, fileName.length() - 4);
				if (!base.startsWith(".") && get(base) == null && Files.isRegularFile(file)) {
					loose.add(file);
				}
				if (loose.size() >= MAX_LOOSE) {
					break;
				}
			}
		}
		loose.sort(Comparator.comparing(p -> p.getFileName().toString()));
		boolean changed = false;
		for (Path file : loose) {
			String fileName = file.getFileName().toString();
			String name = cleanName(fileName.substring(0, fileName.length() - 4).replace('_', ' ').replace('-', ' '));
			try {
				int[] skin = SkinImage.read(readCapped(file));
				addPixels(skin, name.isEmpty() || ID.matcher(name).matches() ? "Skin" : name, CLASSIC, "folder");
				Files.deleteIfExists(file);
				changed = true;
			} catch (SkinImage.Invalid | Full e) {
				refused++;
			}
		}
		return changed;
	}

	/** Loose files on the last load that weren't skins (or didn't fit), left where they were. */
	public synchronized int refused() {
		return refused;
	}

	private static byte[] readCapped(Path file) throws IOException, SkinImage.Invalid {
		if (Files.size(file) > SkinImage.MAX_BYTES) {
			throw new SkinImage.Invalid("That file is too big to be a skin.");
		}
		return Files.readAllBytes(file);
	}

	/** Newest first. */
	public synchronized List<Entry> entries() {
		List<Entry> list = new ArrayList<>(entries);
		list.sort(Comparator.comparingLong(Entry::added).reversed().thenComparing(Entry::id));
		return List.copyOf(list);
	}

	public synchronized Entry get(String id) {
		return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
	}

	public synchronized int size() {
		return entries.size();
	}

	/** The id of the skin the account wears now (as far as we know), or null. */
	public synchronized String worn() {
		return worn;
	}

	/** The address Minecraft gave for the skin we last saw on the account (to notice it changed elsewhere). */
	public synchronized String wornUrl() {
		return wornUrl;
	}

	/** Where a skin's PNG is kept. Only ever made from an id this class generated. */
	public Path file(String id) {
		if (!ID.matcher(id).matches()) {
			throw new IllegalArgumentException("bad skin id");
		}
		return dir.resolve(id + ".png");
	}

	/** A skin's file, as saved (64x64). */
	public byte[] read(String id) throws IOException {
		return Files.readAllBytes(file(id));
	}

	/** Adds a skin from PNG bytes (64x64, or an old 64x32 that's converted). */
	public synchronized Added add(byte[] png, String name, String model, String source) throws SkinImage.Invalid, Full, IOException {
		Added added = addPixels(SkinImage.read(png), name, model, source);
		if (!added.existed()) {
			save();
		}
		return added;
	}

	/** Adds a skin from 64x64 pixels (e.g. made in an editor). */
	public synchronized Added add(int[] skin, String name, String model, String source) throws Full, IOException {
		checkPixels(skin);
		Added added = addPixels(skin, name, model, source);
		if (!added.existed()) {
			save();
		}
		return added;
	}

	private Added addPixels(int[] skin, String name, String model, String source) throws Full, IOException {
		model = SLIM.equals(model) ? SLIM : CLASSIC;
		String fingerprint = SkinImage.fingerprint(skin, model);
		for (Entry entry : entries) {
			if (entry.fingerprint().equals(fingerprint)) {
				return new Added(entry, true);
			}
		}
		if (entries.size() >= MAX_SKINS) {
			throw new Full();
		}
		String id = newId();
		write(file(id), SkinImage.png(skin));
		String clean = cleanName(name);
		Entry entry = new Entry(id, clean.isEmpty() ? "Skin" : clean, model, nextAdded(), 0, Text.clean(source, 40), fingerprint);
		entries.add(entry);
		return new Added(entry, false);
	}

	/** Strictly increasing, so the newest really sorts first even when several are added in one millisecond. */
	private long nextAdded() {
		long now = System.currentTimeMillis();
		for (Entry entry : entries) {
			now = Math.max(now, entry.added() + 1);
		}
		return now;
	}

	/** Replaces a skin's picture (for the skin editor). False if there's no such skin. */
	public synchronized boolean update(String id, int[] skin) throws IOException {
		checkPixels(skin);
		Entry entry = get(id);
		if (entry == null) {
			return false;
		}
		write(file(id), SkinImage.png(skin));
		replace(entry.withFingerprint(SkinImage.fingerprint(skin, entry.model())));
		save();
		return true;
	}

	private static void checkPixels(int[] skin) {
		if (skin == null || skin.length != SkinImage.SIZE * SkinImage.SIZE) {
			throw new IllegalArgumentException("a skin is 64x64 pixels");
		}
	}

	public synchronized boolean rename(String id, String name) throws IOException {
		Entry entry = get(id);
		String clean = cleanName(name);
		if (entry == null || clean.isEmpty()) {
			return false;
		}
		replace(entry.withName(clean));
		save();
		return true;
	}

	/** Classic or slim arms. */
	public synchronized boolean setModel(String id, String model) throws IOException {
		Entry entry = get(id);
		if (entry == null) {
			return false;
		}
		model = SLIM.equals(model) ? SLIM : CLASSIC;
		String fingerprint = entry.fingerprint();
		try {
			fingerprint = SkinImage.fingerprint(SkinImage.read(readCapped(file(id))), model);
		} catch (SkinImage.Invalid e) {
			// keep the old one
		}
		replace(entry.withModel(model, fingerprint));
		save();
		return true;
	}

	public synchronized boolean delete(String id) throws IOException {
		Entry entry = get(id);
		if (entry == null) {
			return false;
		}
		entries.remove(entry);
		Files.deleteIfExists(file(id));
		if (id.equals(worn)) {
			worn = null;
		}
		save();
		return true;
	}

	/** Records that the account now wears {@code id} (null: Minecraft's default skin), shown at {@code url}. */
	public synchronized void markWorn(String id, String url) throws IOException {
		Entry entry = id == null ? null : get(id);
		worn = entry == null ? null : id;
		wornUrl = url != null && url.length() <= 300 ? url : null;
		if (entry != null) {
			replace(entry.withLastWorn(System.currentTimeMillis()));
		}
		save();
	}

	private void replace(Entry entry) {
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).id().equals(entry.id())) {
				entries.set(i, entry);
				return;
			}
		}
	}

	private String newId() {
		while (true) {
			byte[] bytes = new byte[6];
			RANDOM.nextBytes(bytes);
			String id = HexFormat.of().formatHex(bytes);
			if (get(id) == null && !Files.exists(dir.resolve(id + ".png"))) {
				return id;
			}
		}
	}

	/** A name as shown: formatting codes and control characters removed, one line, at most {@link #MAX_NAME} characters. */
	public static String cleanName(String name) {
		String clean = Text.clean(name == null ? "" : name.replace('\n', ' ').replace('\r', ' '), 200).replaceAll("\\s+", " ").strip();
		return clean.length() <= MAX_NAME ? clean : clean.substring(0, MAX_NAME).strip();
	}

	private void save() throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		if (worn != null) {
			root.addProperty("worn", worn);
		}
		if (wornUrl != null) {
			root.addProperty("wornUrl", wornUrl);
		}
		JsonArray list = new JsonArray();
		for (Entry entry : entries) {
			JsonObject o = new JsonObject();
			o.addProperty("id", entry.id());
			o.addProperty("name", entry.name());
			o.addProperty("model", entry.model());
			o.addProperty("added", entry.added());
			o.addProperty("lastWorn", entry.lastWorn());
			o.addProperty("source", entry.source());
			o.addProperty("fingerprint", entry.fingerprint());
			list.add(o);
		}
		root.add("skins", list);
		write(dir.resolve("index.json"), root.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Writes a file whole or not at all: to a temporary file first, then moved over the old one. */
	private static void write(Path target, byte[] data) throws IOException {
		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling("." + target.getFileName() + ".tmp");
		Files.write(temp, data);
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** For messages: "classic" or "slim" from anything. */
	public static String model(String text) {
		return text != null && text.toLowerCase(Locale.ROOT).equals(SLIM) ? SLIM : CLASSIC;
	}
}

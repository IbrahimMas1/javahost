package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What one jar in mods/ is, from its top-level {@code fabric.mod.json}: id, name, version, what it depends on and what
 * it provides. Jars are whatever the player put in the folder, so this never throws: a jar that isn't a Fabric mod, or
 * can't be read, gives {@code id == null} and its file name as its name. Sizes and counts are capped, text is cleaned.
 *
 * <p>Used in-game only (Gson), not by the after-exit helpers.
 */
public record ModJar(String file, String id, String name, String version, String description, List<String> depends,
		List<String> provides, String environment, boolean parsed) {
	/** A {@code fabric.mod.json} bigger than this is treated as unreadable. */
	public static final int MAX_JSON_BYTES = 1 << 20;
	/** At most this many jars are listed from one folder. */
	public static final int MAX_JARS = 2000;
	static final int MAX_IDS = 256;
	/** Dependencies everyone has; never a reason to turn anything on or off. */
	static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader");
	/** Fabric's rule for mod ids (a little looser on length). */
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

	public ModJar {
		depends = List.copyOf(depends);
		provides = List.copyOf(provides);
	}

	/** Not a Fabric mod, or unreadable. */
	static ModJar unknown(String file) {
		return new ModJar(file, null, file, "", "", List.of(), List.of(), "*", false);
	}

	/** The ids this jar answers to: its own and the ones it provides. */
	public Set<String> ids() {
		Set<String> ids = new LinkedHashSet<>();
		if (id != null) {
			ids.add(id);
		}
		ids.addAll(provides);
		return ids;
	}

	/** Reads one jar. Never throws. */
	public static ModJar read(Path jar) {
		String file = jar.getFileName() == null ? "" : jar.getFileName().toString();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null || entry.isDirectory() || entry.getSize() > MAX_JSON_BYTES) {
				return unknown(file);
			}
			byte[] bytes;
			try (InputStream in = zip.getInputStream(entry)) {
				bytes = in.readNBytes(MAX_JSON_BYTES + 1); // the size in the header can lie
			}
			if (bytes.length > MAX_JSON_BYTES) {
				return unknown(file);
			}
			return parse(file, new String(bytes, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException | StackOverflowError e) {
			return unknown(file);
		}
	}

	/** Parses {@code fabric.mod.json} text. Never throws. Public for tests. */
	public static ModJar parse(String file, String json) {
		try {
			if (json.startsWith("﻿")) {
				json = json.substring(1);
			}
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			String id = id(string(root.get("id")));
			if (id == null) {
				return unknown(file);
			}
			String name = Text.clean(string(root.get("name")), 100);
			String version = Text.clean(string(root.get("version")), 64);
			String description = Text.clean(string(root.get("description")), 300);
			String environment = string(root.get("environment"));
			environment = switch (environment == null ? "*" : environment) {
				case "client", "server" -> environment;
				default -> "*";
			};
			List<String> depends = new ArrayList<>();
			if (root.get("depends") instanceof JsonObject deps) {
				for (String key : deps.keySet()) {
					String dep = id(key);
					if (dep != null && !BUILT_IN.contains(dep) && !dep.equals(id) && !depends.contains(dep) && depends.size() < MAX_IDS) {
						depends.add(dep);
					}
				}
			}
			List<String> provides = new ArrayList<>();
			if (root.get("provides") instanceof JsonArray list) {
				for (JsonElement element : list) {
					// A plain id, or (some loaders) an object with an "id".
					String provided = id(element instanceof JsonObject object ? string(object.get("id")) : string(element));
					if (provided != null && !provided.equals(id) && !provides.contains(provided) && provides.size() < MAX_IDS) {
						provides.add(provided);
					}
				}
			}
			return new ModJar(file, id, name.isEmpty() ? id : name, version, description, depends, provides, environment, true);
		} catch (RuntimeException | StackOverflowError e) {
			return unknown(file);
		}
	}

	/**
	 * Every {@code *.jar} file directly in {@code dir} (not folders, not {@code .disabled} and the like), sorted by
	 * file name, ignoring case. A missing or unreadable folder gives an empty list.
	 */
	public static List<ModJar> scan(Path dir) {
		List<Path> jars;
		try (Stream<Path> files = Files.list(dir)) {
			jars = files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p))
					.sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
							.thenComparing(p -> p.getFileName().toString()))
					.limit(MAX_JARS).toList();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
		return jars.stream().map(ModJar::read).toList();
	}

	private static String string(JsonElement element) {
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static String id(String text) {
		if (text == null) {
			return null;
		}
		String id = text.strip();
		return ID.matcher(id).matches() ? id : null;
	}
}

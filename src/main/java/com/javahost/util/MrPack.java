package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads a Modrinth modpack (.mrpack): a zip with {@code modrinth.index.json} listing the files to download (with
 * hashes) plus {@code overrides/} (mostly config files). Every path is checked before anything is written, files may
 * only come from the hosts Modrinth allows, and sizes are capped. Pure Java (plus Gson), unit-tested.
 */
public final class MrPack {
	/** Files a pack may list for download (big Fabric packs list several hundred mods). */
	public static final int MAX_FILES = 2000;
	/** Files a pack may unpack from overrides/ (configs, quests and scripts can run into the thousands). */
	public static final int MAX_OVERRIDE_FILES = 50_000;
	public static final long MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024;
	/** Folders a pack may put files in, relative to the game folder. */
	private static final List<String> ALLOWED_ROOTS = List.of("mods/", "config/", "resourcepacks/", "shaderpacks/");

	/** A file to download: where it goes (e.g. "mods/sodium.jar"), its hash, size and download addresses. */
	public record PackFile(String path, String sha512, long size, List<String> urls) {
	}

	/** {@code name}/{@code version}: the pack's own. {@code minecraft}: the game version it's made for. */
	public record Index(String name, String version, String minecraft, String fabricLoader, List<PackFile> files) {
	}

	private MrPack() {
	}

	/** Reads and checks the index; throws IOException if the pack is malformed or asks for anything unsafe. */
	public static Index readIndex(Path mrpack) throws IOException {
		try (ZipFile zip = new ZipFile(mrpack.toFile())) {
			ZipEntry entry = zip.getEntry("modrinth.index.json");
			if (entry == null || entry.getSize() > 4 * 1024 * 1024) {
				throw new IOException("Not a Modrinth modpack");
			}
			String json;
			try (InputStream in = zip.getInputStream(entry)) {
				json = new String(in.readNBytes(4 * 1024 * 1024), StandardCharsets.UTF_8);
			}
			return parseIndex(json);
		}
	}

	public static Index parseIndex(String json) throws IOException {
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (!"minecraft".equals(root.get("game").getAsString())) {
				throw new IOException("Not a Minecraft modpack");
			}
			JsonObject deps = root.getAsJsonObject("dependencies");
			String minecraft = deps != null && deps.has("minecraft") ? deps.get("minecraft").getAsString() : "";
			String fabric = deps != null && deps.has("fabric-loader") ? deps.get("fabric-loader").getAsString() : "";
			if (fabric.isEmpty()) {
				throw new IOException("This pack isn't for Fabric");
			}
			List<PackFile> files = new ArrayList<>();
			long total = 0;
			for (JsonElement element : root.getAsJsonArray("files")) {
				JsonObject file = element.getAsJsonObject();
				JsonObject env = file.getAsJsonObject("env");
				if (env != null && env.has("client") && "unsupported".equals(env.get("client").getAsString())) {
					continue; // server-only file
				}
				String path = file.get("path").getAsString();
				if (!safePath(path)) {
					throw new IOException("Unsafe path in pack: " + path);
				}
				String sha512 = file.getAsJsonObject("hashes").get("sha512").getAsString().toLowerCase(java.util.Locale.ROOT);
				if (!sha512.matches("[0-9a-f]{128}")) {
					throw new IOException("Bad hash for " + path);
				}
				List<String> urls = new ArrayList<>();
				for (JsonElement url : file.getAsJsonArray("downloads")) {
					if (allowedUrl(url.getAsString())) {
						urls.add(url.getAsString());
					}
				}
				if (urls.isEmpty()) {
					throw new IOException(path + " isn't hosted anywhere JavaHost trusts");
				}
				long size = file.has("fileSize") ? file.get("fileSize").getAsLong() : 0;
				total += Math.max(0, size);
				files.add(new PackFile(path, sha512, size, List.copyOf(urls)));
			}
			if (files.size() > MAX_FILES || total > MAX_TOTAL_BYTES) {
				throw new IOException("This pack is too big");
			}
			return new Index(Text.clean(root.get("name").getAsString(), 60), Text.clean(root.has("versionId") ? root.get("versionId").getAsString() : "", 40),
					minecraft, fabric, List.copyOf(files));
		} catch (IllegalStateException | NullPointerException | ClassCastException | UnsupportedOperationException
				| com.google.gson.JsonParseException e) {
			throw new IOException("Unreadable modpack", e);
		}
	}

	/** A relative path inside one of the allowed folders, with no tricks. */
	public static boolean safePath(String path) {
		if (path.isEmpty() || path.length() > 200 || path.startsWith("/") || path.contains("\\") || path.contains(":")
				|| path.contains("\0")) {
			return false;
		}
		for (String part : path.split("/")) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				return false;
			}
		}
		return ALLOWED_ROOTS.stream().anyMatch(path::startsWith);
	}

	public static boolean allowedUrl(String url) {
		try {
			URI uri = URI.create(url);
			return "https".equals(uri.getScheme()) && uri.getHost() != null && Modrinth.PACK_HOSTS.contains(uri.getHost());
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Copies the pack's {@code overrides/} and {@code client-overrides/} into {@code target} (same layout as the
	 * game folder), skipping anything outside the allowed folders. Returns the relative paths written.
	 */
	public static List<String> extractOverrides(Path mrpack, Path target) throws IOException {
		List<String> written = new ArrayList<>();
		long total = 0;
		try (ZipFile zip = new ZipFile(mrpack.toFile())) {
			for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
				String name = entry.getName();
				String relative = name.startsWith("overrides/") ? name.substring("overrides/".length())
						: name.startsWith("client-overrides/") ? name.substring("client-overrides/".length()) : null;
				if (relative == null || entry.isDirectory() || !safePath(relative)) {
					continue;
				}
				if (written.size() >= MAX_OVERRIDE_FILES) {
					throw new IOException("Too many files in the pack");
				}
				Path out = target.resolve(relative).normalize();
				if (!out.startsWith(target)) {
					continue;
				}
				Files.createDirectories(out.getParent());
				try (InputStream in = zip.getInputStream(entry); OutputStream o = Files.newOutputStream(out)) {
					byte[] buffer = new byte[1 << 16];
					int read;
					while ((read = in.read(buffer)) > 0) {
						total += read;
						if (total > MAX_TOTAL_BYTES) {
							throw new IOException("This pack is too big");
						}
						o.write(buffer, 0, read);
					}
				}
				written.add(relative);
			}
		}
		return written;
	}
}

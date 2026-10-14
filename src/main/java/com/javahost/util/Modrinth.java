package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Downloads Fabric mods from Modrinth (the official mod site) for an exact Minecraft version, verifying each
 * file against Modrinth's published SHA-512 hash. Shared by the in-game Performance tab and the installer, so
 * it only uses plain Java and Gson.
 */
public final class Modrinth {
	/** Fabric API's Modrinth project id. */
	public static final String FABRIC_API = "P7dR8mSH";

	/**
	 * A specific file to download. {@code requires} lists the Modrinth project ids it depends on; {@code versionId}
	 * is the Modrinth version it belongs to.
	 */
	public record ModFile(String projectId, String project, String versionNumber, String fileName, String url, String sha512,
			List<String> requires, String versionId) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final String USER_AGENT = "JavaHost/1.0 (javahost mod installer)";

	private Modrinth() {
	}

	/** Newest Fabric build of {@code project} (slug or id) for this game version, preferring stable releases. */
	public static ModFile latest(String project, String gameVersion) throws IOException, InterruptedException {
		String query = "?loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8)
				+ "&game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8);
		JsonArray versions = getJson("https://api.modrinth.com/v2/project/" + project + "/version" + query).getAsJsonArray();
		for (String type : new String[] {"release", "beta", "alpha"}) {
			for (JsonElement element : versions) {
				JsonObject version = element.getAsJsonObject();
				if (type.equals(version.get("version_type").getAsString())) {
					return toModFile(project, version);
				}
			}
		}
		return null;
	}

	private static ModFile toModFile(String project, JsonObject version) throws IOException {
		JsonObject file = null;
		for (JsonElement element : version.getAsJsonArray("files")) {
			JsonObject candidate = element.getAsJsonObject();
			if (file == null || (candidate.has("primary") && candidate.get("primary").getAsBoolean())) {
				file = candidate;
			}
		}
		if (file == null) {
			throw new IOException(project + " has no files");
		}
		String fileName = file.get("filename").getAsString();
		if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar")) {
			throw new IOException("Unexpected file name " + fileName);
		}
		List<String> requires = new ArrayList<>();
		for (JsonElement dep : version.getAsJsonArray("dependencies")) {
			JsonObject d = dep.getAsJsonObject();
			if ("required".equals(d.get("dependency_type").getAsString()) && d.has("project_id") && !d.get("project_id").isJsonNull()) {
				requires.add(d.get("project_id").getAsString());
			}
		}
		return new ModFile(version.get("project_id").getAsString(), project, version.get("version_number").getAsString(), fileName,
				file.get("url").getAsString(), file.getAsJsonObject("hashes").get("sha512").getAsString(), requires, version.get("id").getAsString());
	}

	/**
	 * Resolves the given projects plus everything they require, skipping projects {@code alreadyHave} accepts
	 * (by Modrinth project id). Projects with no build for this game version are reported and skipped.
	 */
	public static List<ModFile> resolve(List<String> projects, String gameVersion, Predicate<String> alreadyHave, Consumer<String> progress)
			throws IOException, InterruptedException {
		List<String> queue = new ArrayList<>(projects);
		List<String> seenIds = new ArrayList<>();
		List<ModFile> files = new ArrayList<>();
		for (int i = 0; i < queue.size(); i++) {
			String project = queue.get(i);
			progress.accept("Looking up " + project + "...");
			ModFile file = latest(project, gameVersion);
			if (file == null) {
				progress.accept(project + " has no build for Minecraft " + gameVersion + " yet; skipping.");
				continue;
			}
			if (seenIds.contains(file.projectId()) || alreadyHave.test(file.projectId())) {
				continue;
			}
			seenIds.add(file.projectId());
			files.add(file);
			for (String dependency : file.requires()) {
				if (!seenIds.contains(dependency) && !queue.contains(dependency) && !alreadyHave.test(dependency)) {
					queue.add(dependency);
				}
			}
		}
		return files;
	}

	/** Downloads a file to {@code target}, checking its SHA-512. Skips the download if an identical file is there. */
	public static void download(ModFile file, Path target) throws IOException, InterruptedException {
		download(file, target, List.of("cdn.modrinth.com"));
	}

	/** The hosts Modrinth allows modpack files to come from. */
	public static final List<String> PACK_HOSTS = List.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");

	/** As {@link #download(ModFile, Path)}, from any of {@code hosts} (https only). */
	public static void download(ModFile file, Path target, List<String> hosts) throws IOException, InterruptedException {
		if (Files.exists(target) && sha512(target).equalsIgnoreCase(file.sha512())) {
			return;
		}
		URI uri = URI.create(file.url());
		if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !hosts.contains(uri.getHost())) {
			throw new IOException("Refusing to download from " + uri.getHost());
		}
		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".download");
		HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(5)).build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new IOException("Download failed (HTTP " + response.statusCode() + ")");
		}
		MessageDigest digest = sha512Digest();
		try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
				out.write(buffer, 0, read);
			}
		}
		if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(file.sha512())) {
			Files.deleteIfExists(temp);
			throw new IOException("Checksum mismatch for " + file.fileName() + "; download discarded");
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	public static String sha512(Path file) throws IOException {
		MessageDigest digest = sha512Digest();
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha512Digest() {
		try {
			return MessageDigest.getInstance("SHA-512");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Finds the exact Modrinth versions of files by their SHA-512 hashes (one request). Files Modrinth doesn't know
	 * are left out of the result.
	 */
	public static java.util.Map<String, ModFile> versionsByHash(List<String> sha512s) throws IOException, InterruptedException {
		java.util.Map<String, ModFile> found = new java.util.HashMap<>();
		if (sha512s.isEmpty()) {
			return found;
		}
		JsonObject body = new JsonObject();
		JsonArray hashes = new JsonArray();
		sha512s.forEach(hashes::add);
		body.add("hashes", hashes);
		body.addProperty("algorithm", "sha512");
		JsonObject result = postJson("https://api.modrinth.com/v2/version_files", body).getAsJsonObject();
		for (String hash : sha512s) {
			JsonElement version = result.get(hash);
			if (version != null && version.isJsonObject()) {
				ModFile file = fileWithHash(version.getAsJsonObject(), hash);
				if (file != null) {
					found.put(hash, file);
				}
			}
		}
		return found;
	}

	/** The file with this exact hash in Modrinth version {@code versionId}, or null if it isn't there. */
	public static ModFile fileInVersion(String versionId, String sha512) throws IOException, InterruptedException {
		if (!versionId.matches("[A-Za-z0-9]{1,16}")) {
			throw new IOException("Bad version id");
		}
		return fileWithHash(getJson("https://api.modrinth.com/v2/version/" + versionId).getAsJsonObject(), sha512);
	}

	private static ModFile fileWithHash(JsonObject version, String sha512) {
		for (JsonElement element : version.getAsJsonArray("files")) {
			JsonObject file = element.getAsJsonObject();
			String hash = file.getAsJsonObject("hashes").get("sha512").getAsString();
			if (hash.equalsIgnoreCase(sha512)) {
				return new ModFile(version.get("project_id").getAsString(), version.get("project_id").getAsString(),
						version.get("version_number").getAsString(), file.get("filename").getAsString(), file.get("url").getAsString(), hash,
						List.of(), version.get("id").getAsString());
			}
		}
		return null;
	}

	/** A modpack found by {@link #searchModpacks}. */
	public record Pack(String projectId, String slug, String title, String description, long downloads, String author) {
	}

	/** Fabric modpacks on Modrinth for this Minecraft version, most downloaded first (or best match for a search). */
	public static List<Pack> searchModpacks(String query, String gameVersion) throws IOException, InterruptedException {
		String facets = "[[\"project_type:modpack\"],[\"versions:" + gameVersion + "\"],[\"categories:fabric\"]]";
		String url = "https://api.modrinth.com/v2/search?limit=20&index=" + (query.isBlank() ? "downloads" : "relevance")
				+ "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8);
		List<Pack> packs = new ArrayList<>();
		for (JsonElement element : getJson(url).getAsJsonObject().getAsJsonArray("hits")) {
			JsonObject hit = element.getAsJsonObject();
			packs.add(new Pack(hit.get("project_id").getAsString(), hit.get("slug").getAsString(), Text.clean(hit.get("title").getAsString(), 60),
					Text.clean(hit.has("description") ? hit.get("description").getAsString() : "", 160),
					hit.has("downloads") ? hit.get("downloads").getAsLong() : 0, Text.clean(hit.has("author") ? hit.get("author").getAsString() : "", 32)));
		}
		return packs;
	}

	/** The newest Fabric .mrpack of a modpack for this game version, or null. */
	public static ModFile latestPack(String projectId, String gameVersion) throws IOException, InterruptedException {
		if (!projectId.matches("[A-Za-z0-9]{1,16}")) {
			throw new IOException("Bad project id");
		}
		String query = "?loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8)
				+ "&game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8);
		JsonArray versions = getJson("https://api.modrinth.com/v2/project/" + projectId + "/version" + query).getAsJsonArray();
		// Newest stable release first; a beta only if that's all there is.
		List<JsonObject> ordered = new ArrayList<>();
		for (String type : new String[] {"release", "beta", "alpha"}) {
			for (JsonElement element : versions) {
				if (type.equals(element.getAsJsonObject().get("version_type").getAsString())) {
					ordered.add(element.getAsJsonObject());
				}
			}
		}
		for (JsonObject version : ordered) {
			for (JsonElement f : version.getAsJsonArray("files")) {
				JsonObject file = f.getAsJsonObject();
				String name = file.get("filename").getAsString();
				if (name.endsWith(".mrpack") && name.matches("[A-Za-z0-9][A-Za-z0-9._+ ()-]*\\.mrpack")) {
					return new ModFile(projectId, projectId, version.get("version_number").getAsString(), name, file.get("url").getAsString(),
							file.getAsJsonObject("hashes").get("sha512").getAsString(), List.of(), version.get("id").getAsString());
				}
			}
		}
		return null;
	}

	public static JsonElement postJson(String url, JsonObject body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost());
		}
		return JsonParser.parseString(response.body());
	}

	public static JsonElement getJson(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(20)).build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost());
		}
		return JsonParser.parseString(response.body());
	}
}

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
		download(file, target, hosts, (read, total) -> {
		}, () -> false);
	}

	/** Bytes downloaded so far of {@code total} ({@code -1} when the server doesn't say). */
	@FunctionalInterface
	public interface Progress {
		void update(long read, long total);
	}

	/** Thrown when a download is stopped through its cancel check; the partial file is deleted. */
	public static final class Cancelled extends IOException {
		public Cancelled() {
			super("Cancelled");
		}
	}

	/**
	 * As {@link #download(ModFile, Path, List)}, reporting progress as bytes arrive and stopping (with {@link Cancelled})
	 * as soon as {@code cancelled} says so.
	 */
	public static void download(ModFile file, Path target, List<String> hosts, Progress progress, java.util.function.BooleanSupplier cancelled)
			throws IOException, InterruptedException {
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
		long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
		progress.update(0, total);
		try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
			copy(in, out, digest, total, progress, cancelled);
		} catch (Cancelled e) {
			Files.deleteIfExists(temp);
			throw e;
		}
		if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(file.sha512())) {
			Files.deleteIfExists(temp);
			throw new IOException("Checksum mismatch for " + file.fileName() + "; download discarded");
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Copies {@code in} to {@code out}, hashing and reporting as it goes. Package-visible for tests. */
	static long copy(InputStream in, OutputStream out, MessageDigest digest, long total, Progress progress,
			java.util.function.BooleanSupplier cancelled) throws IOException {
		byte[] buffer = new byte[1 << 16];
		long done = 0;
		int read;
		while ((read = in.read(buffer)) > 0) {
			if (cancelled.getAsBoolean()) {
				throw new Cancelled();
			}
			digest.update(buffer, 0, read);
			out.write(buffer, 0, read);
			done += read;
			progress.update(done, total);
		}
		return done;
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

	/** What a {@link #search} is for. */
	public enum Kind {
		MOD("mod"), MODPACK("modpack");

		final String projectType;

		Kind(String projectType) {
			this.projectType = projectType;
		}
	}

	/**
	 * A mod or modpack found by {@link #search}, for any Minecraft version or loader. {@code gameVersions} and
	 * {@code loaders} say where it runs (see {@link Availability}); {@code iconUrl} is empty or a cdn.modrinth.com URL.
	 */
	public record Project(String id, String slug, Kind kind, String title, String description, String author, long downloads,
			String iconUrl, List<String> gameVersions, List<String> loaders, List<String> categories, String imageUrl) {
		public String page() {
			return "https://modrinth.com/" + kind.projectType + "/" + slug;
		}
	}

	/** Loaders as Modrinth lists them in a project's categories. */
	static final java.util.Set<String> LOADERS = java.util.Set.of("fabric", "forge", "neoforge", "quilt");

	public static final int PAGE = 20;

	/**
	 * Mods or modpacks on Modrinth for every Minecraft version and loader (most downloaded first, or best match for a
	 * search), leaving out performance mods and libraries so the list is about gameplay.
	 */
	public static List<Project> search(Kind kind, String query, int offset) throws IOException, InterruptedException {
		return search(kind, query, "", null, offset);
	}

	/**
	 * As {@link #search(Kind, String, int)}, only in {@code category} (a Modrinth category such as "magic"; empty for all),
	 * and, when {@code runsOn} is a Minecraft version, only Fabric projects for exactly that version.
	 */
	public static List<Project> search(Kind kind, String query, String category, String runsOn, int offset) throws IOException, InterruptedException {
		String facets = facets(kind, category, runsOn);
		String url = "https://api.modrinth.com/v2/search?limit=" + PAGE + "&offset=" + Math.max(0, offset)
				+ "&index=" + (query.isBlank() ? "downloads" : "relevance")
				+ "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8);
		return parseSearch(kind, getJson(url).getAsJsonObject());
	}

	/** The search filter: one AND group per condition, as Modrinth's search API takes them. */
	static String facets(Kind kind, String category, String runsOn) {
		StringBuilder facets = new StringBuilder("[[\"project_type:" + kind.projectType + "\"],[\"categories!=optimization\"],[\"categories!=library\"]");
		if (category != null && category.matches("[a-z-]{1,32}")) {
			facets.append(",[\"categories:").append(category).append("\"]");
		}
		if (runsOn != null && runsOn.matches("[0-9A-Za-z.+-]{1,32}")) {
			facets.append(",[\"versions:").append(runsOn).append("\"],[\"categories:fabric\"]");
		}
		return facets.append("]").toString();
	}

	/** Reads search hits; anything malformed is skipped (the response comes from the network). */
	static List<Project> parseSearch(Kind kind, JsonObject response) {
		List<Project> projects = new ArrayList<>();
		JsonArray hits = response.has("hits") ? response.getAsJsonArray("hits") : new JsonArray();
		for (JsonElement element : hits) {
			try {
				JsonObject hit = element.getAsJsonObject();
				String id = hit.get("project_id").getAsString();
				String slug = hit.get("slug").getAsString();
				if (!id.matches("[A-Za-z0-9]{1,16}") || !slug.matches("[A-Za-z0-9._-]{1,64}")) {
					continue;
				}
				List<String> versions = new ArrayList<>();
				if (hit.has("versions")) {
					for (JsonElement v : hit.getAsJsonArray("versions")) {
						String version = v.getAsString();
						if (version.matches("[0-9A-Za-z.+-]{1,32}") && versions.size() < 400) {
							versions.add(version);
						}
					}
				}
				List<String> loaders = new ArrayList<>();
				List<String> categories = new ArrayList<>();
				if (hit.has("categories")) {
					for (JsonElement c : hit.getAsJsonArray("categories")) {
						String category = c.getAsString();
						if (LOADERS.contains(category)) {
							loaders.add(category);
						} else if (category.matches("[a-z-]{1,32}") && categories.size() < 8) {
							categories.add(category);
						}
					}
				}
				String image = hit.has("featured_gallery") && !hit.get("featured_gallery").isJsonNull() ? hit.get("featured_gallery").getAsString() : "";
				if (!image.startsWith("https://cdn.modrinth.com/")) {
					image = "";
				}
				String icon = hit.has("icon_url") && !hit.get("icon_url").isJsonNull() ? hit.get("icon_url").getAsString() : "";
				if (!icon.startsWith("https://cdn.modrinth.com/")) {
					icon = "";
				}
				projects.add(new Project(id, slug, kind, Text.clean(hit.get("title").getAsString(), 60),
						Text.clean(hit.has("description") ? hit.get("description").getAsString() : "", 160),
						Text.clean(hit.has("author") ? hit.get("author").getAsString() : "", 32),
						hit.has("downloads") ? hit.get("downloads").getAsLong() : 0, icon, List.copyOf(versions), List.copyOf(loaders),
						List.copyOf(categories), image));
			} catch (RuntimeException e) {
				// skip a malformed hit
			}
		}
		return projects;
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

	/** Icon URLs (cdn.modrinth.com only) for projects by slug, in one request. */
	public static java.util.Map<String, String> iconUrls(List<String> slugs) throws IOException, InterruptedException {
		List<String> safe = slugs.stream().filter(s -> s.matches("[A-Za-z0-9._-]{1,64}")).toList();
		java.util.Map<String, String> icons = new java.util.HashMap<>();
		if (safe.isEmpty()) {
			return icons;
		}
		String ids = "[" + String.join(",", safe.stream().map(s -> "\"" + s + "\"").toList()) + "]";
		for (JsonElement element : getJson("https://api.modrinth.com/v2/projects?ids=" + URLEncoder.encode(ids, StandardCharsets.UTF_8)).getAsJsonArray()) {
			JsonObject project = element.getAsJsonObject();
			String icon = project.has("icon_url") && !project.get("icon_url").isJsonNull() ? project.get("icon_url").getAsString() : "";
			if (icon.startsWith("https://cdn.modrinth.com/")) {
				icons.put(project.get("slug").getAsString(), icon);
			}
		}
		return icons;
	}

	/** A project icon from Modrinth's CDN (https only, at most {@code maxBytes}). */
	public static byte[] icon(String url, int maxBytes) throws IOException, InterruptedException {
		URI uri = URI.create(url);
		if (!"https".equals(uri.getScheme()) || !"cdn.modrinth.com".equals(uri.getHost())) {
			throw new IOException("Icons only come from cdn.modrinth.com");
		}
		HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(20)).build();
		HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (java.io.InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				throw new IOException("HTTP " + response.statusCode() + " for an icon");
			}
			byte[] data = in.readNBytes(maxBytes + 1);
			if (data.length > maxBytes) {
				throw new IOException("Icon too big");
			}
			return data;
		}
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

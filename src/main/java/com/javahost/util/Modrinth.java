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

	/** A specific file to download. {@code requires} lists the Modrinth project ids it depends on. */
	public record ModFile(String projectId, String project, String versionNumber, String fileName, String url, String sha512,
			List<String> requires) {
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
				file.get("url").getAsString(), file.getAsJsonObject("hashes").get("sha512").getAsString(), requires);
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
		if (Files.exists(target) && sha512(target).equalsIgnoreCase(file.sha512())) {
			return;
		}
		URI uri = URI.create(file.url());
		if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().equals("cdn.modrinth.com")) {
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

	public static JsonElement getJson(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(20)).build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost());
		}
		return JsonParser.parseString(response.body());
	}
}

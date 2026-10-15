package com.javahost.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.util.UpdateManifest;

/**
 * The newest JavaHost release: its signed update.json lists a build for each Minecraft version, and the installer
 * downloads the one the player picks. Every file is checked against the signed SHA-256 before it's used.
 */
public final class Releases {
	static final String LATEST_RELEASE = "https://api.github.com/repos/IbrahimMas1/javahost/releases/latest";
	private static final List<String> TRUSTED_HOSTS = List.of("github.com", "objects.githubusercontent.com",
			"release-assets.githubusercontent.com", "cdn.modrinth.com");
	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15)).build();

	private Releases() {
	}

	/**
	 * Fetches and checks the latest release's update.json. Tests point {@code javahost.installer.updateDir} at a
	 * folder with update.json(.sig) and set {@code javahost.installer.updateKey} to their own public key.
	 */
	public static UpdateManifest latest() throws IOException, InterruptedException {
		String dir = System.getProperty("javahost.installer.updateDir");
		byte[] json;
		String sig;
		if (dir != null) {
			json = Files.readAllBytes(Path.of(dir, "update.json"));
			sig = Files.readString(Path.of(dir, "update.json.sig"));
		} else {
			JsonObject release = JsonParser.parseString(new String(get(LATEST_RELEASE, "application/vnd.github+json"))).getAsJsonObject();
			String jsonUrl = asset(release, "update.json");
			String sigUrl = asset(release, "update.json.sig");
			if (jsonUrl == null || sigUrl == null) {
				throw new IOException("The latest JavaHost release has no update.json");
			}
			json = get(jsonUrl, "application/octet-stream");
			sig = new String(get(sigUrl, "application/octet-stream"));
		}
		String key = System.getProperty("javahost.installer.updateKey", UpdateManifest.PUBLIC_KEY);
		try {
			return UpdateManifest.verifyAndParse(json, sig, key);
		} catch (GeneralSecurityException e) {
			throw new IOException("JavaHost's release list isn't signed correctly, so nothing was installed", e);
		}
	}

	/** Downloads one file of a release to {@code target}, checking its size and SHA-256. */
	public static void download(UpdateManifest.FileEntry file, Path target) throws IOException, InterruptedException {
		if (Files.exists(target) && sha256(target).equals(file.sha256())) {
			return;
		}
		URI uri = URI.create(file.url());
		boolean testFile = "file".equals(uri.getScheme()) && System.getProperty("javahost.installer.updateDir") != null;
		if (!testFile && !("https".equals(uri.getScheme()) && TRUSTED_HOSTS.contains(uri.getHost()))) {
			throw new IOException("Refusing to download " + file.name() + " from " + uri.getHost());
		}
		Files.createDirectories(target.getParent());
		Path part = target.resolveSibling(target.getFileName() + ".download");
		try (InputStream in = testFile ? Files.newInputStream(Path.of(uri)) : stream(uri); OutputStream out = Files.newOutputStream(part)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int n;
			while ((n = in.read(buffer)) > 0) {
				total += n;
				if (total > file.size() + 1024) {
					throw new IOException(file.name() + " is bigger than expected");
				}
				out.write(buffer, 0, n);
				digest.update(buffer, 0, n);
			}
			if (!HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
				throw new IOException(file.name() + " didn't match its checksum (download damaged or tampered with)");
			}
		} catch (IOException | InterruptedException e) {
			Files.deleteIfExists(part);
			throw e;
		} catch (GeneralSecurityException e) {
			Files.deleteIfExists(part);
			throw new IOException(e);
		}
		Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
	}

	private static InputStream stream(URI uri) throws IOException, InterruptedException {
		HttpResponse<InputStream> response = HTTP.send(request(uri, "application/octet-stream"), HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException("HTTP " + response.statusCode() + " downloading " + uri);
		}
		return response.body();
	}

	private static byte[] get(String url, String accept) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = HTTP.send(request(URI.create(url), accept), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " from " + url);
		}
		return response.body();
	}

	private static HttpRequest request(URI uri, String accept) {
		return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent", "JavaHost-Installer/" + Installer.MOD_VERSION)
				.header("Accept", accept).build();
	}

	private static String asset(JsonObject release, String name) {
		if (!release.has("assets")) {
			return null;
		}
		for (JsonElement asset : release.getAsJsonArray("assets")) {
			JsonObject o = asset.getAsJsonObject();
			if (name.equals(o.get("name").getAsString())) {
				return o.get("browser_download_url").getAsString();
			}
		}
		return null;
	}

	static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			int n;
			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (GeneralSecurityException e) {
			throw new IOException(e);
		}
	}
}

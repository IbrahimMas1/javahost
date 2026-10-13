package com.javahost.client.update;

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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

import com.javahost.JavaHost;
import com.javahost.client.Toasts;
import com.javahost.util.UpdateManifest;
import com.javahost.util.UpdateManifest.FileEntry;
import com.javahost.util.Versions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Checks GitHub for a newer JavaHost, downloads and verifies it, and hands the swap to {@code UpdateSwap}, which
 * runs after the game closes. Nothing in mods/ changes while the game is running.
 */
public final class Updater {
	static final String LATEST_RELEASE = "https://api.github.com/repos/IbrahimMas1/javahost/releases/latest";
	/** Downloads are only accepted from these hosts (GitHub releases and Modrinth's CDN). */
	private static final List<String> TRUSTED_HOSTS = List.of("github.com", "objects.githubusercontent.com",
			"release-assets.githubusercontent.com", "cdn.modrinth.com");
	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15)).build();

	private Updater() {
	}

	static Path dir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("javahost").resolve("updates");
	}

	static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	private static Path planFile() {
		return dir().resolve("plan.properties");
	}

	private static Path resultFile() {
		return dir().resolve("result.properties");
	}

	// ---------------------------------------------------------------- checking

	/**
	 * A test-only setting (a local release server or key). Ignored outside the development environment, so a
	 * launcher argument can never point a real player's updates somewhere else.
	 */
	static String testProperty(String name) {
		String value = System.getProperty(name);
		if (value != null && !net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			JavaHost.LOGGER.warn("[Update] ignoring {}: test settings only work in development", name);
			return null;
		}
		return value;
	}

	/** Finds the newest release (verified), newer than us or not; null if there isn't one or it can't be checked. */
	static CompletableFuture<UpdateManifest> fetchLatest() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String override = testProperty("javahost.updateUrl"); // tests: a folder with update.json(.sig)
				String jsonUrl;
				String sigUrl;
				if (override != null) {
					jsonUrl = override + "/update.json";
					sigUrl = override + "/update.json.sig";
				} else {
					String release = get(LATEST_RELEASE, "application/vnd.github+json");
					jsonUrl = assetUrl(release, "update.json");
					sigUrl = assetUrl(release, "update.json.sig");
					if (jsonUrl == null || sigUrl == null) {
						return null; // a release without an update manifest
					}
				}
				byte[] json = getBytes(jsonUrl);
				String sig = new String(getBytes(sigUrl));
				String testKey = testProperty("javahost.updateKey");
				String key = testKey != null ? testKey : UpdateManifest.PUBLIC_KEY;
				UpdateManifest manifest = UpdateManifest.verifyAndParse(json, sig, key);
				boolean newer = Versions.compare(manifest.version(), JavaHost.VERSION) > 0;
				JavaHost.LOGGER.info("[Update] latest is {} (we have {}){}{}", manifest.version(), JavaHost.VERSION,
						newer ? ": update available" : "", manifest.requires(JavaHost.VERSION) ? " (important)" : "");
				return manifest;
			} catch (Exception e) {
				JavaHost.LOGGER.warn("[Update] couldn't check for updates: {}", e.toString());
				return null;
			}
		}, com.javahost.util.IoExecutor.NET);
	}

	/** browser_download_url of the asset with this exact name, from a GitHub release JSON. */
	static String assetUrl(String releaseJson, String name) {
		var release = com.google.gson.JsonParser.parseString(releaseJson).getAsJsonObject();
		if (!release.has("assets")) {
			return null;
		}
		for (var asset : release.getAsJsonArray("assets")) {
			var o = asset.getAsJsonObject();
			if (name.equals(o.get("name").getAsString())) {
				return o.get("browser_download_url").getAsString();
			}
		}
		return null;
	}

	private static String get(String url, String accept) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
				.header("User-Agent", "JavaHost/" + JavaHost.VERSION).header("Accept", accept).build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost());
		}
		return response.body();
	}

	private static byte[] getBytes(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
				.header("User-Agent", "JavaHost/" + JavaHost.VERSION).build();
		HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	// ---------------------------------------------------------------- downloading

	private static boolean trusted(URI uri) {
		if (testProperty("javahost.updateUrl") != null && "localhost".equals(uri.getHost())) {
			return true; // automated tests
		}
		return "https".equals(uri.getScheme()) && TRUSTED_HOSTS.contains(uri.getHost());
	}

	/**
	 * Downloads every file the update needs (skipping ones already in mods/), checks each SHA-256, and writes the
	 * swap plan. After this, the update happens when the game closes.
	 */
	static CompletableFuture<Void> download(UpdateManifest manifest, DoubleConsumer progress) {
		return CompletableFuture.runAsync(() -> {
			try {
				Path target = dir().resolve(manifest.version());
				Files.createDirectories(target);
				List<FileEntry> needed = new ArrayList<>();
				for (FileEntry file : manifest.files()) {
					Path existing = modsDir().resolve(file.name());
					if (!(Files.exists(existing) && sha256(existing).equals(file.sha256()))) {
						needed.add(file);
					}
				}
				long total = Math.max(1, needed.stream().mapToLong(FileEntry::size).sum());
				long done = 0;
				for (FileEntry file : needed) {
					URI uri = URI.create(file.url());
					if (!trusted(uri)) {
						throw new IOException("Refusing to download from " + uri.getHost());
					}
					Path part = target.resolve(file.name() + ".part");
					HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
							.header("User-Agent", "JavaHost/" + JavaHost.VERSION).build();
					HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
					if (response.statusCode() != 200) {
						throw new IOException("HTTP " + response.statusCode() + " downloading " + file.name());
					}
					MessageDigest digest = MessageDigest.getInstance("SHA-256");
					try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
						byte[] buffer = new byte[64 * 1024];
						int n;
						long fileDone = 0;
						while ((n = in.read(buffer)) > 0) {
							out.write(buffer, 0, n);
							digest.update(buffer, 0, n);
							fileDone += n;
							if (fileDone > file.size() + 1024) {
								throw new IOException(file.name() + " is bigger than expected");
							}
							progress.accept((done + fileDone) / (double) total);
						}
					}
					String got = HexFormat.of().formatHex(digest.digest());
					if (!got.equals(file.sha256())) {
						Files.deleteIfExists(part);
						throw new IOException(file.name() + " didn't match its checksum (download damaged or tampered with)");
					}
					Files.move(part, target.resolve(file.name()), StandardCopyOption.REPLACE_EXISTING);
					done += file.size();
				}
				writePlan(manifest, target, needed);
				progress.accept(1);
				JavaHost.LOGGER.info("[Update] {} downloaded and verified; it's installed when the game closes", manifest.version());
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
			}
		}, com.javahost.util.IoExecutor.NET);
	}

	private static void writePlan(UpdateManifest manifest, Path target, List<FileEntry> files) throws IOException {
		Properties plan = new Properties();
		plan.setProperty("kind", "update");
		plan.setProperty("version", manifest.version());
		plan.setProperty("mods", modsDir().toAbsolutePath().toString());
		plan.setProperty("backup", dir().resolve("previous").toAbsolutePath().toString());
		plan.setProperty("result", resultFile().toAbsolutePath().toString());
		Path helper = null;
		for (int i = 0; i < files.size(); i++) {
			Path file = target.resolve(files.get(i).name()).toAbsolutePath();
			plan.setProperty("file." + i + ".new", file.toString());
			plan.setProperty("file." + i + ".replaces", files.get(i).replaces());
			if (files.get(i).name().startsWith("javahost-")) {
				helper = file;
			}
		}
		// The swap runs from the new JavaHost jar (it's outside mods/, so nothing holds it open).
		plan.setProperty("helper", helper != null ? helper.toString() : copyOfThisJar().toString());
		try (OutputStream out = Files.newOutputStream(planFile())) {
			plan.store(out, "JavaHost update waiting for the game to close");
		}
		planChanged();
	}

	/** A copy of the running JavaHost jar outside mods/, to run the swap helper from. */
	private static Path copyOfThisJar() throws IOException {
		Path jar = FabricLoader.getInstance().getModContainer(JavaHost.MOD_ID).orElseThrow().getOrigin().getPaths().getFirst();
		Path copy = dir().resolve("helper.jar");
		Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
		return copy;
	}

	static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			int n;
			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/** The waiting plan, read once and kept in memory (Settings asks every frame); null if there isn't one. */
	private static volatile Properties cachedPlan;
	private static volatile boolean planLoaded;
	private static volatile long planLoadedAt;

	private static Properties plan() {
		// Re-read at most once a second, so a change made outside JavaHost is still noticed.
		if (!planLoaded || System.currentTimeMillis() - planLoadedAt > 1000) {
			Properties plan = null;
			if (Files.exists(planFile())) {
				try (InputStream in = Files.newInputStream(planFile())) {
					plan = new Properties();
					plan.load(in);
				} catch (IOException e) {
					plan = null;
				}
			}
			cachedPlan = plan;
			planLoaded = true;
			planLoadedAt = System.currentTimeMillis();
		}
		return cachedPlan;
	}

	/** The plan file changed: read it again next time. */
	private static void planChanged() {
		planLoaded = false;
	}

	/** An update is downloaded and will be installed when the game closes. */
	public static boolean staged() {
		return plan() != null;
	}

	/** "update" or "rollback" for what's waiting to happen when the game closes (null if nothing). */
	public static String stagedKind() {
		Properties plan = plan();
		return plan == null ? null : plan.getProperty("kind", "update");
	}

	/** Changes your mind: nothing happens when the game closes. */
	public static void cancelStaged() {
		try {
			Files.deleteIfExists(planFile());
			planChanged();
			JavaHost.LOGGER.info("[Update] the waiting update/rollback was cancelled");
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Update] couldn't cancel", e);
		}
	}

	public static String stagedVersion() {
		Properties plan = plan();
		return plan == null ? null : plan.getProperty("version");
	}

	// ---------------------------------------------------------------- installing (after the game closes)

	/** Called as the game closes: starts the swap helper, which waits for the game to exit. */
	public static void launchSwapIfStaged() {
		if (!staged()) {
			return;
		}
		try {
			Properties plan = new Properties();
			try (InputStream in = Files.newInputStream(planFile())) {
				plan.load(in);
			}
			plan.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
			Path running = dir().resolve("running.properties");
			try (OutputStream out = Files.newOutputStream(running)) {
				plan.store(out, "JavaHost update in progress");
			}
			Files.delete(planFile());
			planChanged();
			String java = ProcessHandle.current().info().command().orElse("java");
			new ProcessBuilder(java, "-cp", plan.getProperty("helper"), "com.javahost.util.UpdateSwap", running.toString())
					.redirectErrorStream(true).redirectOutput(dir().resolve("swap.log").toFile()).start();
			JavaHost.LOGGER.info("[Update] installing {} as the game closes", plan.getProperty("version"));
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Update] couldn't start the update helper", e);
		}
	}

	/** Called at startup: reports how the last update went. */
	public static void reportLastResult() {
		Path result = resultFile();
		if (!Files.exists(result)) {
			return;
		}
		try (InputStream in = Files.newInputStream(result)) {
			Properties r = new Properties();
			r.load(in);
			if (Boolean.parseBoolean(r.getProperty("ok")) && "rollback".equals(r.getProperty("kind"))) {
				Toasts.notify("Back on JavaHost " + JavaHost.VERSION, "The previous version is restored.");
			} else if (Boolean.parseBoolean(r.getProperty("ok"))) {
				Toasts.notify("Updated to JavaHost " + r.getProperty("version"), "You're on the latest version. Have fun!");
			} else {
				Toasts.notify("Update didn't finish", "Still on " + JavaHost.VERSION + ". " + r.getProperty("reason")
						+ " JavaHost will offer it again.");
			}
		} catch (IOException ignored) {
			// Nothing to report.
		}
		try {
			Files.deleteIfExists(result);
			Files.deleteIfExists(dir().resolve("running.properties"));
		} catch (IOException ignored) {
			// Harmless.
		}
	}

	// ---------------------------------------------------------------- going back

	/** The JavaHost version kept from the last update (null if none), e.g. "1.5.0". */
	public static String previousVersion() {
		try (Stream<Path> files = Files.list(dir().resolve("previous"))) {
			return files.map(p -> p.getFileName().toString())
					.filter(name -> name.matches("javahost-[0-9].*\\.jar"))
					.map(name -> name.substring("javahost-".length(), name.length() - ".jar".length()))
					.findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	/** Whether a previous version is kept (from the last update). */
	public static boolean canRollBack() {
		return previousVersion() != null;
	}

	/**
	 * Why the previous version can't be restored, or null if it can. An important release raises the minimum safe
	 * version; versions older than that stay updated.
	 */
	public static String rollBackBlockedReason(UpdateManifest latest) {
		String previous = previousVersion();
		if (previous == null || latest == null || !latest.requires(previous)) {
			return null;
		}
		return "JavaHost " + latest.minimum() + " is an important update, so " + previous + " can't be restored.";
	}

	/** Puts the previous version back when the game closes (same helper, the other direction). */
	public static void stageRollBack() throws IOException {
		Path previous = dir().resolve("previous");
		Path restore = dir().resolve("restore");
		Files.createDirectories(restore);
		Properties plan = new Properties();
		plan.setProperty("kind", "rollback");
		plan.setProperty("version", String.valueOf(previousVersion()));
		plan.setProperty("mods", modsDir().toAbsolutePath().toString());
		plan.setProperty("backup", dir().resolve("rolled-back").toAbsolutePath().toString());
		plan.setProperty("result", resultFile().toAbsolutePath().toString());
		int i = 0;
		try (Stream<Path> files = Files.list(previous)) {
			for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
				Path copy = restore.resolve(jar.getFileName());
				Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
				String name = jar.getFileName().toString();
				String replaces = name.startsWith("javahost-") ? "javahost-[0-9].*\\.jar"
						: name.startsWith("fabric-api-") ? "fabric-api-.*\\.jar" : java.util.regex.Pattern.quote(name);
				plan.setProperty("file." + i + ".new", copy.toAbsolutePath().toString());
				plan.setProperty("file." + i + ".replaces", replaces);
				i++;
			}
		}
		plan.setProperty("helper", copyOfThisJar().toString());
		try (OutputStream out = Files.newOutputStream(planFile())) {
			plan.store(out, "JavaHost: go back to the previous version when the game closes");
		}
		planChanged();
	}
}

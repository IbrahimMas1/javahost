import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads a release's mod jars (one per Minecraft build) to Modrinth and CurseForge. JDK only, run by CI after a
 * release, or by hand for an existing tag (publish.yml).
 *
 * Usage: java scripts/PublishMod.java JAR_DIR VERSION NOTES_FILE stonecutter.properties.toml [--dry-run]
 *        java scripts/PublishMod.java --mark-only JAR curseforge|modrinth OUT_DIR
 *   (--mark-only makes just the site copy of one jar, the way a publish does, and checks it; no tokens, no uploads.)
 *
 * Each site is skipped unless its settings are in the environment:
 *   MODRINTH_TOKEN + MODRINTH_PROJECT_ID, CURSEFORGE_TOKEN + CURSEFORGE_PROJECT_ID.
 * Modrinth versions that already exist are skipped, so a re-run only uploads what's missing. CurseForge has no such
 * check: don't re-run a release that reached it.
 */
public class PublishMod {
	static final String USER_AGENT = "IbrahimMas1/javahost (https://github.com/IbrahimMas1/javahost)";
	static final String MODRINTH = "https://api.modrinth.com/v2";
	static final String CURSEFORGE = "https://minecraft.curseforge.com/api";
	static final String MODRINTH_FABRIC_API = "P7dR8mSH";
	/** e4mc: the relay JavaHost uses when nothing direct works (optional). */
	static final String MODRINTH_E4MC = "qANg5Jrr";
	static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	record Build(String node, List<String> releases, String java, Path jar) {
	}

	public static void main(String[] a) throws Exception {
		if (a.length > 0 && a[0].equals("--mark-only")) {
			if (a.length != 4) {
				System.err.println("Usage: java scripts/PublishMod.java --mark-only JAR curseforge|modrinth OUT_DIR");
				System.exit(2);
			}
			try {
				Path copy = marked(Path.of(a[1]), a[2], Files.createDirectories(Path.of(a[3])));
				System.out.println("Site copy: " + copy);
			} catch (IllegalStateException e) {
				System.err.println(e.getMessage());
				System.exit(1);
			}
			return;
		}
		Path jarDir = Path.of(a[0]);
		String version = a[1];
		String notes = Files.readString(Path.of(a[2])).strip();
		List<Build> builds = builds(Files.readString(Path.of(a[3])), jarDir, version);
		boolean dryRun = a.length > 4 && a[4].equals("--dry-run");

		String modrinthToken = System.getenv("MODRINTH_TOKEN");
		String modrinthProject = System.getenv("MODRINTH_PROJECT_ID");
		String curseToken = System.getenv("CURSEFORGE_TOKEN");
		String curseProject = System.getenv("CURSEFORGE_PROJECT_ID");
		// PUBLISH_ONLY=modrinth or curseforge limits a run to one site (for re-running one that failed).
		String only = System.getenv("PUBLISH_ONLY") == null ? "" : System.getenv("PUBLISH_ONLY").strip().toLowerCase(java.util.Locale.ROOT);
		boolean modrinth = notBlank(modrinthToken) && notBlank(modrinthProject) && !only.equals("curseforge");
		boolean curse = notBlank(curseToken) && notBlank(curseProject) && !only.equals("modrinth");
		if (!modrinth && !curse && !dryRun) {
			System.out.println("No Modrinth or CurseForge settings: nothing to publish.");
			return;
		}
		if (modrinth || dryRun) {
			modrinth(builds, version, notes, modrinthToken, notBlank(modrinthProject) ? modrinthProject : "PROJECT_ID", dryRun && !modrinth);
		}
		if (curse || dryRun) {
			curseForge(builds, version, notes, curseToken, notBlank(curseProject) ? curseProject : "PROJECT_ID", dryRun && !curse);
		}
	}

	/** One build per node in stonecutter.properties.toml, with the jar for it. */
	static List<Build> builds(String toml, Path jarDir, String version) throws IOException {
		List<Build> builds = new ArrayList<>();
		Matcher node = Pattern.compile("(?m)^\\[\"([^\"]+)\"]\\s*$").matcher(toml);
		List<String> names = new ArrayList<>();
		List<Integer> starts = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		while (node.find()) {
			names.add(node.group(1));
			starts.add(node.start());
			ends.add(node.end());
		}
		for (int i = 0; i < names.size(); i++) {
			String body = toml.substring(ends.get(i), i + 1 < names.size() ? starts.get(i + 1) : toml.length());
			Matcher releases = Pattern.compile("mod\\.mc_releases\\s*=\\s*\\[([^]]*)]").matcher(body);
			if (!releases.find()) {
				throw new IllegalStateException("No mod.mc_releases for " + names.get(i));
			}
			List<String> list = new ArrayList<>();
			Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(releases.group(1));
			while (item.find()) {
				list.add(item.group(1));
			}
			String name = names.get(i);
			Path jar = jarDir.resolve("javahost-" + version + "+" + name + ".jar");
			if (!Files.isRegularFile(jar)) {
				throw new IllegalStateException("Missing " + jar);
			}
			builds.add(new Build(name, list, name.startsWith("1.") ? "21" : "25", jar));
		}
		return builds;
	}

	// ---------------------------------------------------------------- Modrinth

	static void modrinth(List<Build> builds, String version, String notes, String token, String project, boolean dryRun) throws Exception {
		String existing = "[]";
		if (!dryRun) {
			try {
				existing = send(HttpRequest.newBuilder(URI.create(MODRINTH + "/project/" + project + "/version"))
						.header("Authorization", token).GET(), "Modrinth versions");
			} catch (IOException e) {
				// A token that can only create versions may not read a draft project's list: upload everything.
				System.out.println("Modrinth: couldn't list existing versions (" + e.getMessage() + "), uploading all");
			}
		}
		for (Build b : builds) {
			String number = version + "+" + b.node();
			if (existing.contains("\"version_number\":\"" + number + "\"")) {
				System.out.println("Modrinth: " + number + " is already there");
				continue;
			}
			String data = "{"
					+ "\"name\":" + json("JavaHost " + version + " for " + label(b.releases())) + ","
					+ "\"version_number\":" + json(number) + ","
					+ "\"changelog\":" + json(notes) + ","
					+ "\"dependencies\":[{\"project_id\":\"" + MODRINTH_FABRIC_API + "\",\"dependency_type\":\"required\"},"
					+ "{\"project_id\":\"" + MODRINTH_E4MC + "\",\"dependency_type\":\"optional\"}],"
					+ "\"game_versions\":" + jsonList(b.releases()) + ","
					+ "\"version_type\":\"release\","
					+ "\"loaders\":[\"fabric\"],"
					// Everyone playing needs it; a server without JavaHost still works.
					+ "\"environment\":\"client_only_server_optional\","
					+ "\"featured\":" + (b == builds.get(0)) + ","
					+ "\"project_id\":" + json(project) + ","
					+ "\"file_parts\":[\"file\"],"
					+ "\"primary_file\":\"file\""
					+ "}";
			Path jar = marked(b.jar(), "modrinth");
			if (dryRun) {
				System.out.println("Modrinth (dry run): " + jar + " " + data);
				continue;
			}
			Multipart body = new Multipart().field("data", data).file("file", jar);
			send(HttpRequest.newBuilder(URI.create(MODRINTH + "/version")).header("Authorization", token)
					.header("Content-Type", body.contentType()).POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes())), "Modrinth upload " + number);
			System.out.println("Modrinth: uploaded " + number);
		}
	}

	// ---------------------------------------------------------------- CurseForge

	static void curseForge(List<Build> builds, String version, String notes, String token, String project, boolean dryRun) throws Exception {
		Map<String, Integer> ids = dryRun ? Map.of() : curseVersionIds(token);
		for (Build b : builds) {
			List<String> wanted = new ArrayList<>(b.releases());
			wanted.add("Fabric");
			wanted.add("Client");
			wanted.add("Server");
			wanted.add("Java " + b.java());
			List<Integer> versionIds = new ArrayList<>();
			List<String> missing = new ArrayList<>();
			for (String name : wanted) {
				Integer id = ids.get(name);
				if (id != null) {
					versionIds.add(id);
				} else if (b.releases().contains(name)) {
					missing.add(name);
				}
			}
			if (!dryRun && versionIds.isEmpty()) {
				throw new IllegalStateException("CurseForge doesn't list any of " + b.releases());
			}
			if (!missing.isEmpty()) {
				System.out.println("CurseForge: not listed yet, left off " + b.node() + ": " + missing);
			}
			String metadata = "{"
					+ "\"changelog\":" + json(notes) + ","
					+ "\"changelogType\":\"markdown\","
					+ "\"displayName\":" + json("JavaHost " + version + " for " + label(b.releases())) + ","
					+ "\"gameVersions\":" + versionIds + ","
					+ "\"releaseType\":\"release\","
					+ "\"relations\":{\"projects\":[{\"slug\":\"fabric-api\",\"type\":\"requiredDependency\"},"
					+ "{\"slug\":\"e4mc\",\"type\":\"optionalDependency\"}]}"
					+ "}";
			Path jar = marked(b.jar(), "curseforge");
			if (dryRun) {
				System.out.println("CurseForge (dry run): " + jar + " " + wanted + " " + metadata);
				continue;
			}
			Multipart body = new Multipart().field("metadata", metadata).file("file", jar);
			send(HttpRequest.newBuilder(URI.create(CURSEFORGE + "/projects/" + project + "/upload-file")).header("X-Api-Token", token)
					.header("Content-Type", body.contentType()).POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes())),
					"CurseForge upload " + b.node());
			System.out.println("CurseForge: uploaded " + version + " for " + b.node());
		}
	}

	/**
	 * CurseForge's ids for Minecraft versions, loaders, sides and Java versions, by name. Minecraft versions come
	 * only from "minecraft-*" types, so "1.21" is never mistaken for a plugin API version.
	 */
	static Map<String, Integer> curseVersionIds(String token) throws Exception {
		String types = send(HttpRequest.newBuilder(URI.create(CURSEFORGE + "/game/version-types")).header("X-Api-Token", token).GET(),
				"CurseForge version types");
		Map<Integer, String> typeSlugs = new LinkedHashMap<>();
		Matcher t = Pattern.compile("\\{[^{}]*?\"id\"\\s*:\\s*(\\d+)[^{}]*?\"slug\"\\s*:\\s*\"([^\"]+)\"[^{}]*}").matcher(types);
		while (t.find()) {
			typeSlugs.put(Integer.parseInt(t.group(1)), t.group(2));
		}
		String versions = send(HttpRequest.newBuilder(URI.create(CURSEFORGE + "/game/versions")).header("X-Api-Token", token).GET(),
				"CurseForge versions");
		Map<String, Integer> ids = new LinkedHashMap<>();
		Matcher v = Pattern.compile("\\{[^{}]*}").matcher(versions);
		Pattern id = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
		Pattern type = Pattern.compile("\"gameVersionTypeID\"\\s*:\\s*(\\d+)");
		Pattern name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
		while (v.find()) {
			String o = v.group();
			Matcher mi = id.matcher(o);
			Matcher mt = type.matcher(o);
			Matcher mn = name.matcher(o);
			if (!mi.find() || !mt.find() || !mn.find()) {
				continue;
			}
			String slug = typeSlugs.getOrDefault(Integer.parseInt(mt.group(1)), "");
			if (slug.startsWith("minecraft-") || slug.equals("modloader") || slug.equals("environment") || slug.equals("java")) {
				ids.putIfAbsent(mn.group(1), Integer.parseInt(mi.group(1)));
			}
		}
		return ids;
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * What's taken out of the copies for CurseForge and Modrinth: JavaHost's self-updater (it checks GitHub, downloads a
	 * new jar and swaps it in after the game closes). Both sites require updates to come only through them, so their
	 * copies don't carry that code at all. Nothing else in the jar names these classes (the rest of JavaHost reaches the
	 * updater only through com.javahost.update.Updates, by class name), which {@link #check} makes sure of.
	 */
	static final List<Pattern> SELF_UPDATE = List.of(
			Pattern.compile("com/javahost/client/update/.*"),
			Pattern.compile("com/javahost/util/UpdateSwap(\\$.*)?\\.class"),
			Pattern.compile("com/javahost/util/UpdateManifest(\\$.*)?\\.class"),
			// The hosts other than Modrinth's CDN that a modpack's files may come from (GitHub copies only).
			Pattern.compile("com/javahost/util/OpenPackHosts\\.class"));
	/** How the same code is named from other classes (in their constant pools). */
	static final List<String> SELF_UPDATE_REFERENCES = List.of("com/javahost/client/update/", "com/javahost/util/UpdateSwap",
			"com/javahost/util/UpdateManifest", "com/javahost/util/OpenPackHosts");
	/** Text that must not be in any class of a site copy: the GitHub API and release downloads. */
	static final List<String> FORBIDDEN_TEXT = List.of("api.github.com", "/releases/", "raw.githubusercontent.com", "gitlab.com");
	static final String MARKER = "javahost/distribution.properties";

	static Path marked(Path jar, String platform) throws IOException {
		return marked(jar, platform, Files.createTempDirectory("javahost-" + platform));
	}

	/**
	 * A copy of {@code jar} (same file name, in {@code dir}) for {@code platform}: with
	 * {@code javahost/distribution.properties} saying which site it's for (JavaHost reads it in util/Distribution: such
	 * copies leave updates to the site and only get modpack files from Modrinth) and without the self-updater. Throws
	 * (so nothing is uploaded) if the copy still has any of the updater, GitHub release addresses or no marker. The
	 * GitHub jars stay as they are.
	 */
	static Path marked(Path jar, String platform, Path dir) throws IOException {
		if (!platform.equals("curseforge") && !platform.equals("modrinth")) {
			throw new IllegalStateException("Unknown site: " + platform);
		}
		Path copy = dir.resolve(jar.getFileName().toString());
		if (Files.exists(copy) && Files.isSameFile(copy, jar)) {
			throw new IllegalStateException("The site copy would overwrite " + jar + ": pick another folder");
		}
		Files.copy(jar, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		List<String> removed = new ArrayList<>();
		try (FileSystem zip = FileSystems.newFileSystem(copy)) {
			Path entry = zip.getPath("javahost", "distribution.properties");
			Files.createDirectories(entry.getParent());
			Files.writeString(entry, "# Downloaded from " + platform + ": updates come from there (see Distribution.java)\nplatform=" + platform + "\n");
			List<Path> files;
			try (var walk = Files.walk(zip.getPath("/"))) {
				files = walk.filter(Files::isRegularFile).toList();
			}
			for (Path file : files) {
				String name = file.toString().substring(1);
				if (SELF_UPDATE.stream().anyMatch(p -> p.matcher(name).matches())) {
					Files.delete(file);
					removed.add(name);
				}
			}
			Path folder = zip.getPath("com", "javahost", "client", "update");
			if (Files.isDirectory(folder)) {
				Files.delete(folder);
			}
		}
		System.out.println(platform + ": removed " + removed.size() + " self-update entries from " + copy.getFileName() + ":");
		removed.stream().sorted().forEach(name -> System.out.println("  - " + name));
		List<String> problems = check(copy, platform);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Refusing to publish " + copy.getFileName() + " to " + platform + ":\n  " + String.join("\n  ", problems));
		}
		System.out.println(platform + ": check passed (no self-update classes, no references to them, no " + FORBIDDEN_TEXT
				+ " in any class, marker present)");
		return copy;
	}

	/** Everything wrong with a site copy, or an empty list. Looks inside jars bundled in the jar too. */
	static List<String> check(Path jar, String platform) throws IOException {
		List<String> problems = new ArrayList<>();
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			var marker = zip.getEntry(MARKER);
			if (marker == null) {
				problems.add("no " + MARKER);
			} else {
				String text = new String(zip.getInputStream(marker).readAllBytes(), StandardCharsets.UTF_8);
				if (!text.contains("platform=" + platform + "\n")) {
					problems.add(MARKER + " doesn't say platform=" + platform);
				}
			}
			for (var entries = zip.entries(); entries.hasMoreElements();) {
				var entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				byte[] bytes = zip.getInputStream(entry).readAllBytes();
				scan(entry.getName(), bytes, true, problems);
			}
		}
		return problems;
	}

	private static void scan(String name, byte[] bytes, boolean ours, List<String> problems) throws IOException {
		if (name.endsWith(".jar")) {
			// A library bundled inside (jar-in-jar): its classes count too.
			try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
				for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
					if (!entry.isDirectory()) {
						scan(name + "!/" + entry.getName(), in.readAllBytes(), false, problems);
					}
				}
			}
			return;
		}
		if (!name.endsWith(".class")) {
			return;
		}
		if (ours && SELF_UPDATE.stream().anyMatch(p -> p.matcher(name).matches())) {
			problems.add("still has " + name);
		}
		// Class files keep names and string constants as (modified) UTF-8, so a byte search finds them.
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		for (String forbidden : FORBIDDEN_TEXT) {
			if (text.contains(forbidden)) {
				problems.add(name + " contains \"" + forbidden + "\"");
			}
		}
		for (String reference : SELF_UPDATE_REFERENCES) {
			if (text.contains(reference)) {
				problems.add(name + " refers to " + reference + "... (it would fail without the updater)");
			}
		}
	}

	/** "26.3", "26.1–26.1.2". */
	static String label(List<String> releases) {
		return releases.size() == 1 ? releases.get(0) : releases.get(0) + "–" + releases.get(releases.size() - 1);
	}

	static String send(HttpRequest.Builder request, String what) throws IOException, InterruptedException {
		HttpResponse<String> response = HTTP.send(request.header("User-Agent", USER_AGENT).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException(what + " failed: HTTP " + response.statusCode() + " " + response.body());
		}
		return response.body();
	}

	static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	static String json(String s) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	static String jsonList(List<String> items) {
		return "[" + String.join(",", items.stream().map(PublishMod::json).toList()) + "]";
	}

	static final class Multipart {
		private final String boundary = "----javahost" + UUID.randomUUID();
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Multipart field(String name, String value) {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
					+ "Content-Type: application/json\r\n\r\n" + value + "\r\n");
			return this;
		}

		Multipart file(String name, Path file) throws IOException {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getFileName() + "\"\r\n"
					+ "Content-Type: application/java-archive\r\n\r\n");
			out.write(Files.readAllBytes(file));
			write("\r\n");
			return this;
		}

		String contentType() {
			return "multipart/form-data; boundary=" + boundary;
		}

		byte[] bytes() {
			write("--" + boundary + "--\r\n");
			return out.toByteArray();
		}

		private void write(String s) {
			out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
		}
	}
}

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release tool (run by CI on a version tag): writes update.json for the release and signs it.
 *
 *   java scripts/MakeUpdateManifest.java <out dir> <version> <protocol> <jars dir> <stonecutter.properties.toml> <notes file> [minimum]
 *
 * jars dir: the built mod, one javahost-<version>+<minecraft>.jar per Minecraft version.
 * stonecutter.properties.toml: for each Minecraft version, the releases its build runs on and its Fabric API version.
 * minimum: the oldest version that's still safe (set by an important release); older ones must update and can't
 * be restored with "Go back to the previous version".
 *
 * Each Minecraft version becomes a target (its JavaHost jar and Fabric API). The top-level "minecraft" and "files"
 * repeat the 26.3 target, because that's all JavaHost 1.9 and older read.
 *
 * The signing key comes from the UPDATE_SIGNING_KEY environment variable (base64 PKCS#8 Ed25519).
 * Fabric API is looked up on Modrinth for the exact version each build was made against.
 */
public class MakeUpdateManifest {
	static final String REPO = "IbrahimMas1/javahost";
	static final String LEGACY_MINECRAFT = "26.3";
	static final HttpClient HTTP = HttpClient.newHttpClient();

	record Node(String minecraft, List<String> releases, String fabricApi) {
	}

	public static void main(String[] a) throws Exception {
		Path out = Path.of(a[0]);
		String version = a[1];
		int protocol = Integer.parseInt(a[2]);
		Path jars = Path.of(a[3]);
		List<Node> nodes = readNodes(Files.readString(Path.of(a[4])));
		String notes = Files.exists(Path.of(a[5])) ? Files.readString(Path.of(a[5])).strip() : "";
		String minimum = a.length > 6 && !a[6].isBlank() ? a[6] : "0";

		Map<String, String> files = new LinkedHashMap<>(); // minecraft -> its files, as JSON
		for (Node node : nodes) {
			Path jar = jars.resolve("javahost-" + version + "+" + node.minecraft() + ".jar");
			if (!Files.exists(jar)) {
				throw new IllegalStateException("Missing build for Minecraft " + node.minecraft() + ": " + jar);
			}
			byte[] jarBytes = Files.readAllBytes(jar);
			String jarName = jar.getFileName().toString();
			String list = file(jarName, "https://github.com/" + REPO + "/releases/download/v" + version + "/" + jarName,
					sha256(jarBytes), jarBytes.length, "javahost-[0-9].*\\\\.jar")
					+ ",\n" + fabricApi(node.minecraft(), node.fabricApi());
			files.put(node.minecraft(), list);
		}
		if (!files.containsKey(LEGACY_MINECRAFT)) {
			throw new IllegalStateException("No " + LEGACY_MINECRAFT + " build: JavaHost 1.9 and older couldn't update");
		}

		StringBuilder targets = new StringBuilder();
		for (Node node : nodes) {
			if (!targets.isEmpty()) {
				targets.append(",\n");
			}
			targets.append("    {\"minecraft\": \"").append(esc(node.minecraft())).append("\", \"releases\": [")
					.append(String.join(", ", node.releases().stream().map(r -> "\"" + esc(r) + "\"").toList()))
					.append("],\n     \"files\": [\n").append(files.get(node.minecraft()).replaceAll("(?m)^    ", "      ")).append("\n     ]}");
		}

		String json = "{\n"
				+ "  \"version\": \"" + esc(version) + "\",\n"
				+ "  \"protocol\": " + protocol + ",\n"
				+ "  \"minecraft\": \"" + LEGACY_MINECRAFT + "\",\n"
				+ "  \"notes\": \"" + esc(notes) + "\",\n"
				+ "  \"releasePage\": \"https://github.com/" + REPO + "/releases/tag/v" + esc(version) + "\",\n"
				+ "  \"minimum\": \"" + esc(minimum) + "\",\n"
				+ "  \"files\": [\n" + files.get(LEGACY_MINECRAFT) + "\n  ],\n"
				+ "  \"targets\": [\n" + targets + "\n  ]\n}\n";
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
				Base64.getDecoder().decode(System.getenv("UPDATE_SIGNING_KEY").trim())));
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update(bytes);
		Files.createDirectories(out);
		Files.write(out.resolve("update.json"), bytes);
		Files.writeString(out.resolve("update.json.sig"), Base64.getEncoder().encodeToString(signer.sign()) + "\n");
		System.out.println(json);
	}

	/** The [version] sections of stonecutter.properties.toml, in order. */
	static List<Node> readNodes(String toml) {
		List<Node> nodes = new ArrayList<>();
		Matcher section = Pattern.compile("(?m)^\\[\"([^\"]+)\"\\]\\s*$").matcher(toml);
		List<int[]> bounds = new ArrayList<>();
		List<String> names = new ArrayList<>();
		while (section.find()) {
			names.add(section.group(1));
			bounds.add(new int[] {section.end(), 0});
		}
		for (int i = 0; i < names.size(); i++) {
			int end = i + 1 < bounds.size() ? toml.lastIndexOf('[', bounds.get(i + 1)[0] - 1) : toml.length();
			String body = toml.substring(bounds.get(i)[0], end);
			Matcher releases = Pattern.compile("(?m)^mod\\.mc_releases\\s*=\\s*\\[([^\\]]*)\\]").matcher(body);
			Matcher api = Pattern.compile("(?m)^deps\\.fabric_api\\s*=\\s*\"([^\"]+)\"").matcher(body);
			if (!releases.find() || !api.find()) {
				throw new IllegalStateException("stonecutter.properties.toml: " + names.get(i) + " needs mod.mc_releases and deps.fabric_api");
			}
			List<String> list = new ArrayList<>();
			Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(releases.group(1));
			while (item.find()) {
				list.add(item.group(1));
			}
			nodes.add(new Node(names.get(i), List.copyOf(list), api.group(1)));
		}
		return nodes;
	}

	/** Fabric API: the Modrinth file for the version this build was made against. */
	static String fabricApi(String minecraft, String fabricApi) throws Exception {
		String versions = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22"
				+ minecraft + "%22%5D")).header("User-Agent", "JavaHost release tool").build(), HttpResponse.BodyHandlers.ofString()).body();
		Matcher m = Pattern.compile("\"version_number\":\"" + Pattern.quote(fabricApi) + "\".*?\"url\":\"([^\"]+)\",\"filename\":\"([^\"]+)\"").matcher(versions);
		if (!m.find()) {
			throw new IllegalStateException("Fabric API " + fabricApi + " not found on Modrinth for " + minecraft);
		}
		byte[] api;
		try (InputStream in = HTTP.send(HttpRequest.newBuilder(URI.create(m.group(1))).build(), HttpResponse.BodyHandlers.ofInputStream()).body()) {
			api = in.readAllBytes();
		}
		return file(m.group(2), m.group(1), sha256(api), api.length, "fabric-api-.*\\\\.jar");
	}

	static String file(String name, String url, String sha, long size, String replaces) {
		return "    {\"name\": \"" + esc(name) + "\", \"url\": \"" + esc(url) + "\", \"sha256\": \"" + sha + "\", \"size\": " + size
				+ ", \"replaces\": \"" + replaces + "\"}";
	}

	static String sha256(byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
	}

	static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}
}

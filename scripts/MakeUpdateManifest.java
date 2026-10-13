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
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release tool (run by CI on a version tag): writes update.json for the release and signs it.
 *
 *   java scripts/MakeUpdateManifest.java <out dir> <version> <protocol> <minecraft> <fabric api version> <javahost jar> <notes file> [minimum]
 *
 * minimum: the oldest version that's still safe (set by an important release); older ones must update and can't
 * be restored with "Go back to the previous version".
 *
 * The signing key comes from the UPDATE_SIGNING_KEY environment variable (base64 PKCS#8 Ed25519).
 * Fabric API is looked up on Modrinth for the exact version the mod was built against.
 */
public class MakeUpdateManifest {
	static final String REPO = "IbrahimMas1/javahost";

	public static void main(String[] a) throws Exception {
		Path out = Path.of(a[0]);
		String version = a[1];
		int protocol = Integer.parseInt(a[2]);
		String minecraft = a[3];
		String fabricApi = a[4];
		Path jar = Path.of(a[5]);
		String notes = Files.exists(Path.of(a[6])) ? Files.readString(Path.of(a[6])).strip() : "";
		String minimum = a.length > 7 && !a[7].isBlank() ? a[7] : "0";

		StringBuilder files = new StringBuilder();
		byte[] jarBytes = Files.readAllBytes(jar);
		String jarName = jar.getFileName().toString();
		files.append(file(jarName, "https://github.com/" + REPO + "/releases/download/v" + version + "/" + jarName,
				sha256(jarBytes), jarBytes.length, "javahost-[0-9].*\\\\.jar"));

		// Fabric API: the Modrinth file for the version we built against.
		HttpClient http = HttpClient.newHttpClient();
		String versions = http.send(HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22"
				+ minecraft + "%22%5D")).header("User-Agent", "JavaHost release tool").build(), HttpResponse.BodyHandlers.ofString()).body();
		Matcher m = Pattern.compile("\"version_number\":\"" + Pattern.quote(fabricApi) + "\".*?\"url\":\"([^\"]+)\",\"filename\":\"([^\"]+)\"").matcher(versions);
		if (!m.find()) {
			throw new IllegalStateException("Fabric API " + fabricApi + " not found on Modrinth");
		}
		byte[] api;
		try (InputStream in = http.send(HttpRequest.newBuilder(URI.create(m.group(1))).build(), HttpResponse.BodyHandlers.ofInputStream()).body()) {
			api = in.readAllBytes();
		}
		files.append(",\n").append(file(m.group(2), m.group(1), sha256(api), api.length, "fabric-api-.*\\\\.jar"));

		String json = "{\n"
				+ "  \"version\": \"" + esc(version) + "\",\n"
				+ "  \"protocol\": " + protocol + ",\n"
				+ "  \"minecraft\": \"" + esc(minecraft) + "\",\n"
				+ "  \"notes\": \"" + esc(notes) + "\",\n"
				+ "  \"releasePage\": \"https://github.com/" + REPO + "/releases/tag/v" + esc(version) + "\",\n"
				+ "  \"minimum\": \"" + esc(minimum) + "\",\n"
				+ "  \"files\": [\n" + files + "\n  ]\n}\n";
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

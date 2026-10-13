package com.javahost.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What a JavaHost release contains (update.json), checked against its Ed25519 signature (update.json.sig) before
 * anything is trusted. The public key is built into the mod, so only releases signed with our key are accepted.
 */
public record UpdateManifest(String version, int protocol, String minecraft, String notes, String releasePage, List<FileEntry> files) {
	/**
	 * One file to install. {@code replaces} is a regular expression for the jars in mods/ it takes the place of
	 * (e.g. "javahost-[0-9].*\\.jar"), so older versions are moved out of the way.
	 */
	public record FileEntry(String name, String url, String sha256, long size, String replaces) {
	}

	/** The release signing key (Ed25519, X.509 / base64). Its private half lives only in the release workflow's secrets. */
	public static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAVyahZgQBPByQoruX+qDjCAdMy6GmsRkEqDAewcPcV3k=";

	/** Parses update.json after checking {@code signatureB64} over its exact bytes. Throws if anything is off. */
	public static UpdateManifest verifyAndParse(byte[] json, String signatureB64, String publicKeyB64) throws GeneralSecurityException {
		PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64.trim())));
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(json);
		if (!verifier.verify(Base64.getDecoder().decode(signatureB64.trim()))) {
			throw new GeneralSecurityException("Update signature doesn't match");
		}
		return parse(new String(json, StandardCharsets.UTF_8));
	}

	static UpdateManifest parse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		List<FileEntry> files = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("files")) {
			JsonObject f = element.getAsJsonObject();
			String name = f.get("name").getAsString();
			if (!name.matches("[A-Za-z0-9._+-]+\\.jar")) {
				throw new IllegalArgumentException("Bad file name in update: " + name);
			}
			files.add(new FileEntry(name, f.get("url").getAsString(), f.get("sha256").getAsString().toLowerCase(),
					f.get("size").getAsLong(), f.get("replaces").getAsString()));
		}
		return new UpdateManifest(root.get("version").getAsString(), root.get("protocol").getAsInt(),
				root.get("minecraft").getAsString(), root.has("notes") ? root.get("notes").getAsString() : "",
				root.has("releasePage") ? root.get("releasePage").getAsString() : "", List.copyOf(files));
	}

	public long totalSize() {
		return files.stream().mapToLong(FileEntry::size).sum();
	}
}

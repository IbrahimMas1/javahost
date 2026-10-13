package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {
	@TempDir
	Path temp;

	static final String JSON = """
			{"version": "1.5.1", "protocol": 2, "minecraft": "26.3", "notes": "Fixes", "releasePage": "https://example",
			 "files": [{"name": "javahost-1.5.1.jar", "url": "https://github.com/x", "sha256": "AB12", "size": 10, "replaces": "javahost-[0-9].*\\\\.jar"}]}
			""";

	static String sign(KeyPair keys, byte[] data) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(data);
		return Base64.getEncoder().encodeToString(signer.sign());
	}

	@Test
	void acceptsOnlyCorrectlySignedManifests() throws Exception {
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
		byte[] json = JSON.getBytes(StandardCharsets.UTF_8);
		String sig = sign(keys, json);

		UpdateManifest manifest = UpdateManifest.verifyAndParse(json, sig, pub);
		assertEquals("1.5.1", manifest.version());
		assertEquals(2, manifest.protocol());
		assertEquals("ab12", manifest.files().getFirst().sha256());
		assertTrue("javahost-1.4.4.jar".matches(manifest.files().getFirst().replaces()));
		assertFalse("javahost-gametest-1.0.0.jar".matches(manifest.files().getFirst().replaces()));

		byte[] tampered = JSON.replace("1.5.1", "6.6.6").getBytes(StandardCharsets.UTF_8);
		assertThrows(GeneralSecurityException.class, () -> UpdateManifest.verifyAndParse(tampered, sig, pub));
		KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		assertThrows(GeneralSecurityException.class, () -> UpdateManifest.verifyAndParse(json, sign(other, json), pub));
		// The key built into the mod is a valid Ed25519 public key.
		assertThrows(GeneralSecurityException.class, () -> UpdateManifest.verifyAndParse(json, sig, UpdateManifest.PUBLIC_KEY));
	}

	@Test
	void refusesPathsInFileNames() {
		assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(JSON.replace("javahost-1.5.1.jar", "../../evil.jar")));
	}

	private Properties plan(Path mods, Path newJar) {
		Properties plan = new Properties();
		plan.setProperty("mods", mods.toString());
		plan.setProperty("backup", temp.resolve("updates/previous").toString());
		plan.setProperty("version", "1.5.1");
		plan.setProperty("file.0.new", newJar.toString());
		plan.setProperty("file.0.replaces", "javahost-[0-9].*\\.jar");
		return plan;
	}

	@Test
	void swapsJarsAndKeepsTheOldOnes() throws Exception {
		Path mods = Files.createDirectories(temp.resolve("mods"));
		Files.writeString(mods.resolve("javahost-1.4.4.jar"), "old");
		Files.writeString(mods.resolve("sodium.jar"), "other");
		Path newJar = Files.writeString(Files.createDirectories(temp.resolve("dl")).resolve("javahost-1.5.1.jar"), "new");
		Path result = temp.resolve("result.properties");

		assertTrue(UpdateSwap.run(plan(mods, newJar), result));
		assertEquals("new", Files.readString(mods.resolve("javahost-1.5.1.jar")));
		assertFalse(Files.exists(mods.resolve("javahost-1.4.4.jar")));
		assertEquals("other", Files.readString(mods.resolve("sodium.jar")));
		assertEquals("old", Files.readString(temp.resolve("updates/previous/javahost-1.4.4.jar")));
		assertEquals("true", load(result).getProperty("ok"));
	}

	@Test
	void putsEverythingBackIfSomethingFails() throws Exception {
		Path mods = Files.createDirectories(temp.resolve("mods"));
		Files.writeString(mods.resolve("javahost-1.4.4.jar"), "old");
		Path newJar = Files.writeString(Files.createDirectories(temp.resolve("dl")).resolve("javahost-1.5.1.jar"), "new");
		Properties plan = plan(mods, newJar);
		plan.setProperty("file.1.new", temp.resolve("dl/missing.jar").toString()); // second file never downloaded
		plan.setProperty("file.1.replaces", "fabric-api-.*\\.jar");
		Path result = temp.resolve("result.properties");

		assertFalse(UpdateSwap.run(plan, result));
		assertEquals("old", Files.readString(mods.resolve("javahost-1.4.4.jar")), "the old jar is back");
		assertFalse(Files.exists(mods.resolve("javahost-1.5.1.jar")), "the new jar was removed again");
		assertEquals("false", load(result).getProperty("ok"));
	}

	private static Properties load(Path file) throws Exception {
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
		}
		return p;
	}
}

package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
			{"version": "1.5.1", "protocol": 2, "minecraft": "26.3", "notes": "Fixes", "releasePage": "https://example", "minimum": "1.5.1",
			 "files": [{"name": "javahost-1.5.1.jar", "url": "https://github.com/x", "sha256": "AB12", "size": 10, "replaces": "javahost-[0-9].*\\\\.jar"}]}
			""";

	static String sign(KeyPair keys, byte[] data) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(data);
		return Base64.getEncoder().encodeToString(signer.sign());
	}

	@Test
	void picksTheBuildForThisMinecraftVersion() {
		String json = """
				{"version": "1.10.0", "protocol": 3, "minecraft": "26.3", "minimum": "1.7.0",
				 "files": [{"name": "javahost-1.10.0+26.3.jar", "url": "https://github.com/a", "sha256": "aa", "size": 1, "replaces": "javahost-[0-9].*\\\\.jar"}],
				 "targets": [
				  {"minecraft": "26.3", "releases": ["26.3"],
				   "files": [{"name": "javahost-1.10.0+26.3.jar", "url": "https://github.com/a", "sha256": "aa", "size": 1, "replaces": "x"}]},
				  {"minecraft": "1.21.1", "releases": ["1.21", "1.21.1"],
				   "files": [{"name": "javahost-1.10.0+1.21.1.jar", "url": "https://github.com/b", "sha256": "bb", "size": 2, "replaces": "x"},
				             {"name": "fabric-api-0.116.17+1.21.1.jar", "url": "https://cdn.modrinth.com/c", "sha256": "cc", "size": 3, "replaces": "y"}]}]}
				""";
		UpdateManifest manifest = UpdateManifest.parse(json);
		assertEquals(List.of("26.3", "1.21.1"), manifest.minecraftVersions());

		UpdateManifest old = manifest.forMinecraft("1.21");
		assertEquals("1.21.1", old.minecraft(), "1.21 runs the 1.21.1 build");
		assertEquals(2, old.files().size());
		assertEquals("javahost-1.10.0+1.21.1.jar", old.files().getFirst().name());
		assertEquals("1.10.0", old.version());
		assertEquals("javahost-1.10.0+26.3.jar", manifest.forMinecraft("26.3").files().getFirst().name());
		assertNull(manifest.forMinecraft("1.20.1"), "no build for this version: the installer is needed");

		// A release from before 1.10 (no targets) only fits its own Minecraft version.
		UpdateManifest single = UpdateManifest.parse(JSON);
		assertEquals(single, single.forMinecraft("26.3"));
		assertNull(single.forMinecraft("1.21.1"));

		assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(json.replace("\"1.21\", \"1.21.1\"", "\"../x\"")));
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
		assertTrue(manifest.requires("1.5.0"), "older than the minimum: must update, can't be restored");
		assertFalse(manifest.requires("1.5.1"));
		assertFalse(UpdateManifest.parse(JSON.replace("\"minimum\": \"1.5.1\",", "")).requires("0.1"), "no minimum: nothing is required");
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
		plan.setProperty("kind", "rollback");
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
		assertEquals("rollback", load(result).getProperty("kind"), "the result says what was attempted");
	}

	private static Properties load(Path file) throws Exception {
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
		}
		return p;
	}
}

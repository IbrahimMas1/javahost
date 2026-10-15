package com.javahost.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.util.Modrinth;
import com.javahost.util.UpdateManifest;

/**
 * Installs into a fake Minecraft folder for real (downloads Fabric's version file and mods from Modrinth),
 * then updates and uninstalls, checking that only JavaHost's own entries are touched. JavaHost itself comes from a
 * test release signed with a throwaway key, like the real one on GitHub.
 */
class InstallerTest {
	private static final String MC = "26.3";

	private static final String ORIGINAL_PROFILES = """
			{
			  "profiles": {
			    "abc123": { "name": "", "type": "latest-release", "lastVersionId": "latest-release", "created": "2024-01-01T00:00:00Z" }
			  },
			  "settings": { "enableSnapshots": false },
			  "version": 3
			}
			""";

	private static JsonObject profiles(Path mc) throws Exception {
		return JsonParser.parseString(Files.readString(mc.resolve("launcher_profiles.json"))).getAsJsonObject().getAsJsonObject("profiles");
	}

	private static List<String> mods(Path mc) throws Exception {
		try (Stream<Path> list = Files.list(mc.resolve("javahost/mods"))) {
			return list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).sorted().toList();
		}
	}

	/** A release folder with update.json(.sig) whose 26.3 build is the jar Gradle just built (plus Fabric API). */
	private static UpdateManifest testRelease(Path dir) throws Exception {
		Files.createDirectories(dir);
		Path jar = dir.resolve("javahost-9.9.9+26.3.jar");
		Files.copy(Path.of(System.getProperty("javahost.test.modJar")), jar);
		Modrinth.ModFile api = Modrinth.resolve(List.of("fabric-api"), MC, id -> false, step -> {
		}).getFirst();
		Path apiJar = dir.resolve(api.fileName());
		Modrinth.download(api, apiJar);
		String files = "[" + entry(jar, "javahost-[0-9].*\\\\.jar") + ", " + entry(apiJar, "fabric-api-.*\\\\.jar") + "]";
		String json = "{\"version\": \"9.9.9\", \"protocol\": 3, \"minecraft\": \"26.3\", \"minimum\": \"0\", \"files\": " + files
				+ ", \"targets\": [{\"minecraft\": \"26.3\", \"releases\": [\"26.3\"], \"files\": " + files + "}]}";
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(json.getBytes());
		Files.writeString(dir.resolve("update.json"), json);
		Files.writeString(dir.resolve("update.json.sig"), Base64.getEncoder().encodeToString(signer.sign()));
		System.setProperty("javahost.installer.updateDir", dir.toString());
		System.setProperty("javahost.installer.updateKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
		return Releases.latest();
	}

	private static String entry(Path file, String replaces) throws Exception {
		return "{\"name\": \"" + file.getFileName() + "\", \"url\": \"" + file.toUri() + "\", \"sha256\": \"" + Releases.sha256(file)
				+ "\", \"size\": " + Files.size(file) + ", \"replaces\": \"" + replaces + "\"}";
	}

	@Test
	void installUpdateUninstall(@TempDir Path mc, @TempDir Path release) throws Exception {
		UpdateManifest build = testRelease(release).forMinecraft(MC);
		Edition edition = new Edition(MC);
		Files.writeString(mc.resolve("launcher_profiles.json"), ORIGINAL_PROFILES);
		Files.writeString(mc.resolve("options.txt"), "fov:0.5\nkey_key.jump:key.keyboard.space\n");
		Files.createDirectories(mc.resolve("resourcepacks"));
		Files.writeString(mc.resolve("resourcepacks/MyPack.zip"), "zip");
		Files.createDirectories(mc.resolve("saves/My World"));
		Files.writeString(mc.resolve("saves/My World/level.dat"), "level");

		Installer.install(new Installer.Options(mc, build, false, true, false, true, true, true),
				(fraction, step) -> System.out.println(Math.round(fraction * 100) + "% " + step));

		// Fabric loader version in the launcher's versions folder.
		JsonObject profile = profiles(mc).getAsJsonObject(edition.profileKey());
		String versionId = profile.get("lastVersionId").getAsString();
		assertTrue(versionId.startsWith("fabric-loader-") && versionId.endsWith("-" + MC), versionId);
		JsonObject versionJson = JsonParser.parseString(Files.readString(mc.resolve("versions/" + versionId + "/" + versionId + ".json"))).getAsJsonObject();
		assertEquals(MC, versionJson.get("inheritsFrom").getAsString());
		assertTrue(Files.exists(mc.resolve("versions/" + versionId + "/" + versionId + ".jar")));

		// The profile: separate game folder, our icon, more memory, and the player's own profile untouched.
		assertEquals("JavaHost 26.3", profile.get("name").getAsString());
		assertEquals(mc.resolve("javahost").toAbsolutePath().toString(), profile.get("gameDir").getAsString());
		assertTrue(profile.get("icon").getAsString().startsWith("data:image/png;base64,"));
		assertTrue(profile.get("javaArgs").getAsString().startsWith("-Xmx"));
		assertTrue(profiles(mc).has("abc123"));
		assertTrue(Files.exists(mc.resolve("launcher_profiles.json.javahost-backup")));
		assertEquals(ORIGINAL_PROFILES, Files.readString(mc.resolve("launcher_profiles.json.javahost-backup")));

		// Mods: JavaHost itself, Fabric API, and the relay (performance mods were switched off).
		List<String> mods = mods(mc);
		System.out.println("Installed mods: " + mods);
		assertTrue(mods.contains("javahost-9.9.9+26.3.jar"), mods.toString());
		assertTrue(mods.stream().anyMatch(n -> n.startsWith("fabric-api-")), mods.toString());
		assertTrue(mods.stream().anyMatch(n -> n.startsWith("e4mc")), mods.toString());
		assertFalse(mods.stream().anyMatch(n -> n.startsWith("sodium")), mods.toString());

		// Copied (not moved) from normal Minecraft.
		assertTrue(Files.exists(mc.resolve("javahost/options.txt")));
		assertTrue(Files.exists(mc.resolve("javahost/resourcepacks/MyPack.zip")));
		assertTrue(Files.exists(mc.resolve("javahost/saves/My World/level.dat")));
		assertTrue(Files.exists(mc.resolve("saves/My World/level.dat")));

		// Updating again doesn't duplicate anything and keeps the original creation time.
		String created = profile.get("created").getAsString();
		Installer.install(new Installer.Options(mc, build, false, true, false, true, true, true), (f, s) -> {
		});
		assertEquals(2, profiles(mc).size());
		assertEquals(created, profiles(mc).getAsJsonObject(edition.profileKey()).get("created").getAsString());
		assertEquals(mods, mods(mc));

		// Uninstall: profile and Fabric version gone, the player's profile and JavaHost's worlds kept.
		Installer.uninstall(mc, edition, false, (f, s) -> {
		});
		assertFalse(profiles(mc).has(edition.profileKey()));
		assertTrue(profiles(mc).has("abc123"));
		assertFalse(Files.exists(mc.resolve("versions/" + versionId)));
		assertTrue(Files.exists(mc.resolve("javahost/saves/My World/level.dat")));
		assertFalse(Installer.isInstalled(mc, edition));
	}

	@Test
	void refusesWithoutLauncher(@TempDir Path mc) {
		Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> Installer.install(new Installer.Options(mc, null, false, false, false, false, false, false), (f, s) -> {
				}));
		assertTrue(e.getMessage().contains("Open the Minecraft Launcher once"), e.getMessage());
	}
}

package com.javahost.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Installs into a fake Minecraft folder for real (downloads Fabric's version file and mods from Modrinth),
 * then updates and uninstalls, checking that only JavaHost's own entries are touched.
 */
class InstallerTest {
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

	@Test
	void installUpdateUninstall(@TempDir Path mc) throws Exception {
		Files.writeString(mc.resolve("launcher_profiles.json"), ORIGINAL_PROFILES);
		Files.writeString(mc.resolve("options.txt"), "fov:0.5\nkey_key.jump:key.keyboard.space\n");
		Files.createDirectories(mc.resolve("resourcepacks"));
		Files.writeString(mc.resolve("resourcepacks/MyPack.zip"), "zip");
		Files.createDirectories(mc.resolve("saves/My World"));
		Files.writeString(mc.resolve("saves/My World/level.dat"), "level");

		Installer.install(new Installer.Options(mc, false, true, false, true, true, true),
				(fraction, step) -> System.out.println(Math.round(fraction * 100) + "% " + step));

		// Fabric loader version in the launcher's versions folder.
		JsonObject profile = profiles(mc).getAsJsonObject(LauncherProfiles.PROFILE_KEY);
		String versionId = profile.get("lastVersionId").getAsString();
		assertTrue(versionId.startsWith("fabric-loader-") && versionId.endsWith("-" + Installer.MINECRAFT_VERSION), versionId);
		JsonObject versionJson = JsonParser.parseString(Files.readString(mc.resolve("versions/" + versionId + "/" + versionId + ".json"))).getAsJsonObject();
		assertEquals(Installer.MINECRAFT_VERSION, versionJson.get("inheritsFrom").getAsString());
		assertTrue(Files.exists(mc.resolve("versions/" + versionId + "/" + versionId + ".jar")));

		// The profile: separate game folder, our icon, more memory, and the player's own profile untouched.
		assertEquals("JavaHost", profile.get("name").getAsString());
		assertEquals(mc.resolve("javahost").toAbsolutePath().toString(), profile.get("gameDir").getAsString());
		assertTrue(profile.get("icon").getAsString().startsWith("data:image/png;base64,"));
		assertTrue(profile.get("javaArgs").getAsString().startsWith("-Xmx"));
		assertTrue(profiles(mc).has("abc123"));
		assertTrue(Files.exists(mc.resolve("launcher_profiles.json.javahost-backup")));
		assertEquals(ORIGINAL_PROFILES, Files.readString(mc.resolve("launcher_profiles.json.javahost-backup")));

		// Mods: JavaHost itself, Fabric API, and the relay (performance mods were switched off).
		List<String> mods = mods(mc);
		System.out.println("Installed mods: " + mods);
		assertTrue(mods.stream().anyMatch(n -> n.startsWith("javahost-")), mods.toString());
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
		Installer.install(new Installer.Options(mc, false, true, false, true, true, true), (f, s) -> {
		});
		assertEquals(2, profiles(mc).size());
		assertEquals(created, profiles(mc).getAsJsonObject(LauncherProfiles.PROFILE_KEY).get("created").getAsString());
		assertEquals(mods, mods(mc));

		// Uninstall: profile and Fabric version gone, the player's profile and JavaHost's worlds kept.
		Installer.uninstall(mc, false, (f, s) -> {
		});
		assertFalse(profiles(mc).has(LauncherProfiles.PROFILE_KEY));
		assertTrue(profiles(mc).has("abc123"));
		assertFalse(Files.exists(mc.resolve("versions/" + versionId)));
		assertTrue(Files.exists(mc.resolve("javahost/saves/My World/level.dat")));
		assertFalse(Installer.isInstalled(mc));
	}

	@Test
	void refusesWithoutLauncher(@TempDir Path mc) {
		Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> Installer.install(new Installer.Options(mc, false, false, false, false, false, false), (f, s) -> {
				}));
		assertTrue(e.getMessage().contains("Open the Minecraft Launcher once"), e.getMessage());
	}
}

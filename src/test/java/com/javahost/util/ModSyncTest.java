package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Modded worlds: the mod list friends get, modpacks, and switching mod sets safely. */
class ModSyncTest {
	private static final String HASH = "a".repeat(128);

	private static ModManifest.Entry mod(String id, String version, boolean content, String modrinth) {
		return new ModManifest.Entry(id, id, version, content, id + "-" + version + ".jar", 1_000_000, HASH, modrinth, "");
	}

	@Test
	void contentModsMustMatchExactlyLibrariesJustHaveToBeThere() {
		ModManifest host = new ModManifest("AlexPlays", "", List.of(
				mod("naturalist", "2.0.6", true, "AbCd1234"),
				mod("geckolib", "5.1", false, "EfGh5678"),
				mod("fabric-api", "0.140.0", false, "IjKl9012")));
		assertTrue(host.missing(ModManifest.of(Map.of("naturalist", "2.0.6", "geckolib", "5.0", "fabric-api", "0.141.0"))).isEmpty(),
				"a different library version is fine");
		assertEquals(List.of("naturalist"), host.missing(ModManifest.of(Map.of("naturalist", "2.0.5", "geckolib", "5.1", "fabric-api", "0.140.0")))
				.stream().map(ModManifest.Entry::id).toList(), "a content mod in another version is missing");
		assertEquals(3, host.missing(ModManifest.of(Map.of())).size());
	}

	@Test
	void theListSurvivesTheTripAndHasAStableId() {
		ModManifest host = new ModManifest("AlexPlays", "", List.of(mod("naturalist", "2.0.6", true, "AbCd1234"), mod("geckolib", "5.1", false, "")));
		ModManifest back = ModManifest.fromJson(host.toJson());
		assertEquals(host, back);
		assertEquals(host.id(), new ModManifest("Someone", "", List.of(host.mods().get(1), host.mods().get(0))).id(),
				"the same mods give the same id, whoever hosts and in any order");
	}

	@Test
	void aHostileListIsRefused() {
		String good = new ModManifest("A", "", List.of(mod("naturalist", "2.0.6", true, "AbCd1234"))).toJson();
		assertThrows(IllegalArgumentException.class, () -> ModManifest.fromJson(good.replace("naturalist-2.0.6.jar", "../../evil.jar")));
		assertThrows(IllegalArgumentException.class, () -> ModManifest.fromJson(good.replace("naturalist-2.0.6.jar", "evil.exe")));
		assertThrows(IllegalArgumentException.class, () -> ModManifest.fromJson(good.replace("AbCd1234", "../x")));
		assertThrows(IllegalArgumentException.class, () -> ModManifest.fromJson("{\"mods\": 5}"));
		StringBuilder many = new StringBuilder("{\"mods\":[");
		for (int i = 0; i <= ModManifest.MAX_MODS; i++) {
			many.append(i == 0 ? "" : ",").append("{\"id\":\"m").append(i).append("\",\"file\":\"m.jar\",\"sha512\":\"").append(HASH).append("\"}");
		}
		assertThrows(IllegalArgumentException.class, () -> ModManifest.fromJson(many.append("]}").toString()), "too many mods");
		String page = ModManifest.fromJson(good.replace("\"page\":\"\"", "\"page\":\"javascript:alert(1)\"")).mods().getFirst().page();
		assertEquals("", page, "only https links");
		String name = ModManifest.fromJson(good.replace("\"name\":\"naturalist\"", "\"name\":\"§kNatural\\u0000ist\"")).mods().getFirst().name();
		assertFalse(name.contains("§") || name.contains("\u0000"), "names are cleaned: " + name);
	}

	// ================================================================ modpacks

	private static String index(String path, String url) {
		return "{\"game\":\"minecraft\",\"name\":\"Cosy Pack\",\"versionId\":\"1.2\",\"dependencies\":{\"minecraft\":\"26.3\",\"fabric-loader\":\"0.17\"},"
				+ "\"files\":[{\"path\":\"" + path + "\",\"hashes\":{\"sha512\":\"" + HASH + "\"},\"fileSize\":10,\"downloads\":[\"" + url + "\"]}]}";
	}

	@Test
	void modpacksOnlyPutFilesInSafePlacesFromTrustedHosts() throws IOException {
		MrPack.Index index = MrPack.parseIndex(index("mods/sodium.jar", "https://cdn.modrinth.com/data/x/sodium.jar"));
		assertEquals("Cosy Pack", index.name());
		assertEquals("26.3", index.minecraft());
		assertEquals(1, index.files().size());
		assertThrows(IOException.class, () -> MrPack.parseIndex(index("../mods/evil.jar", "https://cdn.modrinth.com/x.jar")));
		assertThrows(IOException.class, () -> MrPack.parseIndex(index("mods/../../evil.jar", "https://cdn.modrinth.com/x.jar")));
		assertThrows(IOException.class, () -> MrPack.parseIndex(index("saves/evil.dat", "https://cdn.modrinth.com/x.jar")));
		assertThrows(IOException.class, () -> MrPack.parseIndex(index("/etc/evil.jar", "https://cdn.modrinth.com/x.jar")));
		assertThrows(IOException.class, () -> MrPack.parseIndex(index("mods/a.jar", "https://evil.example/a.jar")), "untrusted host");
		assertThrows(IOException.class, () -> MrPack.parseIndex(index("mods/a.jar", "http://cdn.modrinth.com/a.jar")), "not https");
		assertThrows(IOException.class, () -> MrPack.parseIndex(index("mods/a.jar", "https://cdn.modrinth.com/a.jar").replace("\"fabric-loader\":\"0.17\"",
				"\"forge\":\"52\"")), "Forge packs aren't for us");
	}

	@Test
	void overridesStayInsideTheAllowedFolders(@TempDir Path dir) throws IOException {
		Path pack = dir.resolve("pack.mrpack");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			for (String name : List.of("modrinth.index.json", "overrides/config/sodium.json", "overrides/../escape.txt", "overrides/saves/x.dat",
					"client-overrides/resourcepacks/pack.zip")) {
				zip.putNextEntry(new ZipEntry(name));
				zip.write("x".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		Path out = dir.resolve("out");
		List<String> written = MrPack.extractOverrides(pack, out);
		assertEquals(List.of("config/sodium.json", "resourcepacks/pack.zip"), written);
		assertFalse(Files.exists(dir.resolve("escape.txt")));
		assertFalse(Files.exists(out.resolve("saves")));
	}

	@Test
	void bigPacksWithThousandsOfConfigFilesUnpack(@TempDir Path dir) throws IOException {
		Path pack = dir.resolve("big.mrpack");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			for (int i = 0; i < 3000; i++) {
				zip.putNextEntry(new ZipEntry("overrides/config/quests/chapter" + i + ".snbt"));
				zip.write("{}".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		assertEquals(3000, MrPack.extractOverrides(pack, dir.resolve("out")).size(), "a pack with 3000 config files isn't turned away");
	}

	@Test
	void removedModsAreDeletedAndNothingElse(@TempDir Path dir) throws IOException {
		Path mods = dir.resolve("mods");
		write(mods.resolve("modmenu.jar"), "jar");
		write(mods.resolve(".javahost-modmenu"), "modmenu.jar");
		write(mods.resolve("keep.jar"), "jar");
		write(dir.resolve("outside.txt"), "precious");
		Path list = dir.resolve("remove-on-exit.txt");
		Files.write(list, List.of("modmenu.jar", ".javahost-modmenu", "../outside.txt", "..", "sub/keep.jar", "missing.jar"));
		assertEquals(List.of(), RemoveAfterExit.removeAll(mods, list));
		assertFalse(Files.exists(mods.resolve("modmenu.jar")));
		assertFalse(Files.exists(mods.resolve(".javahost-modmenu")));
		assertTrue(Files.exists(mods.resolve("keep.jar")), "only listed files go");
		assertTrue(Files.exists(dir.resolve("outside.txt")), "names with folders are ignored");
		assertFalse(Files.exists(list), "the list is gone once it's done");
	}

	// ================================================================ switching mod sets

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	private static Properties plan(Path game, Path store) {
		Properties plan = new Properties();
		plan.setProperty("mods", game.resolve("mods").toString());
		plan.setProperty("game", game.toString());
		plan.setProperty("store", store.toString());
		plan.setProperty("state", store.resolve("active.properties").toString());
		plan.setProperty("result", store.resolve("result.properties").toString());
		return plan;
	}

	private static Properties load(Path file) throws IOException {
		Properties p = new Properties();
		try (var in = Files.newInputStream(file)) {
			p.load(in);
		}
		return p;
	}

	@Test
	void switchingToAFriendsModsAndBackLeavesEverythingAsItWas(@TempDir Path dir) throws IOException {
		Path game = dir.resolve("game");
		Path store = dir.resolve("store");
		Path mods = game.resolve("mods");
		write(mods.resolve("javahost-1.9.0.jar"), "javahost");
		write(mods.resolve("sodium.jar"), "mine");
		write(mods.resolve("naturalist-2.0.5.jar"), "old naturalist");
		write(game.resolve("config/naturalist.json"), "my settings");
		Path set = dir.resolve("set");
		write(set.resolve("jars/naturalist-2.0.6.jar"), "new naturalist");
		write(set.resolve("jars/geckolib.jar"), "geckolib");
		write(set.resolve("overrides/config/naturalist.json"), "pack settings");
		write(set.resolve("overrides/config/geckolib.json"), "new file");

		Properties go = plan(game, store);
		go.setProperty("kind", "switch");
		go.setProperty("label", "AlexPlays's world");
		ModSwap.store(go, "out", List.of("naturalist-2.0.5.jar"));
		ModSwap.store(go, "in", List.of(set.resolve("jars/naturalist-2.0.6.jar").toString(), set.resolve("jars/geckolib.jar").toString()));
		go.setProperty("configFrom", set.resolve("overrides").toString());
		ModSwap.store(go, "config", List.of("config/naturalist.json", "config/geckolib.json"));
		assertTrue(ModSwap.run(go, store.resolve("result.properties")));
		assertEquals(List.of("geckolib.jar", "javahost-1.9.0.jar", "naturalist-2.0.6.jar", "sodium.jar"), names(mods));
		assertEquals("pack settings", Files.readString(game.resolve("config/naturalist.json")));

		// And back, using what the switch wrote down.
		Properties state = load(store.resolve("active.properties"));
		Properties back = plan(game, store);
		back.setProperty("kind", "restore");
		ModSwap.store(back, "remove", ModSwap.list(state, "added"));
		ModSwap.store(back, "back", ModSwap.list(state, "movedOut"));
		ModSwap.store(back, "configRemove", ModSwap.list(state, "configCreated"));
		ModSwap.store(back, "configRestore", ModSwap.list(state, "configBacked"));
		assertTrue(ModSwap.run(back, store.resolve("result.properties")));
		assertEquals(List.of("javahost-1.9.0.jar", "naturalist-2.0.5.jar", "sodium.jar"), names(mods));
		assertEquals("my settings", Files.readString(game.resolve("config/naturalist.json")));
		assertFalse(Files.exists(game.resolve("config/geckolib.json")));
		assertFalse(Files.exists(store.resolve("active.properties")));
	}

	@Test
	void aFailedSwitchPutsEverythingBack(@TempDir Path dir) throws IOException {
		Path game = dir.resolve("game");
		Path store = dir.resolve("store");
		Path mods = game.resolve("mods");
		write(mods.resolve("javahost.jar"), "javahost");
		write(mods.resolve("naturalist-2.0.5.jar"), "old");
		Properties go = plan(game, store);
		go.setProperty("kind", "switch");
		ModSwap.store(go, "out", List.of("naturalist-2.0.5.jar"));
		ModSwap.store(go, "in", List.of(dir.resolve("missing/naturalist-2.0.6.jar").toString()));
		assertFalse(ModSwap.run(go, store.resolve("result.properties")));
		assertEquals(List.of("javahost.jar", "naturalist-2.0.5.jar"), names(mods));
		assertEquals("false", load(store.resolve("result.properties")).getProperty("ok"));
		assertFalse(Files.exists(store.resolve("active.properties")));
	}

	@Test
	void namesFromAPlanCantEscapeTheirFolder(@TempDir Path dir) throws IOException {
		Path game = dir.resolve("game");
		Path store = dir.resolve("store");
		write(game.resolve("mods/a.jar"), "a");
		write(dir.resolve("precious.txt"), "keep me");
		Properties go = plan(game, store);
		go.setProperty("kind", "switch");
		ModSwap.store(go, "remove", List.of("../../precious.txt"));
		assertFalse(ModSwap.run(go, store.resolve("result.properties")));
		assertTrue(Files.exists(dir.resolve("precious.txt")));
	}

	private static List<String> names(Path folder) throws IOException {
		try (var files = Files.list(folder)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

}

package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Turning the player's own mods on and off (and Vanilla mode) through {@link ModSwap} and {@link AfterExit}. */
class ModSwapTest {
	private Path game;
	private Path mods;
	private Path off;

	private void setUp(Path dir) throws IOException {
		game = dir.resolve("game");
		mods = game.resolve("mods");
		off = dir.resolve("off");
		ModJarTest.jar(mods.resolve("javahost.jar"), "{\"id\": \"javahost\", \"depends\": {\"fabric-api\": \"*\"}}");
		ModJarTest.jar(mods.resolve("fabric-api.jar"), "{\"id\": \"fabric-api\"}");
		ModJarTest.jar(mods.resolve("cozylib.jar"), "{\"id\": \"cozylib\"}");
		ModJarTest.jar(mods.resolve("glowberry.jar"), "{\"id\": \"glowberry\", \"depends\": {\"cozylib\": \"*\"}}");
		ModJarTest.jar(mods.resolve("Minimap.jar"), "{\"id\": \"minimap\"}");
		ModJarTest.jar(mods.resolve("e4mc.jar"), "{\"id\": \"e4mc\"}");
		ModJarTest.jar(mods.resolve("plain.jar"), null);
		Files.writeString(mods.resolve(".javahost-minimap"), "Minimap.jar");
	}

	private Path state() {
		return off.resolve("state.properties");
	}

	private Path result() {
		return off.resolve("result.properties");
	}

	private Properties plan(String kind, ModToggle.Plan plan) {
		return ModToggle.toProperties(mods, game, off, state(), result(), -1, kind, kind, plan);
	}

	private List<String> inMods() {
		return ModJar.scan(mods).stream().map(ModJar::file).toList();
	}

	private List<String> inStore() throws IOException {
		return ModSwap.storeContents(off.resolve("mods"));
	}

	/** Turns the wanted jars off (and every other stored one back on), as the Mods tab will. */
	private boolean want(Set<String> wantOff) throws IOException {
		ModToggle.Plan p = ModToggle.plan(inMods(), inStore(), wantOff);
		return ModSwap.run(plan(wantOff.isEmpty() ? "restore" : "toggle", p), result());
	}

	static Map<String, String> fingerprint(Path folder) throws IOException {
		Map<String, String> files = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				files.put(folder.relativize(p).toString(), sha256(p));
			}
		}
		return files;
	}

	private static String sha256(Path file) throws IOException {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static Properties load(Path file) throws IOException {
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
		}
		return p;
	}

	@Test
	void vanillaAndBackLeavesTheFolderByteForByteAsItWas(@TempDir Path dir) throws IOException {
		setUp(dir);
		Map<String, String> before = fingerprint(mods);
		List<String> out = ModToggle.vanilla(ModJar.scan(mods), ModToggle.VANILLA_KEEP);
		assertEquals(List.of("cozylib.jar", "glowberry.jar", "Minimap.jar", "plain.jar"), out);
		assertTrue(ModSwap.run(plan("vanilla", ModToggle.plan(inMods(), inStore(), Set.copyOf(out))), result()));
		assertEquals(List.of("e4mc.jar", "fabric-api.jar", "javahost.jar"), inMods());
		assertEquals(List.of("Minimap.jar", "cozylib.jar", "glowberry.jar", "plain.jar"), inStore());
		Properties state = load(state());
		assertEquals(inStore(), ModSwap.list(state, "movedOut"));
		assertEquals("vanilla", state.getProperty("kind"));

		assertTrue(want(Set.of()));
		assertEquals(before, fingerprint(mods), "every file back, same names, same bytes");
		assertEquals(List.of(), inStore());
		assertFalse(Files.exists(state()), "nothing left in the store: no state");
		assertEquals("true", load(result()).getProperty("ok"));
		assertFalse(Files.exists(off.resolve("removing")), "the parking place is cleared");
	}

	@Test
	void repeatedTogglesKeepTheStateEqualToTheStore(@TempDir Path dir) throws IOException {
		setUp(dir);
		assertTrue(want(Set.of("Minimap.jar")));
		assertEquals(List.of("Minimap.jar"), ModSwap.list(load(state()), "movedOut"));
		assertTrue(want(Set.of("Minimap.jar", "plain.jar")));
		assertEquals(List.of("Minimap.jar", "plain.jar"), ModSwap.list(load(state()), "movedOut"));
		assertTrue(want(Set.of("plain.jar")));
		assertEquals(List.of("plain.jar"), ModSwap.list(load(state()), "movedOut"));
		assertEquals(inStore(), ModSwap.list(load(state()), "movedOut"));
		assertTrue(inMods().contains("Minimap.jar"));
		assertTrue(want(Set.of()));
		assertFalse(Files.exists(state()));
	}

	/** The one file in store/replaced, checked against the result's {@code replaced} list. */
	private Path onlyReplaced(String name) throws IOException {
		List<String> listed = ModSwap.list(load(result()), "replaced");
		assertEquals(1, listed.size(), listed.toString());
		assertTrue(listed.get(0).matches("replaced/\\d+-" + java.util.regex.Pattern.quote(name)), listed.get(0));
		Path file = off.resolve(listed.get(0));
		assertTrue(Files.isRegularFile(file));
		return file;
	}

	@Test
	void aJarReinstalledWhileOffMergesWhenIdenticalAndTheOldCopyIsKeptAsideWhenNot(@TempDir Path dir) throws IOException {
		setUp(dir);
		assertTrue(want(Set.of("Minimap.jar", "plain.jar")));
		// The player puts the very same Minimap back, and a different file named plain.jar.
		Files.copy(off.resolve("mods/Minimap.jar"), mods.resolve("Minimap.jar"));
		String storedPlain = sha256(off.resolve("mods/plain.jar"));
		Files.writeString(mods.resolve("plain.jar"), "a different plain");

		assertTrue(want(Set.of()));
		assertTrue(inMods().contains("Minimap.jar"));
		assertFalse(Files.exists(off.resolve("mods/Minimap.jar")), "identical: the stored copy is dropped");
		assertEquals("a different plain", Files.readString(mods.resolve("plain.jar")), "the one in mods stays");
		assertEquals(List.of(), inStore(), "nothing is left on both sides");
		assertEquals(storedPlain, sha256(onlyReplaced("plain.jar")), "the stored one is kept aside, not lost");
		assertEquals(List.of("plain.jar"), ModSwap.list(load(result()), "conflicts"));
		assertFalse(Files.exists(state()), "everything is back on");
	}

	@Test
	void turningOffAJarTheStoreAlreadyHasNeverOverwritesIt(@TempDir Path dir) throws IOException {
		setUp(dir);
		Files.createDirectories(off.resolve("mods"));
		Files.writeString(off.resolve("mods/plain.jar"), "an older plain");
		Files.copy(mods.resolve("Minimap.jar"), off.resolve("mods/Minimap.jar"));
		String plain = sha256(mods.resolve("plain.jar"));
		ModToggle.Plan p = new ModToggle.Plan(List.of("Minimap.jar", "plain.jar"), List.of());
		assertTrue(ModSwap.run(plan("toggle", p), result()));
		assertFalse(inMods().contains("Minimap.jar"), "identical: off, one copy");
		assertFalse(inMods().contains("plain.jar"), "different: it still goes off");
		assertEquals(plain, sha256(off.resolve("mods/plain.jar")));
		assertEquals("an older plain", Files.readString(onlyReplaced("plain.jar")), "the older stored copy is kept aside");
		assertEquals(List.of("plain.jar"), ModSwap.list(load(result()), "conflicts"));
		assertEquals(List.of("Minimap.jar", "plain.jar"), ModSwap.list(load(state()), "movedOut"));
		assertTrue(want(Set.of()));
		assertTrue(Files.exists(off.resolve("replaced")), "replaced/ is never cleaned up automatically");
	}

	@Test
	void aFailureAtTheLastStepPutsEveryFileBack(@TempDir Path dir) throws IOException {
		setUp(dir);
		assertTrue(want(Set.of("Minimap.jar", "glowberry.jar")));
		Files.copy(off.resolve("mods/glowberry.jar"), mods.resolve("glowberry.jar")); // will be merged
		Files.writeString(mods.resolve("Minimap.jar"), "a different minimap"); // the stored one would be replaced
		Files.writeString(off.resolve("mods/plain.jar"), "an older plain"); // would be replaced by the one going off
		Map<String, String> modsBefore = fingerprint(mods);
		Map<String, String> storeBefore = fingerprint(off.resolve("mods"));

		// Off: cozylib, plain; back: Minimap, glowberry. The state can't be written.
		Files.writeString(dir.resolve("blocker"), "a file where the state folder should be");
		ModToggle.Plan wanted = new ModToggle.Plan(List.of("cozylib.jar", "plain.jar"), List.of("Minimap.jar", "glowberry.jar"));
		Properties p = ModToggle.toProperties(mods, game, off, dir.resolve("blocker/state.properties"), result(), -1, "", "toggle", wanted);
		assertFalse(ModSwap.run(p, result()));
		assertEquals(modsBefore, fingerprint(mods));
		assertEquals(storeBefore, fingerprint(off.resolve("mods")), "duplicates and replaced copies are back where they were");
		assertEquals(Map.of(), Files.exists(off.resolve("replaced")) ? fingerprint(off.resolve("replaced")) : Map.of());
		assertEquals("false", load(result()).getProperty("ok"));
	}

	@Test
	void restoreAlwaysEndsTheStateEvenWithStrayFilesInTheStore(@TempDir Path dir) throws IOException {
		setUp(dir);
		Files.createDirectories(off.resolve("mods"));
		Files.writeString(off.resolve("mods/stray-from-an-old-version.jar"), "stray");
		assertTrue(want(Set.of("Minimap.jar", "stray-from-an-old-version.jar")));
		assertEquals(List.of("Minimap.jar", "stray-from-an-old-version.jar"), ModSwap.list(load(state()), "movedOut"));

		ModToggle.Plan onlyMinimap = new ModToggle.Plan(List.of(), List.of("Minimap.jar"));
		assertTrue(ModSwap.run(plan("restore", onlyMinimap), result()));
		assertFalse(Files.exists(state()), "restore: no state, whatever is left in the store");
		assertEquals(List.of("stray-from-an-old-version.jar"), inStore());
		assertTrue(inMods().contains("Minimap.jar"));
	}

	@Test
	void aModSetSwitchStillTracksItsStoreAndReplacesAClashingStoredCopy(@TempDir Path dir) throws IOException {
		setUp(dir);
		Path set = dir.resolve("set");
		Files.createDirectories(set.resolve("jars"));
		Files.writeString(set.resolve("jars/naturalist.jar"), "naturalist");
		Files.createDirectories(off.resolve("mods"));
		Files.writeString(off.resolve("mods/old-map.jar"), "kept from the set before");
		Files.writeString(off.resolve("mods/plain.jar"), "a different plain");
		Properties go = plan("toggle", new ModToggle.Plan(List.of("Minimap.jar"), List.of("plain.jar")));
		go.setProperty("kind", "switch");
		go.setProperty("set", "host-abc");
		ModSwap.store(go, "keepOut", List.of("old-map.jar"));
		ModSwap.store(go, "in", List.of(set.resolve("jars/naturalist.jar").toString()));
		assertTrue(ModSwap.run(go, result()));
		Properties state = load(state());
		assertEquals("host-abc", state.getProperty("set"));
		assertEquals(List.of("naturalist.jar"), ModSwap.list(state, "added"));
		assertEquals(List.of("Minimap.jar", "old-map.jar"), ModSwap.list(state, "movedOut"));
		assertFalse(state.containsKey("conflicts.0"));
		assertEquals(List.of("plain.jar"), ModSwap.list(load(result()), "conflicts"));
		assertEquals("a different plain", Files.readString(onlyReplaced("plain.jar")));
		assertEquals(List.of("Minimap.jar", "old-map.jar"), inStore());
		assertTrue(Files.exists(mods.resolve("naturalist.jar")));
		assertFalse(Files.exists(off.resolve("removing")), "the copy's staging place is cleared");
	}

	@Test
	void afterExitRemovesThenSwapsInOneGo(@TempDir Path dir) throws IOException {
		setUp(dir);
		Path list = dir.resolve("remove-on-exit.txt");
		Files.write(list, List.of("Minimap.jar", ".javahost-minimap", "../escape.jar"));
		Path planFile = dir.resolve("plan.properties");
		try (var out = Files.newOutputStream(planFile)) {
			plan("toggle", new ModToggle.Plan(List.of("Minimap.jar", "plain.jar"), List.of())).store(out, null);
		}
		assertTrue(AfterExit.run(-1, mods, list, planFile, 5));
		assertFalse(Files.exists(mods.resolve("Minimap.jar")), "removed, not stored");
		assertFalse(Files.exists(mods.resolve(".javahost-minimap")));
		assertFalse(Files.exists(list));
		assertEquals(List.of("plain.jar"), inStore());
		assertEquals(List.of("plain.jar"), ModSwap.list(load(state()), "movedOut"));
		assertEquals("true", load(result()).getProperty("ok"));

		// Each half on its own, and nothing at all.
		Files.createDirectories(mods.resolve("folder"));
		Files.write(list, List.of("cozylib.jar", "folder"));
		assertTrue(AfterExit.run(-1, mods, list, null, 5));
		assertFalse(Files.exists(mods.resolve("cozylib.jar")));
		assertTrue(Files.isDirectory(mods.resolve("folder")), "only files are removed");
		assertTrue(AfterExit.run(-1, mods, null, dir.resolve("no-plan.properties"), 5));
		AfterExit.main(new String[] {"-1", mods.toString(), "-", "-"});
	}
}

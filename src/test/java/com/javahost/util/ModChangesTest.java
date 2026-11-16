package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModChangesTest {
	@TempDir
	Path dir;

	@Test
	void wantingWhatAlreadyIsForgetsTheChange() {
		ModChanges changes = new ModChanges();
		assertTrue(changes.want("glowberry.jar", false, true));
		assertEquals(Set.of("glowberry.jar"), changes.off());
		assertFalse(changes.wantsOn("glowberry.jar", true));
		assertTrue(changes.want("glowberry.jar", true, true));
		assertTrue(changes.isEmpty(), "switching it back on again is no change at all");
		assertTrue(changes.want("mossy.jar", true, false));
		assertEquals(Set.of("mossy.jar"), changes.on());
		assertTrue(changes.wantsOn("mossy.jar", false));
		assertFalse(changes.wantsOn("other.jar", false), "an off jar nobody touched stays off");
	}

	@Test
	void onlyPlainJarNamesAreKept() {
		ModChanges changes = new ModChanges();
		assertFalse(changes.want("../evil.jar", false, true));
		assertFalse(changes.want("sub/dir.jar", false, true));
		assertFalse(changes.want("notes.txt", false, true));
		assertFalse(changes.want(".javahost-glowberry", false, true));
		assertTrue(changes.isEmpty());
	}

	@Test
	void thePlanIsWhatModToggleGives() {
		ModChanges changes = new ModChanges();
		changes.want("cozylib.jar", false, true);
		changes.want("glowberry.jar", false, true);
		changes.want("mossy.jar", true, false);
		List<String> on = List.of("cozylib.jar", "glowberry.jar", "minimap.jar", "leaving.jar");
		List<String> off = List.of("mossy.jar", "oldmap.jar");
		Set<String> wantOff = changes.wantOff(on, off);
		assertEquals(Set.of("cozylib.jar", "glowberry.jar", "oldmap.jar"), wantOff, "what's off and stays off, plus what's wanted off");
		ModToggle.Plan plan = changes.plan(on, off, Set.of());
		assertEquals(ModToggle.plan(on, off, wantOff), plan);
		assertEquals(List.of("cozylib.jar", "glowberry.jar"), plan.out());
		assertEquals(List.of("mossy.jar"), plan.back());
		// A jar being removed is left alone (the removal deletes it first).
		changes.want("leaving.jar", false, true);
		assertEquals(List.of("cozylib.jar", "glowberry.jar"), changes.plan(on, off, Set.of("leaving.jar")).out());
	}

	@Test
	void changesForJarsThatAreGoneAreDropped() {
		ModChanges changes = new ModChanges();
		changes.want("glowberry.jar", false, true);
		changes.want("mossy.jar", true, false);
		assertFalse(changes.prune(List.of("glowberry.jar"), List.of("mossy.jar")));
		assertTrue(changes.prune(List.of(), List.of("mossy.jar")), "deleted by hand");
		assertEquals(Set.of(), changes.off());
		assertTrue(changes.prune(List.of("mossy.jar"), List.of()), "put back in mods/ by hand");
		assertTrue(changes.isEmpty());
	}

	@Test
	void theFileRoundTripsAndIsDeletedWhenEmpty() throws IOException {
		Path file = dir.resolve("mods-off").resolve("wanted.properties");
		ModChanges changes = new ModChanges();
		changes.want("glowberry.jar", false, true);
		changes.want("mossy paths.jar", true, false);
		changes.write(file);
		ModChanges read = ModChanges.read(file);
		assertEquals(changes.off(), read.off());
		assertEquals(changes.on(), read.on());
		read.clear();
		read.write(file);
		assertFalse(Files.exists(file));
		assertTrue(ModChanges.read(file).isEmpty(), "a missing file is no changes");
	}

	@Test
	void aTamperedFileIsCleanedOnRead() throws IOException {
		Path file = dir.resolve("wanted.properties");
		Files.writeString(file, "off.0=../../escape.jar\noff.1=fine.jar\non.0=fine.jar\non.1=C\\:\\\\x.jar\non.2=back.jar\n");
		ModChanges read = ModChanges.read(file);
		assertEquals(Set.of("fine.jar"), read.off());
		assertEquals(Set.of("back.jar"), read.on(), "a name can't be wanted both ways");
		Files.writeString(file, "garbage \u0000\u0001");
		assertTrue(ModChanges.read(file).isEmpty());
		Files.write(file, new byte[2 << 20]);
		assertTrue(ModChanges.read(file).isEmpty(), "an oversized file is ignored");
	}

	@Test
	void theListIsCapped() {
		ModChanges changes = new ModChanges();
		for (int i = 0; i < ModChanges.MAX + 10; i++) {
			changes.want("m" + i + ".jar", false, true);
		}
		assertEquals(ModChanges.MAX, changes.size());
		assertTrue(changes.want("m0.jar", true, true), "taking one back still works when full");
	}

	// ---------------------------------------------------------------- states

	private static final Map<String, String> PROTECTED = Map.of("javahost", "This is JavaHost", "fabric-api", "JavaHost needs it");

	private static ModStates.Inputs inputs(List<ModJar> on, List<ModJar> off, Set<String> loadedFiles, Set<String> loadedIds, Set<String> atStart,
			ModChanges changes, Set<String> removing) {
		return new ModStates.Inputs(on, off, loadedFiles, loadedIds, atStart, changes, removing, Map.of("glowberry.jar", "glowberry"), PROTECTED,
				Set.of());
	}

	private static ModStates.Entry find(List<ModStates.Entry> entries, String file) {
		return entries.stream().filter(e -> e.file().equals(file)).findFirst().orElseThrow();
	}

	@Test
	void statesComeFromWhatLoadedWhatIsWantedAndWhatIsLeaving() {
		ModJar fabricApi = ModToggleTest.mod("fabric-api.jar", "fabric-api");
		ModJar cozylib = ModToggleTest.mod("cozylib.jar", "cozylib");
		ModJar glowberry = ModToggleTest.mod("glowberry.jar", "glowberry", "cozylib");
		ModJar fresh = ModToggleTest.mod("fresh.jar", "fresh");
		ModJar leaving = ModToggleTest.mod("leaving.jar", "leaving");
		ModJar plain = ModJar.parse("plain.jar", "not json");
		ModJar mossy = ModToggleTest.mod("mossy.jar", "mossy");
		ModJar oldmap = ModToggleTest.mod("oldmap.jar", "oldmap");
		ModChanges changes = new ModChanges();
		changes.want("cozylib.jar", false, true);
		changes.want("mossy.jar", true, false);
		List<ModStates.Entry> entries = ModStates.derive(inputs(List.of(fabricApi, cozylib, glowberry, fresh, leaving, plain), List.of(mossy, oldmap),
				Set.of("fabric-api.jar", "glowberry.jar", "leaving.jar"), Set.of("cozylib"),
				Set.of("fabric-api.jar", "cozylib.jar", "glowberry.jar", "leaving.jar", "plain.jar"), changes, Set.of("leaving.jar")));
		assertEquals(ModStates.State.ON, find(entries, "fabric-api.jar").state());
		assertEquals("JavaHost needs it", find(entries, "fabric-api.jar").protectedReason());
		assertEquals(ModStates.State.TURNING_OFF, find(entries, "cozylib.jar").state());
		assertEquals(ModStates.State.ON, find(entries, "glowberry.jar").state());
		assertTrue(find(entries, "glowberry.jar").installedByJavaHost());
		assertEquals(ModStates.State.NEW, find(entries, "fresh.jar").state(), "put in mods/ since the start and not loaded");
		assertEquals(ModStates.State.REMOVING, find(entries, "leaving.jar").state());
		assertEquals(ModStates.State.ON, find(entries, "plain.jar").state(), "there since the start: shown on, though Fabric doesn't load it");
		assertEquals(ModStates.State.TURNING_ON, find(entries, "mossy.jar").state());
		assertEquals(ModStates.State.OFF, find(entries, "oldmap.jar").state());
		assertTrue(find(entries, "mossy.jar").wantedOn());
		assertFalse(find(entries, "cozylib.jar").wantedOn());
		// On ones first, then off, each by name.
		assertEquals(List.of("cozylib.jar", "fabric-api.jar", "fresh.jar", "glowberry.jar", "leaving.jar", "plain.jar", "mossy.jar", "oldmap.jar"),
				entries.stream().map(ModStates.Entry::file).toList());
	}

	@Test
	void aNameInBothPlacesIsListedOnce() {
		ModJar a = ModToggleTest.mod("same.jar", "same");
		List<ModStates.Entry> entries = ModStates.derive(inputs(List.of(a), List.of(a), Set.of("same.jar"), Set.of(), Set.of("same.jar"),
				new ModChanges(), Set.of()));
		assertEquals(1, entries.size());
		assertTrue(entries.getFirst().inMods());
	}

	@Test
	void searchMatchesNamesIdsAndFiles() {
		ModJar glowberry = ModToggleTest.mod("glowberry-1.2.jar", "glowberry");
		ModJar mossy = ModJar.parse("mossy-paths.jar", "{\"id\": \"mossy\", \"name\": \"Mossy Paths\"}");
		List<ModStates.Entry> entries = ModStates.derive(inputs(List.of(glowberry, mossy), List.of(), Set.of(), Set.of(), Set.of(),
				new ModChanges(), Set.of()));
		assertEquals(List.of("mossy-paths.jar"), ModStates.search(entries, "mossy p").stream().map(ModStates.Entry::file).toList());
		assertEquals(List.of("glowberry-1.2.jar"), ModStates.search(entries, "GLOW").stream().map(ModStates.Entry::file).toList());
		assertEquals(2, ModStates.search(entries, "  ").size());
	}
}

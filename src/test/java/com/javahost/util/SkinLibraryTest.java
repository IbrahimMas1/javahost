package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The skin collection: what counts as a skin, old skins, the front view, the cap, duplicates and the index file. */
class SkinLibraryTest {
	/** An original test character: a coloured block body, seeded so each one is different. */
	static int[] character(int seed, int height) {
		int[] pixels = new int[64 * height];
		java.util.Random random = new java.util.Random(seed);
		int shirt = 0xFF000000 | random.nextInt(0xFFFFFF);
		int trousers = 0xFF000000 | random.nextInt(0xFFFFFF);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < 64; x++) {
				pixels[y * 64 + x] = y < 16 ? (x < 32 ? 0xFFC8A07A : 0) : y < 32 ? (x < 16 ? trousers : shirt) : y >= 48 && x >= 16 && x < 48 ? (x < 32 ? trousers : shirt) : 0;
			}
		}
		pixels[9 * 64 + 10] = 0xFF2040FF; // an eye
		return pixels;
	}

	static byte[] png(int seed) {
		return SkinImage.png(character(seed, 64));
	}

	@Test
	void onlyRealSkinsAreAccepted() throws Exception {
		assertEquals(64 * 64, SkinImage.read(png(1)).length);
		SkinImage.Invalid wrongSize = assertThrows(SkinImage.Invalid.class, () -> SkinImage.read(SkinImage.png(new int[32 * 32], 32, 32)));
		assertTrue(wrongSize.getMessage().contains("64×64"), wrongSize.getMessage());
		assertThrows(SkinImage.Invalid.class, () -> SkinImage.read("not a png at all, just some text here".getBytes(StandardCharsets.UTF_8)));
		assertThrows(SkinImage.Invalid.class, () -> SkinImage.read(new byte[0]));
		assertThrows(SkinImage.Invalid.class, () -> SkinImage.read(new byte[SkinImage.MAX_BYTES + 1]));
		// A header claiming 64x64 with garbage after it isn't decoded into anything.
		byte[] liar = java.util.Arrays.copyOf(png(1), 40);
		assertThrows(SkinImage.Invalid.class, () -> SkinImage.read(liar));
		// An "image bomb" header (huge size) is refused from the header alone.
		byte[] bomb = png(1).clone();
		bomb[16] = 0x7F;
		assertThrows(SkinImage.Invalid.class, () -> SkinImage.read(bomb));
	}

	@Test
	void oldSkinsGetTheirLeftLimbsMirrored() throws Exception {
		int[] legacy = character(3, 32);
		legacy[20 * 64 + 4] = 0xFFFF0000; // right leg, front face, leftmost pixel
		legacy[20 * 64 + 44] = 0xFF00FF00; // right arm, front face, leftmost pixel
		int[] skin = SkinImage.read(SkinImage.png(legacy, 64, 32));
		assertEquals(64 * 64, skin.length);
		// Mirrored: the leftmost pixel of the right leg's front is the rightmost of the left leg's front (20..23, 52).
		assertEquals(0xFFFF0000, skin[52 * 64 + 23]);
		assertEquals(0xFF00FF00, skin[52 * 64 + 39]);
		// The top half is kept.
		for (int i = 0; i < 64 * 16; i++) {
			if ((legacy[i] >>> 24) != 0 && i % 64 < 32) {
				assertEquals(legacy[i], skin[i]);
			}
		}
	}

	@Test
	void anOpaqueOldHatIsMadeSeeThrough() {
		int[] legacy = new int[64 * 32];
		java.util.Arrays.fill(legacy, 0xFF336699);
		int[] skin = SkinImage.fromLegacy(legacy);
		assertEquals(0, skin[8 * 64 + 40] >>> 24);
		assertEquals(0xFF336699, skin[8 * 64 + 8]);
	}

	@Test
	void theFrontViewPutsTheFaceOnTop() {
		int[] skin = character(5, 64);
		int[] front = SkinImage.front(skin, false);
		assertEquals(SkinImage.FRONT_W * SkinImage.FRONT_H, front.length);
		assertEquals(0xFF2040FF, front[1 * 16 + 6]); // the eye: head front (8,8) drawn at (4,0)
		assertEquals(0, front[0] >>> 24); // beside the head is empty
		int[] slim = SkinImage.front(skin, true);
		assertEquals(0, slim[10 * 16] >>> 24); // slim arms leave the outer column empty
		assertNotEquals(0, front[10 * 16] >>> 24);
	}

	@Test
	void fingerprintsIgnoreHiddenColourAndCountTheModel() {
		int[] a = character(7, 64);
		int[] b = a.clone();
		b[40] = 0x00123456; // transparent either way
		assertEquals(SkinImage.fingerprint(a, "classic"), SkinImage.fingerprint(b, "classic"));
		assertNotEquals(SkinImage.fingerprint(a, "classic"), SkinImage.fingerprint(a, "slim"));
	}

	@Test
	void addRenameDeleteAndRoundTrip(@TempDir Path dir) throws Exception {
		SkinFolder folder = new SkinFolder(dir);
		folder.load();
		SkinFolder.Added first = folder.add(png(1), "§cRed\u0007 Knight", "slim", "file");
		assertFalse(first.existed());
		assertEquals("Red Knight", first.entry().name());
		assertTrue(first.entry().slim());
		assertTrue(first.entry().id().matches("[0-9a-f]{12}"));
		assertTrue(Files.isRegularFile(dir.resolve(first.entry().id() + ".png")));
		SkinFolder.Added second = folder.add(png(2), "x".repeat(100), "classic", "player:Steve123");
		assertEquals(SkinFolder.MAX_NAME, second.entry().name().length());
		assertEquals(second.entry().id(), folder.entries().get(0).id(), "newest first");

		// The same picture again is not added twice; the same picture with other arms is a different skin.
		assertTrue(folder.add(png(1), "Again", "slim", "file").existed());
		assertFalse(folder.add(png(1), "Wide", "classic", "file").existed());
		assertEquals(3, folder.size());

		assertTrue(folder.rename(first.entry().id(), "Knight"));
		assertFalse(folder.rename(first.entry().id(), "   "));
		folder.markWorn(first.entry().id(), "https://textures.minecraft.net/texture/abc");

		SkinFolder again = new SkinFolder(dir);
		again.load();
		assertEquals(folder.entries(), again.entries());
		assertEquals(first.entry().id(), again.worn());
		assertEquals("Knight", again.get(first.entry().id()).name());
		assertTrue(again.get(first.entry().id()).lastWorn() > 0);
		assertArrayEquals(Files.readAllBytes(dir.resolve(first.entry().id() + ".png")), again.read(first.entry().id()));

		assertTrue(again.delete(first.entry().id()));
		assertNull(again.worn());
		assertFalse(Files.exists(dir.resolve(first.entry().id() + ".png")));
		assertThrows(IllegalArgumentException.class, () -> again.file("../index"));
	}

	@Test
	void updateKeepsTheIdForTheEditor(@TempDir Path dir) throws Exception {
		SkinFolder folder = new SkinFolder(dir);
		folder.load();
		String id = folder.add(character(1, 64), "Mine", "classic", "editor").entry().id();
		String before = folder.get(id).fingerprint();
		assertTrue(folder.update(id, character(2, 64)));
		assertNotEquals(before, folder.get(id).fingerprint());
		assertArrayEquals(character(2, 64), SkinImage.read(folder.read(id)));
	}

	@Test
	void theCollectionIsCapped(@TempDir Path dir) throws Exception {
		SkinFolder folder = new SkinFolder(dir);
		folder.load();
		for (int i = 0; i < SkinFolder.MAX_SKINS; i++) {
			folder.add(character(1000 + i, 64), "Skin " + i, "classic", "file");
		}
		assertThrows(SkinFolder.Full.class, () -> folder.add(png(5), "One more", "classic", "file"));
		assertEquals(SkinFolder.MAX_SKINS, folder.size());
	}

	@Test
	void filesDroppedInTheFolderAreTakenIn(@TempDir Path dir) throws Exception {
		Files.write(dir.resolve("My_Cool-Skin.png"), png(9));
		Files.write(dir.resolve("notes.png"), "not an image".getBytes(StandardCharsets.UTF_8));
		Files.write(dir.resolve("tiny.png"), SkinImage.png(new int[16 * 16], 16, 16));
		SkinFolder folder = new SkinFolder(dir);
		folder.load();
		assertEquals(1, folder.size());
		SkinFolder.Entry entry = folder.entries().get(0);
		assertEquals("My Cool Skin", entry.name());
		assertEquals("folder", entry.source());
		assertFalse(Files.exists(dir.resolve("My_Cool-Skin.png")), "the original is replaced by the saved copy");
		assertEquals(2, folder.refused());
		assertTrue(Files.exists(dir.resolve("notes.png")), "files that aren't skins are left alone");
	}

	@Test
	void aHostileIndexIsSurvived(@TempDir Path dir) throws Exception {
		SkinFolder folder = new SkinFolder(dir);
		folder.load();
		String id = folder.add(png(1), "Good", "classic", "file").entry().id();
		String fingerprint = folder.get(id).fingerprint();
		Files.writeString(dir.resolve("index.json"), """
				{"worn": "../../../etc/passwd", "wornUrl": "javascript:alert(1)", "skins": [
				  {"id": "../../evil", "name": "Evil"},
				  {"id": "%s", "name": "§kGood\\n\\u0000", "model": "giant", "added": -5, "fingerprint": "%s"},
				  {"id": "%s", "name": "Duplicate id"},
				  {"id": "aaaaaaaaaaaa", "name": "No file"},
				  "not an object", 42, null,
				  {"id": {"nested": true}, "name": ["x"]}
				]}""".formatted(id, fingerprint, id));
		SkinFolder again = new SkinFolder(dir);
		again.load();
		List<SkinFolder.Entry> entries = again.entries();
		assertEquals(1, entries.size());
		assertEquals("Good", entries.get(0).name());
		assertEquals("classic", entries.get(0).model());
		assertEquals(0, entries.get(0).added());
		assertNull(again.worn());
		assertNull(again.wornUrl());

		Files.writeString(dir.resolve("index.json"), "{{{{ not json");
		SkinFolder broken = new SkinFolder(dir);
		broken.load();
		assertEquals(1, broken.size(), "a broken index loses names, not skins");

		Files.writeString(dir.resolve("index.json"), "[" + "[".repeat(5000));
		new SkinFolder(dir).load();
	}

	@Test
	void namesAreCleaned() {
		assertEquals("Red Knight", SkinFolder.cleanName("  §4Red\n\tKnight  "));
		assertEquals("", SkinFolder.cleanName(null));
		assertEquals(SkinFolder.MAX_NAME, SkinFolder.cleanName("a".repeat(500)).length());
	}

	@Test
	void writesLeaveNoTemporaryFiles(@TempDir Path dir) throws IOException, Exception {
		SkinFolder folder = new SkinFolder(dir);
		folder.load();
		folder.add(png(1), "One", "classic", "file");
		try (var files = Files.list(dir)) {
			assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
		}
	}
}

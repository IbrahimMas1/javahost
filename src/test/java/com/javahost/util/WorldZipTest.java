package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldZipTest {
	@TempDir
	Path temp;

	@Test
	void backsUpAndRestoresAWorld() throws IOException {
		Path world = temp.resolve("saves/My World");
		Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("level.dat"), "level");
		Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
		Files.writeString(world.resolve("session.lock"), "locked");

		Path zip = temp.resolve("backups/My World/2026-10-12.zip");
		assertTrue(WorldZip.zip(world, zip) > 0);

		Path restored = temp.resolve("saves/Restored");
		WorldZip.unzip(zip, restored);
		assertEquals("level", Files.readString(restored.resolve("level.dat")));
		assertEquals("chunks", Files.readString(restored.resolve("region/r.0.0.mca")));
		assertFalse(Files.exists(restored.resolve("session.lock")), "the lock file isn't backed up");
		assertThrows(IOException.class, () -> WorldZip.unzip(zip, restored), "never unzips over an existing world");
	}

	@Test
	void refusesEntriesThatEscapeTheWorldFolder() throws IOException {
		Path evil = temp.resolve("evil.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(evil))) {
			zip.putNextEntry(new ZipEntry("../../outside.txt"));
			zip.write(1);
			zip.closeEntry();
		}
		assertThrows(IOException.class, () -> WorldZip.unzip(evil, temp.resolve("saves/World")));
		assertFalse(Files.exists(temp.resolve("outside.txt")));
	}

	private Path zipOf(String name, String... entries) throws IOException {
		Path file = temp.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			for (int i = 0; i < entries.length; i += 2) {
				zip.putNextEntry(new ZipEntry(entries[i]));
				zip.write(entries[i + 1].getBytes());
				zip.closeEntry();
			}
		}
		return file;
	}

	@Test
	void unpacksASentWorldAndFlattensOneFolder() throws IOException {
		Path flat = zipOf("flat.zip", "level.dat", "L", "region/r.0.0.mca", "R");
		WorldZip.unzipWorld(flat, temp.resolve("saves/A"), 1000, 10);
		assertEquals("R", Files.readString(temp.resolve("saves/A/region/r.0.0.mca")));
		assertEquals(2, WorldZip.unpackedSize(flat));

		Path nested = zipOf("nested.zip", "Survival/level.dat", "L", "Survival/region/r.0.0.mca", "R", "__MACOSX/junk", "J");
		WorldZip.unzipWorld(nested, temp.resolve("saves/B"), 1000, 10);
		assertEquals("L", Files.readString(temp.resolve("saves/B/level.dat")));
		assertFalse(Files.exists(temp.resolve("saves/B/junk")), "only the world folder is unpacked");
	}

	@Test
	void refusesNonWorldsBombsAndEscapes() throws IOException {
		assertThrows(IOException.class, () -> WorldZip.unzipWorld(zipOf("none.zip", "readme.txt", "hi"), temp.resolve("saves/C"), 1000, 10),
				"not a world");
		assertThrows(IOException.class, () -> WorldZip.unzipWorld(zipOf("big.zip", "level.dat", "x".repeat(5000)), temp.resolve("saves/D"), 1000, 10),
				"bigger than it said");
		assertThrows(IOException.class, () -> WorldZip.unzipWorld(zipOf("many.zip", "level.dat", "L", "a", "1", "b", "2", "c", "3"),
				temp.resolve("saves/E"), 1000, 3), "too many files");
		assertThrows(IOException.class, () -> WorldZip.unzipWorld(zipOf("evil2.zip", "level.dat", "L", "../outside.txt", "x"),
				temp.resolve("saves/F"), 1000, 10), "escapes the folder");
		assertFalse(Files.exists(temp.resolve("saves/outside.txt")));
	}
}

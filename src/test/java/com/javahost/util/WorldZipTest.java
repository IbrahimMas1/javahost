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
}

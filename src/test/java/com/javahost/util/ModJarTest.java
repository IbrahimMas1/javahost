package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModJarTest {
	/** A small jar with a {@code fabric.mod.json} (none when {@code json} is null) and a class file. */
	static Path jar(Path file, String json) throws IOException {
		Files.createDirectories(file.getParent());
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			if (json != null) {
				zip.putNextEntry(new ZipEntry("fabric.mod.json"));
				zip.write(json.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
			zip.putNextEntry(new ZipEntry("com/example/Mod.class"));
			zip.write(("class of " + file.getFileName()).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return file;
	}

	@Test
	void aNormalModIsRead(@TempDir Path dir) throws IOException {
		ModJar mod = ModJar.read(jar(dir.resolve("glowberry-1.2.jar"), """
				{"schemaVersion": 1, "id": "glowberry", "version": "1.2.0", "name": "Glow§cberry",
				 "description": "Berries that glow.", "environment": "client",
				 "depends": {"fabricloader": ">=0.15", "minecraft": "~1.21", "java": ">=21", "fabric-api": "*", "cozylib": ">=2"}}
				"""));
		assertTrue(mod.parsed());
		assertEquals("glowberry-1.2.jar", mod.file());
		assertEquals("glowberry", mod.id());
		assertEquals("Glowberry", mod.name(), "formatting codes are cleaned");
		assertEquals("1.2.0", mod.version());
		assertEquals("Berries that glow.", mod.description());
		assertEquals("client", mod.environment());
		assertEquals(List.of("fabric-api", "cozylib"), mod.depends(), "minecraft, java and fabricloader are left out");
		assertEquals(List.of(), mod.provides());
	}

	@Test
	void dependsWithVersionArraysAndProvides(@TempDir Path dir) throws IOException {
		ModJar mod = ModJar.read(jar(dir.resolve("cozylib.jar"), """
				{"id": "cozylib", "version": "2.0", "depends": {"minecraft": [">=1.21", "<1.22"], "tinyconfig": ["1.x", "2.x"]},
				 "provides": ["cozy_api", {"id": "cozy-core"}, "Bad Id", "cozylib"]}
				"""));
		assertEquals("cozylib", mod.name(), "no name: the id");
		assertEquals("*", mod.environment());
		assertEquals(List.of("tinyconfig"), mod.depends());
		assertEquals(List.of("cozy_api", "cozy-core"), mod.provides(), "invalid ids and its own id are dropped");
		assertEquals(List.of("cozylib", "cozy_api", "cozy-core"), List.copyOf(mod.ids()));
	}

	@Test
	void anythingElseIsAnUnknownJarAndNeverThrows(@TempDir Path dir) throws IOException {
		ModJar plain = ModJar.read(jar(dir.resolve("library.jar"), null));
		assertNull(plain.id());
		assertFalse(plain.parsed());
		assertEquals("library.jar", plain.name());

		Path corrupt = dir.resolve("corrupt.jar");
		Files.write(corrupt, "this is not a zip file at all".getBytes(StandardCharsets.UTF_8));
		assertNull(ModJar.read(corrupt).id());
		assertEquals("corrupt.jar", ModJar.read(corrupt).name());

		assertNull(ModJar.read(dir.resolve("missing.jar")).id());
		assertNull(ModJar.read(jar(dir.resolve("broken-json.jar"), "{\"id\": ")).id());
		assertNull(ModJar.read(jar(dir.resolve("array.jar"), "[1, 2]")).id());
		assertNull(ModJar.read(jar(dir.resolve("bad-id.jar"), "{\"id\": \"../../evil\"}")).id());

		String huge = "{\"id\": \"bigmod\", \"description\": \"" + "x".repeat(ModJar.MAX_JSON_BYTES) + "\"}";
		ModJar big = ModJar.read(jar(dir.resolve("big.jar"), huge));
		assertNull(big.id(), "a fabric.mod.json over 1 MB is unreadable");
		assertEquals("big.jar", big.name());
	}

	@Test
	void textIsCapped() {
		ModJar mod = ModJar.parse("long.jar", "{\"id\": \"longmod\", \"name\": \"" + "n".repeat(500) + "\", \"description\": \""
				+ "d".repeat(5000) + "\", \"environment\": \"everywhere\"}");
		assertEquals(100, mod.name().length());
		assertEquals(300, mod.description().length());
		assertEquals("*", mod.environment());
	}

	@Test
	void scanListsOnlyJarsSortedIgnoringCase(@TempDir Path dir) throws IOException {
		jar(dir.resolve("zebra.jar"), "{\"id\": \"zebra\"}");
		jar(dir.resolve("Apple.jar"), "{\"id\": \"apple\"}");
		jar(dir.resolve("banana.JAR"), "{\"id\": \"banana\"}");
		jar(dir.resolve("old.jar.disabled"), "{\"id\": \"old\"}");
		Files.createDirectories(dir.resolve("folder.jar"));
		Files.writeString(dir.resolve("notes.txt"), "hi");
		assertEquals(List.of("Apple.jar", "banana.JAR", "zebra.jar"), ModJar.scan(dir).stream().map(ModJar::file).toList());
		assertEquals(List.of(), ModJar.scan(dir.resolve("nowhere")));
	}
}

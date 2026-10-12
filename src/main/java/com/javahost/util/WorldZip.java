package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Zips a world folder for a backup, and unzips one safely for a restore. */
public final class WorldZip {
	private WorldZip() {
	}

	/** Zips every file under {@code worldDir} (paths relative to it), except the session lock. */
	public static long zip(Path worldDir, Path zipFile) throws IOException {
		Files.createDirectories(zipFile.getParent());
		Path temp = zipFile.resolveSibling(zipFile.getFileName() + ".part");
		List<Path> files;
		try (Stream<Path> walk = Files.walk(worldDir)) {
			files = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("session.lock")).sorted().toList();
		}
		try (OutputStream out = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Path file : files) {
				String name = worldDir.relativize(file).toString().replace('\\', '/');
				zip.putNextEntry(new ZipEntry(name));
				try {
					Files.copy(file, zip);
				} catch (IOException e) {
					// A file the game is holding open (rare with saving paused); skip it rather than fail the backup.
				}
				zip.closeEntry();
			}
		}
		Files.move(temp, zipFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return Files.size(zipFile);
	}

	/** Unzips into {@code target} (which must not exist yet). Entries that would land outside it are refused. */
	public static void unzip(Path zipFile, Path target) throws IOException {
		if (Files.exists(target)) {
			throw new IOException(target + " already exists");
		}
		Path root = target.toAbsolutePath().normalize();
		Files.createDirectories(root);
		try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(in)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				Path out = root.resolve(entry.getName()).normalize();
				if (!out.startsWith(root) || out.equals(root)) {
					throw new IOException("Unsafe path in backup: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(out);
				} else {
					Files.createDirectories(out.getParent());
					Files.copy(zip, out);
				}
			}
		}
	}
}

package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Zips a world folder for a backup or to send to a friend, and unzips one safely. */
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

	/** The total size of the files in a zip once unpacked (from its directory, so it's quick). */
	public static long unpackedSize(Path zipFile) throws IOException {
		long total = 0;
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				total += Math.max(0, entries.nextElement().getSize());
			}
		}
		return total;
	}

	/**
	 * Unzips a world someone sent into {@code target} (which must not exist yet). The zip must hold a world:
	 * {@code level.dat} at the top, or inside a single folder (which is then flattened). Refuses paths that
	 * would escape {@code target}, more than {@code maxEntries} files, and more than {@code maxBytes} unpacked,
	 * counting the bytes actually written so a zip can't lie about its sizes.
	 */
	public static void unzipWorld(Path zipFile, Path target, long maxBytes, int maxEntries) throws IOException {
		String prefix = worldPrefix(zipFile);
		if (Files.exists(target)) {
			throw new IOException(target + " already exists");
		}
		Path root = target.toAbsolutePath().normalize();
		Files.createDirectories(root);
		long written = 0;
		int count = 0;
		byte[] buffer = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(in)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.getName().startsWith(prefix) || entry.getName().length() == prefix.length()) {
					continue;
				}
				if (++count > maxEntries) {
					throw new IOException("Too many files in this world");
				}
				Path out = root.resolve(entry.getName().substring(prefix.length())).normalize();
				if (!out.startsWith(root) || out.equals(root)) {
					throw new IOException("Unsafe path in world: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(out);
					continue;
				}
				Files.createDirectories(out.getParent());
				try (OutputStream file = Files.newOutputStream(out)) {
					int read;
					while ((read = zip.read(buffer)) > 0) {
						written += read;
						if (written > maxBytes) {
							throw new IOException("This world is bigger than it said");
						}
						file.write(buffer, 0, read);
					}
				}
			}
		}
	}

	/** "" when level.dat is at the top of the zip, "Folder/" when it's inside one folder; otherwise not a world. */
	static String worldPrefix(Path zipFile) throws IOException {
		String found = null;
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				if (name.equals("level.dat")) {
					return "";
				}
				int slash = name.indexOf('/');
				if (slash > 0 && name.substring(slash + 1).equals("level.dat")) {
					found = found == null ? name.substring(0, slash + 1) : found;
				}
			}
		}
		if (found == null) {
			throw new IOException("This isn't a Minecraft world (no level.dat)");
		}
		return found;
	}
}

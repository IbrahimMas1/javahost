package com.javahost.client.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.client.Minecraft;

/** File helpers shared by the sending and receiving side of pack sharing. */
public final class PackFiles {
	private PackFiles() {
	}

	public static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("javahost");
	}

	/** Zipped copies of the host's packs, ready to stream. */
	public static Path outgoing() {
		return root().resolve("outgoing");
	}

	/** Packs received from hosts, named by SHA-1 so joining the same host again is instant. */
	public static Path cache() {
		return root().resolve("cache");
	}

	public static String sha1(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		byte[] buffer = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/** Returns {@code source} itself if it is already a zip, otherwise zips the folder into {@code target}. */
	public static Path asZip(Path source, Path target) throws IOException {
		if (Files.isRegularFile(source)) {
			return source;
		}
		Files.createDirectories(target.getParent());
		List<Path> files;
		try (Stream<Path> walk = Files.walk(source)) {
			files = walk.filter(Files::isRegularFile).sorted().toList();
		}
		try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Path file : files) {
				String name = source.relativize(file).toString().replace('\\', '/');
				ZipEntry entry = new ZipEntry(name);
				// Fixed timestamps keep the hash stable, so guests' caches stay valid between sessions.
				entry.setTime(0L);
				zip.putNextEntry(entry);
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
		return target;
	}

	/** Strips characters that are not safe in a file name. */
	public static String safeName(String name) {
		String cleaned = name.replaceAll("[^A-Za-z0-9 ._+()\\[\\]-]", "_").trim();
		if (cleaned.isEmpty() || cleaned.startsWith(".")) {
			cleaned = "pack" + cleaned;
		}
		return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
	}
}

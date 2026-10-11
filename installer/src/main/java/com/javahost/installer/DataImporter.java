package com.javahost.installer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Copies things from the player's normal Minecraft folder into JavaHost's own folder, so the separate
 * profile doesn't start empty. Always copies (never moves), and never overwrites what's already there.
 */
public final class DataImporter {
	private static final List<String> SETTINGS_FILES = List.of("options.txt", "servers.dat");

	private DataImporter() {
	}

	public static void importSettings(Path from, Path to) throws IOException {
		Files.createDirectories(to);
		for (String name : SETTINGS_FILES) {
			Path source = from.resolve(name);
			Path target = to.resolve(name);
			if (Files.isRegularFile(source) && !Files.exists(target)) {
				Files.copy(source, target);
			}
		}
	}

	public static void importPacks(Path from, Path to) throws IOException {
		copyTree(from.resolve("resourcepacks"), to.resolve("resourcepacks"));
		copyTree(from.resolve("shaderpacks"), to.resolve("shaderpacks"));
	}

	public static void importWorlds(Path from, Path to) throws IOException {
		copyTree(from.resolve("saves"), to.resolve("saves"));
	}

	/** Total size of the player's worlds, to show next to the checkbox. */
	public static long worldsSize(Path minecraftDir) {
		Path saves = minecraftDir.resolve("saves");
		if (!Files.isDirectory(saves)) {
			return 0;
		}
		AtomicLong total = new AtomicLong();
		try (Stream<Path> walk = Files.walk(saves)) {
			walk.filter(Files::isRegularFile).forEach(file -> {
				try {
					total.addAndGet(Files.size(file));
				} catch (IOException ignored) {
					// Skip files we can't read.
				}
			});
		} catch (IOException ignored) {
			// Best effort.
		}
		return total.get();
	}

	public static int worldCount(Path minecraftDir) {
		Path saves = minecraftDir.resolve("saves");
		if (!Files.isDirectory(saves)) {
			return 0;
		}
		try (Stream<Path> list = Files.list(saves)) {
			return (int) list.filter(p -> Files.isRegularFile(p.resolve("level.dat"))).count();
		} catch (IOException e) {
			return 0;
		}
	}

	private static void copyTree(Path source, Path target) throws IOException {
		if (!Files.isDirectory(source)) {
			return;
		}
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(target.resolve(source.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path dest = target.resolve(source.relativize(file).toString());
				if (!Files.exists(dest) && !attrs.isSymbolicLink()) {
					Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}
}

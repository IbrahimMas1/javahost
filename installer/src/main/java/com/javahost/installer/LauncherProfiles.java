package com.javahost.installer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Adds and removes JavaHost's profiles (one per Minecraft version, see {@link Edition}) in the Minecraft Launcher's
 * profile files. The launcher's own files are backed up once before the first change, and only our profile entries
 * are ever touched.
 */
public final class LauncherProfiles {
	private static final String[] FILES = {"launcher_profiles.json", "launcher_profiles_microsoft_store.json"};
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private LauncherProfiles() {
	}

	/** Profile files that exist in this Minecraft folder. */
	public static List<Path> files(Path minecraftDir) {
		List<Path> found = new ArrayList<>();
		for (String name : FILES) {
			Path file = minecraftDir.resolve(name);
			if (Files.isRegularFile(file)) {
				found.add(file);
			}
		}
		return found;
	}

	public static void addProfile(Path minecraftDir, Edition edition, String versionId, String iconDataUri) throws IOException {
		String key = edition.profileKey();
		for (Path file : files(minecraftDir)) {
			backupOnce(file);
			JsonObject root = read(file);
			JsonObject profiles = root.has("profiles") && root.get("profiles").isJsonObject() ? root.getAsJsonObject("profiles") : new JsonObject();
			root.add("profiles", profiles);

			JsonObject profile = profiles.has(key) ? profiles.getAsJsonObject(key) : new JsonObject();
			String now = Instant.now().toString();
			if (!profile.has("created")) {
				profile.addProperty("created", now);
			}
			profile.addProperty("lastUsed", now); // newest profile is preselected in the launcher
			profile.addProperty("name", edition.profileName());
			profile.addProperty("type", "custom");
			profile.addProperty("lastVersionId", versionId);
			profile.addProperty("gameDir", edition.gameDir(minecraftDir).toAbsolutePath().toString());
			profile.addProperty("icon", iconDataUri);
			profile.addProperty("javaArgs", javaArgs());
			profiles.add(key, profile);
			write(file, root);
		}
	}

	/** Removes our profile; returns the version id it used, or {@code null}. */
	public static String removeProfile(Path minecraftDir, Edition edition) throws IOException {
		String key = edition.profileKey();
		String version = null;
		for (Path file : files(minecraftDir)) {
			JsonObject root = read(file);
			if (root.has("profiles") && root.getAsJsonObject("profiles").has(key)) {
				backupOnce(file);
				JsonObject profile = root.getAsJsonObject("profiles").getAsJsonObject(key);
				if (profile.has("lastVersionId")) {
					version = profile.get("lastVersionId").getAsString();
				}
				root.getAsJsonObject("profiles").remove(key);
				write(file, root);
			}
		}
		return version;
	}

	/** Whether any profile other than this one still uses this version (so we must not delete it). */
	public static boolean versionUsedByOthers(Path minecraftDir, Edition edition, String versionId) throws IOException {
		for (Path file : files(minecraftDir)) {
			JsonObject root = read(file);
			if (!root.has("profiles")) {
				continue;
			}
			for (var entry : root.getAsJsonObject("profiles").entrySet()) {
				if (!entry.getKey().equals(edition.profileKey()) && entry.getValue().isJsonObject()) {
					JsonObject p = entry.getValue().getAsJsonObject();
					if (p.has("lastVersionId") && versionId.equals(p.get("lastVersionId").getAsString())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static boolean hasProfile(Path minecraftDir, Edition edition) {
		try {
			for (Path file : files(minecraftDir)) {
				JsonObject root = read(file);
				if (root.has("profiles") && root.getAsJsonObject("profiles").has(edition.profileKey())) {
					return true;
				}
			}
		} catch (IOException ignored) {
			// Treat unreadable as not installed.
		}
		return false;
	}

	/** Memory for Minecraft based on how much the computer has; mods and hosting need more than vanilla's 2 GB. */
	static String javaArgs() {
		long totalGb = 8;
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			totalGb = os.getTotalMemorySize() / (1024L * 1024 * 1024);
		}
		int heap = totalGb >= 16 ? 4 : totalGb >= 8 ? 3 : 2;
		return "-Xmx" + heap + "G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200";
	}

	private static void backupOnce(Path file) throws IOException {
		Path backup = file.resolveSibling(file.getFileName() + ".javahost-backup");
		if (!Files.exists(backup)) {
			Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}

	private static JsonObject read(Path file) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		var parsed = JsonParser.parseString(text.isBlank() ? "{}" : text);
		if (!parsed.isJsonObject()) {
			throw new IOException(file.getFileName() + " isn't a launcher profile file");
		}
		return parsed.getAsJsonObject();
	}

	private static void write(Path file, JsonObject root) throws IOException {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}

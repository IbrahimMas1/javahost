package com.javahost.installer;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.javahost.util.Modrinth;

/**
 * Installs the Fabric loader into the launcher's versions folder, the same way the official Fabric
 * installer does: a version JSON (fetched from Fabric's servers) that inherits from vanilla, plus an
 * empty jar the launcher expects next to it. The launcher downloads everything else itself.
 */
public final class FabricInstaller {
	private static final String META = "https://meta.fabricmc.net/v2/versions/loader/";

	private FabricInstaller() {
	}

	/** Latest stable Fabric loader version for this Minecraft version. */
	public static String latestLoader(String minecraftVersion) throws IOException, InterruptedException {
		for (JsonElement element : Modrinth.getJson(META + enc(minecraftVersion)).getAsJsonArray()) {
			JsonObject loader = element.getAsJsonObject().getAsJsonObject("loader");
			if (loader.get("stable").getAsBoolean()) {
				return loader.get("version").getAsString();
			}
		}
		throw new IOException("Fabric doesn't support Minecraft " + minecraftVersion + " yet");
	}

	/** Writes the Fabric version into {@code versions/} and returns its id (e.g. fabric-loader-0.19.5-26.3). */
	public static String install(Path minecraftDir, String minecraftVersion, String loaderVersion) throws IOException, InterruptedException {
		JsonObject profile = Modrinth.getJson(META + enc(minecraftVersion) + "/" + enc(loaderVersion) + "/profile/json").getAsJsonObject();
		String id = profile.get("id").getAsString();
		if (!id.matches("[A-Za-z0-9._+-]+")) {
			throw new IOException("Unexpected Fabric version id " + id);
		}
		Path dir = minecraftDir.resolve("versions").resolve(id);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(id + ".json"), profile.toString(), StandardCharsets.UTF_8);
		Path jar = dir.resolve(id + ".jar");
		if (!Files.exists(jar)) {
			Files.write(jar, new byte[0]);
		}
		return id;
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}

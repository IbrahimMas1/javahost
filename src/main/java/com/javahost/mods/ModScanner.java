package com.javahost.mods;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javahost.JavaHost;
import com.javahost.util.IoExecutor;
import com.javahost.util.ModManifest;
import com.javahost.util.Modrinth;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Works out which of this game's mods a joining player needs: mods that add things to the game's registries
 * (blocks, items, mobs, sounds...), plus the libraries those mods depend on. Client-only mods (Sodium, minimaps) and
 * mods that only change world generation or game logic on the host (Lithium) aren't needed. Each jar is matched to its
 * exact Modrinth version by hash, so friends can download the very same file.
 */
public final class ModScanner {
	/** Libraries where any version will do (they don't add game content of their own). */
	private static final Set<String> LIBRARIES = Set.of("fabric-api", "fabric-language-kotlin");
	private static final long RECHECK_UNKNOWN_MS = 24 * 60 * 60 * 1000L;

	private static CompletableFuture<ModManifest> scan;

	private ModScanner() {
	}

	/** This game's mod list (worked out once, in the background; the Modrinth lookup is cached on disk). */
	public static synchronized CompletableFuture<ModManifest> manifest() {
		if (scan == null || scan.isCompletedExceptionally()) {
			scan = CompletableFuture.supplyAsync(ModScanner::scan, IoExecutor.NET);
		}
		return scan;
	}

	/** The mod list if it's ready, or null. */
	public static ModManifest ready() {
		CompletableFuture<ModManifest> current = scan;
		return current != null && current.isDone() && !current.isCompletedExceptionally() ? current.join() : null;
	}

	private static ModManifest scan() {
		FabricLoader loader = FabricLoader.getInstance();
		// Mods that put things in registries: the namespaces in use, other than Minecraft's.
		// Only registries Fabric checks against a joining player's (synced, and not optional): a mod that adds just a game
		// rule or a world generation feature doesn't need to be on their computer.
		Map<String, Set<String>> namespaces = new java.util.TreeMap<>(); // namespace -> synced registries it adds to
		BuiltInRegistries.REGISTRY.forEach(registry -> {
			var attributes = net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder.get(registry);
			//? if >=1.21.4 {
			boolean checked = attributes.hasAttribute(net.fabricmc.fabric.api.event.registry.RegistryAttribute.SYNCED)
					&& !attributes.hasAttribute(net.fabricmc.fabric.api.event.registry.RegistryAttribute.OPTIONAL);
			//?} else
			/*boolean checked = attributes.hasAttribute(net.fabricmc.fabric.api.event.registry.RegistryAttribute.SYNCED);*/
			if (checked) {
				//? if >=1.21.11 {
				String registryName = registry.key().identifier().getPath();
				//?} else
				/*String registryName = registry.key().location().getPath();*/
				registry.keySet().forEach(id -> namespaces.computeIfAbsent(id.getNamespace(), n -> new TreeSet<>()).add(registryName));
			}
		});
		namespaces.remove("minecraft");

		Map<String, ModContainer> needed = new LinkedHashMap<>(); // top-level mod id -> its jar's mod
		Set<String> content = new TreeSet<>();
		Deque<ModContainer> queue = new ArrayDeque<>();
		JavaHost.LOGGER.info("[Mods] mods in synced registries: {}", namespaces);
		for (String namespace : namespaces.keySet()) {
			Optional<ModContainer> mod = loader.getModContainer(namespace);
			if (mod.isEmpty()) {
				continue;
			}
			ModContainer top = topLevel(mod.get());
			String id = top.getMetadata().getId();
			if (!ModManifest.ALWAYS_PRESENT.contains(id) && jarOf(top) != null && needed.putIfAbsent(id, top) == null) {
				queue.add(top);
			}
			if (!LIBRARIES.contains(id)) {
				content.add(id);
			}
		}
		// What those mods need to load: their required dependencies (and theirs).
		while (!queue.isEmpty()) {
			ModContainer mod = queue.poll();
			for (ModDependency dependency : allDependencies(mod)) {
				if (dependency.getKind() != ModDependency.Kind.DEPENDS) {
					continue;
				}
				Optional<ModContainer> found = loader.getModContainer(dependency.getModId());
				if (found.isEmpty()) {
					continue;
				}
				ModContainer top = topLevel(found.get());
				String id = top.getMetadata().getId();
				if (ModManifest.ALWAYS_PRESENT.contains(id) || jarOf(top) == null
						|| top.getMetadata().getEnvironment() == ModEnvironment.SERVER) {
					continue;
				}
				if (needed.putIfAbsent(id, top) == null) {
					queue.add(top);
				}
			}
		}
		if (loader.getEnvironmentType() == EnvType.CLIENT) {
			needed.values().removeIf(mod -> mod.getMetadata().getEnvironment() == ModEnvironment.CLIENT && !content.contains(mod.getMetadata().getId()));
		}

		List<ModManifest.Entry> entries = new ArrayList<>();
		Map<String, Path> jars = new HashMap<>();
		Map<String, String> hashes = new HashMap<>();
		for (ModContainer mod : needed.values()) {
			Path jar = jarOf(mod);
			try {
				hashes.put(mod.getMetadata().getId(), Modrinth.sha512(jar));
				jars.put(mod.getMetadata().getId(), jar);
			} catch (IOException e) {
				JavaHost.LOGGER.warn("[Mods] couldn't read {}", jar, e);
			}
		}
		Map<String, String> modrinth = lookUp(new ArrayList<>(hashes.values()));
		for (ModContainer mod : needed.values()) {
			String id = mod.getMetadata().getId();
			if (!hashes.containsKey(id)) {
				continue;
			}
			Path jar = jars.get(id);
			long size;
			try {
				size = Files.size(jar);
			} catch (IOException e) {
				size = 0;
			}
			String page = mod.getMetadata().getContact().get("homepage").or(() -> mod.getMetadata().getContact().get("sources")).orElse("");
			entries.add(new ModManifest.Entry(id, mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(),
					content.contains(id), jar.getFileName().toString(), size, hashes.get(id), modrinth.getOrDefault(hashes.get(id), ""),
					page.startsWith("https://") ? page : ""));
		}
		ModManifest manifest = new ModManifest("", ModSetsState.packName(), List.copyOf(entries));
		JavaHost.LOGGER.info("[Mods] players joining need {} mod(s): {}; {} not on Modrinth", entries.size(),
				entries.stream().map(e -> e.id() + (e.content() ? "" : " (library)")).toList(), entries.stream().filter(e -> !e.onModrinth()).count());
		return manifest;
	}

	private static Iterable<ModDependency> allDependencies(ModContainer mod) {
		List<ModDependency> all = new ArrayList<>(mod.getMetadata().getDependencies());
		// A jar's bundled mods can depend on other jars too.
		for (ModContainer inner : mod.getContainedMods()) {
			all.addAll(inner.getMetadata().getDependencies());
		}
		return all;
	}

	private static ModContainer topLevel(ModContainer mod) {
		ModContainer current = mod;
		while (current.getContainingMod().isPresent()) {
			current = current.getContainingMod().get();
		}
		return current;
	}

	/** The jar file a top-level mod was loaded from, or null (built-in, or a development folder). */
	public static Path jarOf(ModContainer mod) {
		ModOrigin origin = mod.getOrigin();
		if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) {
			return null;
		}
		Path path = origin.getPaths().getFirst();
		return path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path) ? path : null;
	}

	/** Hash to Modrinth version id, from the cache file or one Modrinth request for the rest. */
	private static Map<String, String> lookUp(List<String> hashes) {
		Path cacheFile = FabricLoader.getInstance().getGameDir().resolve("javahost").resolve("mod-index.json");
		Map<String, String> result = new HashMap<>();
		JsonObject cache = new JsonObject();
		try {
			if (Files.exists(cacheFile)) {
				cache = JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			cache = new JsonObject();
		}
		long now = System.currentTimeMillis();
		List<String> ask = new ArrayList<>();
		for (String hash : hashes) {
			JsonObject known = cache.has(hash) ? cache.getAsJsonObject(hash) : null;
			if (known != null && (!known.get("version").getAsString().isEmpty() || now - known.get("checked").getAsLong() < RECHECK_UNKNOWN_MS)) {
				result.put(hash, known.get("version").getAsString());
			} else {
				ask.add(hash);
			}
		}
		if (!ask.isEmpty()) {
			try {
				Map<String, Modrinth.ModFile> found = Modrinth.versionsByHash(ask);
				for (String hash : ask) {
					String version = found.containsKey(hash) ? found.get(hash).versionId() : "";
					result.put(hash, version);
					JsonObject entry = new JsonObject();
					entry.addProperty("version", version);
					entry.addProperty("checked", now);
					cache.add(hash, entry);
				}
				Files.createDirectories(cacheFile.getParent());
				Files.writeString(cacheFile, new Gson().toJson(cache), StandardCharsets.UTF_8);
			} catch (IOException | InterruptedException | RuntimeException e) {
				JavaHost.LOGGER.warn("[Mods] couldn't look the mods up on Modrinth: {}", e.toString());
			}
		}
		return result;
	}
}

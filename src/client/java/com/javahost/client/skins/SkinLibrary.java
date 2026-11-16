package com.javahost.client.skins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.javahost.JavaHost;
import com.javahost.util.IoExecutor;
import com.javahost.util.SkinFolder;
import com.javahost.util.SkinImage;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The player's skin collection in {@code <game dir>/javahost/skins/} (see {@link SkinFolder}), for the menu: disk
 * work on JavaHost's IO threads, a {@link #version()} that changes whenever the collection does, and textures for
 * the skins (the whole skin for 3D models, a flat front view for tiles), registered on the render thread, capped,
 * and released when a skin is deleted.
 * <p>
 * Ready for a skin editor: {@link #add(int[], String, String, String)} saves a new picture and
 * {@link #update(String, int[])} replaces one, both into the same collection.
 */
public final class SkinLibrary {
	/** Front views kept on the graphics card (a page shows a dozen at most). */
	private static final int MAX_PREVIEWS = 64;
	/** Whole skins for the 3D models (the selected one, the worn one, a few recent ones). */
	private static final int MAX_FULL = 8;

	private static SkinFolder folder;
	private static volatile List<SkinFolder.Entry> entries = List.of();
	private static volatile String worn;
	private static volatile int version;
	private static volatile boolean loaded;
	private static volatile int refused;
	private static CompletableFuture<Void> loading;

	private static final Map<String, Identifier> PREVIEWS = lru(MAX_PREVIEWS);
	private static final Map<String, Identifier> FULL = lru(MAX_FULL);
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

	private SkinLibrary() {
	}

	private static Map<String, Identifier> lru(int max) {
		return new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
				if (size() > max) {
					Minecraft.getInstance().getTextureManager().release(eldest.getValue());
					return true;
				}
				return false;
			}
		};
	}

	public static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("javahost").resolve("skins");
	}

	private static synchronized SkinFolder folder() {
		if (folder == null) {
			folder = new SkinFolder(dir());
		}
		return folder;
	}

	/** Reads the folder again (and takes in PNGs dropped into it). Called when the Skins page opens. */
	public static synchronized CompletableFuture<Void> reload() {
		if (loading != null && !loading.isDone()) {
			return loading;
		}
		loading = IoExecutor.run(() -> {
			try {
				folder().load();
				refused = folder().refused();
			} catch (Exception e) {
				JavaHost.LOGGER.warn("[Skins] couldn't read the skins folder: {}", e.toString());
			}
			loaded = true;
			changed();
		});
		return loading;
	}

	private static void changed() {
		entries = folder().entries();
		worn = folder().worn();
		version++;
	}

	/** Changes whenever the collection does (pages compare it to know when to redraw). */
	public static int version() {
		return version;
	}

	public static boolean loaded() {
		return loaded;
	}

	/** Newest first. */
	public static List<SkinFolder.Entry> entries() {
		return entries;
	}

	public static SkinFolder.Entry get(String id) {
		return id == null ? null : entries.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
	}

	/** The skin the account wears now, as far as we know (null: unknown or Minecraft's default). */
	public static String worn() {
		return worn;
	}

	/** PNGs in the folder that weren't skins on the last look. */
	public static int refused() {
		return refused;
	}

	// ---------------------------------------------------------------- changes (all off the render thread)

	@FunctionalInterface
	private interface Work<T> {
		T run() throws Exception;
	}

	private static <T> CompletableFuture<T> work(Work<T> work) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (!loaded) {
					folder().load();
					loaded = true;
				}
				return work.run();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			} finally {
				changed();
			}
		}, IoExecutor.POOL);
	}

	/** Adds a skin from a PNG (checked; an old 64x32 one is converted). */
	public static CompletableFuture<SkinFolder.Added> add(byte[] png, String name, String model, String source) {
		return work(() -> folder().add(png, name, model, source));
	}

	/** Adds a skin from 64x64 ARGB pixels (for the skin editor). */
	public static CompletableFuture<SkinFolder.Added> add(int[] pixels, String name, String model, String source) {
		return work(() -> folder().add(pixels, name, model, source));
	}

	/** Replaces a skin's picture (for the skin editor); its textures are made again. */
	public static CompletableFuture<Boolean> update(String id, int[] pixels) {
		return work(() -> folder().update(id, pixels));
	}

	/** Adds a PNG file the player picked or dropped (read size-capped first). */
	public static CompletableFuture<SkinFolder.Added> addFile(Path file) {
		return work(() -> {
			if (!Files.isRegularFile(file) || Files.size(file) > SkinImage.MAX_BYTES) {
				throw new SkinImage.Invalid(Files.isRegularFile(file) ? "That file is too big to be a skin." : "That isn't a file.");
			}
			String name = file.getFileName().toString();
			int dot = name.lastIndexOf('.');
			name = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').replace('-', ' ');
			return folder().add(Files.readAllBytes(file), name, SkinFolder.CLASSIC, "file");
		});
	}

	public static CompletableFuture<Boolean> rename(String id, String name) {
		return work(() -> folder().rename(id, name));
	}

	public static CompletableFuture<Boolean> setModel(String id, String model) {
		return work(() -> folder().setModel(id, model));
	}

	public static CompletableFuture<Boolean> delete(String id) {
		return work(() -> folder().delete(id)).thenApplyAsync(deleted -> {
			release(id);
			return deleted;
		}, Minecraft.getInstance());
	}

	static CompletableFuture<Void> markWorn(String id, String url) {
		return work(() -> {
			folder().markWorn(id, url);
			return null;
		});
	}

	/** The address Minecraft gave for the skin we last saw on the account. */
	static String wornUrl() {
		return folder().wornUrl();
	}

	/** A saved skin's file, as it would be uploaded. Disk work. */
	static byte[] read(String id) throws java.io.IOException {
		return folder().read(id);
	}

	// ---------------------------------------------------------------- textures (render thread)

	private static String key(SkinFolder.Entry entry) {
		return entry.id() + "_" + entry.model() + "_" + entry.fingerprint();
	}

	/** The flat front view of a skin (16x32) for tiles, or null while it's being made. */
	public static Identifier preview(SkinFolder.Entry entry) {
		return texture(entry, true);
	}

	/** The whole skin texture for a 3D model, or null while it's being loaded. */
	public static Identifier full(SkinFolder.Entry entry) {
		return texture(entry, false);
	}

	private static Identifier texture(SkinFolder.Entry entry, boolean preview) {
		if (entry == null) {
			return null;
		}
		String key = key(entry) + (preview ? "_front" : "");
		Map<String, Identifier> cache = preview ? PREVIEWS : FULL;
		Identifier id = cache.get(key);
		if (id != null || !PENDING.add(key)) {
			return id;
		}
		boolean slim = entry.slim();
		IoExecutor.supply(() -> {
			try {
				byte[] file = folder().read(entry.id());
				return preview ? SkinImage.png(SkinImage.front(SkinImage.read(file), slim), SkinImage.FRONT_W, SkinImage.FRONT_H) : file;
			} catch (Exception e) {
				return null;
			}
		}).thenAcceptAsync(png -> {
			try {
				if (png != null && get(entry.id()) != null) {
					Identifier texture = Identifier.fromNamespaceAndPath("javahost", "skins/" + key);
					register(texture, NativeImage.read(png));
					cache.put(key, texture);
				}
			} catch (Exception e) {
				JavaHost.LOGGER.debug("[Skins] couldn't load a skin texture: {}", e.toString());
			} finally {
				PENDING.remove(key);
			}
		}, Minecraft.getInstance());
		return null;
	}

	/** A loaded skin's full texture, once it's on the graphics card. */
	public static CompletableFuture<Identifier> fullWhenReady(SkinFolder.Entry entry) {
		CompletableFuture<Identifier> result = new CompletableFuture<>();
		Minecraft minecraft = Minecraft.getInstance();
		Runnable[] poll = new Runnable[1];
		long until = System.currentTimeMillis() + 10_000;
		poll[0] = () -> {
			Identifier id = full(entry);
			if (id != null) {
				result.complete(id);
			} else if (System.currentTimeMillis() > until) {
				result.completeExceptionally(new IllegalStateException("skin texture didn't load"));
			} else {
				CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> minecraft.execute(poll[0]));
			}
		};
		minecraft.execute(poll[0]);
		return result;
	}

	/** Puts a PNG (already checked) on the graphics card as {@code id}. Render thread. */
	public static void registerPng(Identifier id, byte[] png) throws java.io.IOException {
		register(id, NativeImage.read(png));
	}

	static void register(Identifier id, NativeImage image) {
		//? if >=1.21.5 {
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "JavaHost skin", image));
		//?} else
		/*Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));*/
	}

	/** Frees a deleted skin's textures. */
	private static void release(String id) {
		for (Map<String, Identifier> cache : List.of(PREVIEWS, FULL)) {
			cache.entrySet().removeIf(e -> {
				if (e.getKey().startsWith(id + "_")) {
					Minecraft.getInstance().getTextureManager().release(e.getValue());
					return true;
				}
				return false;
			});
		}
	}

	/** For tests: how many skin textures are registered now. */
	public static int texturesLoaded() {
		return PREVIEWS.size() + FULL.size();
	}
}

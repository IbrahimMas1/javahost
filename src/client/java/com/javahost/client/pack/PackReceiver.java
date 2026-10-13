package com.javahost.client.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.net.Payloads.PackChunk;
import com.javahost.net.Payloads.PackInfo;
import com.javahost.net.Payloads.PackKind;
import com.javahost.net.Payloads.PackOffer;
import com.javahost.net.Payloads.PackAck;
import com.javahost.net.Payloads.PackRequest;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import com.javahost.client.gui.PackOfferScreen;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.network.chat.Component;

/**
 * Guest side of pack sharing. When the host offers packs, asks the player, downloads whatever
 * isn't cached yet, then applies the resource packs as server packs (removed automatically on
 * disconnect) and switches Iris to the host's shader, restoring the player's own shader afterwards.
 */
public final class PackReceiver {

	/** Largest pack we'll take from a host. */
	private static final long MAX_PACK_BYTES = 512L * 1024 * 1024;
	/** Received packs kept for quick rejoins; the least recently used go first. */
	private static final long CACHE_BUDGET = 2L * 1024 * 1024 * 1024;

	private static final class Download {
		final PackInfo info;
		FileChannel channel;
		long received;
		int nextIndex;
		java.security.MessageDigest sha1;

		Download(PackInfo info) {
			this.info = info;
		}
	}

	private static PackOffer pendingPrompt;
	/** An offer that arrived before we'd confirmed whose world this is; handled once confirmed. */
	private static PackOffer heldOffer;
	private static PackOffer activeOffer;
	private static final Map<String, Download> DOWNLOADS = new LinkedHashMap<>();
	private static boolean changedShader;
	private static String previousShader;

	private PackReceiver() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PackOffer.TYPE, (offer, context) -> onOffer(offer));
		ClientPlayNetworking.registerGlobalReceiver(PackChunk.TYPE, (chunk, context) -> onChunk(chunk));
		ClientTickEvents.END_CLIENT_TICK.register(PackReceiver::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> onDisconnect());
	}

	/** Download progress from 0 to 1, or -1 when nothing is downloading. */
	public static float progress() {
		if (DOWNLOADS.isEmpty()) {
			return -1;
		}
		long total = 0;
		long received = 0;
		for (Download download : DOWNLOADS.values()) {
			total += download.info.size();
			received += download.received;
		}
		return total == 0 ? 1 : (float) received / total;
	}

	private static void onOffer(PackOffer received) {
		if (com.javahost.client.friends.InviteFlow.verifying()) {
			heldOffer = received; // nothing from a world we haven't confirmed yet
			return;
		}
		// Without Iris there is nothing to do with a shader pack, so don't offer one.
		// Packs are named by their SHA-1 on disk, so anything else (like "../") is refused, as are silly sizes.
		List<PackInfo> usable = received.packs().stream()
				.filter(info -> info.sha1().matches("[0-9a-f]{40}") && info.size() > 0 && info.size() <= MAX_PACK_BYTES)
				.filter(info -> info.kind() == PackKind.RESOURCE_PACK || IrisCompat.isInstalled())
				.toList();
		if (usable.isEmpty() || (activeOffer != null && activeOffer.packs().equals(usable))) {
			return;
		}
		PackOffer offer = new PackOffer(usable, received.shaderSettings());
		if (Config.get().autoAcceptHostPacks) {
			accept(offer);
		} else {
			pendingPrompt = offer;
		}
	}

	/** Waits until the player is actually in the world with no menu open before asking. */
	private static void tick(Minecraft minecraft) {
		if (heldOffer != null && minecraft.level != null && !com.javahost.client.friends.InviteFlow.verifying()) {
			PackOffer offer = heldOffer;
			heldOffer = null;
			onOffer(offer);
		}
		if (pendingPrompt == null || minecraft.player == null || minecraft.gui.screen() != null) {
			return;
		}
		PackOffer offer = pendingPrompt;
		pendingPrompt = null;

		long bytes = 0;
		for (PackInfo info : offer.packs()) {
			if (!Files.exists(cached(info))) {
				bytes += info.size();
			}
		}
		minecraft.gui.setScreen(new PackOfferScreen(offer.packs(), bytes, yes -> {
			if (yes) {
				accept(offer);
			}
		}));
	}

	private static void accept(PackOffer offer) {
		activeOffer = offer;
		closeDownloads();
		List<String> missing = new ArrayList<>();
		for (PackInfo info : offer.packs()) {
			if (!Files.exists(cached(info))) {
				missing.add(info.sha1());
				DOWNLOADS.put(info.sha1(), new Download(info));
			}
		}
		if (missing.isEmpty()) {
			apply(offer);
		} else {
			ClientPlayNetworking.send(new PackRequest(missing));
		}
	}

	private static void onChunk(PackChunk chunk) {
		Download download = DOWNLOADS.get(chunk.sha1());
		if (download == null) {
			return;
		}
		try {
			Path part = part(download.info);
			if (chunk.index() == 0) {
				Files.createDirectories(part.getParent());
				download.channel = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING);
				download.received = 0;
				download.nextIndex = 0;
				download.sha1 = java.security.MessageDigest.getInstance("SHA-1");
			}
			if (download.channel == null) {
				return;
			}
			// In order, and never more than the offer said: a host can't stream us an endless file.
			if (chunk.index() != download.nextIndex || download.received + chunk.data().length > download.info.size()) {
				throw new IOException("Unexpected data for " + download.info.name());
			}
			ByteBuffer data = ByteBuffer.wrap(chunk.data());
			while (data.hasRemaining()) {
				download.channel.write(data);
			}
			download.sha1.update(chunk.data());
			download.received += chunk.data().length;
			download.nextIndex++;
			ClientPlayNetworking.send(new PackAck(chunk.sha1(), chunk.index()));

			if (download.received == download.info.size()) {
				download.channel.close();
				download.channel = null;
				String actual = HexFormat.of().formatHex(download.sha1.digest());
				if (!actual.equals(chunk.sha1())) {
					Files.deleteIfExists(part);
					throw new IOException("Checksum mismatch for " + download.info.name());
				}
				Files.move(part, cached(download.info), StandardCopyOption.REPLACE_EXISTING);
				DOWNLOADS.remove(chunk.sha1());
				if (DOWNLOADS.isEmpty() && activeOffer != null) {
					apply(activeOffer);
				}
			}
		} catch (IOException | java.security.NoSuchAlgorithmException e) {
			JavaHost.LOGGER.warn("Failed to receive pack {}", download.info.name(), e);
			DOWNLOADS.remove(chunk.sha1());
			discard(download);
			toast("Couldn't download " + download.info.name());
		}
	}

	private static void apply(PackOffer offer) {
		Minecraft minecraft = Minecraft.getInstance();
		DownloadedPackSource packSource = minecraft.getDownloadedPackSource();
		boolean anyResourcePack = false;
		for (PackInfo info : offer.packs()) {
			if (info.kind() == PackKind.RESOURCE_PACK && Files.exists(cached(info))) {
				if (!anyResourcePack) {
					// The player already agreed in our prompt, so let the server-pack system load them.
					packSource.allowServerPacks();
					anyResourcePack = true;
				}
				packSource.pushLocalPack(packId(info), cached(info));
			}
		}

		java.util.Set<Path> inUse = new java.util.HashSet<>();
		for (PackInfo info : offer.packs()) {
			inUse.add(cached(info));
			try {
				Files.setLastModifiedTime(cached(info), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
			} catch (IOException ignored) {
				// Only used to decide what to tidy away first.
			}
			if (info.kind() == PackKind.SHADER_PACK && Files.exists(cached(info))) {
				applyShader(info, offer.shaderSettings());
			}
		}
		trimCache(inUse);
		toast("Now using the host's packs");
	}

	private static void applyShader(PackInfo info, String settings) {
		Path shaderDir = IrisCompat.shaderpacksDirectory();
		if (shaderDir == null) {
			return;
		}
		String safe = PackFiles.safeName(info.name());
		String fileName = "JavaHost - " + (safe.toLowerCase().endsWith(".zip") ? safe : safe + ".zip");
		PackOffer offer = activeOffer;
		// Copying (and checking) a big shader takes a moment: do it in the background, then switch on this thread.
		com.javahost.util.IoExecutor.supply(() -> {
			try {
				Path target = shaderDir.resolve(fileName);
				Files.createDirectories(shaderDir);
				if (!Files.exists(target) || Files.size(target) != info.size() || !PackFiles.sha1(target).equals(info.sha1())) {
					Files.copy(cached(info), target, StandardCopyOption.REPLACE_EXISTING);
				}
				if (!settings.isEmpty()) {
					Files.writeString(shaderDir.resolve(fileName + ".txt"), settings, StandardCharsets.UTF_8);
				}
				return true;
			} catch (IOException e) {
				JavaHost.LOGGER.warn("Could not install shared shader {}", info.name(), e);
				return false;
			}
		}).thenAccept(ok -> Minecraft.getInstance().execute(() -> {
			if (!ok) {
				toast("Couldn't load the host's shader");
				return;
			}
			if (activeOffer != offer) {
				return; // left the world (or got a new offer) meanwhile
			}
			if (!changedShader) {
				Optional<String> current = IrisCompat.activeShaderPack();
				previousShader = current.orElse(null);
			}
			if (IrisCompat.apply(fileName)) {
				changedShader = true;
			}
		}));
	}

	private static void onDisconnect() {
		pendingPrompt = null;
		heldOffer = null;
		activeOffer = null;
		closeDownloads();
		if (changedShader) {
			changedShader = false;
			String restore = previousShader;
			previousShader = null;
			Minecraft.getInstance().execute(() -> IrisCompat.apply(restore));
		}
	}

	private static void closeDownloads() {
		DOWNLOADS.values().forEach(PackReceiver::discard);
		DOWNLOADS.clear();
	}

	/** Closes and deletes an unfinished download. */
	private static void discard(Download download) {
		if (download.channel != null) {
			try {
				download.channel.close();
			} catch (IOException ignored) {
				// Deleted below anyway.
			}
			download.channel = null;
		}
		try {
			Files.deleteIfExists(part(download.info));
		} catch (IOException ignored) {
			// A leftover .part is harmless; it's overwritten next time.
		}
	}

	private static Path part(PackInfo info) {
		return PackFiles.cache().resolve(info.sha1() + ".part");
	}

	/** Keeps the cache of received packs under its budget, dropping the least recently used first. */
	private static void trimCache(java.util.Set<Path> inUse) {
		com.javahost.util.IoExecutor.run(() -> {
			try (java.util.stream.Stream<Path> list = Files.list(PackFiles.cache())) {
				List<Path> zips = new ArrayList<>(list.filter(p -> p.getFileName().toString().endsWith(".zip")).toList());
				zips.sort(java.util.Comparator.comparingLong(PackReceiver::lastUsed).reversed());
				long total = 0;
				for (Path zip : zips) {
					total += Files.size(zip);
					if (total > CACHE_BUDGET && !inUse.contains(zip)) {
						Files.deleteIfExists(zip);
					}
				}
			} catch (IOException e) {
				JavaHost.LOGGER.warn("Couldn't tidy the pack cache", e);
			}
		});
	}

	private static long lastUsed(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private static Path cached(PackInfo info) {
		return PackFiles.cache().resolve(info.sha1() + ".zip");
	}

	private static UUID packId(PackInfo info) {
		return UUID.nameUUIDFromBytes(HexFormat.of().parseHex(info.sha1()));
	}

	private static void toast(String title) {
		com.javahost.client.Toasts.show("Host's packs", title);
	}
}

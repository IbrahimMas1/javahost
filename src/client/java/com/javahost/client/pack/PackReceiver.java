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

	private static final class Download {
		final PackInfo info;
		FileChannel channel;
		long received;

		Download(PackInfo info) {
			this.info = info;
		}
	}

	private static PackOffer pendingPrompt;
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
		// Without Iris there is nothing to do with a shader pack, so don't offer one.
		List<PackInfo> usable = received.packs().stream()
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
			Path part = PackFiles.cache().resolve(chunk.sha1() + ".part");
			if (chunk.index() == 0) {
				Files.createDirectories(part.getParent());
				download.channel = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING);
			}
			if (download.channel == null) {
				return;
			}
			ByteBuffer data = ByteBuffer.wrap(chunk.data());
			while (data.hasRemaining()) {
				download.channel.write(data);
			}
			download.received += chunk.data().length;
			ClientPlayNetworking.send(new PackAck(chunk.sha1(), chunk.index()));

			if (chunk.index() == chunk.total() - 1) {
				download.channel.close();
				download.channel = null;
				String actual = PackFiles.sha1(part);
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
		} catch (IOException e) {
			JavaHost.LOGGER.warn("Failed to receive pack {}", download.info.name(), e);
			DOWNLOADS.remove(chunk.sha1());
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

		for (PackInfo info : offer.packs()) {
			if (info.kind() == PackKind.SHADER_PACK && Files.exists(cached(info))) {
				applyShader(info, offer.shaderSettings());
			}
		}
		toast("Now using the host's packs");
	}

	private static void applyShader(PackInfo info, String settings) {
		Path shaderDir = IrisCompat.shaderpacksDirectory();
		if (shaderDir == null) {
			return;
		}
		try {
			String fileName = PackFiles.safeName(info.name());
			if (!fileName.toLowerCase().endsWith(".zip")) {
				fileName += ".zip";
			}
			fileName = "JavaHost - " + fileName;
			Path target = shaderDir.resolve(fileName);
			Files.createDirectories(shaderDir);
			if (!Files.exists(target) || !PackFiles.sha1(target).equals(info.sha1())) {
				Files.copy(cached(info), target, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!settings.isEmpty()) {
				Files.writeString(shaderDir.resolve(fileName + ".txt"), settings, StandardCharsets.UTF_8);
			}

			if (!changedShader) {
				Optional<String> current = IrisCompat.activeShaderPack();
				previousShader = current.orElse(null);
			}
			if (IrisCompat.apply(fileName)) {
				changedShader = true;
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("Could not install shared shader {}", info.name(), e);
			toast("Couldn't load the host's shader");
		}
	}

	private static void onDisconnect() {
		pendingPrompt = null;
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
		for (Download download : DOWNLOADS.values()) {
			if (download.channel != null) {
				try {
					download.channel.close();
				} catch (IOException ignored) {
					// Partial file is overwritten next time.
				}
			}
		}
		DOWNLOADS.clear();
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

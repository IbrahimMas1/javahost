package com.javahost.client.share;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.javahost.JavaHost;
import com.javahost.client.Popups;
import com.javahost.client.Toasts;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Identity;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Notifications;
import com.javahost.client.friends.Presence;
import com.javahost.client.host.WorldBackups;
import com.javahost.net.Payloads;
import com.javahost.net.Payloads.ShareDown;
import com.javahost.net.Payloads.ShareUp;
import com.javahost.share.WorldOwner;
import com.javahost.util.FriendCrypto;
import com.javahost.util.Versions;
import com.javahost.util.WorldTransfer;
import com.javahost.util.WorldZip;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Sending a copy of a world to a friend in the same world, over the game connection (LAN, direct or the relay).
 *
 * <ol>
 * <li>The sender zips the world (a live copy of the open world, another saved world, or a backup) and sends an
 * offer, sealed for that friend: name, sizes, SHA-256 and a fresh transfer key.</li>
 * <li>The friend accepts (after a disk-space check) or declines.</li>
 * <li>Chunks flow with a small window and acks; each is encrypted, so the host forwarding them can't read it.</li>
 * <li>The receiver checks the hash, unzips safely into saves/ as "World (from Alex)" and makes itself the owner,
 * so it plays as itself there.</li>
 * </ol>
 * Leaving the world, a cancel, or a stall stops it cleanly on both sides and removes partial files.
 * Everything here runs on the client thread except the zipping and unzipping.
 */
public final class WorldShare {
	public static final String MIN_VERSION = "1.6.0";
	private static final long OFFER_TIMEOUT_MS = 5 * 60_000L;
	private static final long STALL_MS = 60_000L;
	/** Chunks sent per tick at most (~1 MB), on top of the window, so a fast link doesn't stutter the game. */
	private static int chunksPerTick = 32;
	private static final byte ANSWER_NO = 0, ANSWER_YES = 1, ANSWER_SAVED = 2;

	public enum Stage { PREPARING, WAITING, OFFERED, SENDING, RECEIVING, FINISHING, DONE, FAILED }

	/** Where a world to send comes from. {@code size} is -1 until it's been measured. */
	public record Source(Kind kind, String name, Path path, long size, long time) {
		public enum Kind { LIVE, SAVE, BACKUP }
	}

	public static final class Transfer {
		public final long id;
		public final boolean outgoing;
		public final UUID peer;
		public final String peerName;
		private final long created = System.currentTimeMillis();
		private String world;
		private long size;
		private Stage stage;
		private String message;
		private long done;
		private long lastActivity = System.currentTimeMillis();
		private long speedAt;
		private long speedBytes;
		private double speed;
		private int toastStep = -1;
		// Sending.
		private Path zip;
		private boolean deleteZip;
		private byte[] key;
		private WorldTransfer.Sender window;
		private FileChannel channel;
		// Receiving.
		private WorldTransfer.Header header;
		private WorldTransfer.Receiver receiver;
		private Path part;
		private OutputStream out;
		private Path unpackedTo;
		private Path result;

		Transfer(long id, boolean outgoing, UUID peer, String peerName, String world, Stage stage) {
			this.id = id;
			this.outgoing = outgoing;
			this.peer = peer;
			this.peerName = peerName;
			this.world = world;
			this.stage = stage;
		}

		public Stage stage() {
			return stage;
		}

		public String world() {
			return world;
		}

		public long size() {
			return size;
		}

		public long created() {
			return created;
		}

		public Path result() {
			return result;
		}

		public boolean active() {
			return stage != Stage.DONE && stage != Stage.FAILED;
		}

		/** 0..1 while the bytes are moving; 1 once they're all across. */
		public double progress() {
			return size <= 0 ? 0 : Math.min(1, done / (double) size);
		}

		public String headline() {
			return outgoing ? "Sending \"" + world + "\" to " + peerName : "\"" + world + "\" from " + peerName;
		}

		public String status() {
			return switch (stage) {
				case PREPARING -> "Getting the world ready...";
				case WAITING -> "Waiting for " + peerName + " to accept · " + megabytes(size);
				case OFFERED -> megabytes(size) + " · for you";
				case SENDING, RECEIVING -> moving();
				case FINISHING -> outgoing ? peerName + " is unpacking it..." : "Checking and unpacking...";
				case DONE -> message != null ? message : outgoing ? peerName + " has it now" : "Now in your Singleplayer worlds";
				case FAILED -> message;
			};
		}

		private String moving() {
			String text = Math.round(progress() * 100) + "%";
			if (speed > 1024) {
				text += " · " + String.format(java.util.Locale.ROOT, "%.1f MB/s", speed / (1024 * 1024));
				long left = (long) ((size - done) / speed);
				text += " · " + (left < 60 ? "<1 min left" : Math.round(left / 60.0) + " min left");
			} else {
				text += " of " + megabytes(size);
			}
			return text;
		}

		private void measure(long now) {
			if (speedAt == 0) {
				speedAt = now;
				speedBytes = done;
			} else if (now - speedAt >= 1000) {
				double sample = (done - speedBytes) * 1000.0 / (now - speedAt);
				speed = speed == 0 ? sample : speed * 0.6 + sample * 0.4;
				speedAt = now;
				speedBytes = done;
			}
		}
	}

	private static final List<Transfer> TRANSFERS = new ArrayList<>();
	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

	private WorldShare() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ShareDown.TYPE, (payload, context) -> receive(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(() -> {
			for (Transfer transfer : List.copyOf(TRANSFERS)) {
				if (transfer.outgoing && transfer.stage == Stage.FINISHING) {
					sentUnconfirmed(transfer);
				} else if (transfer.active() && transfer.stage != Stage.FINISHING) {
					fail(transfer, "Stopped: you left the world", false);
				}
			}
		}));
		cleanFolder(outgoingDir());
		cleanFolder(incomingDir());
	}

	private static boolean pausedForTests;

	/** The in-game tests slow sending down to cancel a transfer halfway. */
	public static void throttleForTests(int chunks) {
		chunksPerTick = chunks;
	}

	/** The in-game tests show made-up transfers; this stops the timeouts and "left the world" checks meanwhile. */
	public static void pauseForTests(boolean paused) {
		pausedForTests = paused;
	}

	public static List<Transfer> transfers() {
		return List.copyOf(TRANSFERS);
	}

	public static void dismiss(Transfer transfer) {
		if (!transfer.active()) {
			TRANSFERS.remove(transfer);
		}
	}

	// ================================================================ sending

	/** Why you can't send a world to this friend right now, or null if you can. */
	public static String cannotSendTo(UUID friend) {
		Optional<Friend> known = Friends.friend(friend);
		if (known.isEmpty()) {
			return "Only friends can be sent worlds";
		}
		if (!InviteFlow.playingTogether(friend)) {
			return "Join the same world as " + known.get().name() + " first";
		}
		if (!ClientPlayNetworking.canSend(ShareUp.TYPE)) {
			return "The host needs JavaHost 1.6 or newer";
		}
		Optional<String> version = Presence.versionOf(friend);
		if (version.isEmpty() || Versions.compare(version.get(), MIN_VERSION) < 0) {
			return known.get().name() + " needs JavaHost 1.6 or newer";
		}
		if (TRANSFERS.stream().anyMatch(t -> t.outgoing && t.peer.equals(friend) && t.active())) {
			return "Already sending " + known.get().name() + " a world";
		}
		return null;
	}

	/** Friends in this world you could send a world to. */
	public static List<Friend> reachableFriends() {
		return Friends.friends().stream().filter(f -> InviteFlow.playingTogether(f.id())).toList();
	}

	/** The worlds you can send: the open one (a live copy), your other saved worlds, and your backups. */
	public static List<Source> sources() {
		return savedSources(liveSource());
	}

	/** The world that's open, as a live copy (game thread), or null. */
	public static Source liveSource() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server == null ? null : new Source(Source.Kind.LIVE, server.getWorldData().getLevelName(),
				server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), -1, System.currentTimeMillis());
	}

	/** {@code live} (if any) followed by every other saved world and backup. Reads disk: call off the game thread. */
	public static List<Source> savedSources(Source live) {
		Minecraft minecraft = Minecraft.getInstance();
		List<Source> sources = new ArrayList<>();
		Path open = live == null ? null : live.path();
		if (live != null) {
			sources.add(live);
		}
		Path saves = minecraft.getLevelSource().getBaseDir();
		try (Stream<Path> list = Files.list(saves)) {
			for (Path dir : list.filter(d -> Files.isRegularFile(d.resolve("level.dat"))).toList()) {
				if (dir.toAbsolutePath().normalize().equals(open)) {
					continue;
				}
				long time = Files.getLastModifiedTime(dir.resolve("level.dat")).toMillis();
				sources.add(new Source(Source.Kind.SAVE, WorldOwner.nameOf(dir), dir, -1, time));
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Share] couldn't list saved worlds", e);
		}
		sources.sort(Comparator.comparing((Source s) -> s.kind() != Source.Kind.LIVE).thenComparing(Comparator.comparingLong(Source::time).reversed()));
		for (WorldBackups.Backup backup : WorldBackups.listAll()) {
			sources.add(new Source(Source.Kind.BACKUP, backup.world(), backup.file(), backup.size(), backup.time()));
		}
		return sources;
	}

	/** Size on disk of a world folder or zip (call off the client thread for folders). */
	public static long measure(Source source) {
		if (source.size() >= 0) {
			return source.size();
		}
		try (Stream<Path> walk = Files.walk(source.path())) {
			return walk.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		} catch (IOException e) {
			return 0;
		}
	}

	/** Starts sending: zips if needed, then offers it to the friend. */
	public static Transfer send(Friend friend, Source source) {
		long id = RANDOM.nextLong();
		Transfer transfer = new Transfer(id, true, friend.id(), friend.name(), source.name(), Stage.PREPARING);
		TRANSFERS.addFirst(transfer);
		Minecraft minecraft = Minecraft.getInstance();
		CompletableFuture<Path> zipped;
		if (source.kind() == Source.Kind.BACKUP) {
			transfer.zip = source.path();
			zipped = CompletableFuture.completedFuture(source.path());
		} else {
			Path zip = outgoingDir().resolve(Long.toHexString(id) + ".zip");
			transfer.zip = zip;
			transfer.deleteZip = true;
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (source.kind() == Source.Kind.LIVE && server != null) {
				zipped = new CompletableFuture<>();
				CompletableFuture<Path> result = zipped;
				server.execute(() -> WorldBackups.snapshot(server, zip).whenComplete((path, error) -> {
					if (error != null) {
						result.completeExceptionally(error);
					} else {
						result.complete(path);
					}
				}));
			} else {
				zipped = CompletableFuture.supplyAsync(() -> {
					try {
						WorldZip.zip(source.path(), zip);
						return zip;
					} catch (IOException e) {
						throw new java.util.concurrent.CompletionException(e);
					}
				}, com.javahost.util.IoExecutor.POOL);
			}
		}
		zipped.thenApplyAsync(zip -> {
			try {
				return new WorldTransfer.Header(source.name(), Files.size(zip), WorldZip.unpackedSize(zip), WorldTransfer.sha256(zip),
						FriendCrypto.randomKey());
			} catch (IOException e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, com.javahost.util.IoExecutor.POOL).whenComplete((header, error) -> minecraft.execute(() -> {
			if (transfer.stage != Stage.PREPARING) {
				cleanUp(transfer, true); // cancelled while zipping: the zip has just been finished
				return;
			}
			if (error != null) {
				JavaHost.LOGGER.warn("[Share] couldn't prepare {}", source.name(), error);
				fail(transfer, "Couldn't copy the world: " + rootMessage(error), false);
				return;
			}
			if (header.size() > WorldTransfer.MAX_BYTES || header.unpacked() > WorldTransfer.MAX_BYTES) {
				fail(transfer, "This world is too big to send (over 8 GB)", false);
				return;
			}
			String problem = cannotSendTo(friend.id());
			if (problem != null && !problem.startsWith("Already")) {
				fail(transfer, problem, false);
				return;
			}
			try {
				byte[] friendKey = Identity.sharedKeyWith(friend.id(), friend.box());
				transfer.key = header.key();
				transfer.size = header.size();
				transfer.stage = Stage.WAITING;
				transfer.lastActivity = System.currentTimeMillis();
				send(transfer, Payloads.SHARE_OFFER, 0, WorldTransfer.sealHeader(friendKey, id, header));
				Toasts.player(friend.id(), "World offered to " + friend.name(), "\"" + source.name() + "\" · " + megabytes(header.size()));
			} catch (GeneralSecurityException e) {
				fail(transfer, "Couldn't encrypt the offer", false);
			}
		}));
		return transfer;
	}

	// ================================================================ receiving

	public static void accept(Transfer transfer) {
		if (transfer.stage != Stage.OFFERED) {
			return;
		}
		try {
			Path saves = Minecraft.getInstance().getLevelSource().getBaseDir();
			Files.createDirectories(incomingDir());
			long need = transfer.header.size() + transfer.header.unpacked() + 64L * 1024 * 1024;
			long free = Files.getFileStore(saves).getUsableSpace();
			if (free < need) {
				fail(transfer, "Not enough disk space: needs " + megabytes(need) + ", " + megabytes(free) + " free", true);
				return;
			}
			transfer.part = incomingDir().resolve(Long.toHexString(transfer.id) + ".part");
			transfer.out = new BufferedOutputStream(Files.newOutputStream(transfer.part), 1 << 20);
			transfer.receiver = new WorldTransfer.Receiver(transfer.header);
			transfer.stage = Stage.RECEIVING;
			transfer.lastActivity = System.currentTimeMillis();
			send(transfer, Payloads.SHARE_ANSWER, 0, new byte[] { ANSWER_YES });
		} catch (IOException e) {
			fail(transfer, "Couldn't start: " + e.getMessage(), true);
		}
	}

	public static void decline(Transfer transfer) {
		if (transfer.stage == Stage.OFFERED) {
			send(transfer, Payloads.SHARE_ANSWER, 0, new byte[] { ANSWER_NO });
			TRANSFERS.remove(transfer);
		}
	}

	/** Stops a transfer from either side; the other side is told. */
	public static void cancel(Transfer transfer) {
		if (transfer.active() && transfer.stage != Stage.FINISHING) {
			fail(transfer, transfer.outgoing ? "You cancelled it" : "You stopped it", true);
		}
	}

	private static void receive(ShareDown payload) {
		Transfer transfer = TRANSFERS.stream().filter(t -> t.id == payload.id() && t.peer.equals(payload.from())).findFirst().orElse(null);
		try {
			switch (payload.kind()) {
				case Payloads.SHARE_OFFER -> onOffer(payload);
				case Payloads.SHARE_ANSWER -> {
					if (transfer != null && transfer.outgoing && payload.data().length == 1) {
						onAnswer(transfer, payload.data()[0]);
					}
				}
				case Payloads.SHARE_CHUNK -> {
					if (transfer != null && !transfer.outgoing && transfer.stage == Stage.RECEIVING) {
						onChunk(transfer, payload.index(), payload.data());
					}
				}
				case Payloads.SHARE_ACK -> {
					if (transfer != null && transfer.outgoing && transfer.stage == Stage.SENDING) {
						onAck(transfer, payload.index());
					}
				}
				case Payloads.SHARE_CANCEL -> {
					if (transfer != null && transfer.active()) {
						String reason = new String(payload.data(), StandardCharsets.UTF_8);
						fail(transfer, transfer.peerName + ": " + (reason.isBlank() ? "stopped" : reason), false);
					}
				}
				default -> {
				}
			}
		} catch (IOException | GeneralSecurityException | RuntimeException e) {
			JavaHost.LOGGER.warn("[Share] transfer {} failed", payload.id(), e);
			if (transfer != null && transfer.active()) {
				fail(transfer, transfer.outgoing ? "Something went wrong while sending" : "The world didn't arrive intact", true);
			}
		}
	}

	private static void onOffer(ShareDown payload) {
		Optional<Friend> friend = Friends.friend(payload.from());
		if (friend.isEmpty()) {
			return; // only friends can send us worlds (and only they could seal an offer for us)
		}
		WorldTransfer.Header header;
		try {
			header = WorldTransfer.openHeader(Identity.sharedKeyWith(friend.get().id(), friend.get().box()), payload.id(), payload.data());
		} catch (GeneralSecurityException e) {
			JavaHost.LOGGER.warn("[Share] ignored an offer from {} that didn't check out", friend.get().name(), e);
			return;
		}
		Transfer transfer = new Transfer(payload.id(), false, friend.get().id(), friend.get().name(), header.world(), Stage.OFFERED);
		transfer.header = header;
		transfer.size = header.size();
		if (TRANSFERS.stream().anyMatch(t -> !t.outgoing && t.peer.equals(transfer.peer) && t.active())) {
			send(transfer, Payloads.SHARE_CANCEL, 0, "Already getting a world from you".getBytes(StandardCharsets.UTF_8));
			return;
		}
		TRANSFERS.addFirst(transfer);
		Notifications.ping(Notifications.Kind.INVITE);
		Toasts.player(transfer.peer, "World from " + transfer.peerName,
				"\"" + header.world() + "\" · " + megabytes(header.size()) + ". Answer in JavaHost (J)");
		Popups.offerWorld(transfer);
	}

	private static void onAnswer(Transfer transfer, byte answer) throws IOException {
		if (answer == ANSWER_SAVED && transfer.stage == Stage.FINISHING) {
			transfer.stage = Stage.DONE;
			cleanUp(transfer, false);
			Toasts.notify(transfer.peerName + " has your world", "\"" + transfer.world + "\" arrived safely.");
		} else if (transfer.stage != Stage.WAITING) {
			return;
		} else if (answer == ANSWER_YES) {
			transfer.channel = FileChannel.open(transfer.zip, StandardOpenOption.READ);
			transfer.window = new WorldTransfer.Sender(WorldTransfer.chunkCount(transfer.size));
			transfer.stage = Stage.SENDING;
			transfer.lastActivity = System.currentTimeMillis();
		} else {
			fail(transfer, transfer.peerName + " said no thanks", false);
		}
	}

	private static void onAck(Transfer transfer, int upTo) {
		transfer.window.ack(upTo);
		transfer.done = Math.min(transfer.size, (long) (transfer.window.acked() + 1) * WorldTransfer.PLAIN_CHUNK);
		transfer.lastActivity = System.currentTimeMillis();
		if (transfer.window.done()) {
			closeQuietly(transfer.channel);
			transfer.channel = null;
			transfer.stage = Stage.FINISHING;
		}
		progressToast(transfer);
	}

	private static void onChunk(Transfer transfer, int index, byte[] sealed) throws IOException, GeneralSecurityException {
		byte[] plain = transfer.receiver.accept(transfer.id, index, sealed);
		transfer.out.write(plain);
		transfer.done = transfer.receiver.received();
		transfer.lastActivity = System.currentTimeMillis();
		int ack = transfer.receiver.ackDue();
		if (ack >= 0) {
			send(transfer, Payloads.SHARE_ACK, ack, new byte[0]);
		}
		progressToast(transfer);
		if (transfer.receiver.complete()) {
			transfer.out.close();
			transfer.out = null;
			if (!transfer.receiver.verified()) {
				fail(transfer, "The world didn't arrive intact", true);
				return;
			}
			transfer.stage = Stage.FINISHING;
			unpack(transfer);
		}
	}

	private static void unpack(Transfer transfer) {
		Minecraft minecraft = Minecraft.getInstance();
		Path saves = minecraft.getLevelSource().getBaseDir();
		String name = transfer.world + " (from " + transfer.peerName + ")";
		Path target = WorldOwner.freeFolder(saves, name);
		transfer.unpackedTo = target;
		UUID me = Identity.id();
		long limit = Math.min(WorldTransfer.MAX_BYTES, transfer.header.unpacked() + 16L * 1024 * 1024);
		CompletableFuture.runAsync(() -> {
			try {
				WorldZip.unzipWorld(transfer.part, target, limit, 500_000);
				WorldOwner.adopt(target, me, target.getFileName().toString());
			} catch (IOException e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, com.javahost.util.IoExecutor.POOL).whenComplete((ok, error) -> minecraft.execute(() -> {
			if (error != null) {
				JavaHost.LOGGER.warn("[Share] couldn't unpack {}", transfer.world, error);
				fail(transfer, "Couldn't unpack the world: " + rootMessage(error), true);
				return;
			}
			transfer.result = target;
			transfer.unpackedTo = null;
			transfer.stage = Stage.DONE;
			cleanUp(transfer, false);
			send(transfer, Payloads.SHARE_ANSWER, 0, new byte[] { ANSWER_SAVED });
			JavaHost.LOGGER.info("[Share] received {} from {} into {}", transfer.world, transfer.peerName, target.getFileName());
			Toasts.notify("World received from " + transfer.peerName,
					"\"" + target.getFileName() + "\" is in your Singleplayer worlds. You play as yourself there.");
		}));
	}

	// ================================================================ every tick

	public static void tick(Minecraft minecraft) {
		if (TRANSFERS.isEmpty() || pausedForTests) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Transfer transfer : List.copyOf(TRANSFERS)) {
			if (!transfer.active()) {
				continue;
			}
			transfer.measure(now);
			if (transfer.stage == Stage.FINISHING) {
				// The bytes are all across. The receiver is unpacking locally (and may leave); the sender waits a
				// while for the "saved" answer, then counts it as sent.
				if (transfer.outgoing && (now - transfer.lastActivity > STALL_MS * 5 || !InviteFlow.playingTogether(transfer.peer))) {
					sentUnconfirmed(transfer);
				}
				continue;
			}
			if (transfer.stage != Stage.PREPARING && !InviteFlow.playingTogether(transfer.peer)) {
				fail(transfer, transfer.peerName + " left the world", false);
				continue;
			}
			long quiet = now - transfer.lastActivity;
			switch (transfer.stage) {
				case WAITING, OFFERED -> {
					if (now - transfer.created > OFFER_TIMEOUT_MS) {
						fail(transfer, transfer.stage == Stage.WAITING ? transfer.peerName + " didn't answer" : "The offer expired", true);
					}
				}
				case SENDING -> {
					if (quiet > STALL_MS) {
						fail(transfer, "The connection stalled", true);
					} else {
						pump(transfer);
					}
				}
				case RECEIVING -> {
					if (quiet > STALL_MS) {
						fail(transfer, "The connection stalled", true);
					}
				}
				default -> {
				}
			}
		}
	}

	private static void pump(Transfer transfer) {
		try {
			for (int sent = 0; sent < chunksPerTick && transfer.window.canSend(); sent++) {
				int index = transfer.window.take();
				int length = WorldTransfer.chunkLength(transfer.size, index);
				ByteBuffer buffer = ByteBuffer.allocate(length);
				long offset = (long) index * WorldTransfer.PLAIN_CHUNK;
				while (buffer.hasRemaining()) {
					if (transfer.channel.read(buffer, offset + buffer.position()) < 0) {
						throw new IOException("The world file shrank while sending");
					}
				}
				send(transfer, Payloads.SHARE_CHUNK, index, WorldTransfer.sealChunk(transfer.key, transfer.id, index, buffer.array()));
			}
		} catch (IOException | GeneralSecurityException e) {
			JavaHost.LOGGER.warn("[Share] sending {} failed", transfer.world, e);
			fail(transfer, "Couldn't read the world file", true);
		}
	}

	// ================================================================ helpers

	/** Every byte arrived but the friend left (or went quiet) before saying it was unpacked. */
	private static void sentUnconfirmed(Transfer transfer) {
		transfer.stage = Stage.DONE;
		transfer.message = "Sent. " + transfer.peerName + " left before it finished unpacking";
		cleanUp(transfer, false);
	}

	private static void send(Transfer transfer, byte kind, int index, byte[] data) {
		if (ClientPlayNetworking.canSend(ShareUp.TYPE)) {
			ClientPlayNetworking.send(new ShareUp(transfer.peer, transfer.id, kind, index, data));
		}
	}

	private static void fail(Transfer transfer, String why, boolean tellPeer) {
		boolean wasActive = transfer.active();
		if (tellPeer && wasActive && transfer.stage != Stage.PREPARING) {
			send(transfer, Payloads.SHARE_CANCEL, 0, why.getBytes(StandardCharsets.UTF_8));
		}
		transfer.stage = Stage.FAILED;
		transfer.message = why;
		cleanUp(transfer, true);
		if (wasActive) {
			Toasts.show("world-share-" + transfer.id, transfer.outgoing ? "World not sent" : "World not received", why);
		}
	}

	/** Closes files and deletes what's no longer needed: always the temporary zip, and partial worlds on failure. */
	private static void cleanUp(Transfer transfer, boolean failed) {
		closeQuietly(transfer.channel);
		transfer.channel = null;
		closeQuietly(transfer.out);
		transfer.out = null;
		try {
			if (transfer.deleteZip && transfer.zip != null && transfer.stage != Stage.FINISHING) {
				Files.deleteIfExists(transfer.zip);
			}
			if (transfer.part != null) {
				Files.deleteIfExists(transfer.part);
			}
			if (failed && transfer.unpackedTo != null) {
				deleteTree(transfer.unpackedTo);
				transfer.unpackedTo = null;
			}
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Share] couldn't clean up after {}", transfer.world, e);
		}
	}

	private static void progressToast(Transfer transfer) {
		int step = (int) (transfer.progress() * 4);
		if (step != transfer.toastStep && step < 4) {
			transfer.toastStep = step;
			Toasts.show("world-share-" + transfer.id, transfer.headline(), transfer.status());
		}
	}

	public static Path outgoingDir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("javahost").resolve("outgoing-worlds");
	}

	public static Path incomingDir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("javahost").resolve("incoming-worlds");
	}

	/** Leftovers from a game that closed mid-transfer. */
	private static void cleanFolder(Path dir) {
		if (Files.isDirectory(dir)) {
			try {
				deleteTree(dir);
			} catch (IOException e) {
				JavaHost.LOGGER.warn("[Share] couldn't clear {}", dir, e);
			}
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ignored) {
				// Nothing more to do with it.
			}
		}
	}

	private static String rootMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root != root.getCause()) {
			root = root.getCause();
		}
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

	public static String megabytes(long bytes) {
		if (bytes >= 1024L * 1024 * 1024) {
			return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
		}
		return Math.max(1, Math.round(bytes / (1024.0 * 1024))) + " MB";
	}
}

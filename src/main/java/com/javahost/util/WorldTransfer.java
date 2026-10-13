package com.javahost.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The pieces of sending a world copy to a friend, without any Minecraft code: the offer header, chunk encryption,
 * and the flow control on both ends.
 *
 * <ul>
 * <li>The offer header (world name, sizes, SHA-256, and a fresh random transfer key) is sealed with the two
 * friends' shared key, so the host forwarding the bytes can't read the world or forge an offer.</li>
 * <li>Each chunk is AES-GCM under the transfer key, with the transfer id and chunk index authenticated, so a
 * chunk can't be swapped, replayed or reordered without being noticed.</li>
 * <li>The sender keeps at most {@link #WINDOW} chunks in flight; the receiver acks every {@link #ACK_EVERY}, so a
 * big world never floods the connection and gameplay packets keep flowing alongside it.</li>
 * </ul>
 */
public final class WorldTransfer {
	/** Largest sealed chunk: serverbound custom payloads are capped at 32 KiB, like the fast lane's pieces. */
	public static final int SEALED_CHUNK = 30 * 1024;
	/** AES-GCM adds a 12-byte nonce and a 16-byte tag. */
	public static final int PLAIN_CHUNK = SEALED_CHUNK - 28;
	public static final int WINDOW = 64;
	public static final int ACK_EVERY = 16;
	/** Largest world (zipped or unzipped) we'll send or accept. */
	public static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;

	private WorldTransfer() {
	}

	/** What the receiver needs to know before saying yes, plus the key the chunks are sealed with. */
	public record Header(String world, long size, long unpacked, String sha256, byte[] key) {
		public int chunks() {
			return chunkCount(size);
		}
	}

	public static int chunkCount(long size) {
		return (int) Math.max(1, (size + PLAIN_CHUNK - 1) / PLAIN_CHUNK);
	}

	public static byte[] sealHeader(byte[] friendKey, long id, Header header) throws GeneralSecurityException {
		JsonObject json = new JsonObject();
		json.addProperty("world", header.world());
		json.addProperty("size", header.size());
		json.addProperty("unpacked", header.unpacked());
		json.addProperty("sha256", header.sha256());
		json.addProperty("key", Base64.getEncoder().encodeToString(header.key()));
		return FriendCrypto.sealBytes(friendKey, json.toString().getBytes(StandardCharsets.UTF_8), "javahost-world-offer:" + id);
	}

	/** Opens and checks an offer header; throws if it was forged, tampered with or doesn't make sense. */
	public static Header openHeader(byte[] friendKey, long id, byte[] sealed) throws GeneralSecurityException {
		byte[] plain = FriendCrypto.openBytes(friendKey, sealed, "javahost-world-offer:" + id);
		try {
			JsonObject json = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
			Header header = new Header(json.get("world").getAsString(), json.get("size").getAsLong(), json.get("unpacked").getAsLong(),
					json.get("sha256").getAsString(), Base64.getDecoder().decode(json.get("key").getAsString()));
			if (header.size() < 1 || header.size() > MAX_BYTES || header.unpacked() < 0 || header.unpacked() > MAX_BYTES
					|| header.key().length != 32 || !header.sha256().matches("[0-9a-f]{64}") || header.world().isBlank()) {
				throw new GeneralSecurityException("Offer doesn't make sense");
			}
			return header;
		} catch (RuntimeException e) {
			throw new GeneralSecurityException("Offer is malformed", e);
		}
	}

	public static byte[] sealChunk(byte[] key, long id, int index, byte[] plain) throws GeneralSecurityException {
		return FriendCrypto.sealBytes(key, plain, chunkContext(id, index));
	}

	public static byte[] openChunk(byte[] key, long id, int index, byte[] sealed) throws GeneralSecurityException {
		return FriendCrypto.openBytes(key, sealed, chunkContext(id, index));
	}

	private static String chunkContext(long id, int index) {
		return "javahost-world-chunk:" + id + ":" + index;
	}

	/** Bytes of chunk {@code index} of a file of {@code size} bytes. */
	public static int chunkLength(long size, int index) {
		return (int) Math.min(PLAIN_CHUNK, size - (long) index * PLAIN_CHUNK);
	}

	/** The sender's window: which chunk to send next, and whether another one may go out yet. */
	public static final class Sender {
		private final int total;
		private int next;
		private int acked = -1;

		public Sender(int total) {
			this.total = total;
		}

		public boolean canSend() {
			return next < total && next - acked <= WINDOW;
		}

		/** The index to send now; call only when {@link #canSend()}. */
		public int take() {
			return next++;
		}

		/** The receiver has every chunk up to and including {@code upTo}. */
		public void ack(int upTo) {
			if (upTo >= acked && upTo < next) {
				acked = upTo;
			}
		}

		public boolean done() {
			return acked >= total - 1;
		}

		public int acked() {
			return acked;
		}

		public int total() {
			return total;
		}
	}

	/** The receiver's side: chunks must arrive in order (the game connection keeps them in order). */
	public static final class Receiver {
		private final Header header;
		private final MessageDigest sha;
		private int expected;
		private long received;

		public Receiver(Header header) {
			this.header = header;
			try {
				this.sha = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}

		/**
		 * Opens chunk {@code index} and returns its bytes to write. Throws if it's out of order, tampered with or
		 * the wrong size.
		 */
		public byte[] accept(long id, int index, byte[] sealed) throws GeneralSecurityException {
			if (index != expected) {
				throw new GeneralSecurityException("Chunk " + index + " arrived, expected " + expected);
			}
			byte[] plain = openChunk(header.key(), id, index, sealed);
			if (plain.length != chunkLength(header.size(), index)) {
				throw new GeneralSecurityException("Chunk " + index + " has the wrong size");
			}
			sha.update(plain);
			expected++;
			received += plain.length;
			return plain;
		}

		/** The index to ack now, or -1: every {@link #ACK_EVERY} chunks and at the end. */
		public int ackDue() {
			int last = expected - 1;
			return last >= 0 && (expected % ACK_EVERY == 0 || complete()) ? last : -1;
		}

		public boolean complete() {
			return expected >= header.chunks();
		}

		public long received() {
			return received;
		}

		/** True when every chunk is in and the whole file matches the hash in the offer. Call once, at the end. */
		public boolean verified() {
			return complete() && received == header.size() && HexFormat.of().formatHex(sha.digest()).equals(header.sha256());
		}
	}

	/** SHA-256 of a file as lowercase hex. */
	public static String sha256(java.nio.file.Path file) throws java.io.IOException {
		try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = in.read(buffer)) > 0) {
				sha.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(sha.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

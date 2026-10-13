package com.javahost.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Packs a message (text plus an optional file) into pieces small enough for one packet, and puts them back
 * together on the other side, in any order. Used by the same-world fast lane.
 */
public final class Chunks {
	private Chunks() {
	}

	/** A message's bytes: [text length][text][file]. */
	public static byte[] pack(String text, byte[] file) {
		byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
		byte[] extra = file == null ? new byte[0] : file;
		return ByteBuffer.allocate(4 + utf8.length + extra.length).putInt(utf8.length).put(utf8).put(extra).array();
	}

	public record Unpacked(String text, byte[] file) {
	}

	public static Unpacked unpack(byte[] packed) {
		ByteBuffer buffer = ByteBuffer.wrap(packed);
		int length = buffer.getInt();
		if (length < 0 || length > buffer.remaining()) {
			throw new IllegalArgumentException("bad message");
		}
		byte[] utf8 = new byte[length];
		buffer.get(utf8);
		byte[] file = new byte[buffer.remaining()];
		buffer.get(file);
		return new Unpacked(new String(utf8, StandardCharsets.UTF_8), file.length == 0 ? null : file);
	}

	public static List<byte[]> split(byte[] data, int pieceSize) {
		List<byte[]> pieces = new ArrayList<>();
		for (int start = 0; start < data.length; start += pieceSize) {
			pieces.add(Arrays.copyOfRange(data, start, Math.min(data.length, start + pieceSize)));
		}
		if (pieces.isEmpty()) {
			pieces.add(new byte[0]);
		}
		return pieces;
	}

	/** Collects the pieces of one transfer. Not thread-safe: use from one thread. */
	public static final class Assembly {
		private final byte[][] pieces;
		private final int maxBytes;
		private final long started = System.currentTimeMillis();
		private int received;
		private int bytes;

		/** Throws if {@code total} is impossible for a transfer of at most {@code maxBytes} in {@code pieceSize} pieces. */
		public Assembly(int total, int maxBytes, int pieceSize) {
			if (total < 1 || total > maxBytes / pieceSize + 1) {
				throw new IllegalArgumentException("Bad piece count " + total);
			}
			this.pieces = new byte[total][];
			this.maxBytes = maxBytes;
		}

		/** Adds a piece; returns the whole message once every piece is in, otherwise null. Throws if too big. */
		public byte[] add(int index, byte[] data) {
			if (index < 0 || index >= pieces.length || pieces[index] != null) {
				return null; // out of range or a duplicate
			}
			bytes += data.length;
			if (bytes > maxBytes) {
				throw new IllegalStateException("transfer too big");
			}
			pieces[index] = data;
			if (++received < pieces.length) {
				return null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(bytes);
			for (byte[] piece : pieces) {
				out.writeBytes(piece);
			}
			return out.toByteArray();
		}

		public int bytes() {
			return bytes;
		}

		public boolean olderThan(long ms) {
			return System.currentTimeMillis() - started > ms;
		}
	}
}

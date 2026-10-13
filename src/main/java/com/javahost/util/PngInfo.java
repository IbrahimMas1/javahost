package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a PNG's width and height from its header without decoding it, so an "image bomb" (a small file that
 * claims to be enormous) is turned away before it can eat gigabytes of memory.
 */
public final class PngInfo {
	/** Biggest side we'll decode (16K covers every real screen and screenshot mod). */
	public static final int MAX_SIDE = 16384;
	/** Most pixels we'll decode: an 8K screenshot is 33 million. */
	public static final long MAX_PIXELS = 40_000_000L;
	private static final byte[] MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	private PngInfo() {
	}

	/** {width, height} from the IHDR header, or null if this isn't a PNG. */
	public static int[] size(byte[] data) {
		if (data.length < 24) {
			return null;
		}
		for (int i = 0; i < MAGIC.length; i++) {
			if (data[i] != MAGIC[i]) {
				return null;
			}
		}
		if (data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
			return null;
		}
		return new int[] {readInt(data, 16), readInt(data, 20)};
	}

	public static int[] size(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return size(in.readNBytes(24));
		}
	}

	/** True for a real PNG of a size that's safe to decode. */
	public static boolean safe(int[] size) {
		return size != null && size[0] > 0 && size[1] > 0 && size[0] <= MAX_SIDE && size[1] <= MAX_SIDE
				&& (long) size[0] * size[1] <= MAX_PIXELS;
	}

	private static int readInt(byte[] data, int at) {
		return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
	}
}

package com.javahost.util;

/** Whether two IPv4 addresses are on the same local network. */
public final class Subnets {
	private Subnets() {
	}

	/** True if {@code a} and {@code b} share their first {@code prefix} bits (and the prefix is a real LAN size). */
	public static boolean same(byte[] a, byte[] b, int prefix) {
		if (a.length != 4 || b.length != 4 || prefix < 8 || prefix > 32) {
			return false;
		}
		int ia = ((a[0] & 0xFF) << 24) | ((a[1] & 0xFF) << 16) | ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
		int ib = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
		int mask = prefix == 32 ? -1 : ~(-1 >>> prefix);
		return (ia & mask) == (ib & mask);
	}
}

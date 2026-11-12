package com.javahost.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;

/**
 * The little of STUN (RFC 5389) needed to learn "what address and port does the internet see this socket as?":
 * building a binding request and reading the address out of the answer. Sending and receiving is the caller's job,
 * on the very socket whose address it wants to know.
 */
public final class Stun {
	private static final int MAGIC = 0x2112A442;
	private static final SecureRandom RANDOM = new SecureRandom();

	private Stun() {
	}

	/** A binding request (20 bytes); bytes 8-19 are its random transaction id. */
	public static byte[] request() {
		byte[] packet = new byte[20];
		packet[1] = 0x01; // binding request, no attributes
		packet[4] = 0x21;
		packet[5] = 0x12;
		packet[6] = (byte) 0xA4;
		packet[7] = 0x42;
		byte[] id = new byte[12];
		RANDOM.nextBytes(id);
		System.arraycopy(id, 0, packet, 8, 12);
		return packet;
	}

	/** Whether a datagram is a STUN message (so it isn't mistaken for the game's traffic). */
	public static boolean isStun(byte[] data, int length) {
		return length >= 20 && (data[0] & 0xC0) == 0 && data[4] == 0x21 && data[5] == 0x12 && data[6] == (byte) 0xA4 && data[7] == 0x42;
	}

	/**
	 * The public address in a binding success response to {@code request}, or null if this isn't one (wrong
	 * transaction, an error, or damaged).
	 */
	public static InetSocketAddress mapped(byte[] data, int length, byte[] request) {
		if (!isStun(data, length) || data[0] != 0x01 || data[1] != 0x01) {
			return null;
		}
		for (int i = 8; i < 20; i++) {
			if (request != null && data[i] != request[i]) {
				return null;
			}
		}
		int end = Math.min(length, 20 + (((data[2] & 0xFF) << 8) | (data[3] & 0xFF)));
		int at = 20;
		while (end - at >= 4) {
			int type = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
			int size = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
			at += 4;
			if (size > end - at) {
				return null;
			}
			if (type == 0x0020 && size >= 8) { // XOR-MAPPED-ADDRESS
				int family = data[at + 1] & 0xFF;
				int port = ((((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF)) ^ (MAGIC >>> 16)) & 0xFFFF;
				int bytes = family == 0x01 ? 4 : family == 0x02 ? 16 : 0;
				if (bytes == 0 || size < 4 + bytes) {
					return null;
				}
				byte[] address = new byte[bytes];
				for (int i = 0; i < bytes; i++) {
					// IPv4 is hidden with the magic number; IPv6 with the magic number and then the transaction id.
					byte mask = i < 4 ? (byte) (MAGIC >>> (24 - 8 * i)) : data[8 + i - 4];
					address[i] = (byte) (data[at + 4 + i] ^ mask);
				}
				try {
					return new InetSocketAddress(InetAddress.getByAddress(address), port);
				} catch (UnknownHostException e) {
					return null;
				}
			}
			at += size + ((4 - size % 4) % 4);
		}
		return null;
	}
}

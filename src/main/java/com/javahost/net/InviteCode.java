package com.javahost.net;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Short, typo-tolerant invite codes that encode an address and port.
 * IPv4: {@code JH-7K2QD-M9X4A}. IPv6: {@code JH6-} followed by 29 characters in groups of five.
 * Uses Crockford base32 (no I, L, O or U) so codes are easy to read out loud.
 */
public final class InviteCode {
	private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
	private static final int V4_CHARS = 10; // 48 bits
	private static final int V6_CHARS = 29; // 144 bits

	private InviteCode() {
	}

	/** Returns a code for an IPv4 or IPv6 literal, or {@code null} if it isn't one. */
	public static String encode(String ip, int port) {
		InetAddress address;
		try {
			// Only literals: getByName never does a DNS lookup for these.
			if (ip == null || !ip.matches("[0-9a-fA-F:.%]+")) {
				return null;
			}
			address = InetAddress.getByName(ip);
		} catch (UnknownHostException e) {
			return null;
		}
		byte[] ipBytes = address.getAddress();
		byte[] all = new byte[ipBytes.length + 2];
		System.arraycopy(ipBytes, 0, all, 0, ipBytes.length);
		all[ipBytes.length] = (byte) (port >> 8);
		all[ipBytes.length + 1] = (byte) port;

		if (address instanceof Inet4Address) {
			String body = toBase32(all, V4_CHARS);
			return "JH-" + group(body);
		}
		if (address instanceof Inet6Address) {
			return "JH6-" + group(toBase32(all, V6_CHARS));
		}
		return null;
	}

	/**
	 * Turns an invite code back into {@code host:port} ({@code [v6]:port} for IPv6). Anything that isn't a
	 * code, such as a normal server address or a relay domain, is passed through unchanged. Returns
	 * {@code null} for a malformed code.
	 */
	public static String decode(String input) {
		String trimmed = input.trim();
		String upper = trimmed.toUpperCase(Locale.ROOT);
		String compact = upper.replace("-", "").replace(" ", "");
		boolean v6 = upper.startsWith("JH6") && compact.length() == 3 + V6_CHARS;
		boolean v4 = !v6 && upper.matches("JH[- ]?[0-9A-Z]{5}[- ]?[0-9A-Z]{5}");
		if (!v4 && !v6) {
			return trimmed.isEmpty() ? null : trimmed;
		}
		String body = compact.substring(v6 ? 3 : 2).replace('O', '0').replace('I', '1').replace('L', '1');
		byte[] bytes = fromBase32(body, v6 ? 18 : 6);
		if (bytes == null) {
			return null;
		}
		int port = ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
		byte[] ip = new byte[bytes.length - 2];
		System.arraycopy(bytes, 0, ip, 0, ip.length);
		try {
			InetAddress address = InetAddress.getByAddress(ip);
			String host = address.getHostAddress();
			return (v6 ? "[" + host + "]" : host) + ":" + port;
		} catch (UnknownHostException e) {
			return null;
		}
	}

	private static String toBase32(byte[] bytes, int chars) {
		BigInteger value = new BigInteger(1, bytes);
		char[] out = new char[chars];
		for (int i = chars - 1; i >= 0; i--) {
			out[i] = ALPHABET.charAt(value.intValue() & 31);
			value = value.shiftRight(5);
		}
		return new String(out);
	}

	private static byte[] fromBase32(String body, int byteCount) {
		BigInteger value = BigInteger.ZERO;
		for (char c : body.toCharArray()) {
			int digit = ALPHABET.indexOf(c);
			if (digit < 0) {
				return null;
			}
			value = value.shiftLeft(5).or(BigInteger.valueOf(digit));
		}
		if (value.bitLength() > byteCount * 8) {
			return null;
		}
		byte[] raw = value.toByteArray();
		byte[] out = new byte[byteCount];
		int copy = Math.min(raw.length, byteCount);
		System.arraycopy(raw, raw.length - copy, out, byteCount - copy, copy);
		return out;
	}

	private static String group(String body) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < body.length(); i += 5) {
			if (i > 0) {
				out.append('-');
			}
			out.append(body, i, Math.min(body.length(), i + 5));
		}
		return out.toString();
	}
}

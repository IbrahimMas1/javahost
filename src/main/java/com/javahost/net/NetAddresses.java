package com.javahost.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** Finding the addresses friends can use to reach this computer. */
public final class NetAddresses {
	private NetAddresses() {
	}

	/** This computer's address on the home network, or {@code null}. */
	public static String lanIpv4() {
		// "Connecting" a UDP socket sends nothing; it just makes the OS pick the outgoing interface.
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(InetAddress.getByName("192.0.2.1"), 9);
			String address = socket.getLocalAddress().getHostAddress();
			return "0.0.0.0".equals(address) ? null : address;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * A public (globally routable) IPv6 address of this computer, or {@code null}. IPv6 has no NAT, so when
	 * the router's firewall lets it through, friends with IPv6 can connect straight here, even when the
	 * internet provider shares one IPv4 address between many homes (CGNAT).
	 */
	public static String globalIpv6() {
		try {
			for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) {
					continue;
				}
				for (InetAddress address : Collections.list(nic.getInetAddresses())) {
					if (address instanceof Inet6Address v6 && isGlobal(v6)) {
						String text = v6.getHostAddress();
						int zone = text.indexOf('%');
						return zone >= 0 ? text.substring(0, zone) : text;
					}
				}
			}
		} catch (SocketException ignored) {
			// No usable interfaces.
		}
		return null;
	}

	private static boolean isGlobal(Inet6Address address) {
		if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
				|| address.isAnyLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte first = address.getAddress()[0];
		// Global unicast is 2000::/3; this also rules out unique-local fc00::/7 addresses.
		return (first & 0xE0) == 0x20;
	}
}

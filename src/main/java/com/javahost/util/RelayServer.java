package com.javahost.util;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The JavaHost relay: a meeting point for two players whose routers won't let them connect directly. Each sends its
 * datagrams here and they are passed on to the other, unread (the game's traffic inside is encrypted by Minecraft,
 * and the relay never has the keys). It needs nothing but Java and one open UDP port:
 *
 * <pre>java RelayServer.java 25580</pre>
 *
 * <p>How it stays out of trouble on the open internet:
 * <ul>
 * <li>A player joins a room (a session number both games agreed on privately) only after echoing a cookie sent to
 * its address, so nobody can make the relay send to an address that isn't theirs.</li>
 * <li>A room holds two players. Datagrams go only between those two, one out per one in: no amplification.</li>
 * <li>Each room has a data allowance (a burst, then a steady rate), rooms close after a minute of silence, and there
 * are caps on rooms in total and per address.</li>
 * <li>Nothing about players is written down. The log has counts only.</li>
 * </ul>
 * This file stands alone on purpose (no other JavaHost classes), so it can be copied to a server and run as is.
 */
public final class RelayServer implements AutoCloseable {
	static final byte MAGIC = 'J';
	static final byte JOIN = 4;
	static final byte OK = 5;
	static final byte CHALLENGE = 6;
	static final byte PING = 7;
	static final byte PONG = 8;
	private static final int MAX_ROOMS = 4000;
	private static final int MAX_ROOMS_PER_ADDRESS = 40;
	private static final long IDLE_MS = 60_000;
	/** Each room may send this much at once, then this much a second (enough for a join's burst of chunks, then play). */
	private static final long BURST_BYTES = 8L * 1024 * 1024;
	private static final long BYTES_PER_SECOND = 400L * 1024;

	private static final class Room {
		InetSocketAddress first;
		InetSocketAddress second;
		long activeAt;
		long allowance = BURST_BYTES;
		long refilledAt;
	}

	private final DatagramSocket socket;
	private final Map<Integer, Room> rooms = new HashMap<>();
	private final Map<InetAddress, Integer> roomsPerAddress = new HashMap<>();
	private final byte[] secret = new byte[32];
	private volatile boolean closed;
	private long forwarded;
	private long forwardedBytes;
	private long dropped;

	public RelayServer(int port) throws IOException {
		socket = new DatagramSocket(port);
		socket.setSoTimeout(1000);
		try {
			socket.setReceiveBufferSize(4 << 20);
			socket.setSendBufferSize(4 << 20);
		} catch (IOException e) {
			// the defaults will do
		}
		new SecureRandom().nextBytes(secret);
	}

	public int port() {
		return socket.getLocalPort();
	}

	public synchronized int rooms() {
		return rooms.size();
	}

	public synchronized long forwarded() {
		return forwarded;
	}

	public synchronized long dropped() {
		return dropped;
	}

	/** Runs the relay on a thread of its own. */
	public RelayServer start() {
		Thread thread = new Thread(this::run, "JavaHost relay");
		thread.setDaemon(true);
		thread.start();
		return this;
	}

	/** Runs the relay until it's closed. */
	public void run() {
		byte[] buffer = new byte[2048];
		DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
		long tidiedAt = System.currentTimeMillis();
		while (!closed) {
			try {
				packet.setLength(buffer.length);
				socket.receive(packet);
				received(buffer, packet.getLength(), (InetSocketAddress) packet.getSocketAddress());
			} catch (SocketTimeoutException e) {
				// time to tidy up
			} catch (IOException e) {
				if (closed) {
					break;
				}
			} catch (RuntimeException e) {
				// one odd datagram must not stop the relay
			}
			long now = System.currentTimeMillis();
			if (now - tidiedAt >= 1000) {
				tidiedAt = now;
				tidy(now);
			}
		}
		socket.close();
	}

	private synchronized void received(byte[] data, int length, InetSocketAddress from) throws IOException {
		if (length < 6 || data[0] != MAGIC) {
			return;
		}
		byte type = data[1];
		if (type == PING) {
			data[1] = PONG;
			socket.send(new DatagramPacket(data, Math.min(length, 16), from)); // never bigger than what came in
			return;
		}
		int id = ByteBuffer.wrap(data, 2, 4).getInt();
		long now = System.currentTimeMillis();
		if (type == JOIN) {
			byte[] cookie = cookie(from, now / 30_000);
			boolean proven = length >= 14 && (same(data, 6, cookie) || same(data, 6, cookie(from, now / 30_000 - 1)));
			if (!proven) {
				// Send the cookie to where they say they are; only someone really there can send it back.
				byte[] challenge = new byte[14];
				header(challenge, CHALLENGE, id);
				System.arraycopy(cookie, 0, challenge, 6, 8);
				socket.send(new DatagramPacket(challenge, challenge.length, from));
				return;
			}
			Room room = rooms.get(id);
			if (room == null) {
				if (rooms.size() >= MAX_ROOMS || roomsPerAddress.getOrDefault(from.getAddress(), 0) >= MAX_ROOMS_PER_ADDRESS) {
					dropped++;
					return;
				}
				room = new Room();
				room.refilledAt = now;
				rooms.put(id, room);
			}
			if (!from.equals(room.first) && !from.equals(room.second)) {
				if (room.first == null) {
					room.first = from;
				} else if (room.second == null) {
					room.second = from;
				} else {
					dropped++;
					return; // two players already: not for anyone else
				}
				roomsPerAddress.merge(from.getAddress(), 1, Integer::sum);
			}
			room.activeAt = now;
			byte[] ok = new byte[6];
			header(ok, OK, id);
			socket.send(new DatagramPacket(ok, ok.length, from));
			return;
		}
		if (type < 1 || type > 3) {
			return;
		}
		Room room = rooms.get(id);
		InetSocketAddress to = room == null ? null : from.equals(room.first) ? room.second : from.equals(room.second) ? room.first : null;
		if (to == null) {
			dropped++;
			return;
		}
		room.allowance = Math.min(BURST_BYTES, room.allowance + (now - room.refilledAt) * BYTES_PER_SECOND / 1000);
		room.refilledAt = now;
		if (room.allowance < length) {
			dropped++; // over its allowance: the games slow down when datagrams go missing
			return;
		}
		room.allowance -= length;
		room.activeAt = now;
		forwarded++;
		forwardedBytes += length;
		socket.send(new DatagramPacket(data, length, to));
	}

	private synchronized void tidy(long now) {
		Iterator<Room> all = rooms.values().iterator();
		while (all.hasNext()) {
			Room room = all.next();
			if (now - room.activeAt > IDLE_MS) {
				all.remove();
				for (InetSocketAddress member : new InetSocketAddress[] {room.first, room.second}) {
					if (member != null) {
						roomsPerAddress.computeIfPresent(member.getAddress(), (address, count) -> count <= 1 ? null : count - 1);
					}
				}
			}
		}
	}

	private byte[] cookie(InetSocketAddress who, long period) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			mac.update(who.getAddress().getAddress());
			mac.update(ByteBuffer.allocate(12).putInt(who.getPort()).putLong(period).array());
			return mac.doFinal();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean same(byte[] data, int at, byte[] cookie) {
		int difference = 0;
		for (int i = 0; i < 8; i++) {
			difference |= data[at + i] ^ cookie[i];
		}
		return difference == 0;
	}

	private static void header(byte[] packet, byte type, int id) {
		packet[0] = MAGIC;
		packet[1] = type;
		ByteBuffer.wrap(packet, 2, 4).putInt(id);
	}

	@Override
	public void close() {
		closed = true;
	}

	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 25580;
		RelayServer relay = new RelayServer(port);
		System.out.println("JavaHost relay listening on UDP port " + relay.port() + ". Open that port in the firewall; Ctrl+C stops it.");
		Thread stats = new Thread(() -> {
			long before = 0;
			while (true) {
				try {
					Thread.sleep(10 * 60 * 1000L);
				} catch (InterruptedException e) {
					return;
				}
				long megabytes;
				int rooms;
				long dropped;
				synchronized (relay) {
					megabytes = relay.forwardedBytes / (1024 * 1024);
					rooms = relay.rooms.size();
					dropped = relay.dropped;
				}
				System.out.println(java.time.LocalDateTime.now().withNano(0) + "  players connected through here: " + rooms + " pairs, "
						+ (megabytes - before) + " MB in the last 10 minutes, " + dropped + " datagrams refused so far");
				before = megabytes;
			}
		});
		stats.setDaemon(true);
		stats.start();
		relay.run();
	}
}

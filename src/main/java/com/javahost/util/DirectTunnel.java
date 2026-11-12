package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A direct player-to-player link: one UDP socket that reaches the other player straight through both home routers
 * (no relay in between), carrying the game's ordinary connection inside {@link Kcp}.
 *
 * <p>How two players behind routers meet: each asks a STUN server what its socket looks like from outside, they swap
 * those addresses (JavaHost does that over the friends' encrypted messages), and then both send to each other at
 * once. Each router sees its own player talking to that address first, so it lets the answers in. This works unless
 * a router gives every destination a different port.
 *
 * <p>The host's end ({@link #host}) passes each player's stream on to the game's own port on this computer; the
 * guest's end ({@link #guest}) opens a port on this computer for the game to connect to. Only someone holding a
 * session's token (sent in the sealed message) can open or use it.
 */
public final class DirectTunnel implements AutoCloseable {
	private static final byte MAGIC = 'J';
	private static final byte PROBE = 1;
	private static final byte DATA = 2;
	private static final byte CLOSE = 3;
	private static final int TOKEN = 16;
	private static final int DATA_HEADER = 10; // magic, type, session 4, first 4 of the token
	private static final String[][] STUN_SERVERS = {{"stun.cloudflare.com", "3478"}, {"stun.l.google.com", "19302"}, {"stun1.l.google.com", "19302"}};
	private static final long PROBE_FOR_MS = 12_000;
	private static final long SILENT_MS = 30_000;

	private final DatagramSocket socket;
	/** Where the host's end hands streams to (the game's port here); 0 on a guest's end. */
	private final int serverPort;
	private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
	private volatile boolean closed;
	private volatile InetSocketAddress publicAddress;
	private volatile boolean portVaries;
	private volatile long publicAddressAt;
	private volatile byte[][] stunRequests = new byte[0][];
	private volatile CompletableFuture<InetSocketAddress> stunWaiting;
	private final InetSocketAddress[] stunAnswers = new InetSocketAddress[STUN_SERVERS.length];

	private final class Session {
		final int id;
		final byte[] token;
		final List<InetSocketAddress> candidates;
		final long startedAt = System.currentTimeMillis();
		final CompletableFuture<Integer> ready = new CompletableFuture<>();
		final Kcp kcp;
		volatile InetSocketAddress remote;
		volatile long heardAt = System.currentTimeMillis();
		volatile long probedAt;
		volatile Socket stream;
		volatile ServerSocket listener;
		volatile boolean over;

		Session(int id, byte[] token, List<InetSocketAddress> candidates) {
			this.id = id;
			this.token = token;
			this.candidates = List.copyOf(candidates);
			this.kcp = new Kcp((data, length) -> {
				InetSocketAddress to = remote;
				if (to != null) {
					byte[] packet = new byte[DATA_HEADER + length];
					header(packet, DATA, id);
					System.arraycopy(token, 0, packet, 6, 4);
					System.arraycopy(data, 0, packet, DATA_HEADER, length);
					transmit(packet, to);
				}
			});
		}
	}

	private DirectTunnel(int serverPort) throws IOException {
		this.serverPort = serverPort;
		socket = new DatagramSocket(0);
		socket.setSoTimeout(5);
		try {
			socket.setReceiveBufferSize(1 << 20);
			socket.setSendBufferSize(1 << 20);
		} catch (IOException e) {
			// the defaults will do
		}
		IoExecutor.NET.execute(this::loop);
	}

	/** The host's end: players who get through are connected to the game listening on {@code serverPort} here. */
	public static DirectTunnel host(int serverPort) throws IOException {
		return new DirectTunnel(serverPort);
	}

	/** A guest's end. */
	public static DirectTunnel guest() throws IOException {
		return new DirectTunnel(0);
	}

	public int localPort() {
		return socket.getLocalPort();
	}

	// ---------------------------------------------------------------- addresses

	/**
	 * Asks STUN servers how this socket looks from the internet. Completes with null if none answers within a
	 * couple of seconds. A recent answer is reused.
	 */
	public CompletableFuture<InetSocketAddress> findPublicAddress() {
		if (publicAddress != null && System.currentTimeMillis() - publicAddressAt < 15_000) {
			return CompletableFuture.completedFuture(publicAddress);
		}
		CompletableFuture<InetSocketAddress> waiting = new CompletableFuture<>();
		stunWaiting = waiting;
		byte[][] requests = new byte[STUN_SERVERS.length][];
		synchronized (stunAnswers) {
			java.util.Arrays.fill(stunAnswers, null);
		}
		stunRequests = requests;
		IoExecutor.NET.execute(() -> {
			for (int round = 0; round < 3 && !waiting.isDone(); round++) {
				for (int i = 0; i < STUN_SERVERS.length; i++) {
					try {
						// IPv4 on purpose: that's the address a router rewrites.
						InetAddress server = null;
						for (InetAddress address : InetAddress.getAllByName(STUN_SERVERS[i][0])) {
							if (address instanceof Inet4Address) {
								server = address;
								break;
							}
						}
						if (server != null) {
							if (requests[i] == null) {
								requests[i] = Stun.request();
							}
							transmit(requests[i], new InetSocketAddress(server, Integer.parseInt(STUN_SERVERS[i][1])));
						}
					} catch (IOException | RuntimeException e) {
						// that server can't be reached: the others may
					}
				}
				try {
					Thread.sleep(600);
				} catch (InterruptedException e) {
					return;
				}
				stunDone(false);
			}
			stunDone(true);
		});
		return waiting;
	}

	private void stunDone(boolean giveUp) {
		CompletableFuture<InetSocketAddress> waiting = stunWaiting;
		if (waiting == null || waiting.isDone()) {
			return;
		}
		InetSocketAddress first = null;
		int answers = 0;
		boolean varies = false;
		synchronized (stunAnswers) {
			for (InetSocketAddress answer : stunAnswers) {
				if (answer != null) {
					answers++;
					if (first == null) {
						first = answer;
					} else if (!first.equals(answer)) {
						varies = true;
					}
				}
			}
		}
		if (answers >= 2 || (giveUp && answers >= 1) || giveUp) {
			portVaries = varies;
			publicAddress = first;
			publicAddressAt = System.currentTimeMillis();
			waiting.complete(first);
		}
	}

	/** Whether the router gave different servers different ports: then a direct link usually can't be made. */
	public boolean portVaries() {
		return portVaries;
	}

	/**
	 * Every address the other player could try for this socket, "address:port" each: the public one (if known), then
	 * this computer's own addresses (for someone on the same network, or with IPv6).
	 */
	public List<String> candidates() {
		List<String> out = new ArrayList<>();
		InetSocketAddress known = publicAddress;
		if (known != null) {
			out.add(format(known));
		}
		try {
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
					continue;
				}
				for (InetAddress address : Collections.list(network.getInetAddresses())) {
					boolean usable = address instanceof Inet4Address ? address.isSiteLocalAddress()
							: address instanceof Inet6Address && !address.isLinkLocalAddress() && !address.isSiteLocalAddress()
									&& !address.isLoopbackAddress() && (address.getAddress()[0] & 0xE0) == 0x20; // global IPv6
					String text = usable ? format(new InetSocketAddress(address, localPort())) : null;
					if (text != null && !out.contains(text) && out.size() < 8) {
						out.add(text);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// the public address alone will have to do
		}
		return out;
	}

	private static String format(InetSocketAddress address) {
		String host = address.getAddress().getHostAddress();
		int zone = host.indexOf('%');
		if (zone >= 0) {
			host = host.substring(0, zone);
		}
		return (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
	}

	/** Reads "address:port" (IPv6 in brackets); null unless it's a plain numeric address and a real port. */
	public static InetSocketAddress parse(String text) {
		if (text == null || text.length() > 64) {
			return null;
		}
		int colon = text.lastIndexOf(':');
		if (colon <= 0) {
			return null;
		}
		String host = text.substring(0, colon);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		if (!host.matches("[0-9.]{7,15}") && !host.matches("[0-9a-fA-F:.]{2,45}")) {
			return null; // numbers only: never look a name up
		}
		try {
			int port = Integer.parseInt(text.substring(colon + 1));
			if (port < 1 || port > 65535) {
				return null;
			}
			InetAddress address = InetAddress.getByName(host);
			return address.isAnyLocalAddress() || address.isMulticastAddress() ? null : new InetSocketAddress(address, port);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- sessions

	/**
	 * Host: a player with this session and token is about to come through from one of {@code theirAddresses}. Starts
	 * sending toward them so the router lets them in; their stream is connected to the game when they arrive.
	 */
	public void expect(int id, byte[] token, List<InetSocketAddress> theirAddresses) {
		if (serverPort == 0 || token.length != TOKEN || sessions.size() >= 64) {
			return;
		}
		sessions.putIfAbsent(id, new Session(id, token.clone(), theirAddresses));
	}

	/**
	 * Guest: reaches the host at one of {@code theirAddresses}. Completes with a port on this computer to connect the
	 * game to, or fails if the host can't be reached directly in time.
	 */
	public CompletableFuture<Integer> connect(int id, byte[] token, List<InetSocketAddress> theirAddresses) {
		Session session = new Session(id, token.clone(), theirAddresses);
		sessions.put(id, session);
		return session.ready;
	}

	/** How long the round trip to the other player is on session {@code id}, in ms (0 = not known yet, -1 = no such session). */
	public int roundTrip(int id) {
		Session session = sessions.get(id);
		return session == null ? -1 : session.kcp.roundTrip();
	}

	/** Sessions that are connected right now. */
	public int connected() {
		int count = 0;
		for (Session session : sessions.values()) {
			if (session.remote != null && !session.over) {
				count++;
			}
		}
		return count;
	}

	// ---------------------------------------------------------------- the socket

	private static int now() {
		return (int) (System.nanoTime() / 1_000_000L);
	}

	private static void header(byte[] packet, byte type, int id) {
		packet[0] = MAGIC;
		packet[1] = type;
		packet[2] = (byte) (id >> 24);
		packet[3] = (byte) (id >> 16);
		packet[4] = (byte) (id >> 8);
		packet[5] = (byte) id;
	}

	private void transmit(byte[] packet, InetSocketAddress to) {
		try {
			socket.send(new DatagramPacket(packet, packet.length, to));
		} catch (IOException | RuntimeException e) {
			// an address we can't send to (no IPv6 here, say): the others may work
		}
	}

	private void probe(Session session, InetSocketAddress to, boolean answer) {
		byte[] packet = new byte[6 + TOKEN + 1];
		header(packet, PROBE, session.id);
		System.arraycopy(session.token, 0, packet, 6, TOKEN);
		packet[6 + TOKEN] = (byte) (answer ? 1 : 0);
		transmit(packet, to);
	}

	private void loop() {
		byte[] buffer = new byte[2048];
		DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
		long tickedAt = 0;
		while (!closed) {
			try {
				packet.setLength(buffer.length);
				socket.receive(packet);
				received(buffer, packet.getLength(), (InetSocketAddress) packet.getSocketAddress());
			} catch (SocketTimeoutException e) {
				// time for the housekeeping below
			} catch (IOException e) {
				if (closed) {
					break;
				}
			} catch (RuntimeException e) {
				// one bad datagram must not end the link
			}
			long time = System.currentTimeMillis();
			if (time - tickedAt >= 10) {
				tickedAt = time;
				for (Session session : sessions.values()) {
					tick(session, time);
				}
			}
		}
		for (Session session : sessions.values()) {
			end(session, true); // say goodbye, so the other side hangs up now rather than after a silence
		}
		socket.close();
	}

	private void received(byte[] data, int length, InetSocketAddress from) {
		if (Stun.isStun(data, length)) {
			byte[][] requests = stunRequests;
			for (int i = 0; i < requests.length; i++) {
				InetSocketAddress mapped = requests[i] == null ? null : Stun.mapped(data, length, requests[i]);
				if (mapped != null) {
					synchronized (stunAnswers) {
						stunAnswers[i] = mapped;
					}
					stunDone(false);
				}
			}
			return;
		}
		if (length < 6 || data[0] != MAGIC) {
			return;
		}
		int id = ((data[2] & 0xFF) << 24) | ((data[3] & 0xFF) << 16) | ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
		Session session = sessions.get(id);
		if (session == null || session.over) {
			return;
		}
		byte type = data[1];
		if (type == PROBE || type == CLOSE) {
			if (length < 6 + TOKEN || !MessageDigest.isEqual(session.token, java.util.Arrays.copyOfRange(data, 6, 6 + TOKEN))) {
				return; // not from the player we're expecting
			}
			if (type == CLOSE) {
				end(session, false);
				return;
			}
			boolean answer = length > 6 + TOKEN && data[6 + TOKEN] == 1;
			session.heardAt = System.currentTimeMillis();
			if (serverPort != 0) {
				// Host: the player got through. Remember where from (also if their router moved them), and say so.
				boolean first = session.remote == null;
				session.remote = from;
				if (!answer) {
					probe(session, from, true);
				}
				if (first) {
					IoExecutor.NET.execute(() -> attachToGame(session));
				}
			} else if (answer && session.remote == null) {
				// Guest: the host heard us and this is its answer, so the way is open both ways.
				session.remote = from;
				IoExecutor.NET.execute(() -> listenForGame(session));
			} else if (!answer && session.remote == null) {
				probe(session, from, false); // the host's opening shot arrived: answer at once rather than on the next beat
			}
		} else if (type == DATA && length > DATA_HEADER && from.equals(session.remote)
				&& data[6] == session.token[0] && data[7] == session.token[1] && data[8] == session.token[2] && data[9] == session.token[3]) {
			session.heardAt = System.currentTimeMillis();
			int now = now();
			session.kcp.input(data, DATA_HEADER, length - DATA_HEADER, now);
			session.kcp.flush(now); // acknowledge straight away
			if (session.kcp.readable()) {
				synchronized (session) {
					session.notifyAll();
				}
			}
		}
	}

	private void tick(Session session, long time) {
		if (session.over) {
			if (time - session.heardAt > 5_000) {
				sessions.remove(session.id);
			}
			return;
		}
		if (session.remote == null) {
			if (time - session.startedAt > PROBE_FOR_MS) {
				session.ready.completeExceptionally(new IOException("No direct route to the other player"));
				end(session, false);
			} else if (time - session.probedAt >= 100) {
				session.probedAt = time;
				for (InetSocketAddress candidate : session.candidates) {
					probe(session, candidate, false);
				}
			}
			return;
		}
		session.kcp.flush(now());
		if (session.kcp.dead() || time - session.heardAt > SILENT_MS) {
			end(session, true);
		} else if (time - session.probedAt >= 5_000) {
			session.probedAt = time;
			probe(session, session.remote, serverPort != 0); // keeps the routers' doors open while the game is quiet
		}
	}

	// ---------------------------------------------------------------- the game's side

	private void attachToGame(Session session) {
		try {
			Socket stream = new Socket();
			stream.setTcpNoDelay(true);
			stream.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), 3000);
			session.stream = stream;
			pump(session, stream);
		} catch (IOException e) {
			end(session, true);
		}
	}

	private void listenForGame(Session session) {
		try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			session.listener = listener;
			listener.setSoTimeout(20_000);
			session.ready.complete(listener.getLocalPort());
			Socket stream = listener.accept();
			stream.setTcpNoDelay(true);
			session.stream = stream;
			pump(session, stream);
		} catch (IOException e) {
			session.ready.completeExceptionally(e);
			end(session, true);
		}
	}

	/** Copies both ways between the game's connection and the link until either ends. */
	private void pump(Session session, Socket stream) {
		IoExecutor.NET.execute(() -> {
			byte[] buffer = new byte[16 * 1024];
			try (OutputStream out = stream.getOutputStream()) {
				while (!session.over) {
					int n = session.kcp.receive(buffer, 0, buffer.length);
					if (n > 0) {
						out.write(buffer, 0, n);
						out.flush();
					} else {
						synchronized (session) {
							if (!session.kcp.readable() && !session.over) {
								session.wait(50);
							}
						}
					}
				}
			} catch (IOException | InterruptedException e) {
				// the game closed its side
			}
			end(session, true);
		});
		byte[] buffer = new byte[16 * 1024];
		try (InputStream in = stream.getInputStream()) {
			int n;
			while (!session.over && (n = in.read(buffer)) >= 0) {
				while (session.kcp.pending() > 900 && !session.over) {
					Thread.sleep(2); // the line is busy: let it catch up rather than pile data up
				}
				session.kcp.send(buffer, 0, n);
				session.kcp.flush(now()); // on its way now, not on the next beat
			}
		} catch (IOException | InterruptedException e) {
			// the game closed its side
		}
		// Give what's queued a moment to get across before hanging up.
		long until = System.currentTimeMillis() + 1500;
		while (!session.over && session.kcp.pending() > 0 && System.currentTimeMillis() < until) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				break;
			}
		}
		end(session, true);
	}

	private void end(Session session, boolean tellThem) {
		if (session.over) {
			return;
		}
		session.over = true;
		session.heardAt = System.currentTimeMillis();
		InetSocketAddress to = session.remote;
		if (tellThem && to != null) {
			byte[] packet = new byte[6 + TOKEN];
			header(packet, CLOSE, session.id);
			System.arraycopy(session.token, 0, packet, 6, TOKEN);
			transmit(packet, to);
			transmit(packet, to); // twice: it's one small datagram, and a lost goodbye costs the other side 30 s
		}
		session.ready.completeExceptionally(new IOException("The direct link closed"));
		try {
			Socket stream = session.stream;
			if (stream != null) {
				stream.close();
			}
			ServerSocket listener = session.listener;
			if (listener != null) {
				listener.close();
			}
		} catch (IOException e) {
			// closing anyway
		}
		synchronized (session) {
			session.notifyAll();
		}
	}

	@Override
	public void close() {
		closed = true;
	}

	public boolean isClosed() {
		return closed;
	}
}

package com.javahost.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * A reliable, ordered byte stream over datagrams that may be lost, repeated or shuffled: what carries the game's
 * connection on a direct player-to-player link. It follows the design of KCP (github.com/skywind3000/kcp, MIT):
 * every piece is numbered and acknowledged, a piece is sent again quickly once later ones have been acknowledged
 * past it (no waiting for a timer), and the pace follows how much the other side says it can take. That keeps the
 * delay low on a lossy line, where plain TCP stalls.
 *
 * <p>Not a network class: it's handed incoming datagrams ({@link #input}), asked to act now and then
 * ({@link #flush}), and gives its outgoing datagrams to {@link Output}. Times are milliseconds on any steady clock.
 * All methods are synchronized, so one thread can feed it while others read and write the stream.
 */
public final class Kcp {
	public interface Output {
		void send(byte[] datagram, int length);
	}

	/** The biggest datagram this produces. */
	public static final int MTU = 1180;
	private static final int HEADER = 17; // command 1, window 2, time 4, number 4, received-up-to 4, length 2
	private static final int MSS = MTU - HEADER;
	private static final byte PUSH = 1;
	private static final byte ACK = 2;
	private static final byte ASK = 3;
	private static final byte TELL = 4;
	private static final int WINDOW = 512;
	private static final int MIN_PACE = 16;
	private static final int MIN_RTO = 50;
	private static final int MAX_RTO = 4000;
	private static final int FAST_RESEND = 2;
	private static final int DEAD_AFTER = 40;

	private static final class Segment {
		final byte[] data;
		int number;
		int sentAt;
		int resendAt;
		int rto;
		int skipped;
		int sends;

		Segment(byte[] data) {
			this.data = data;
		}
	}

	private final Output output;
	private final ArrayDeque<byte[]> waiting = new ArrayDeque<>();
	private final ArrayList<Segment> sent = new ArrayList<>();
	private final TreeMap<Integer, byte[]> early = new TreeMap<>();
	private final ArrayDeque<byte[]> ready = new ArrayDeque<>();
	private int readyOffset;
	private int[] acks = new int[64];
	private int ackCount;
	private int oldestUnacked;
	private int nextNumber;
	private int nextExpected;
	private int theirWindow = 32;
	private float pace = 32;
	private float threshold = WINDOW;
	private int smoothedRtt;
	private int rttVariance;
	private int rto = 200;
	private int probeAt;
	private int probeWait;
	private boolean tell;
	private boolean windowWasShut;
	private boolean dead;
	private final byte[] packet = new byte[MTU];
	private int packetLength;

	public Kcp(Output output) {
		this.output = output;
	}

	// ---------------------------------------------------------------- the stream

	/** Queues bytes to send. */
	public synchronized void send(byte[] data, int offset, int length) {
		while (length > 0) {
			int size = Math.min(MSS, length);
			byte[] piece = new byte[size];
			System.arraycopy(data, offset, piece, 0, size);
			waiting.add(piece);
			offset += size;
			length -= size;
		}
	}

	/** Copies received bytes into {@code out}; returns how many (0 if none have arrived). */
	public synchronized int receive(byte[] out, int offset, int length) {
		int copied = 0;
		while (copied < length && !ready.isEmpty()) {
			byte[] piece = ready.peek();
			int size = Math.min(length - copied, piece.length - readyOffset);
			System.arraycopy(piece, readyOffset, out, offset + copied, size);
			copied += size;
			readyOffset += size;
			if (readyOffset == piece.length) {
				ready.poll();
				readyOffset = 0;
			}
		}
		if (copied > 0) {
			deliverInOrder();
			if (windowWasShut && ready.size() < WINDOW / 2) {
				windowWasShut = false;
				tell = true; // the sender stopped because we were full: say there's room again
			}
		}
		return copied;
	}

	/** Pieces queued or sent but not yet acknowledged: the writer should wait while this is large. */
	public synchronized int pending() {
		return waiting.size() + sent.size();
	}

	/** Whether there is anything to read. */
	public synchronized boolean readable() {
		return !ready.isEmpty();
	}

	/** The other side has stopped answering (a piece went unacknowledged through {@value #DEAD_AFTER} sends). */
	public synchronized boolean dead() {
		return dead;
	}

	/** The measured round trip, in milliseconds (0 until something has been acknowledged). */
	public synchronized int roundTrip() {
		return smoothedRtt;
	}

	// ---------------------------------------------------------------- incoming

	/** A datagram arrived from the other side. */
	public synchronized void input(byte[] data, int offset, int length, int now) {
		int end = offset + length;
		int before = oldestUnacked;
		int highestAck = 0;
		boolean anyAck = false;
		while (end - offset >= HEADER) {
			byte command = data[offset];
			int window = ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
			int time = readInt(data, offset + 3);
			int number = readInt(data, offset + 7);
			int upTo = readInt(data, offset + 11);
			int size = ((data[offset + 15] & 0xFF) << 8) | (data[offset + 16] & 0xFF);
			offset += HEADER;
			if (size > end - offset || command < PUSH || command > TELL) {
				return; // not ours, or cut short
			}
			theirWindow = window;
			acknowledgedBelow(upTo);
			if (command == ACK) {
				if (now - time >= 0) {
					measured(now - time);
				}
				acknowledged(number);
				if (!anyAck || number - highestAck > 0) {
					highestAck = number;
				}
				anyAck = true;
			} else if (command == PUSH) {
				if (number - (nextExpected + WINDOW) < 0) {
					noteAck(number, time);
					if (number - nextExpected >= 0 && !early.containsKey(number)) {
						byte[] piece = new byte[size];
						System.arraycopy(data, offset, piece, 0, size);
						early.put(number, piece);
						deliverInOrder();
					}
				}
			} else if (command == ASK) {
				tell = true;
			}
			offset += size;
		}
		if (anyAck) {
			for (Segment segment : sent) {
				if (segment.number - highestAck < 0) {
					segment.skipped++; // something sent after it has arrived: it is probably lost
				} else {
					break;
				}
			}
		}
		oldestUnacked = sent.isEmpty() ? nextNumber : sent.get(0).number;
		int progressed = oldestUnacked - before;
		if (progressed > 0) {
			// Speed up while things arrive: quickly at first, then a little per round trip.
			pace = pace < threshold ? pace + progressed : pace + progressed / pace;
			pace = Math.min(pace, WINDOW);
		}
	}

	private void acknowledgedBelow(int upTo) {
		int remove = 0;
		while (remove < sent.size() && sent.get(remove).number - upTo < 0) {
			remove++;
		}
		if (remove > 0) {
			sent.subList(0, remove).clear();
		}
	}

	private void acknowledged(int number) {
		for (int i = 0; i < sent.size(); i++) {
			int difference = sent.get(i).number - number;
			if (difference == 0) {
				sent.remove(i);
				return;
			}
			if (difference > 0) {
				return;
			}
		}
	}

	private void measured(int rtt) {
		if (smoothedRtt == 0) {
			smoothedRtt = Math.max(1, rtt);
			rttVariance = rtt / 2;
		} else {
			rttVariance = (3 * rttVariance + Math.abs(rtt - smoothedRtt)) / 4;
			smoothedRtt = Math.max(1, (7 * smoothedRtt + rtt) / 8);
		}
		rto = Math.max(MIN_RTO, Math.min(MAX_RTO, smoothedRtt + Math.max(10, 4 * rttVariance)));
	}

	private void noteAck(int number, int time) {
		if (ackCount * 2 + 2 > acks.length) {
			acks = java.util.Arrays.copyOf(acks, acks.length * 2);
		}
		acks[ackCount * 2] = number;
		acks[ackCount * 2 + 1] = time;
		ackCount++;
	}

	private void deliverInOrder() {
		while (!early.isEmpty() && ready.size() < WINDOW) {
			byte[] next = early.remove(nextExpected);
			if (next == null) {
				break;
			}
			ready.add(next);
			nextExpected++;
		}
	}

	// ---------------------------------------------------------------- outgoing

	/** Sends what's due: acknowledgements, new pieces as the pace allows, and pieces that look lost. Call every 10 ms or so. */
	public synchronized void flush(int now) {
		int room = Math.max(0, WINDOW - ready.size());
		if (room == 0) {
			windowWasShut = true;
		}
		for (int i = 0; i < ackCount; i++) {
			write(ACK, room, acks[i * 2 + 1], acks[i * 2], null);
		}
		ackCount = 0;

		// The other side is full: ask now and then whether that has changed, in case its word got lost.
		if (theirWindow == 0) {
			if (probeWait == 0) {
				probeWait = 100;
				probeAt = now + probeWait;
			} else if (now - probeAt >= 0) {
				probeWait = Math.min(probeWait * 2, 2000);
				probeAt = now + probeWait;
				write(ASK, room, now, 0, null);
			}
		} else {
			probeWait = 0;
		}
		if (tell) {
			tell = false;
			write(TELL, room, now, 0, null);
		}

		int allowed = Math.min(Math.min(WINDOW, theirWindow), (int) pace);
		while (!waiting.isEmpty() && nextNumber - (oldestUnacked + allowed) < 0) {
			Segment segment = new Segment(waiting.poll());
			segment.number = nextNumber++;
			sent.add(segment);
		}

		boolean timedOut = false;
		int resentFast = 0;
		for (Segment segment : sent) {
			boolean send = false;
			if (segment.sends == 0) {
				send = true;
				segment.rto = rto;
				segment.resendAt = now + segment.rto + (rto >> 3);
			} else if (now - segment.resendAt >= 0) {
				send = true;
				segment.rto += Math.max(segment.rto, rto) / 2;
				segment.resendAt = now + Math.min(segment.rto, MAX_RTO);
				timedOut = true;
			} else if (segment.skipped >= FAST_RESEND) {
				send = true;
				segment.skipped = 0;
				segment.resendAt = now + segment.rto;
				resentFast++;
			}
			if (send) {
				segment.sends++;
				segment.sentAt = now;
				write(PUSH, room, now, segment.number, segment.data);
				if (segment.sends >= DEAD_AFTER) {
					dead = true;
				}
			}
		}
		emit();

		// Losses mean we're sending faster than the line carries: ease off (but never to a crawl, this is a game).
		if (resentFast > 0) {
			threshold = Math.max(MIN_PACE, (nextNumber - oldestUnacked) / 2f);
			pace = threshold + resentFast;
		}
		if (timedOut) {
			threshold = Math.max(MIN_PACE, pace / 2);
			pace = Math.max(MIN_PACE, pace / 2);
		}
	}

	private void write(byte command, int window, int time, int number, byte[] data) {
		int size = data == null ? 0 : data.length;
		if (packetLength + HEADER + size > MTU) {
			emit();
		}
		int at = packetLength;
		packet[at] = command;
		packet[at + 1] = (byte) (window >> 8);
		packet[at + 2] = (byte) window;
		writeInt(packet, at + 3, time);
		writeInt(packet, at + 7, number);
		writeInt(packet, at + 11, nextExpected);
		packet[at + 15] = (byte) (size >> 8);
		packet[at + 16] = (byte) size;
		if (size > 0) {
			System.arraycopy(data, 0, packet, at + HEADER, size);
		}
		packetLength += HEADER + size;
	}

	private void emit() {
		if (packetLength > 0) {
			output.send(packet, packetLength);
			packetLength = 0;
		}
	}

	private static int readInt(byte[] data, int at) {
		return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
	}

	private static void writeInt(byte[] data, int at, int value) {
		data[at] = (byte) (value >> 24);
		data[at + 1] = (byte) (value >> 16);
		data[at + 2] = (byte) (value >> 8);
		data[at + 3] = (byte) value;
	}
}

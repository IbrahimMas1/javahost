package com.javahost.client.voice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.net.Payloads.VoiceConfig;
import com.javahost.net.Payloads.VoiceDown;
import com.javahost.net.Payloads.VoiceDown2;
import com.javahost.net.Payloads.VoiceUp;
import com.javahost.net.Payloads.VoiceUp2;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Client half of proximity voice chat.
 *
 * <p>A microphone thread records 20 ms frames, encodes them with Opus (about 24 kbit/s) and sends them
 * while push-to-talk is held or the player is speaking. A speaker thread decodes everyone else's
 * frames through a small jitter buffer and mixes them into stereo, with volume falling off over
 * distance and each voice panned toward where that player stands.
 */
public final class VoiceClient {
	public static final int SAMPLE_RATE = 48000;
	public static final int FRAME_SAMPLES = 960; // 20 ms
	private static final AudioFormat MIC_FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
	private static final AudioFormat SPEAKER_FORMAT = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
	private static final int JITTER_FRAMES = 2;
	private static final int MAX_QUEUED_FRAMES = 8;
	private static final int VOICE_ACTIVATION_HANG_FRAMES = 20;

	private record Position(double x, double y, double z) {
	}

	private record Listener(double x, double y, double z, float yaw, Map<UUID, Position> players, Map<UUID, String> names) {
	}

	private static final class Speaker {
		final ArrayDeque<byte[]> queue = new ArrayDeque<>();
		final OpusDecoder decoder;
		boolean playing;
		int concealed;
		volatile long lastHeard;
		/** Their latest frame was party voice: play it at full volume, unpanned, wherever they are. */
		volatile boolean party;

		Speaker() throws OpusException {
			decoder = new OpusDecoder(SAMPLE_RATE, 1);
		}
	}

	private static volatile boolean serverHasVoice;
	private static volatile int range = 48;
	private static volatile boolean pushToTalkHeld;
	private static volatile boolean transmitting;
	private static volatile float micLevelDb = -100;
	private static volatile String micProblem;
	private static volatile Listener listener;
	private static volatile boolean testingMic;
	/** Automated tests send a generated tone instead of opening the real microphone. */
	private static volatile boolean testTone;
	/** Per speaker: how many clearly audible frames we've decoded (used by tests and the UI). */
	private static final Map<UUID, java.util.concurrent.atomic.AtomicInteger> AUDIBLE_FRAMES = new ConcurrentHashMap<>();
	private static final Map<UUID, Speaker> SPEAKERS = new ConcurrentHashMap<>();
	private static boolean started;

	private VoiceClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(VoiceConfig.TYPE, (config, context) -> {
			serverHasVoice = true;
			range = config.range();
		});
		ClientPlayNetworking.registerGlobalReceiver(VoiceDown.TYPE, (frame, context) -> receive(frame.speaker(), frame.opus(), false));
		ClientPlayNetworking.registerGlobalReceiver(VoiceDown2.TYPE, (frame, context) -> receive(frame.speaker(), frame.opus(), frame.party()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			serverHasVoice = false;
			listener = null;
			SPEAKERS.clear();
			AUDIBLE_FRAMES.clear();
		});
		ClientTickEvents.END_CLIENT_TICK.register(VoiceClient::tick);
	}

	// ---------------------------------------------------------------- state for the UI

	/** The host runs JavaHost 1.4+, so party voice works here. */
	public static boolean partyAvailable() {
		return serverHasVoice && ClientPlayNetworking.canSend(VoiceUp2.TYPE);
	}

	/** Talking to everyone in the world rather than just nearby players (only when the host supports it). */
	public static boolean partyMode() {
		return "PARTY".equals(Config.get().voiceMode) && partyAvailable();
	}

	/** Sends one microphone frame, as party or nearby voice, in the format the host understands. */
	private static void sendFrame(int sequence, byte[] opus) {
		if (ClientPlayNetworking.canSend(VoiceUp2.TYPE)) {
			ClientPlayNetworking.send(new VoiceUp2(sequence, partyMode(), opus));
		} else if (ClientPlayNetworking.canSend(VoiceUp.TYPE)) {
			ClientPlayNetworking.send(new VoiceUp(sequence, opus));
		}
	}

	public static boolean isAvailable() {
		return serverHasVoice;
	}

	public static boolean isTransmitting() {
		return transmitting;
	}

	public static float micLevelDb() {
		return micLevelDb;
	}

	public static String micProblem() {
		return micProblem;
	}

	/** Players heard in the last quarter second, with their names. */
	public static Map<UUID, String> talking() {
		Listener current = listener;
		Map<UUID, String> talking = new java.util.LinkedHashMap<>();
		if (current == null) {
			return talking;
		}
		long now = System.currentTimeMillis();
		SPEAKERS.forEach((id, speaker) -> {
			if (now - speaker.lastHeard < 250) {
				talking.put(id, current.names().getOrDefault(id, "Someone"));
			}
		});
		return talking;
	}

	/** Names of players heard in the last quarter second. */
	public static List<String> talkingNames() {
		Listener current = listener;
		List<String> names = new ArrayList<>();
		if (current == null) {
			return names;
		}
		long now = System.currentTimeMillis();
		SPEAKERS.forEach((id, speaker) -> {
			if (now - speaker.lastHeard < 250) {
				names.add(current.names().getOrDefault(id, "Someone"));
			}
		});
		return names;
	}

	public static void setPushToTalkHeld(boolean held) {
		pushToTalkHeld = held;
	}

	public static void setTestTone(boolean enabled) {
		testTone = enabled;
		if (enabled) {
			ensureThreadsStarted();
		}
	}

	/** Players we can currently hear or who are near enough to (for the volume list): id -> name. */
	public static Map<UUID, String> nearbyPlayers() {
		Listener current = listener;
		Map<UUID, String> nearby = new java.util.LinkedHashMap<>();
		if (current == null) {
			return nearby;
		}
		current.players().forEach((id, position) -> {
			double dx = position.x() - current.x();
			double dy = position.y() - current.y();
			double dz = position.z() - current.z();
			if (dx * dx + dy * dy + dz * dz <= (double) range * range && current.names().containsKey(id)
					&& !id.equals(net.minecraft.client.Minecraft.getInstance().player.getUUID())) {
				nearby.put(id, current.names().get(id));
			}
		});
		return nearby;
	}

	/** Whether this player was heard in the last quarter second (for the nametag speaker icon). */
	public static boolean isTalking(UUID player) {
		Speaker speaker = SPEAKERS.get(player);
		return speaker != null && System.currentTimeMillis() - speaker.lastHeard < 250
				&& !Config.get().mutedPlayers.contains(player.toString());
	}

	/** Number of audible voice frames decoded from this player since joining. */
	public static int audibleFramesFrom(UUID speaker) {
		java.util.concurrent.atomic.AtomicInteger count = AUDIBLE_FRAMES.get(speaker);
		return count == null ? 0 : count.get();
	}

	/** Keeps the microphone open while the settings screen shows its level meter, even outside a world. */
	public static void setTestingMic(boolean testing) {
		testingMic = testing;
		if (testing) {
			ensureThreadsStarted();
		}
	}

	/** Microphones the player can pick from in settings. */
	public static List<String> microphones() {
		List<String> names = new ArrayList<>();
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, MIC_FORMAT);
		for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
			if (AudioSystem.getMixer(mixer).isLineSupported(info)) {
				names.add(mixer.getName());
			}
		}
		return names;
	}

	// ---------------------------------------------------------------- game thread

	private static void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null || !serverHasVoice) {
			listener = null;
			return;
		}
		ensureThreadsStarted();
		Map<UUID, Position> players = new HashMap<>();
		Map<UUID, String> names = new HashMap<>();
		for (AbstractClientPlayer player : minecraft.level.players()) {
			players.put(player.getUUID(), new Position(player.getX(), player.getEyeY(), player.getZ()));
			names.put(player.getUUID(), player.getPlainTextName());
		}
		listener = new Listener(minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ(),
				minecraft.player.getYRot(), players, names);

		long now = System.currentTimeMillis();
		SPEAKERS.values().removeIf(speaker -> now - speaker.lastHeard > 30_000);
	}

	private static void receive(UUID from, byte[] opus, boolean party) {
		Speaker speaker = SPEAKERS.computeIfAbsent(from, id -> {
			try {
				return new Speaker();
			} catch (OpusException e) {
				throw new IllegalStateException(e);
			}
		});
		speaker.party = party;
		synchronized (speaker.queue) {
			speaker.queue.add(opus);
			while (speaker.queue.size() > MAX_QUEUED_FRAMES) {
				// We fell behind (e.g. a lag spike); drop old audio rather than drift further behind.
				speaker.queue.poll();
			}
		}
		speaker.lastHeard = System.currentTimeMillis();
	}

	private static synchronized void ensureThreadsStarted() {
		if (started) {
			return;
		}
		started = true;
		Thread mic = new Thread(VoiceClient::microphoneLoop, "JavaHost Microphone");
		mic.setDaemon(true);
		mic.start();
		Thread speaker = new Thread(VoiceClient::speakerLoop, "JavaHost Speaker");
		speaker.setDaemon(true);
		speaker.start();
	}

	// ---------------------------------------------------------------- microphone thread

	private static boolean shouldRecord() {
		return (listener != null || testingMic) && Config.get().voiceEnabled && !testTone;
	}

	private static void microphoneLoop() {
		TargetDataLine line = null;
		String openedDevice = null;
		OpusEncoder encoder;
		try {
			encoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
			encoder.setBitrate(24000);
			encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
			encoder.setComplexity(5);
		} catch (OpusException e) {
			JavaHost.LOGGER.error("Voice encoder unavailable", e);
			micProblem = "Voice encoder failed to start";
			return;
		}

		byte[] raw = new byte[FRAME_SAMPLES * 2];
		short[] pcm = new short[FRAME_SAMPLES];
		byte[] packet = new byte[1024];
		int sequence = 0;
		int hang = 0;
		int silentFrames = 0;

		while (true) {
			Config config = Config.get();
			try {
				if (testTone) {
					if (line != null) {
						line.close();
						line = null;
					}
					Thread.sleep(20);
					for (int i = 0; i < FRAME_SAMPLES; i++) {
						pcm[i] = (short) (6000 * Math.sin(2 * Math.PI * 440 * (sequence * FRAME_SAMPLES + i) / SAMPLE_RATE));
					}
					transmitting = listener != null;
					if (transmitting) {
						int length = encoder.encode(pcm, 0, FRAME_SAMPLES, packet, 0, packet.length);
						sendFrame(sequence++, java.util.Arrays.copyOf(packet, length));
					}
					continue;
				}
				// Close the mic whenever voice isn't needed, so the OS "mic in use" light goes off.
				if (!shouldRecord() || (line != null && !config.microphone.equals(openedDevice))) {
					if (line != null) {
						line.close();
						line = null;
					}
					transmitting = false;
					micLevelDb = -100;
					if (!shouldRecord()) {
						Thread.sleep(100);
						continue;
					}
				}
				if (line == null) {
					line = openMicrophone(config.microphone);
					openedDevice = config.microphone;
					line.start();
					micProblem = null;
					silentFrames = 0;
				}

				int read = 0;
				while (read < raw.length) {
					int n = line.read(raw, read, raw.length - read);
					if (n <= 0) {
						break; // unplugged or stopped: read() returns at once, and looping would spin a CPU core
					}
					read += n;
				}
				if (read < raw.length) {
					line.close();
					line = null;
					micProblem = "The microphone stopped. Reconnecting...";
					Thread.sleep(500);
					continue;
				}

				double gain = config.micGain;
				double sumSquares = 0;
				boolean allZero = true;
				for (int i = 0; i < FRAME_SAMPLES; i++) {
					int sample = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
					if (sample != 0) {
						allZero = false;
					}
					sample = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample * gain));
					pcm[i] = (short) sample;
					sumSquares += (double) sample * sample;
				}
				double rms = Math.sqrt(sumSquares / FRAME_SAMPLES) / Short.MAX_VALUE;
				micLevelDb = (float) (rms > 0 ? 20 * Math.log10(rms) : -100);

				silentFrames = allZero ? silentFrames + 1 : 0;
				if (silentFrames == 150) {
					micProblem = "Microphone is completely silent. On macOS, allow microphone access for your Minecraft launcher in System Settings > Privacy & Security.";
				} else if (silentFrames == 0 && micProblem != null && micProblem.startsWith("Microphone is completely")) {
					micProblem = null;
				}

				boolean send;
				if (config.pushToTalk) {
					send = pushToTalkHeld;
				} else {
					if (micLevelDb > config.activationThresholdDb) {
						hang = VOICE_ACTIVATION_HANG_FRAMES;
					}
					send = hang-- > 0;
				}
				send &= listener != null && !config.muted;
				transmitting = send;
				if (send) {
					int length = encoder.encode(pcm, 0, FRAME_SAMPLES, packet, 0, packet.length);
					byte[] opus = new byte[length];
					System.arraycopy(packet, 0, opus, 0, length);
					sendFrame(sequence++, opus);
				}
			} catch (InterruptedException e) {
				return;
			} catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
				micProblem = "Can't open the microphone: " + e.getMessage();
				if (line != null) {
					line.close();
					line = null;
				}
				sleepQuietly(2000);
			} catch (Exception e) {
				// Usually a disconnect racing with a send; try again shortly.
				JavaHost.LOGGER.debug("Voice capture hiccup", e);
				sleepQuietly(50);
			}
		}
	}

	private static TargetDataLine openMicrophone(String deviceName) throws LineUnavailableException {
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, MIC_FORMAT);
		TargetDataLine line = null;
		if (!deviceName.isEmpty()) {
			for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
				if (mixer.getName().equals(deviceName) && AudioSystem.getMixer(mixer).isLineSupported(info)) {
					line = (TargetDataLine) AudioSystem.getMixer(mixer).getLine(info);
					break;
				}
			}
		}
		if (line == null) {
			line = (TargetDataLine) AudioSystem.getLine(info);
		}
		line.open(MIC_FORMAT, FRAME_SAMPLES * 2 * 4);
		return line;
	}

	// ---------------------------------------------------------------- speaker thread

	private static void speakerLoop() {
		SourceDataLine line = null;
		short[] decoded = new short[FRAME_SAMPLES];
		float[] mixL = new float[FRAME_SAMPLES];
		float[] mixR = new float[FRAME_SAMPLES];
		byte[] out = new byte[FRAME_SAMPLES * 4];

		while (true) {
			try {
				Listener here = listener;
				if (here == null) {
					if (line != null) {
						line.drain();
						line.close();
						line = null;
					}
					Thread.sleep(100);
					continue;
				}
				if (line == null) {
					line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, SPEAKER_FORMAT));
					line.open(SPEAKER_FORMAT, FRAME_SAMPLES * 4 * 4);
					line.start();
				}

				java.util.Arrays.fill(mixL, 0);
				java.util.Arrays.fill(mixR, 0);
				double volume = Config.get().outputVolume;

				for (Map.Entry<UUID, Speaker> entry : SPEAKERS.entrySet()) {
					Speaker speaker = entry.getValue();
					if (!nextFrame(speaker, decoded)) {
						continue;
					}
					// Muted players are dropped here (still decoded above, so their stream stays in sync).
					if (Config.get().mutedPlayers.contains(entry.getKey().toString())) {
						continue;
					}
					double energy = 0;
					for (short sample : decoded) {
						energy += sample * (double) sample;
					}
					if (Math.sqrt(energy / FRAME_SAMPLES) > 300) {
						AUDIBLE_FRAMES.computeIfAbsent(entry.getKey(), id -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
					}
					double playerVolume = Config.get().playerVolumes.getOrDefault(entry.getKey().toString(), 1.0);
					float[] gains;
					if (speaker.party) {
						// Party voice: same loudness from anywhere, straight down the middle.
						float gain = (float) (volume * playerVolume);
						gains = new float[] {gain, gain};
					} else {
						Position from = here.players().get(entry.getKey());
						if (from == null) {
							continue; // Not loaded on our side (other dimension or just left).
						}
						gains = spatialGains(here, from, volume * playerVolume);
					}
					for (int i = 0; i < FRAME_SAMPLES; i++) {
						mixL[i] += decoded[i] * gains[0];
						mixR[i] += decoded[i] * gains[1];
					}
				}

				for (int i = 0; i < FRAME_SAMPLES; i++) {
					short l = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixL[i]));
					short r = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixR[i]));
					out[i * 4] = (byte) l;
					out[i * 4 + 1] = (byte) (l >> 8);
					out[i * 4 + 2] = (byte) r;
					out[i * 4 + 3] = (byte) (r >> 8);
				}
				// Blocks once the device buffer is full, which paces this loop at real time.
				line.write(out, 0, out.length);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				JavaHost.LOGGER.warn("Voice playback problem; retrying", e);
				if (line != null) {
					line.close();
					line = null;
				}
				sleepQuietly(2000);
			}
		}
	}

	/** Pulls the next 20 ms of a speaker's voice, covering a lost frame or two with Opus concealment. */
	private static boolean nextFrame(Speaker speaker, short[] into) throws OpusException {
		byte[] packet;
		synchronized (speaker.queue) {
			if (!speaker.playing) {
				if (speaker.queue.size() < JITTER_FRAMES) {
					return false;
				}
				speaker.playing = true;
			}
			packet = speaker.queue.poll();
		}
		if (packet != null) {
			speaker.concealed = 0;
			speaker.decoder.decode(packet, 0, packet.length, into, 0, FRAME_SAMPLES, false);
			return true;
		}
		if (speaker.concealed < 3) {
			speaker.concealed++;
			speaker.decoder.decode(null, 0, 0, into, 0, FRAME_SAMPLES, false);
			return true;
		}
		// They stopped talking; wait for the buffer to refill before playing again.
		speaker.playing = false;
		return false;
	}

	/** Left/right gain for a voice: fades out toward the edge of range and pans toward the speaker. */
	private static float[] spatialGains(Listener here, Position from, double volume) {
		double dx = from.x() - here.x();
		double dy = from.y() - here.y();
		double dz = from.z() - here.z();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double fullVolumeWithin = 4;
		double falloff = distance <= fullVolumeWithin ? 1
				: Math.max(0, 1 - (distance - fullVolumeWithin) / Math.max(1, range - fullVolumeWithin));
		double loudness = volume * falloff * falloff;

		double pan = 0;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > 0.5) {
			double yaw = Math.toRadians(here.yaw());
			// Minecraft yaw 0 faces +Z; the player's right-hand side is then -X.
			double rightX = -Math.cos(yaw);
			double rightZ = -Math.sin(yaw);
			pan = (dx * rightX + dz * rightZ) / horizontal * 0.8;
		}
		float left = (float) (loudness * Math.sqrt((1 - pan) / 2) * Math.sqrt(2));
		float right = (float) (loudness * Math.sqrt((1 + pan) / 2) * Math.sqrt(2));
		return new float[] {Math.min(left, 2f), Math.min(right, 2f)};
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}
}

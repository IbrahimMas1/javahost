package com.javahost.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.javahost.JavaHost;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Every custom packet JavaHost sends. All of them travel over the normal Minecraft
 * connection, so hosting only ever needs the one game port to be reachable.
 */
public final class Payloads {
	/** Largest chunk of a pack file sent in one packet (vanilla caps clientbound custom payloads at 1 MiB). */
	public static final int PACK_CHUNK_SIZE = 256 * 1024;
	/** Opus frames are tiny (~60-120 bytes at our bitrate); this is a generous ceiling. */
	public static final int MAX_VOICE_FRAME = 1024;

	private Payloads() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, path));
	}

	// ---------------------------------------------------------------- voice

	/** Client -> server: one 20 ms Opus frame from the player's microphone. */
	public record VoiceUp(int sequence, byte[] opus) implements CustomPacketPayload {
		public static final Type<VoiceUp> TYPE = payloadType("voice_up");
		public static final StreamCodec<FriendlyByteBuf, VoiceUp> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeVarInt(p.sequence).writeByteArray(p.opus),
				buf -> new VoiceUp(buf.readVarInt(), buf.readByteArray(MAX_VOICE_FRAME)));

		@Override
		public Type<VoiceUp> type() {
			return TYPE;
		}
	}

	/** Server -> client: a frame spoken by another player, relayed because they are within range. */
	public record VoiceDown(UUID speaker, int sequence, byte[] opus) implements CustomPacketPayload {
		public static final Type<VoiceDown> TYPE = payloadType("voice_down");
		public static final StreamCodec<FriendlyByteBuf, VoiceDown> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUUID(p.speaker).writeVarInt(p.sequence).writeByteArray(p.opus),
				buf -> new VoiceDown(buf.readUUID(), buf.readVarInt(), buf.readByteArray(MAX_VOICE_FRAME)));

		@Override
		public Type<VoiceDown> type() {
			return TYPE;
		}
	}

	/** Server -> client: tells a joining player that voice chat is available and how far it carries. */
	public record VoiceConfig(int range) implements CustomPacketPayload {
		public static final Type<VoiceConfig> TYPE = payloadType("voice_config");
		public static final StreamCodec<FriendlyByteBuf, VoiceConfig> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeVarInt(p.range),
				buf -> new VoiceConfig(buf.readVarInt()));

		@Override
		public Type<VoiceConfig> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- pack sharing

	public enum PackKind { RESOURCE_PACK, SHADER_PACK }

	public record PackInfo(String sha1, PackKind kind, String name, long size) {
		static void write(FriendlyByteBuf buf, PackInfo info) {
			buf.writeUtf(info.sha1, 40).writeVarInt(info.kind.ordinal()).writeUtf(info.name, 256).writeVarLong(info.size);
		}

		static PackInfo read(FriendlyByteBuf buf) {
			return new PackInfo(buf.readUtf(40), PackKind.values()[buf.readVarInt()], buf.readUtf(256), buf.readVarLong());
		}
	}

	/**
	 * Server -> client: the host's active resource packs (in load order) and shader pack.
	 * {@code shaderSettings} is the host's Iris option file for that shader, so it looks identical.
	 */
	public record PackOffer(List<PackInfo> packs, String shaderSettings) implements CustomPacketPayload {
		public static final Type<PackOffer> TYPE = payloadType("pack_offer");
		public static final StreamCodec<FriendlyByteBuf, PackOffer> CODEC = CustomPacketPayload.codec(
				(p, buf) -> {
					buf.writeVarInt(p.packs.size());
					p.packs.forEach(info -> PackInfo.write(buf, info));
					buf.writeUtf(p.shaderSettings, 65536);
				},
				buf -> {
					int count = Math.min(buf.readVarInt(), 64);
					List<PackInfo> packs = new ArrayList<>(count);
					for (int i = 0; i < count; i++) {
						packs.add(PackInfo.read(buf));
					}
					return new PackOffer(packs, buf.readUtf(65536));
				});

		@Override
		public Type<PackOffer> type() {
			return TYPE;
		}
	}

	/** Client -> server: the packs (by SHA-1) the client does not have cached and wants streamed. */
	public record PackRequest(List<String> sha1s) implements CustomPacketPayload {
		public static final Type<PackRequest> TYPE = payloadType("pack_request");
		public static final StreamCodec<FriendlyByteBuf, PackRequest> CODEC = CustomPacketPayload.codec(
				(p, buf) -> {
					buf.writeVarInt(p.sha1s.size());
					p.sha1s.forEach(s -> buf.writeUtf(s, 40));
				},
				buf -> {
					int count = Math.min(buf.readVarInt(), 64);
					List<String> sha1s = new ArrayList<>(count);
					for (int i = 0; i < count; i++) {
						sha1s.add(buf.readUtf(40));
					}
					return new PackRequest(sha1s);
				});

		@Override
		public Type<PackRequest> type() {
			return TYPE;
		}
	}

	/** Server -> client: one piece of a pack file. Chunks of a pack arrive in order. */
	public record PackChunk(String sha1, int index, int total, byte[] data) implements CustomPacketPayload {
		public static final Type<PackChunk> TYPE = payloadType("pack_chunk");
		public static final StreamCodec<FriendlyByteBuf, PackChunk> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUtf(p.sha1, 40).writeVarInt(p.index).writeVarInt(p.total).writeByteArray(p.data),
				buf -> new PackChunk(buf.readUtf(40), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(PACK_CHUNK_SIZE)));

		@Override
		public Type<PackChunk> type() {
			return TYPE;
		}
	}

	/** Client -> server: acknowledges a chunk so the server knows it can send more (flow control). */
	public record PackAck(String sha1, int index) implements CustomPacketPayload {
		public static final Type<PackAck> TYPE = payloadType("pack_ack");
		public static final StreamCodec<FriendlyByteBuf, PackAck> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUtf(p.sha1, 40).writeVarInt(p.index),
				buf -> new PackAck(buf.readUtf(40), buf.readVarInt()));

		@Override
		public Type<PackAck> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(VoiceUp.TYPE, VoiceUp.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PackRequest.TYPE, PackRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PackAck.TYPE, PackAck.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(VoiceDown.TYPE, VoiceDown.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VoiceConfig.TYPE, VoiceConfig.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PackOffer.TYPE, PackOffer.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PackChunk.TYPE, PackChunk.CODEC);
	}
}

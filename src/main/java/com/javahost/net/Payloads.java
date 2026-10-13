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

	/** Client -> server (JavaHost 1.4+): a microphone frame, either for nearby players or the whole party. */
	public record VoiceUp2(int sequence, boolean party, byte[] opus) implements CustomPacketPayload {
		public static final Type<VoiceUp2> TYPE = payloadType("voice_up2");
		public static final StreamCodec<FriendlyByteBuf, VoiceUp2> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeVarInt(p.sequence).writeBoolean(p.party).writeByteArray(p.opus),
				buf -> new VoiceUp2(buf.readVarInt(), buf.readBoolean(), buf.readByteArray(MAX_VOICE_FRAME)));

		@Override
		public Type<VoiceUp2> type() {
			return TYPE;
		}
	}

	/** Server -> client (1.4+): a relayed frame; party frames come from anywhere in the world and play unpanned. */
	public record VoiceDown2(UUID speaker, int sequence, boolean party, byte[] opus) implements CustomPacketPayload {
		public static final Type<VoiceDown2> TYPE = payloadType("voice_down2");
		public static final StreamCodec<FriendlyByteBuf, VoiceDown2> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUUID(p.speaker).writeVarInt(p.sequence).writeBoolean(p.party).writeByteArray(p.opus),
				buf -> new VoiceDown2(buf.readUUID(), buf.readVarInt(), buf.readBoolean(), buf.readByteArray(MAX_VOICE_FRAME)));

		@Override
		public Type<VoiceDown2> type() {
			return TYPE;
		}
	}

	/** Server -> client (1.4+): the host's JavaHost version, sent on join (for the version check). */
	public record ServerHello(String version) implements CustomPacketPayload {
		public static final Type<ServerHello> TYPE = payloadType("server_hello");
		public static final StreamCodec<FriendlyByteBuf, ServerHello> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUtf(p.version, 32),
				buf -> new ServerHello(buf.readUtf(32)));

		@Override
		public Type<ServerHello> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- world rules

	/** Server -> client: the world rules, the lock, your permission, co-hosts and recent changes (as JSON). */
	public record RulesState(String json) implements CustomPacketPayload {
		public static final Type<RulesState> TYPE = payloadType("rules_state");
		public static final StreamCodec<FriendlyByteBuf, RulesState> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUtf(p.json, 30000),
				buf -> new RulesState(buf.readUtf(30000)));

		@Override
		public Type<RulesState> type() {
			return TYPE;
		}
	}

	/** Client -> server: please change a rule (or "__lock", or "__cohost:&lt;uuid&gt;"); the server checks permission. */
	public record RulesChange(String rule, String value) implements CustomPacketPayload {
		public static final Type<RulesChange> TYPE = payloadType("rules_change");
		public static final StreamCodec<FriendlyByteBuf, RulesChange> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUtf(p.rule, 64).writeUtf(p.value, 32),
				buf -> new RulesChange(buf.readUtf(64), buf.readUtf(32)));

		@Override
		public Type<RulesChange> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- same-world fast lane

	/** Largest piece of a fast-lane transfer; serverbound custom payloads are capped at 32 KiB. */
	public static final int RELAY_CHUNK_SIZE = 30 * 1024;
	/** Largest fast-lane transfer (a sealed message plus a full-size screenshot). */
	public static final int RELAY_MAX_BYTES = 4 * 1024 * 1024;

	/** Client -> server: one piece of an (already end-to-end encrypted) message for a player in this world. */
	public record RelayUp(UUID to, long id, int index, int total, byte[] data) implements CustomPacketPayload {
		public static final Type<RelayUp> TYPE = payloadType("relay_up");
		public static final StreamCodec<FriendlyByteBuf, RelayUp> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUUID(p.to).writeLong(p.id).writeVarInt(p.index).writeVarInt(p.total).writeByteArray(p.data),
				buf -> new RelayUp(buf.readUUID(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(RELAY_CHUNK_SIZE)));

		@Override
		public Type<RelayUp> type() {
			return TYPE;
		}
	}

	/** Server -> client: a piece of a message from another player; the server fills in who it's from. */
	public record RelayDown(UUID from, long id, int index, int total, byte[] data) implements CustomPacketPayload {
		public static final Type<RelayDown> TYPE = payloadType("relay_down");
		public static final StreamCodec<FriendlyByteBuf, RelayDown> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUUID(p.from).writeLong(p.id).writeVarInt(p.index).writeVarInt(p.total).writeByteArray(p.data),
				buf -> new RelayDown(buf.readUUID(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(RELAY_CHUNK_SIZE)));

		@Override
		public Type<RelayDown> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- sending a world copy

	/** What a world-transfer packet carries: see {@code client/share/WorldShare}. */
	public static final byte SHARE_OFFER = 0, SHARE_ANSWER = 1, SHARE_CHUNK = 2, SHARE_ACK = 3, SHARE_CANCEL = 4;

	/** Client -> server: one step of sending a world to a player in this world (chunks are end-to-end encrypted). */
	public record ShareUp(UUID to, long id, byte kind, int index, byte[] data) implements CustomPacketPayload {
		public static final Type<ShareUp> TYPE = payloadType("share_up");
		public static final StreamCodec<FriendlyByteBuf, ShareUp> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUUID(p.to).writeLong(p.id).writeByte(p.kind).writeVarInt(p.index).writeByteArray(p.data),
				buf -> new ShareUp(buf.readUUID(), buf.readLong(), buf.readByte(), buf.readVarInt(), buf.readByteArray(RELAY_CHUNK_SIZE)));

		@Override
		public Type<ShareUp> type() {
			return TYPE;
		}
	}

	/** Server -> client: the same step, from another player; the server fills in who it's from. */
	public record ShareDown(UUID from, long id, byte kind, int index, byte[] data) implements CustomPacketPayload {
		public static final Type<ShareDown> TYPE = payloadType("share_down");
		public static final StreamCodec<FriendlyByteBuf, ShareDown> CODEC = CustomPacketPayload.codec(
				(p, buf) -> buf.writeUUID(p.from).writeLong(p.id).writeByte(p.kind).writeVarInt(p.index).writeByteArray(p.data),
				buf -> new ShareDown(buf.readUUID(), buf.readLong(), buf.readByte(), buf.readVarInt(), buf.readByteArray(RELAY_CHUNK_SIZE)));

		@Override
		public Type<ShareDown> type() {
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
		PayloadTypeRegistry.serverboundPlay().register(VoiceUp2.TYPE, VoiceUp2.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RelayUp.TYPE, RelayUp.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RulesChange.TYPE, RulesChange.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShareUp.TYPE, ShareUp.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(VoiceDown.TYPE, VoiceDown.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VoiceConfig.TYPE, VoiceConfig.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PackOffer.TYPE, PackOffer.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PackChunk.TYPE, PackChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VoiceDown2.TYPE, VoiceDown2.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ServerHello.TYPE, ServerHello.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RelayDown.TYPE, RelayDown.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RulesState.TYPE, RulesState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShareDown.TYPE, ShareDown.CODEC);
	}
}

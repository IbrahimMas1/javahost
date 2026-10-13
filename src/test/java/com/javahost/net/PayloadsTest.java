package com.javahost.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/** Packets a hacked game might send are refused cleanly, and ours never become unreadable for everyone. */
class PayloadsTest {
	@Test
	void nonsenseListSizesAreRefused() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(-5);
		assertThrows(DecoderException.class, () -> Payloads.PackRequest.CODEC.decode(buf));
		FriendlyByteBuf huge = new FriendlyByteBuf(Unpooled.buffer());
		huge.writeVarInt(1_000_000);
		assertThrows(DecoderException.class, () -> Payloads.PackRequest.CODEC.decode(huge));
	}

	@Test
	void aBadPackKindIsRefused() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(1);
		buf.writeUtf("a".repeat(40), 40).writeVarInt(99).writeUtf("pack", 256).writeVarLong(10);
		buf.writeUtf("", 65536);
		assertThrows(DecoderException.class, () -> Payloads.PackOffer.CODEC.decode(buf));
	}

	@Test
	void longNamesAndManyPacksAreClippedWhenSending() {
		List<Payloads.PackInfo> packs = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			packs.add(new Payloads.PackInfo("b".repeat(40), Payloads.PackKind.RESOURCE_PACK, "x".repeat(400), 1));
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		Payloads.PackOffer.CODEC.encode(buf, new Payloads.PackOffer(packs, ""));
		Payloads.PackOffer read = Payloads.PackOffer.CODEC.decode(buf);
		assertEquals(64, read.packs().size());
		assertEquals(256, read.packs().getFirst().name().length());
	}
}

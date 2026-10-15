package com.javahost.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

class WorldOwnerTest {
	@TempDir
	Path temp;

	@Test
	void theReceiverBecomesTheOwnerAndTheSendersPlayerIsKept() throws IOException {
		UUID sender = UUID.fromString("00000000-0000-0000-0000-00000000000a");
		UUID receiver = UUID.fromString("00000000-0000-0000-0000-00000000000b");
		Path world = temp.resolve("Survival");
		Files.createDirectories(world.resolve("players/data"));
		CompoundTag data = new CompoundTag();
		data.putString("LevelName", "Survival");
		//? if >=1.21.5 {
		data.store("singleplayer_uuid", UUIDUtil.CODEC, sender);
		//?} else
		/*data.putUUID("singleplayer_uuid", sender);*/
		data.put("Player", new CompoundTag());
		data.putLong("Time", 1234);
		CompoundTag root = new CompoundTag();
		root.put("Data", data);
		NbtIo.writeCompressed(root, world.resolve("level.dat"));
		Files.writeString(world.resolve("level.dat_old"), "old");
		Files.writeString(world.resolve("players/data/" + sender + ".dat"), "sender");
		Files.createDirectories(world.resolve("javahost"));
		Files.writeString(world.resolve("javahost/rules.json"), "{\"coHosts\":{\"" + sender + "\":\"Alex\"}}");

		WorldOwner.adopt(world, receiver, "Survival (from Alex)");

		assertEquals(receiver, WorldOwner.ownerOf(world).orElseThrow());
		assertEquals("Survival (from Alex)", WorldOwner.nameOf(world));
		//? if >=1.21.5 {
		CompoundTag after = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
		assertEquals(1234, after.getLongOr("Time", 0), "everything else is untouched");
		//?} else {
		/*CompoundTag after = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap()).getCompound("Data");
		assertEquals(1234, after.getLong("Time"), "everything else is untouched");
		*///?}
		assertFalse(after.contains("Player"), "an old embedded player would override the receiver");
		assertFalse(Files.exists(world.resolve("level.dat_old")));
		assertTrue(Files.exists(world.resolve("players/data/" + sender + ".dat")), "the sender's player is kept");
		assertFalse(Files.exists(world.resolve("javahost/rules.json")), "the sender can't bring co-host rights along");
	}

	@Test
	void freeFolderNamesAreSafeAndUnique() throws IOException {
		Files.createDirectories(temp.resolve("Survival (from Alex)"));
		assertEquals("Survival (from Alex) (2)", WorldOwner.freeFolder(temp, "Survival (from Alex)").getFileName().toString());
		assertEquals("a_b_c", WorldOwner.freeFolder(temp, "a/b:c").getFileName().toString());
		assertEquals("World", WorldOwner.freeFolder(temp, "..").getFileName().toString());
		assertEquals("World", WorldOwner.freeFolder(temp, "CON").getFileName().toString());
	}
}

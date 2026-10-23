package com.javahost.client.gui.ui;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.javahost.JavaHost;
import com.javahost.client.compat.ClientCompat;
import com.javahost.util.IoExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * A player's skin and face. Players in our world come from the game's player list; anyone else (you on the title
 * screen, friends who are elsewhere) is looked up from Mojang once per session, showing the default skin until then.
 */
public final class Faces {
	/** Skins looked up from Mojang, by player. */
	private static final Map<UUID, PlayerSkin> FETCHED = new ConcurrentHashMap<>();
	/** Players already looked up (found or not), so each is asked for once. */
	private static final Set<UUID> ASKED = ConcurrentHashMap.newKeySet();
	/** Friends lists are small; the cap stops a flood of remote ids from becoming a flood of requests. */
	private static final int MAX_ASKED = 256;

	private Faces() {
	}

	public static PlayerSkin skinOf(UUID id) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && minecraft.player.getUUID().equals(id)) {
			return minecraft.player.getSkin();
		}
		ClientPacketListener connection = minecraft.getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
		if (info != null) {
			return info.getSkin();
		}
		PlayerSkin fetched = FETCHED.get(id);
		if (fetched != null) {
			return fetched;
		}
		fetch(id);
		return DefaultPlayerSkin.get(id);
	}

	/** Looks up a player's skin in the background; {@link #skinOf} returns it once it's loaded. */
	private static void fetch(UUID id) {
		if (ASKED.size() >= MAX_ASKED || !ASKED.add(id)) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> ClientCompat.fetchProfile(id), IoExecutor.NET)
				.thenComposeAsync(profile -> profile == null ? CompletableFuture.completedFuture(Optional.<PlayerSkin>empty())
						: ClientCompat.loadSkin(profile), minecraft)
				.whenComplete((skin, error) -> {
					if (skin != null && skin.isPresent()) {
						FETCHED.put(id, skin.get());
					} else if (error != null) {
						JavaHost.LOGGER.debug("[Skins] couldn't load a skin: {}", error.toString());
					}
				});
	}

	public static void draw(GuiGraphicsExtractor g, UUID id, int x, int y, int size) {
		PlayerFaceExtractor.extractRenderState(g, skinOf(id), x, y, size);
	}
}

package com.javahost.client.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import com.javahost.JavaHost;
import com.javahost.client.Toasts;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Presence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Where to go back to after restarting for new mods: a friend's world, a public world, or an address. Kept for 20
 * minutes; the first title screen after starting picks it up.
 */
public final class PendingJoin {
	private static final long KEEP_MS = 20 * 60 * 1000L;
	private static final long WAIT_FOR_FRIEND_MS = 60_000L;
	private static boolean checked;
	private static Properties waiting;
	private static long waitingSince;

	private PendingJoin() {
	}

	private static Path file() {
		return ModSets.dir().resolve("pending-join.properties");
	}

	/** Remembers the world we were joining when we found we needed mods. */
	public static void save(ModSets.Needed needed) {
		Properties p = new Properties();
		InviteFlow.Target target = needed.target();
		if (target != null) {
			p.setProperty("kind", target.publicWorld() ? "public" : "friend");
			p.setProperty("id", target.host().toString());
			p.setProperty("name", target.name());
		} else if (!needed.address().isEmpty()) {
			p.setProperty("kind", "address");
			p.setProperty("address", needed.address());
			p.setProperty("name", needed.hostName());
		} else {
			return;
		}
		p.setProperty("expires", String.valueOf(System.currentTimeMillis() + KEEP_MS));
		try {
			ModSets.write(file(), p);
		} catch (IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't remember where to rejoin", e);
		}
	}

	public static void cancel() {
		waiting = null;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// Harmless.
		}
	}

	/** Each tick on the title screen: rejoin once, after starting with the new mods. */
	public static void tick(Minecraft minecraft) {
		if (!checked && minecraft.gui.screen() instanceof TitleScreen) {
			checked = true;
			if (Files.exists(file())) {
				try {
					Properties p = ModSets.read(file());
					Files.deleteIfExists(file());
					if (Long.parseLong(p.getProperty("expires", "0")) > System.currentTimeMillis()) {
						waiting = p;
						waitingSince = System.currentTimeMillis();
						Toasts.show("rejoin", "Rejoining " + p.getProperty("name", "your friend") + "...", "Your mods are ready.");
					}
				} catch (IOException | RuntimeException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't read where to rejoin", e);
				}
			}
		}
		if (waiting == null || minecraft.level != null) {
			return;
		}
		Properties p = waiting;
		String name = p.getProperty("name", "your friend");
		switch (p.getProperty("kind", "")) {
			case "friend" -> {
				UUID id = UUID.fromString(p.getProperty("id"));
				var status = Presence.of(id).filter(Presence.Status::hosting);
				if (status.isPresent() && Friends.friend(id).isPresent()) {
					waiting = null;
					InviteFlow.join(status.get().asInvite(Friends.friend(id).get().name()));
				} else if (System.currentTimeMillis() - waitingSince > WAIT_FOR_FRIEND_MS) {
					waiting = null;
					Toasts.notify("Couldn't rejoin " + name, "Their world isn't open right now. Ask them to invite you again.");
				}
			}
			case "public" -> {
				waiting = null;
				UUID id = UUID.fromString(p.getProperty("id"));
				com.javahost.client.discover.Directory.list().thenAccept(worlds -> minecraft.execute(() -> worlds.stream()
						.filter(w -> w.host().equals(id)).findFirst().ifPresentOrElse(InviteFlow::joinPublic,
								() -> Toasts.notify("Couldn't rejoin " + name, "Their public world has closed."))));
			}
			case "address" -> {
				waiting = null;
				var server = new net.minecraft.client.multiplayer.ServerData(name, p.getProperty("address"), net.minecraft.client.multiplayer.ServerData.Type.OTHER);
				net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), minecraft,
						net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server.ip), server, false, null);
			}
			default -> waiting = null;
		}
	}
}

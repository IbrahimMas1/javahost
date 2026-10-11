package com.javahost.client.host;

import java.util.ArrayList;
import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.Config.RecentJoin;
import com.javahost.net.InviteCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Joining a friend from an invite code, a relay domain or a plain address, and remembering it. */
public final class JoinHelper {
	private static final int MAX_RECENT = 5;

	private JoinHelper() {
	}

	/** Starts connecting. Returns a problem to show the player, or {@code null} if connecting began. */
	public static String join(Screen from, String input) {
		if (input == null || input.isBlank()) {
			return "Paste an invite code first.";
		}
		String address = InviteCode.decode(input);
		if (address == null || !ServerAddress.isValidAddress(address)) {
			return "That code doesn't look right. Check it and try again.";
		}
		remember(input.trim(), label(input.trim()));
		Minecraft minecraft = Minecraft.getInstance();
		ServerData server = new ServerData("Friend's world", address, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(from, minecraft, ServerAddress.parseString(address), server, false, null);
		return null;
	}

	private static String label(String invite) {
		if (invite.toUpperCase().startsWith("JH6")) {
			return "Friend's world (IPv6)";
		}
		if (invite.toUpperCase().startsWith("JH")) {
			return "Friend's world";
		}
		if (invite.endsWith(".e4mc.link")) {
			return "Friend's world (relay)";
		}
		return invite;
	}

	private static void remember(String invite, String label) {
		Config config = Config.get();
		List<RecentJoin> list = new ArrayList<>(config.recentJoins);
		list.removeIf(entry -> entry.invite().equalsIgnoreCase(invite));
		list.addFirst(new RecentJoin(invite, label, System.currentTimeMillis()));
		while (list.size() > MAX_RECENT) {
			list.removeLast();
		}
		config.recentJoins = list;
		config.save();
	}
}

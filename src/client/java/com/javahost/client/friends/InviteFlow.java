package com.javahost.client.friends;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.javahost.JavaHost;
import com.javahost.client.Toasts;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Friends.Invite;
import com.javahost.client.friends.Friends.RouteInfo;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.Connectivity.Route;
import com.javahost.client.host.Connectivity.State;
import com.javahost.client.host.HostController;
import com.javahost.net.InviteCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * One-click invites. The host clicks Invite: the world opens to friends if it isn't already, JavaHost works out
 * how friends can reach it, and sends the friend an encrypted invite. The friend clicks Join: JavaHost tries the
 * best way in first and quietly falls back to the next if it doesn't connect.
 */
public final class InviteFlow {
	private static final Map<UUID, String> STATUS = new HashMap<>();

	// Joining state
	private static List<String> joinQueue = List.of();
	private static int joinIndex = -1;
	private static String joiningHost;
	private static long attemptStarted;
	private static boolean sawConnecting;

	private InviteFlow() {
	}

	/** What to show next to a friend on the Friends tab ("Inviting...", "Invited"). */
	public static String status(UUID friend) {
		return STATUS.get(friend);
	}

	public static boolean canInvite() {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && server.isSingleplayerOwner(minecraft.player.nameAndId());
	}

	// ================================================================ host side

	public static void invite(Friend friend) {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (!canInvite() || server == null) {
			STATUS.put(friend.id(), "Open a world first");
			return;
		}
		if (!HostController.isHosting()) {
			String problem = HostController.start(server);
			if (problem != null) {
				STATUS.put(friend.id(), "Couldn't open the world");
				Toasts.show("Couldn't open your world", problem);
				return;
			}
		}
		STATUS.put(friend.id(), "Inviting...");
		String world = server.getWorldData().getLevelName();
		CompletableFuture.runAsync(() -> {
			// Give the connection checks (and the relay, if needed) up to ~25 s to settle.
			long deadline = System.currentTimeMillis() + 25_000;
			while (System.currentTimeMillis() < deadline && !ready()) {
				sleep(250);
			}
		}).thenRun(() -> minecraft.execute(() -> {
			List<RouteInfo> routes = shareableRoutes();
			if (routes.isEmpty()) {
				STATUS.put(friend.id(), "No way in yet");
				return;
			}
			Friends.sendInvite(friend, world, routes, Connectivity.publicIpv4()).thenAccept(ok -> minecraft.execute(() -> {
				STATUS.put(friend.id(), ok ? "Invited" : "Couldn't send");
				if (ok) {
					Toasts.player(friend.id(), "Invited " + friend.name(), "They'll get a Join button");
				}
			}));
		}));
	}

	private static boolean ready() {
		if (Connectivity.isProbing()) {
			return false;
		}
		for (Route route : Connectivity.routes()) {
			if (route.name().equals("Relay") && route.state() == State.CHECKING) {
				return false;
			}
		}
		return true;
	}

	/** Every route that might work, best first, as plain addresses. */
	private static List<RouteInfo> shareableRoutes() {
		List<RouteInfo> routes = new ArrayList<>();
		for (Route route : Connectivity.routes()) {
			if (route.invite() == null || route.state() == State.UNAVAILABLE || route.state() == State.OFF) {
				continue;
			}
			String address = InviteCode.decode(route.invite());
			if (address != null) {
				routes.add(new RouteInfo(route.name(), route.state().name(), address));
			}
		}
		return routes;
	}

	// ================================================================ guest side

	/** Joins the host's world, trying each way in until one connects. */
	public static void join(Invite invite) {
		CompletableFuture.supplyAsync(InviteFlow::myPublicIpv4).thenAccept(myIp -> Minecraft.getInstance().execute(() -> {
			List<String> order = joinOrder(invite, myIp);
			JavaHost.LOGGER.info("Joining {}'s world via {}", invite.name(), order);
			joinQueue = order;
			joinIndex = -1;
			joiningHost = invite.name();
			next();
		}));
	}

	/** Same home network first (fastest), then direct internet, then the relay, then the maybes. */
	static List<String> joinOrder(Invite invite, String myPublicIp) {
		List<String> order = new ArrayList<>();
		boolean sameHome = myPublicIp != null && !invite.hostIp().isEmpty() && myPublicIp.equals(invite.hostIp());
		String forced = System.getProperty("javahost.test.preferRoute");
		List<String> preference = forced != null
				? List.of(forced)
				: sameHome ? List.of("Same Wi-Fi", "Internet", "Relay", "IPv6") : List.of("Internet", "Relay", "IPv6");
		// Everything known to work comes before anything that only might (a "maybe" can take ~30 s to time out).
		for (String state : List.of("WORKING", "MAYBE")) {
			for (String wanted : preference) {
				for (RouteInfo route : invite.routes()) {
					if (route.name().equals(wanted) && route.state().equals(state) && !order.contains(route.address())) {
						order.add(route.address());
					}
				}
			}
		}
		if (forced == null) {
			for (String state : List.of("WORKING", "MAYBE")) {
				for (RouteInfo route : invite.routes()) {
					if (route.state().equals(state) && !route.name().equals("Same Wi-Fi") && !order.contains(route.address())) {
						order.add(route.address());
					}
				}
			}
		}
		return order;
	}

	private static void next() {
		Minecraft minecraft = Minecraft.getInstance();
		joinIndex++;
		if (joinIndex >= joinQueue.size()) {
			JavaHost.LOGGER.info("Couldn't reach {}'s world by any route", joiningHost);
			joinQueue = List.of();
			Toasts.show("Couldn't join " + joiningHost, "Their world may have closed. Ask them to invite you again.");
			return;
		}
		String address = joinQueue.get(joinIndex);
		if (minecraft.level != null) {
			// Leave (and save) whatever world we're in first.
			minecraft.disconnectFromWorld(Component.literal("Joining a friend"));
		}
		attemptStarted = System.currentTimeMillis();
		sawConnecting = false;
		ServerData server = new ServerData(joiningHost + "'s world", address, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(address), server, false, null);
	}

	/** Watches each join attempt: done once we're in the world; on failure, try the next way in. */
	public static void tick(Minecraft minecraft) {
		if (joinQueue.isEmpty() || joinIndex < 0) {
			return;
		}
		if (minecraft.gui.screen() instanceof ConnectScreen) {
			sawConnecting = true;
		}
		if (minecraft.level != null && minecraft.player != null) {
			JavaHost.LOGGER.info("Joined {}'s world via {}", joiningHost, joinQueue.get(joinIndex));
			joinQueue = List.of();
			return;
		}
		if (sawConnecting && minecraft.gui.screen() instanceof TitleScreen && System.currentTimeMillis() - attemptStarted > 500) {
			// The player pressed Cancel while connecting.
			joinQueue = List.of();
			return;
		}
		if (minecraft.gui.screen() instanceof DisconnectedScreen screen) {
			String reason = screen.getNarrationMessage().getString();
			// Turned away on purpose (e.g. not friends): retrying another route won't help.
			if (reason.contains("just for the host's friends")) {
				joinQueue = List.of();
				return;
			}
			JavaHost.LOGGER.info("Route {} failed ({}), trying the next", joinQueue.get(joinIndex), reason);
			next();
		}
	}

	private static String cachedIp;
	private static long cachedAt;

	private static synchronized String myPublicIpv4() {
		if (cachedIp != null && System.currentTimeMillis() - cachedAt < 5 * 60_000) {
			return cachedIp;
		}
		try {
			HttpURLConnection connection = (HttpURLConnection) URI.create("https://api.ipify.org").toURL().openConnection();
			connection.setConnectTimeout(4000);
			connection.setReadTimeout(4000);
			try (InputStream in = connection.getInputStream()) {
				String ip = new String(in.readNBytes(64), StandardCharsets.US_ASCII).trim();
				cachedIp = ip.matches("[0-9.]+") ? ip : null;
				cachedAt = System.currentTimeMillis();
				return cachedIp;
			}
		} catch (Exception e) {
			return null;
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

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
	/** Bumped by every join, so an older join that's still starting up gives way to the newest. */
	private static int joinToken;
	// Checking the world really is the friend's: who we expect, their signing key, and the challenge we sent.
	private static UUID expectedHost;
	private static String expectedSignKey;
	private static byte[] challenge;
	private static long challengeSent;
	/** Why the last route didn't work out, for the message if none do. */
	private static String lastProblem;
	private static final long PROOF_TIMEOUT_MS = 8000;
	private static final long CONNECT_TIMEOUT_MS = 35_000;
	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

	private InviteFlow() {
	}

	/**
	 * What to show next to a friend on the Friends tab: "Opening your world...", "Sending...",
	 * "Sent, waiting for them", "Delivered", "In your world", or why it failed. Null when nothing is going on.
	 */
	public static String status(UUID friend) {
		if (HostController.guests().stream().anyMatch(g -> g.id().equals(friend))) {
			return "In your world";
		}
		String local = STATUS.get(friend);
		java.util.Optional<Friends.InviteStatus> sent = Friends.inviteStatus(friend);
		if (local != null && (sent.isEmpty() || sent.get().time() < localSince.getOrDefault(friend, 0L))) {
			return local;
		}
		return sent.map(Friends.InviteStatus::detail).orElse(null);
	}

	/** True when this friend is in the same world as us right now (our guest, our host, or both guests somewhere). */
	public static boolean playingTogether(UUID friend) {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level != null && minecraft.getConnection() != null && minecraft.getConnection().getPlayerInfo(friend) != null;
	}

	/** A Join button makes sense: they're hosting and we aren't already in their world. */
	public static boolean joinable(UUID friend) {
		return Presence.of(friend).map(Presence.Status::hosting).orElse(false) && Presence.sameMinecraft(friend) && !playingTogether(friend);
	}

	/** True while an invite is on its way (to disable the button). */
	public static boolean busy(UUID friend) {
		String status = status(friend);
		return status != null && (status.endsWith("...") || status.startsWith("Opening"));
	}

	private static final Map<UUID, Long> localSince = new HashMap<>();

	private static void setLocal(UUID friend, String text) {
		STATUS.put(friend, text);
		localSince.put(friend, System.currentTimeMillis());
		Friends.changed();
	}

	public static boolean canInvite() {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && com.javahost.compat.Compat.isOwner(server, minecraft.player);
	}

	// ================================================================ host side

	public static void invite(Friend friend) {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (!canInvite() || server == null) {
			setLocal(friend.id(), "Open one of your worlds first");
			return;
		}
		if (!HostController.isHosting()) {
			String problem = HostController.start(server);
			if (problem != null) {
				setLocal(friend.id(), "Couldn't open the world");
				Toasts.notify("Couldn't open your world", problem);
				return;
			}
		}
		JavaHost.LOGGER.info("[Friends] inviting {}", friend.name());
		setLocal(friend.id(), "Opening your world...");
		String world = server.getWorldData().getLevelName();
		// Give the connection checks (and the relay, if needed) up to ~25 s to settle, checking every quarter second.
		whenReady(System.currentTimeMillis() + 25_000).thenRun(() -> minecraft.execute(() -> {
			List<RouteInfo> routes = shareableRoutes();
			if (routes.isEmpty()) {
				setLocal(friend.id(), "No way in yet. Check the Host tab");
				JavaHost.LOGGER.warn("[Friends] no routes to share with {}", friend.name());
				return;
			}
			STATUS.remove(friend.id());
			Friends.sendInvite(friend, world, routes, Connectivity.publicIpv4()).thenAccept(ok -> minecraft.execute(() -> {
				if (ok) {
					Toasts.player(friend.id(), "Invited " + friend.name(), "They'll get a Join button");
				}
			}));
		}));
	}

	/** Completes once the connection checks have settled or the deadline passes, without tying up a thread. */
	private static CompletableFuture<Void> whenReady(long deadline) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		Runnable[] check = new Runnable[1];
		check[0] = () -> Minecraft.getInstance().execute(() -> {
			if (ready() || System.currentTimeMillis() >= deadline) {
				done.complete(null);
			} else {
				CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS).execute(check[0]);
			}
		});
		check[0].run();
		return done;
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
	public static List<RouteInfo> shareableRoutes() {
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

	/** The world a join was last started for (to come back to it after restarting with new mods). */
	public record Target(UUID host, String name, boolean publicWorld) {
	}

	private static Target lastTarget;

	/** The world JavaHost is joining right now, or null (e.g. joining from Minecraft's server list). */
	public static Target joiningTarget() {
		return joinQueue.isEmpty() ? null : lastTarget;
	}

	/** Joins the host's world, trying each way in until one connects. */
	public static void join(Invite invite) {
		java.util.Optional<Friend> friend = Friends.friend(invite.from());
		if (friend.isEmpty() || friend.get().signKey() == null) {
			// Without their signing key we can't check the world is really theirs, so we don't go.
			Toasts.notify("Couldn't join " + invite.name(), invite.name() + " needs to update JavaHost first (1.7 or newer).");
			return;
		}
		int token = ++joinToken;
		lastTarget = new Target(invite.from(), invite.name(), false);
		com.javahost.client.mods.ModSets.clearNeeded();
		String signKey = friend.get().signKey();
		CompletableFuture.supplyAsync(InviteFlow::myPublicIpv4, com.javahost.util.IoExecutor.NET).thenAccept(myIp -> Minecraft.getInstance().execute(() -> {
			if (token != joinToken) {
				return; // a newer Join replaced this one
			}
			List<String> order = joinOrder(invite, myIp);
			JavaHost.LOGGER.info("[Friends] joining {}'s world via {}", invite.name(), order);
			publicJoin = false;
			startJoin(invite.from(), invite.name(), signKey, order);
			Toasts.player(invite.from(), "Joining " + invite.name() + "'s world...", invite.world());
			next();
		}));
	}

	/** True after joining a stranger's public world (until we leave): e.g. their packs are always asked about. */
	private static boolean publicJoin;

	public static boolean inPublicWorld() {
		Minecraft minecraft = Minecraft.getInstance();
		return publicJoin && minecraft.level != null && minecraft.getSingleplayerServer() == null;
	}

	/** Joins a public world through its relay address, checking the host's proof against the listed key. */
	private static com.javahost.client.discover.Directory.PublicWorld currentPublic;

	/** The public world we're in (or last joined), for reporting it from inside. */
	public static com.javahost.client.discover.Directory.PublicWorld currentPublic() {
		return inPublicWorld() ? currentPublic : null;
	}

	public static void joinPublic(com.javahost.client.discover.Directory.PublicWorld world) {
		++joinToken;
		currentPublic = world;
		lastTarget = new Target(world.host(), com.javahost.client.discover.Directory.nameOf(world), true);
		com.javahost.client.mods.ModSets.clearNeeded();
		JavaHost.LOGGER.info("[Public] joining {}'s public world \"{}\" via {}", world.name(), world.title(), world.relay());
		publicJoin = true;
		String name = com.javahost.client.discover.Directory.nameOf(world);
		startJoin(world.host(), name, world.signKey(), List.of(world.relay()));
		Toasts.player(world.host(), "Joining " + name + "'s world...", world.title());
		next();
	}

	private static void startJoin(UUID host, String name, String signKey, List<String> order) {
		joinQueue = order;
		joinIndex = -1;
		joiningHost = name;
		expectedHost = host;
		expectedSignKey = signKey;
		challengeSent = 0;
		lastProblem = null;
	}


	/** Same home network first (fastest), then direct internet, then the relay, then the maybes. */
	static List<String> joinOrder(Invite invite, String myPublicIp) {
		List<String> order = new ArrayList<>();
		// Same public address isn't enough (whole campuses and phone networks share one): the host's LAN address must
		// also be on one of our own networks, or "Same Wi-Fi" could reach some other PC that happens to be there.
		boolean sameHome = myPublicIp != null && !invite.hostIp().isEmpty() && myPublicIp.equals(invite.hostIp())
				&& invite.routes().stream().anyMatch(r -> r.name().equals("Same Wi-Fi") && onMyNetwork(r.address()));
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
			JavaHost.LOGGER.info("Couldn't reach {}'s world by any route ({})", joiningHost, lastProblem);
			boolean unconfirmed = expectedHost != null && minecraft.level != null;
			joinQueue = List.of();
			expectedHost = null;
			if (unconfirmed) {
				// We're still in the last world tried, and it never proved it's theirs: don't stay.
				com.javahost.client.compat.ClientCompat.leaveWorld(Component.literal("Couldn't confirm this world"));
				minecraft.gui.setScreen(new TitleScreen());
			}
			Toasts.notify("Couldn't join " + joiningHost, lastProblem != null ? lastProblem
					: "Their world may have closed. Ask them to invite you again.");
			return;
		}
		challengeSent = 0;
		String address = joinQueue.get(joinIndex);
		if (minecraft.level != null) {
			// Leave (and save) whatever world we're in first.
			com.javahost.client.compat.ClientCompat.leaveWorld(Component.literal("Joining a friend"));
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
		if (minecraft.gui.screen() instanceof ConnectScreen connecting) {
			sawConnecting = true;
			// Some ways in (notably the relay, when the host's side has gone quiet) never fail on their own: give up
			// on this one after a while and try the next, rather than leaving "Connecting..." up forever.
			if (System.currentTimeMillis() - attemptStarted > CONNECT_TIMEOUT_MS) {
				String address = joinQueue.get(joinIndex);
				JavaHost.LOGGER.info("Route {} didn't answer in {} s, trying the next", address, CONNECT_TIMEOUT_MS / 1000);
				lastProblem = joiningHost + "'s world didn't answer" + (address.endsWith(".e4mc.link") ? " through the relay" : "")
						+ ". They may need to reopen it, or check their connection.";
				sawConnecting = false;
				connecting.onClose(); // cancels the attempt
				next();
				return;
			}
		}
		if (minecraft.level != null && minecraft.player != null) {
			if (expectedHost == null) {
				JavaHost.LOGGER.info("Joined {}'s world via {}", joiningHost, joinQueue.get(joinIndex));
				joinQueue = List.of();
				return;
			}
			// In, but is it really their world? Ask the host's game to prove it with their signing key.
			if (challengeSent == 0) {
				if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(com.javahost.net.Payloads.HostProofRequest.TYPE)) {
					wrongWorld("doesn't run JavaHost 1.7");
					return;
				}
				challenge = new byte[32];
				RANDOM.nextBytes(challenge);
				challengeSent = System.currentTimeMillis();
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.javahost.net.Payloads.HostProofRequest(challenge));
			} else if (System.currentTimeMillis() - challengeSent > PROOF_TIMEOUT_MS) {
				wrongWorld("didn't prove it's theirs in time");
			}
			return;
		}
		if (sawConnecting && minecraft.gui.screen() instanceof TitleScreen && System.currentTimeMillis() - attemptStarted > 500) {
			// The player pressed Cancel while connecting.
			joinQueue = List.of();
			return;
		}
		if (minecraft.gui.screen() instanceof DisconnectedScreen screen) {
			if (com.javahost.client.mods.ModSets.needed() != null) {
				// We reached their world; it needs mods we don't have. No point trying the other ways in.
				JavaHost.LOGGER.info("{}'s world needs mods we don't have", joiningHost);
				joinQueue = List.of();
				expectedHost = null;
				return;
			}
			String reason = screen.getNarrationMessage().getString();
			// Turned away (e.g. "just for friends"). That may be some other world on this address, so keep trying
			// the other ways in, and say why only if none work.
			if (reason.contains("just for the host's friends") || reason.contains("banned") || reason.contains("full")) {
				lastProblem = reason;
			}
			JavaHost.LOGGER.info("Route {} failed ({}), trying the next", joinQueue.get(joinIndex), reason);
			next();
		}
	}

	/** True while we're in a world but haven't yet confirmed it's the friend's (packs etc. wait until then). */
	public static boolean verifying() {
		return expectedHost != null && !joinQueue.isEmpty();
	}

	/** The host's answer to our challenge. */
	public static void onProof(com.javahost.net.Payloads.HostProof proof) {
		if (expectedHost == null || challengeSent == 0 || joinQueue.isEmpty()) {
			return;
		}
		boolean valid = proof.host().equals(expectedHost) && com.javahost.util.FriendCrypto.verifySigned(expectedSignKey,
				com.javahost.util.FriendCrypto.hostProofBytes(challenge, expectedHost, Identity.id()), proof.signature());
		if (!valid) {
			wrongWorld("isn't theirs");
			return;
		}
		JavaHost.LOGGER.info("Joined {}'s world via {} (confirmed it's theirs)", joiningHost, joinQueue.get(joinIndex));
		joinQueue = List.of();
		expectedHost = null;
		challengeSent = 0;
	}

	/** The world we reached isn't (provably) the friend's: leave at once and try the next way in. */
	private static void wrongWorld(String why) {
		JavaHost.LOGGER.warn("The world at {} {}: leaving", joinQueue.get(joinIndex), why);
		lastProblem = "Couldn't confirm this is " + joiningHost + "'s world. " + joiningHost + " may need to update JavaHost.";
		challengeSent = 0;
		next();
	}

	/** True if {@code hostPort}'s IPv4 address is on the same local network as one of ours. */
	static boolean onMyNetwork(String hostPort) {
		try {
			String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.lastIndexOf(':')) : hostPort;
			if (!host.matches("[0-9.]+")) {
				return false;
			}
			byte[] target = java.net.InetAddress.getByName(host).getAddress();
			var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				java.net.NetworkInterface nic = interfaces.nextElement();
				if (!nic.isUp() || nic.isLoopback()) {
					continue;
				}
				for (java.net.InterfaceAddress address : nic.getInterfaceAddresses()) {
					byte[] mine = address.getAddress().getAddress();
					if (mine.length == 4 && com.javahost.util.Subnets.same(mine, target, address.getNetworkPrefixLength())) {
						return true;
					}
				}
			}
		} catch (Exception e) {
			JavaHost.LOGGER.debug("Couldn't check the local network", e);
		}
		return false;
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
}

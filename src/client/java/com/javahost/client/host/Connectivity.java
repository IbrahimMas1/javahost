package com.javahost.client.host;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.net.InviteCode;
import com.javahost.net.NetAddresses;
import com.javahost.net.UPnP;
import net.minecraft.client.Minecraft;

/**
 * Works out how friends can reach the host's PC, trying each way in order:
 * <ol>
 * <li><b>Internet (IPv4)</b>: the router forwards the game port to us (UPnP).</li>
 * <li><b>IPv6</b>: no NAT involved; the router's firewall is asked to let the port through.</li>
 * <li><b>Relay</b>: e4mc tunnels connections when neither of the above works (e.g. CGNAT).</li>
 * <li><b>Same Wi-Fi</b>: always works for people in the same home.</li>
 * </ol>
 * The host's PC is the game server in every case; the relay only forwards traffic.
 */
public final class Connectivity {
	public enum State { CHECKING, WORKING, MAYBE, UNAVAILABLE, OFF }

	public enum RelayMode { AUTO, ALWAYS, NEVER }

	/** One way of connecting, for display. {@code invite} is what to send a friend (code or address). */
	public record Route(String name, State state, String invite, String detail) {
	}

	private static volatile boolean active;
	private static volatile boolean probing;
	private static int port;
	private static volatile UPnP gateway;
	private static volatile String lanIp;
	private static volatile String publicIpv4;
	private static volatile boolean portMapped;
	private static volatile boolean cgnat;
	private static volatile String ipv6;
	private static volatile String ipv6Pinhole;
	private static volatile State relayState = State.OFF;
	private static volatile String relayDomain;
	private static volatile String relayNote = "";
	private static volatile RelayMode relayMode = RelayMode.AUTO;

	private Connectivity() {
	}

	/** Begins probing in the background. {@code relayAlreadyOn} means e4mc was enabled before publishing. */
	static void start(int gamePort, boolean relayAlreadyOn) {
		active = true;
		probing = true;
		port = gamePort;
		gateway = null;
		lanIp = null;
		publicIpv4 = null;
		portMapped = false;
		cgnat = false;
		ipv6 = null;
		ipv6Pinhole = null;
		relayDomain = null;
		relayNote = "";
		relayState = relayAlreadyOn ? State.CHECKING : State.OFF;
		Config config = Config.get();
		relayMode = RelayMode.valueOf(config.relayMode);
		CompletableFuture.runAsync(() -> probe(config.useUpnp, config.useIpv6, relayMode));
	}

	static void stop() {
		active = false;
		UPnP router = gateway;
		int openedPort = port;
		boolean mapped = portMapped;
		String pinhole = ipv6Pinhole;
		portMapped = false;
		ipv6Pinhole = null;
		relayDomain = null;
		relayState = State.OFF;
		E4mcCompat.stopRelay();
		E4mcCompat.setRelayEnabled(false);
		if (router != null && (mapped || pinhole != null)) {
			CompletableFuture.runAsync(() -> {
				if (mapped) {
					router.closePort(openedPort);
				}
				router.closeIpv6Pinhole(pinhole);
			});
		}
	}

	private static void probe(boolean useUpnp, boolean useIpv6, RelayMode relayMode) {
		try {
			lanIp = NetAddresses.lanIpv4();
			String external = null;
			if (useUpnp) {
				UPnP router = UPnP.discover();
				gateway = router;
				if (router != null) {
					lanIp = router.localAddress();
					portMapped = router.openPort(port);
					external = router.externalAddress();
				}
			}
			if (external == null) {
				external = lookUpPublicIpv4();
			}
			if (external != null && UPnP.isPrivateAddress(external)) {
				// The router itself is behind the ISP's NAT, so nothing from outside can reach it.
				cgnat = true;
				external = null;
			}
			publicIpv4 = external;

			if (useIpv6) {
				ipv6 = NetAddresses.globalIpv6();
				if (ipv6 != null && gateway != null) {
					ipv6Pinhole = gateway.openIpv6Pinhole(ipv6, port);
				}
			}

			if (!active) {
				stop();
				return;
			}

			boolean directWorks = portMapped && publicIpv4 != null;
			boolean wantRelay = relayMode == RelayMode.ALWAYS || (relayMode == RelayMode.AUTO && !directWorks);
			if (wantRelay && relayState == State.OFF) {
				if (E4mcCompat.isInstalled()) {
					relayState = State.CHECKING;
					Minecraft.getInstance().execute(HostController::restartWithRelay);
				} else {
					relayState = State.UNAVAILABLE;
					relayNote = "Install the relay (e4mc) so friends anywhere can join.";
				}
			}
		} catch (RuntimeException e) {
			JavaHost.LOGGER.warn("Connection check failed", e);
		} finally {
			probing = false;
		}
	}

	private static String lookUpPublicIpv4() {
		try {
			HttpURLConnection connection = (HttpURLConnection) URI.create("https://api.ipify.org").toURL().openConnection();
			connection.setConnectTimeout(4000);
			connection.setReadTimeout(4000);
			try (InputStream in = connection.getInputStream()) {
				String ip = new String(in.readNBytes(64), StandardCharsets.US_ASCII).trim();
				return ip.matches("[0-9.]+") ? ip : null;
			}
		} catch (IOException e) {
			return null;
		}
	}

	static void onRelayDomain(String domain) {
		if (!active) {
			return;
		}
		relayDomain = domain;
		relayState = State.WORKING;
		JavaHost.LOGGER.info("Relay ready at {}", domain);
	}

	/** Watches for the relay failing to start, since e4mc doesn't announce failures to us directly. */
	static void tick() {
		if (relayState == State.CHECKING && !probing) {
			String state = E4mcCompat.relayState();
			if ("UNHEALTHY".equals(state) || "STOPPED".equals(state)) {
				relayState = State.UNAVAILABLE;
				relayNote = "The relay couldn't connect. Check your internet connection.";
			}
		}
	}

	// ---------------------------------------------------------------- read by the UI and tests

	public static boolean isProbing() {
		return probing;
	}

	public static String lanIp() {
		return lanIp;
	}

	public static String relayDomain() {
		return relayDomain;
	}

	public static List<Route> routes() {
		List<Route> routes = new ArrayList<>();
		String v4Code = publicIpv4 == null ? null : InviteCode.encode(publicIpv4, port);

		if (probing) {
			routes.add(new Route("Internet", State.CHECKING, null, "Asking your router to open the port..."));
		} else if (portMapped && publicIpv4 != null) {
			routes.add(new Route("Internet", State.WORKING, v4Code, "Router opened port " + port + "."));
		} else if (cgnat) {
			routes.add(new Route("Internet", State.UNAVAILABLE, null,
					"Your provider shares one public address between homes (CGNAT), so direct connections can't get in."));
		} else if (publicIpv4 != null) {
			routes.add(new Route("Internet", State.MAYBE, v4Code,
					"Your router didn't open port " + port + ". Works only if you forward TCP " + port + " to "
							+ (lanIp != null ? lanIp : "this PC") + " yourself."));
		} else {
			routes.add(new Route("Internet", State.UNAVAILABLE, null, "Couldn't find your public address."));
		}

		if (ipv6 != null) {
			String code = InviteCode.encode(ipv6, port);
			routes.add(ipv6Pinhole != null
					? new Route("IPv6", State.WORKING, code, "Router opened its IPv6 firewall. Friends need IPv6 too.")
					: new Route("IPv6", State.MAYBE, code, "Works for friends with IPv6 if your router's firewall allows it."));
		} else if (!probing) {
			routes.add(new Route("IPv6", State.UNAVAILABLE, null, "Your connection has no public IPv6 address."));
		}

		switch (relayState) {
			case WORKING -> routes.add(new Route("Relay", State.WORKING, relayDomain,
					"Works for anyone, even without mods. Traffic passes through e4mc's free relay."));
			case CHECKING -> routes.add(new Route("Relay", State.CHECKING, null, "Starting the relay..."));
			case UNAVAILABLE -> routes.add(new Route("Relay", State.UNAVAILABLE, null, relayNote));
			default -> routes.add(new Route("Relay", State.OFF, null, relayMode == RelayMode.NEVER
					? "Turned off in the host settings."
					: probing ? "Only used if direct connections fail." : "Not needed: friends can connect directly."));
		}

		if (lanIp != null) {
			routes.add(new Route("Same Wi-Fi", State.WORKING, lanIp + ":" + port, "For people on your home network."));
		}
		return routes;
	}

	/** What to tell friends: the most widely working route that is up. */
	public static Route recommended() {
		List<Route> routes = routes();
		for (String preferred : List.of("Internet", "Relay", "IPv6")) {
			for (Route route : routes) {
				if (route.name().equals(preferred) && route.state() == State.WORKING) {
					return route;
				}
			}
		}
		for (Route route : routes) {
			if (route.invite() != null && route.state() != State.UNAVAILABLE) {
				return route;
			}
		}
		return null;
	}
}

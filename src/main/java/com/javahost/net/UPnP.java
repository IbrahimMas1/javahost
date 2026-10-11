package com.javahost.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A small UPnP Internet Gateway Device client. It asks the home router to forward the game port
 * to this computer, which is what lets friends outside the house join without anyone renting a server
 * or editing router settings. Most home routers support this; some have it switched off.
 */
public final class UPnP {
	private static final String[] SEARCH_TARGETS = {
			"urn:schemas-upnp-org:device:InternetGatewayDevice:1",
			"urn:schemas-upnp-org:device:InternetGatewayDevice:2",
			"urn:schemas-upnp-org:service:WANIPConnection:1",
			"urn:schemas-upnp-org:service:WANIPConnection:2",
			"urn:schemas-upnp-org:service:WANPPPConnection:1",
			"urn:schemas-upnp-org:service:WANIPv6FirewallControl:1",
	};

	private final URL controlUrl;
	private final String serviceType;
	private final String localAddress;
	/** IGDv2 IPv6 firewall service, used to let friends reach us over IPv6; {@code null} if the router lacks it. */
	private final URL ipv6ControlUrl;
	private final String ipv6ServiceType;

	private UPnP(URL controlUrl, String serviceType, String localAddress, URL ipv6ControlUrl, String ipv6ServiceType) {
		this.controlUrl = controlUrl;
		this.serviceType = serviceType;
		this.localAddress = localAddress;
		this.ipv6ControlUrl = ipv6ControlUrl;
		this.ipv6ServiceType = ipv6ServiceType;
	}

	/** Finds the router on the local network. Blocks for up to a few seconds; returns {@code null} if none answers. */
	public static UPnP discover() {
		Set<String> locations = new LinkedHashSet<>();
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.setSoTimeout(1500);
			InetAddress group = InetAddress.getByName("239.255.255.250");
			for (String target : SEARCH_TARGETS) {
				byte[] request = ("M-SEARCH * HTTP/1.1\r\n"
						+ "HOST: 239.255.255.250:1900\r\n"
						+ "ST: " + target + "\r\n"
						+ "MAN: \"ssdp:discover\"\r\n"
						+ "MX: 2\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
				socket.send(new DatagramPacket(request, request.length, group, 1900));
			}
			long deadline = System.currentTimeMillis() + 3000;
			byte[] buffer = new byte[2048];
			while (System.currentTimeMillis() < deadline) {
				DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
				try {
					socket.receive(reply);
				} catch (SocketTimeoutException e) {
					if (!locations.isEmpty()) {
						break;
					}
					continue;
				}
				String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII);
				for (String line : text.split("\r\n")) {
					if (line.toLowerCase(Locale.ROOT).startsWith("location:")) {
						locations.add(line.substring(9).trim());
					}
				}
			}
		} catch (IOException e) {
			return null;
		}

		for (String location : locations) {
			try {
				UPnP gateway = fromDescription(URI.create(location).toURL());
				if (gateway != null) {
					return gateway;
				}
			} catch (Exception ignored) {
				// Try the next device that answered.
			}
		}
		return null;
	}

	private static UPnP fromDescription(URL location) throws Exception {
		Document doc;
		HttpURLConnection connection = (HttpURLConnection) location.openConnection();
		connection.setConnectTimeout(3000);
		connection.setReadTimeout(3000);
		try (InputStream in = connection.getInputStream()) {
			doc = parse(in.readAllBytes());
		}

		URL base = location;
		NodeList urlBase = doc.getElementsByTagName("URLBase");
		if (urlBase.getLength() > 0 && !urlBase.item(0).getTextContent().isBlank()) {
			base = URI.create(urlBase.item(0).getTextContent().trim()).toURL();
		}

		URL connectionUrl = null;
		String connectionType = null;
		URL firewallUrl = null;
		String firewallType = null;
		NodeList services = doc.getElementsByTagName("service");
		for (int i = 0; i < services.getLength(); i++) {
			Element service = (Element) services.item(i);
			String type = childText(service, "serviceType");
			String control = childText(service, "controlURL");
			if (type == null || control == null) {
				continue;
			}
			if (connectionUrl == null && (type.contains("WANIPConnection") || type.contains("WANPPPConnection"))) {
				connectionUrl = base.toURI().resolve(control).toURL();
				connectionType = type;
			} else if (firewallUrl == null && type.contains("WANIPv6FirewallControl")) {
				firewallUrl = base.toURI().resolve(control).toURL();
				firewallType = type;
			}
		}
		if (connectionUrl == null) {
			return null;
		}
		return new UPnP(connectionUrl, connectionType, localAddressTowards(location), firewallUrl, firewallType);
	}

	/** The LAN address of this computer as seen from the router (the one the forward must point at). */
	private static String localAddressTowards(URL url) throws IOException {
		int port = url.getPort() == -1 ? 80 : url.getPort();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(url.getHost(), port), 3000);
			return socket.getLocalAddress().getHostAddress();
		}
	}

	public String localAddress() {
		return localAddress;
	}

	/** Forwards {@code port} (TCP) on the router to this computer. */
	public boolean openPort(int port) {
		for (int lease : new int[] {0, 7200}) {
			try {
				soap("AddPortMapping",
						"<NewRemoteHost></NewRemoteHost>"
								+ "<NewExternalPort>" + port + "</NewExternalPort>"
								+ "<NewProtocol>TCP</NewProtocol>"
								+ "<NewInternalPort>" + port + "</NewInternalPort>"
								+ "<NewInternalClient>" + localAddress + "</NewInternalClient>"
								+ "<NewEnabled>1</NewEnabled>"
								+ "<NewPortMappingDescription>JavaHost Minecraft</NewPortMappingDescription>"
								+ "<NewLeaseDuration>" + lease + "</NewLeaseDuration>");
				return true;
			} catch (IOException ignored) {
				// Some routers only accept a finite lease, so retry with one.
			}
		}
		return false;
	}

	public void closePort(int port) {
		try {
			soap("DeletePortMapping",
					"<NewRemoteHost></NewRemoteHost>"
							+ "<NewExternalPort>" + port + "</NewExternalPort>"
							+ "<NewProtocol>TCP</NewProtocol>");
		} catch (IOException ignored) {
			// Mapping already gone or router unreachable; nothing more to do.
		}
	}

	/**
	 * Asks the router's IPv6 firewall to let anyone reach {@code ipv6Address:port} over TCP (an IGDv2 "pinhole").
	 * Returns the pinhole id needed to close it again, or {@code null} if the router can't or won't.
	 */
	public String openIpv6Pinhole(String ipv6Address, int port) {
		if (ipv6ControlUrl == null) {
			return null;
		}
		try {
			Document reply = soap(ipv6ControlUrl, ipv6ServiceType, "AddPinhole",
					"<RemoteHost></RemoteHost>"
							+ "<RemotePort>0</RemotePort>"
							+ "<InternalClient>" + ipv6Address + "</InternalClient>"
							+ "<InternalPort>" + port + "</InternalPort>"
							+ "<Protocol>6</Protocol>"
							+ "<LeaseTime>7200</LeaseTime>");
			NodeList ids = reply.getElementsByTagName("UniqueID");
			return ids.getLength() > 0 ? ids.item(0).getTextContent().trim() : null;
		} catch (IOException e) {
			return null;
		}
	}

	public void closeIpv6Pinhole(String pinholeId) {
		if (ipv6ControlUrl == null || pinholeId == null) {
			return;
		}
		try {
			soap(ipv6ControlUrl, ipv6ServiceType, "DeletePinhole", "<UniqueID>" + pinholeId + "</UniqueID>");
		} catch (IOException ignored) {
			// It expires on its own after the lease time.
		}
	}

	public boolean hasIpv6Firewall() {
		return ipv6ControlUrl != null;
	}

	/** The router's public address, or {@code null} if it won't say. */
	public String externalAddress() {
		try {
			Document reply = soap("GetExternalIPAddress", "");
			NodeList nodes = reply.getElementsByTagName("NewExternalIPAddress");
			if (nodes.getLength() > 0) {
				String ip = nodes.item(0).getTextContent().trim();
				return ip.isEmpty() ? null : ip;
			}
		} catch (IOException ignored) {
			// Fall through.
		}
		return null;
	}

	private Document soap(String action, String arguments) throws IOException {
		return soap(controlUrl, serviceType, action, arguments);
	}

	private static Document soap(URL controlUrl, String serviceType, String action, String arguments) throws IOException {
		String body = "<?xml version=\"1.0\"?>\r\n"
				+ "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
				+ "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
				+ "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
				+ arguments
				+ "</u:" + action + "></s:Body></s:Envelope>";
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

		HttpURLConnection connection = (HttpURLConnection) controlUrl.openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setConnectTimeout(3000);
		connection.setReadTimeout(5000);
		connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
		connection.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
		connection.setFixedLengthStreamingMode(bytes.length);
		try (OutputStream out = connection.getOutputStream()) {
			out.write(bytes);
		}
		int status = connection.getResponseCode();
		if (status != 200) {
			throw new IOException("Router refused " + action + " (HTTP " + status + ")");
		}
		try (InputStream in = connection.getInputStream()) {
			return parse(in.readAllBytes());
		} catch (Exception e) {
			throw new IOException("Bad reply to " + action, e);
		}
	}

	private static Document parse(byte[] xml) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// Router replies are untrusted input: no DTDs, no external entities.
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(new ByteArrayInputStream(xml));
		} catch (Exception e) {
			throw new IOException("Invalid XML from router", e);
		}
	}

	private static String childText(Element parent, String tag) {
		NodeList nodes = parent.getElementsByTagName(tag);
		return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
	}

	/** True for addresses that can't be reached from the internet (a sign of carrier-grade NAT). */
	public static boolean isPrivateAddress(String ip) {
		try {
			InetAddress address = InetAddress.getByName(ip);
			if (address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
				return true;
			}
			byte[] b = address.getAddress();
			// 100.64.0.0/10 is reserved for ISP carrier-grade NAT.
			return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
		} catch (IOException e) {
			return false;
		}
	}
}

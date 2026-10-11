package com.javahost.client.host;

import java.lang.reflect.Field;

import com.javahost.JavaHost;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Optional integration with e4mc, a free open-source relay (https://e4mc.link). When a friend can't reach the
 * host's PC directly (router won't open the port, or the ISP uses CGNAT), e4mc tunnels the connection through
 * its servers and gives the world a domain anyone can join, even players without mods.
 *
 * <p>e4mc starts its relay automatically whenever a world is opened to LAN. JavaHost keeps that switched off
 * and only turns it on when direct connections aren't possible (or the player chose "always relay").
 * Everything goes through reflection so JavaHost runs fine without e4mc installed.
 */
public final class E4mcCompat {
	private static final String DOMAIN_MESSAGE_KEY = "text.e4mc_minecraft.domainAssigned";

	private E4mcCompat() {
	}

	public static boolean isInstalled() {
		return FabricLoader.getInstance().isModLoaded("e4mc");
	}

	/** Whether e4mc should start its relay the next time the world is opened to LAN. Not saved to e4mc's config. */
	public static void setRelayEnabled(boolean enabled) {
		if (!isInstalled()) {
			return;
		}
		try {
			Class<?> config = Class.forName("link.e4mc.Config");
			Object instance = config.getField("INSTANCE").get(null);
			Object hostEnabled = config.getField("hostEnabled").get(instance);
			hostEnabled.getClass().getMethod("setOverride", Object.class).invoke(hostEnabled, enabled);
		} catch (ReflectiveOperationException | RuntimeException e) {
			JavaHost.LOGGER.warn("Could not configure e4mc", e);
		}
	}

	/** Shuts the relay down if it's running. */
	public static void stopRelay() {
		if (!isInstalled()) {
			return;
		}
		try {
			Field sessionField = Class.forName("link.e4mc.E4mcClient").getField("session");
			Object session = sessionField.get(null);
			if (session != null) {
				session.getClass().getMethod("stop").invoke(session);
				sessionField.set(null, null);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			JavaHost.LOGGER.warn("Could not stop e4mc", e);
		}
	}

	/**
	 * e4mc 6.2.3 starts a non-daemon thread pool when this player joins a world through a relay, and never
	 * stops it, so Minecraft hangs on quit and reports a crash. Shutting it down when the game closes fixes that.
	 */
	public static void shutdownForExit() {
		if (!isInstalled()) {
			return;
		}
		try {
			Class<?> sessionClass = Class.forName("link.e4mc.dialtone.DialtoneAmbientSession");
			Object session = sessionClass.getField("INSTANCE").get(null);
			Object group = sessionClass.getField("group").get(session);
			if (group != null) {
				Class.forName("io.netty.util.concurrent.EventExecutorGroup")
						.getMethod("shutdownGracefully", long.class, long.class, java.util.concurrent.TimeUnit.class)
						.invoke(group, 0L, 1L, java.util.concurrent.TimeUnit.SECONDS);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			JavaHost.LOGGER.debug("Could not stop e4mc's background threads", e);
		}
	}

	/** The relay session's state ("STARTING", "STARTED", "UNHEALTHY", ...), or {@code null} if none is running. */
	public static String relayState() {
		if (!isInstalled()) {
			return null;
		}
		try {
			Object session = Class.forName("link.e4mc.E4mcClient").getField("session").get(null);
			if (session == null) {
				return null;
			}
			Object state = session.getClass().getField("state").get(session);
			return state == null ? null : state.toString();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Called for every client chat message. e4mc announces the relay domain in chat; the domain is also the
	 * message's copy-to-clipboard value, which works even when e4mc is set to hide it.
	 */
	public static void onChatMessage(Component message) {
		if (!(message.getContents() instanceof TranslatableContents translatable)) {
			// e4mc appends its "click to stop" text, so the translatable part may be the first sibling.
			if (!message.getSiblings().isEmpty()) {
				onChatMessage(message.getSiblings().getFirst());
			}
			return;
		}
		if (!DOMAIN_MESSAGE_KEY.equals(translatable.getKey()) || translatable.getArgs().length == 0) {
			return;
		}
		if (translatable.getArgs()[0] instanceof Component domainPart
				&& domainPart.getStyle().getClickEvent() instanceof ClickEvent.CopyToClipboard copy) {
			Connectivity.onRelayDomain(copy.value());
		}
	}
}

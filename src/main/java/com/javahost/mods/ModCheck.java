package com.javahost.mods;

import java.util.function.Consumer;

import com.javahost.JavaHost;
import com.javahost.net.Payloads;
import com.javahost.util.ModManifest;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.players.NameAndId;

/**
 * Before Minecraft compares the game's registries with a joining player's (and fails with "registry mismatch" when
 * mods differ), JavaHost sends the list of mods the world needs. A JavaHost player who's missing some answers so,
 * gets a friendly message, and their game offers to download exactly those mods.
 */
public final class ModCheck {
	private static final Identifier EARLY = Identifier.fromNamespaceAndPath(JavaHost.MOD_ID, "mod_check");

	private ModCheck() {
	}

	private record Task(MinecraftServer server) implements ConfigurationTask {
		static final Type TYPE = new Type("javahost:mod_check");

		@Override
		public void start(Consumer<Packet<?>> sender) {
			// Usually ready long before anyone joins; if not, send it as soon as it is.
			ModScanner.manifest().exceptionally(error -> {
				JavaHost.LOGGER.warn("[Mods] couldn't work out the mod list; joining as usual", error);
				return new ModManifest("", "", java.util.List.of());
			}).thenAccept(manifest -> server.execute(() -> {
				var profile = server.getSingleplayerProfile();
				ModManifest named = new ModManifest(profile == null ? "" : profile.name(), manifest.pack(), manifest.mods());
				sender.accept(ServerConfigurationNetworking.createClientboundPacket(new Payloads.ModList(named.toJson())));
			}));
		}

		@Override
		public Type type() {
			return TYPE;
		}
	}

	public static void register() {
		// Ahead of Fabric's own registry check, which runs in the default phase.
		ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
		ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(EARLY, (handler, server) -> {
			JavaHost.LOGGER.info("[Mods] {} is joining: owner={}, has JavaHost 1.9={}, mod list ready={}", handler.getOwner().name(),
					server.isSingleplayerOwner(new NameAndId(handler.getOwner())), ServerConfigurationNetworking.canSend(handler, Payloads.ModList.TYPE),
					ModScanner.ready() != null);
			if (server.isSingleplayerOwner(new NameAndId(handler.getOwner()))
					|| !ServerConfigurationNetworking.canSend(handler, Payloads.ModList.TYPE)) {
				return; // the host, or a player without JavaHost 1.9 (Minecraft's own check still applies)
			}
			ModManifest ready = ModScanner.ready();
			if (ready != null && ready.empty()) {
				return; // nothing modded to check
			}
			handler.addTask(new Task(server));
		});
		ServerConfigurationNetworking.registerGlobalReceiver(Payloads.ModCheckReply.TYPE, (reply, context) -> {
			if (reply.missing() <= 0) {
				context.packetListener().completeTask(Task.TYPE);
			} else {
				JavaHost.LOGGER.info("[Mods] {} is missing {} mod(s); their game will offer to get them", context.packetListener().getOwner().name(),
						reply.missing());
				context.packetListener().disconnect(Component.literal("This world needs " + reply.missing() + " mod"
						+ (reply.missing() == 1 ? "" : "s") + " you don't have yet. JavaHost can download them for you."));
			}
		});
	}

	/** Starts working out the mod list early, so it's ready before the first friend joins. */
	public static void warmUp() {
		ModScanner.manifest();
	}
}

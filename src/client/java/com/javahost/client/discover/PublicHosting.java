package com.javahost.client.discover;

import java.util.List;

import com.google.gson.JsonObject;
import com.javahost.HostState;
import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.Toasts;
import com.javahost.client.friends.Identity;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.E4mcCompat;
import com.javahost.client.host.HostController;
import com.javahost.client.host.WorldBackups;
import com.javahost.util.ListingText;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * Opening your world to the public: hosting through the relay only, listed on the public list with a heartbeat
 * every 3 minutes, and taken off the list when you stop, leave or close the game.
 */
public final class PublicHosting {
	public record Settings(String title, String description, List<String> tags, int max, boolean voice) {
	}

	private static final long HEARTBEAT_MS = 3 * 60_000L;
	private static boolean listed;
	private static boolean busy;
	private static long lastBeat;
	private static String status = "";

	private PublicHosting() {
	}

	public static boolean isPublic() {
		return HostState.publicWorld();
	}

	public static boolean listed() {
		return listed;
	}

	/** What's going on, for the Host tab: "Getting the relay ready...", "Listed", or why not. */
	public static String status() {
		return status;
	}

	/** Why the world can't be opened to the public right now, or null. */
	public static String cannotOpen() {
		String blocked = Directory.blockedReason();
		if (blocked != null) {
			return blocked;
		}
		if (!Directory.available()) {
			return "Public worlds aren't available yet.";
		}
		if (!E4mcCompat.isInstalled()) {
			return "Public worlds need the relay (e4mc). Get it on the Performance tab.";
		}
		if (Minecraft.getInstance().getSingleplayerServer() == null) {
			return "Open one of your worlds first.";
		}
		return null;
	}

	/** Opens the world to the public (render thread). Returns a problem to show, or null. */
	public static String open(Settings settings) {
		String problem = cannotOpen();
		if (problem == null) {
			problem = ListingText.problem(settings.title(), settings.description());
		}
		if (problem != null) {
			return problem;
		}
		Config config = Config.get();
		config.publicTitle = ListingText.clean(settings.title(), ListingText.TITLE_MAX);
		config.publicDescription = ListingText.clean(settings.description(), ListingText.DESCRIPTION_MAX);
		config.publicTags = List.copyOf(settings.tags());
		config.publicMax = settings.max();
		config.publicVoice = settings.voice();
		config.save();

		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		// Reopen through the relay only (no router or IPv6 openings), with the public join rules.
		if (HostController.isHosting()) {
			HostController.stop();
		}
		HostState.setPublic(true, settings.voice());
		HostState.setMaxPlayers(settings.max());
		String error = HostController.start(server);
		if (error != null) {
			HostState.setPublic(false, false);
			return error;
		}
		HostState.setMaxPlayers(settings.max());
		listed = false;
		lastBeat = 0;
		status = "Getting the relay ready...";
		// A fresh backup before any stranger arrives, so griefing can always be undone.
		server.execute(() -> WorldBackups.backUp(server, "opened to the public"));
		JavaHost.LOGGER.info("[Public] opening \"{}\" to the public", config.publicTitle);
		return null;
	}

	/** Takes the world off the list and back to friends only (keeps hosting for friends). */
	public static void close() {
		if (!HostState.publicWorld()) {
			return;
		}
		HostState.setPublic(false, false);
		HostController.applyHostRules(Config.get()); // friends-only and the usual player limit again
		if (listed) {
			Directory.unpublish();
		}
		listed = false;
		status = "";
		JavaHost.LOGGER.info("[Public] closed to the public");
	}

	/** Every client tick: publish once the relay is up, keep the listing alive, close when hosting stops. */
	public static void tick() {
		if (!HostState.publicWorld()) {
			return;
		}
		if (!HostController.isHosting()) {
			close();
			return;
		}
		if (busy) {
			return;
		}
		long now = System.currentTimeMillis();
		String relay = Connectivity.relayDomain();
		if (!listed && relay != null && ListingText.isRelayAddress(relay)) {
			publish(relay);
		} else if (listed && now - lastBeat > HEARTBEAT_MS) {
			busy = true;
			lastBeat = now;
			Directory.heartbeat(HostController.guests().size() + 1).thenAccept(result -> Minecraft.getInstance().execute(() -> {
				busy = false;
				if (!result.ok()) {
					listed = false; // e.g. the list forgot us after a long hiccup: list again on the next tick
				}
			}));
		}
	}

	private static void publish(String relay) {
		Config config = Config.get();
		JsonObject listing = new JsonObject();
		listing.addProperty("title", config.publicTitle);
		listing.addProperty("description", config.publicDescription);
		listing.add("tags", Directory.tagsJson(config.publicTags));
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		String mode = server == null ? "survival" : server.getDefaultGameType().getName();
		listing.addProperty("mode", mode.equals("spectator") ? "adventure" : mode);
		listing.addProperty("difficulty", server == null ? "normal" : server.getWorldData().getDifficulty().getSerializedName());
		listing.addProperty("max", config.publicMax);
		listing.addProperty("relay", relay);
		listing.addProperty("mc", SharedConstants.getCurrentVersion().name());
		listing.addProperty("jv", JavaHost.VERSION);
		listing.addProperty("voice", config.publicVoice);
		listing.addProperty("signkey", Identity.signPublicKey());
		busy = true;
		status = "Adding your world to the public list...";
		Directory.publish(listing).thenAccept(result -> Minecraft.getInstance().execute(() -> {
			busy = false;
			if (!HostState.publicWorld()) {
				if (result.ok()) {
					Directory.unpublish(); // closed while we were listing
				}
				return;
			}
			if (result.ok()) {
				listed = true;
				lastBeat = System.currentTimeMillis();
				status = "Listed: anyone with JavaHost can find it in Discover";
				Toasts.notify("Your world is public", "\"" + config.publicTitle + "\" is in the public list. Visitors can't build until you Trust them.");
			} else {
				status = result.message();
				lastBeat = System.currentTimeMillis();
				// Try again in a minute (e.g. no internet); a rejected listing needs the host to change it.
				if (!result.message().contains("friendly") && !result.message().contains("aren't allowed")) {
					busy = true;
					java.util.concurrent.CompletableFuture.delayedExecutor(60, java.util.concurrent.TimeUnit.SECONDS)
							.execute(() -> Minecraft.getInstance().execute(() -> busy = false));
				} else {
					close();
				}
			}
		}));
	}

	/** The game is closing: take the listing down (briefly waits so it really goes). */
	public static void shutdown() {
		if (listed) {
			try {
				Directory.unpublish().get(2, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception ignored) {
				// It disappears by itself 7 minutes after the last heartbeat anyway.
			}
		}
		listed = false;
		HostState.setPublic(false, false);
	}
}

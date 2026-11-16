package com.javahost.client.skins;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.javahost.JavaHost;
import com.javahost.client.compat.ClientCompat;
import com.javahost.client.friends.Identity;
import com.javahost.util.IoExecutor;
import com.javahost.util.SkinApi;
import com.javahost.util.SkinFolder;
import com.javahost.util.SkinImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Wearing skins: Minecraft's profile service ({@link SkinApi}) with the game's own session, one change at a time
 * with a short pause between changes, plus copying anyone's skin by name. Once a skin is worn the menu (and your own
 * player in a world) shows it at once through {@link #ownSkin}, instead of waiting for the game to look it up again.
 * <p>
 * The access token is read from the game's session only at the moment a call is made and handed straight to
 * {@link SkinApi}, which sends it to {@code https://api.minecraftservices.com} and nowhere else. It is never logged
 * or shown.
 */
public final class SkinService {
	/** A pause after each change, so a flurry of clicks can't run into Minecraft's limit. */
	private static final long COOLDOWN_MS = 4000;

	private static volatile SkinApi.Profile profile;
	private static volatile boolean profileLoading;
	private static volatile String profileError;
	/** The skin being put on now (a library id, or "default"), or null. */
	private static volatile String changing;
	private static volatile long cooldownUntil;
	private static volatile int version;
	/** A message for the Skins page (what went wrong, or what happened). */
	private static volatile String message;
	private static volatile boolean messageIsError;
	private static volatile long messageAt;

	/** What the menu shows as your skin since you changed it this session: a library texture, or the default skin. */
	private static volatile Identifier ownTexture;
	private static volatile boolean ownSlim;
	private static volatile boolean ownDefault;

	private SkinService() {
	}

	// ---------------------------------------------------------------- who we talk to

	/** The development test server standing in for Mojang's, if one is set (never in a real player's game). */
	private static String devBase() {
		String base = System.getProperty("javahost.skinApi");
		return base != null && !base.isBlank() && FabricLoader.getInstance().isDevelopmentEnvironment() ? base : null;
	}

	private static SkinApi api() {
		String dev = devBase();
		return dev != null ? SkinApi.dev(dev) : SkinApi.real(Minecraft.getInstance().getUser().getAccessToken());
	}

	/** Whether this game is signed in to a Microsoft account (or talking to the test server), so skins can be worn. */
	public static boolean canWear() {
		return devBase() != null || SkinApi.looksLikeSession(Minecraft.getInstance().getUser().getAccessToken());
	}

	public static final String SIGN_IN = "Sign in with Microsoft to wear skins.";

	// ---------------------------------------------------------------- state for the page

	public static int version() {
		return version;
	}

	private static void bump() {
		version++;
	}

	public static SkinApi.Profile profile() {
		return profile;
	}

	public static boolean profileLoading() {
		return profileLoading;
	}

	public static String profileError() {
		return profileError;
	}

	/** The library id (or "default") being put on right now, or null. */
	public static String changing() {
		return changing;
	}

	public static boolean busy() {
		return changing != null || System.currentTimeMillis() < cooldownUntil;
	}

	/** The latest message for the player, for a while after it happened (null when there's nothing to say). */
	public static String message() {
		return message != null && System.currentTimeMillis() - messageAt < 12_000 ? message : null;
	}

	public static boolean messageIsError() {
		return messageIsError;
	}

	public static void say(String text, boolean error) {
		message = text;
		messageIsError = error;
		messageAt = System.currentTimeMillis();
		bump();
	}

	public static void clearMessage() {
		message = null;
		bump();
	}

	/** Whether {@code entry} is what the account wears now (with the same arms, when we know them). */
	public static boolean wearing(SkinFolder.Entry entry) {
		if (entry == null || !entry.id().equals(SkinLibrary.worn())) {
			return false;
		}
		SkinApi.Profile known = profile;
		return known == null || known.skinUrl() == null || known.slim() == entry.slim();
	}

	// ---------------------------------------------------------------- the account

	private static boolean importedThisSession;

	/**
	 * Reads the account's profile (active skin and capes). The first time, and whenever the account's skin changed
	 * elsewhere (the launcher, the website), the skin worn now is saved to the collection as "My skin", so there's
	 * always a way back to it.
	 */
	public static void refreshProfile() {
		if (profileLoading || !canWear()) {
			return;
		}
		profileLoading = true;
		profileError = null;
		bump();
		SkinApi api = api();
		CompletableFuture.runAsync(() -> {
			try {
				SkinApi.Profile p = api.profile();
				profile = p;
				saveCurrent(api, p);
			} catch (SkinApi.Failure e) {
				profileError = e.getMessage();
			} finally {
				profileLoading = false;
				bump();
			}
		}, IoExecutor.NET);
	}

	/** Saves the skin the account wears now into the collection, unless we already know it. */
	private static void saveCurrent(SkinApi api, SkinApi.Profile p) {
		String url = p.skinUrl();
		if (url == null) {
			if (SkinLibrary.worn() != null || SkinLibrary.wornUrl() != null) {
				SkinLibrary.markWorn(null, null).join();
			}
			return;
		}
		if (url.equals(SkinLibrary.wornUrl()) && SkinLibrary.worn() != null) {
			return;
		}
		try {
			byte[] png = api.download(url);
			SkinFolder.Added added = SkinLibrary.add(png, "My skin", p.slim() ? SkinFolder.SLIM : SkinFolder.CLASSIC, "account").join();
			SkinLibrary.markWorn(added.entry().id(), url).join();
			importedThisSession = true;
		} catch (SkinApi.Failure | CompletionException e) {
			JavaHost.LOGGER.debug("[Skins] couldn't save the current skin: {}", e.getClass().getSimpleName());
		}
	}

	/** Puts on a saved skin. */
	public static void wear(SkinFolder.Entry entry) {
		if (entry == null || !startChange(entry.id())) {
			return;
		}
		SkinApi api = api();
		CompletableFuture.supplyAsync(() -> {
			try {
				byte[] png = SkinLibrary.read(entry.id());
				SkinImage.read(png); // still a skin
				return api.uploadSkin(png, entry.slim());
			} catch (SkinApi.Failure e) {
				throw new CompletionException(e);
			} catch (SkinImage.Invalid | java.io.IOException e) {
				throw new CompletionException(new SkinApi.Failure("That skin's file is missing or damaged.", 0));
			}
		}, IoExecutor.NET).thenCompose(p -> {
			profile = p;
			return SkinLibrary.markWorn(entry.id(), p.skinUrl()).thenCompose(v -> SkinLibrary.fullWhenReady(entry));
		}).whenCompleteAsync((texture, error) -> {
			if (error == null) {
				ownTexture = texture;
				ownSlim = entry.slim();
				ownDefault = false;
				say("You're wearing " + entry.name() + ".", false);
			} else {
				failed(error);
			}
			endChange();
		}, Minecraft.getInstance());
	}

	/** Back to Minecraft's default skin (Steve, Alex or one of the others, picked by your account). */
	public static void wearDefault() {
		if (!startChange("default")) {
			return;
		}
		SkinApi api = api();
		CompletableFuture.supplyAsync(() -> {
			try {
				return api.resetSkin();
			} catch (SkinApi.Failure e) {
				throw new CompletionException(e);
			}
		}, IoExecutor.NET).thenCompose(p -> {
			profile = p;
			return SkinLibrary.markWorn(null, null);
		}).whenCompleteAsync((v, error) -> {
			if (error == null) {
				ownTexture = null;
				ownDefault = true;
				say("You're wearing Minecraft's default skin.", false);
			} else {
				failed(error);
			}
			endChange();
		}, Minecraft.getInstance());
	}

	/** Shows a cape the account owns, or none ({@code capeId} null). */
	public static void setCape(String capeId) {
		if (!startChange("cape")) {
			return;
		}
		SkinApi api = api();
		CompletableFuture.supplyAsync(() -> {
			try {
				return capeId == null ? api.hideCape() : api.showCape(capeId);
			} catch (SkinApi.Failure e) {
				throw new CompletionException(e);
			}
		}, IoExecutor.NET).whenCompleteAsync((p, error) -> {
			if (error == null) {
				profile = p;
				say((capeId == null ? "Cape hidden." : "Cape changed.") + " Friends see it when you next join them.", false);
			} else {
				failed(error);
			}
			endChange();
		}, Minecraft.getInstance());
	}

	private static synchronized boolean startChange(String what) {
		if (!canWear()) {
			say(SIGN_IN, true);
			return false;
		}
		if (changing != null) {
			return false;
		}
		if (System.currentTimeMillis() < cooldownUntil) {
			say("One moment: Minecraft needs a short pause between skin changes.", true);
			return false;
		}
		changing = what;
		message = null;
		bump();
		return true;
	}

	private static void endChange() {
		changing = null;
		cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
		bump();
	}

	private static void failed(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		say(cause instanceof SkinApi.Failure ? cause.getMessage() : "That didn't work. Try again.", true);
	}

	/** For tests: lets the next change go without waiting out the pause. */
	public static void skipCooldown() {
		cooldownUntil = 0;
	}

	// ---------------------------------------------------------------- copying a player's skin

	/** A skin fetched from someone's profile, not saved yet. */
	public record Fetched(String name, byte[] png, boolean slim) {
	}

	/** Looks a player up by name and fetches their skin. Fails with a message for the player. */
	public static CompletableFuture<Fetched> fetchPlayer(String name) {
		String valid = SkinApi.validName(name);
		if (valid == null) {
			return CompletableFuture.failedFuture(new SkinApi.Failure("That isn't a Minecraft name (letters, numbers and _ only).", 400));
		}
		SkinApi api = api();
		return CompletableFuture.supplyAsync(() -> {
			try {
				UUID id = api.lookup(valid);
				SkinApi.Textures textures = api.textures(id);
				if (textures.skinUrl() == null) {
					throw new SkinApi.Failure(valid + " wears Minecraft's default skin.", 200);
				}
				byte[] png = api.download(textures.skinUrl());
				SkinImage.read(png); // a real skin
				return new Fetched(valid, png, textures.slim());
			} catch (SkinApi.Failure e) {
				throw new CompletionException(e);
			} catch (SkinImage.Invalid e) {
				throw new CompletionException(new SkinApi.Failure("That player's skin couldn't be read.", 200));
			}
		}, IoExecutor.NET);
	}

	/** The message in a failed future, in the player's words. */
	public static String reason(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause instanceof SkinApi.Failure || cause instanceof SkinImage.Invalid || cause instanceof SkinFolder.Full ? cause.getMessage()
				: "That didn't work. Try again.";
	}

	// ---------------------------------------------------------------- showing it at once

	/**
	 * Your skin as the menu (and your own player) should show it: the one you put on this session, or {@code base}
	 * (what the game knows) when you haven't changed it.
	 */
	public static PlayerSkin ownSkin(PlayerSkin base) {
		Identifier texture = ownTexture;
		if (texture != null) {
			return ClientCompat.localSkin(texture, ownSlim, base);
		}
		if (ownDefault) {
			PlayerSkin fallback = net.minecraft.client.resources.DefaultPlayerSkin.get(Identity.id());
			return ClientCompat.localSkin(ClientCompat.skinTexture(fallback), ClientCompat.slim(fallback), base);
		}
		return base;
	}

	/** Whether the skin was changed this session (so the game's own copy of your profile is out of date). */
	public static boolean changedThisSession() {
		return ownTexture != null || ownDefault;
	}

	/** For tests: whether the current skin was saved to the collection this session. */
	public static boolean importedThisSession() {
		return importedThisSession;
	}
}

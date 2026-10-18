package com.javahost.client.friends;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.javahost.client.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** The notification center: recent invites, requests, messages and screenshots, so a missed popup is never lost. */
public final class Notifications {
	/** INFO: JavaHost itself (backups, updates, installs); {@code name} is the title and {@code player} is null. */
	public enum Kind { INVITE, REQUEST, ACCEPTED, MESSAGE, SCREENSHOT, HOSTING, INFO }

	/** {@code payload} is the thing to act on: an Invite, a Request, a screenshot path, or null. */
	public record Item(Kind kind, UUID player, String name, String text, long time, Object payload) {
	}

	/** A button on a JavaHost notice (an INFO item's payload), e.g. "Close Minecraft" when a mod is ready. */
	public record Action(String label, Runnable run) {
	}

	private static final int MAX = 50;
	private static final List<Item> ITEMS = new ArrayList<>();
	private static int unread;

	private Notifications() {
	}

	public static void add(Kind kind, UUID player, String name, String text, Object payload) {
		// Keep only the newest invite per friend, and drop duplicates of the same message.
		ITEMS.removeIf(item -> item.kind() == kind && java.util.Objects.equals(item.player(), player) && (kind == Kind.INVITE || kind == Kind.HOSTING
				|| kind == Kind.REQUEST || item.text().equals(text)));
		ITEMS.addFirst(new Item(kind, player, name, text, System.currentTimeMillis(), payload));
		while (ITEMS.size() > MAX) {
			ITEMS.removeLast();
		}
		unread++;
		chime(kind);
	}

	/** Counts something that lives elsewhere in the center (like a world offer) as unread, with its chime. */
	public static void ping(Kind kind) {
		unread++;
		chime(kind);
	}

	public static List<Item> items() {
		return List.copyOf(ITEMS);
	}

	public static void remove(Item item) {
		ITEMS.remove(item);
	}

	public static void clear() {
		ITEMS.clear();
		unread = 0;
	}

	public static int unread() {
		return unread;
	}

	public static void markRead() {
		unread = 0;
	}

	private static void chime(Kind kind) {
		if (!Config.get().notificationSounds) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		float pitch = switch (kind) {
			case INVITE, HOSTING -> 1.5f;
			case MESSAGE -> 1.8f;
			default -> 1.2f;
		};
		minecraft.execute(() -> minecraft.getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), pitch, 0.35f)));
	}
}

package com.javahost.client;

import java.util.ArrayDeque;
import java.util.Deque;

import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Event;
import com.javahost.client.friends.Friends.Invite;
import com.javahost.client.friends.Friends.Request;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Notifications;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.FriendPopup;
import com.javahost.client.gui.JavaHostScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

/**
 * Turns friend events into toasts, notification-center entries and popups. Invites pop up right away (they're
 * time-sensitive); friend requests wait until a menu is open rather than interrupting play. Nothing is lost if a
 * popup is dismissed: it stays in the notification center.
 */
public final class Popups {
	private static final Deque<Invite> INVITES = new ArrayDeque<>();
	private static final Deque<Request> REQUESTS = new ArrayDeque<>();

	private Popups() {
	}

	public static void startFriends() {
		com.javahost.client.friends.MessageUsage.install();
		Friends.start(Popups::onEvent);
		Presence.start();
		Friends.selfCheck();
	}

	private static void onEvent(Event event) {
		switch (event) {
			case Event.InviteReceived(Invite invite) -> {
				INVITES.removeIf(old -> old.from().equals(invite.from()));
				INVITES.add(invite);
				Notifications.add(Notifications.Kind.INVITE, invite.from(), invite.name(),
						invite.name() + " invited you to " + invite.world(), invite);
				Toasts.player(invite.from(), invite.name() + " invited you!", "Join their world: " + invite.world());
			}
			case Event.RequestReceived(Request request) -> {
				REQUESTS.add(request);
				Notifications.add(Notifications.Kind.REQUEST, request.id(), request.name(), request.name() + " wants to be friends", request);
				Toasts.player(request.id(), request.name() + " wants to be friends", "Answer in the JavaHost menu (J)");
			}
			case Event.Accepted(Friends.Friend friend) -> {
				Notifications.add(Notifications.Kind.ACCEPTED, friend.id(), friend.name(), friend.name() + " accepted your request", null);
				Toasts.player(friend.id(), friend.name() + " accepted!", "You're friends now");
			}
			case Event.ChatReceived(Friends.Friend friend, String text) -> {
				Notifications.add(Notifications.Kind.MESSAGE, friend.id(), friend.name(), text, friend);
				boolean reading = Minecraft.getInstance().gui.screen() instanceof JavaHostScreen screen && screen.isChattingWith(friend.id());
				if (!reading) {
					Toasts.player(friend.id(), friend.name(), text);
				}
			}
			case Event.ScreenshotReceived(Friends.Friend friend, java.nio.file.Path file, String caption) -> {
				Notifications.add(Notifications.Kind.SCREENSHOT, friend.id(), friend.name(), friend.name() + " sent a screenshot", file);
				Toasts.player(friend.id(), friend.name() + " sent a screenshot", "See it in JavaHost (J)");
			}
		}
	}

	/** Screens a popup may cover. */
	private static boolean calm(Screen screen, boolean allowInGame) {
		return (screen == null && allowInGame) || screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen
				|| screen instanceof PauseScreen || screen instanceof JavaHostScreen;
	}

	public static void tick(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		boolean inWorld = minecraft.level != null;

		while (!INVITES.isEmpty() && INVITES.peek().expired()) {
			INVITES.poll();
		}
		// Invites are time-sensitive: show them while playing too.
		if (!INVITES.isEmpty() && calm(screen, inWorld)) {
			Invite invite = INVITES.poll();
			minecraft.gui.setScreen(new FriendPopup(screen, invite.from(), invite.name() + " invited you to play",
					"Join their world \"" + invite.world() + "\" now?" + (inWorld ? " You'll leave this world first." : ""),
					"Join", "Not now", () -> InviteFlow.join(invite), () -> {
					}));
			return;
		}

		// Friend requests wait for a menu rather than interrupting play.
		while (!REQUESTS.isEmpty() && Friends.incoming().stream().noneMatch(r -> r.id().equals(REQUESTS.peek().id()))) {
			REQUESTS.poll(); // already answered from the Friends tab
		}
		if (!REQUESTS.isEmpty() && calm(screen, false)) {
			Request request = REQUESTS.poll();
			minecraft.gui.setScreen(new FriendPopup(screen, request.id(), request.name() + " wants to be friends",
					"Friends can invite each other into their worlds with one click.",
					"Accept", "Decline", () -> Friends.accept(request), () -> Friends.decline(request)));
		}
	}
}

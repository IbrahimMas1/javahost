package com.javahost.client;

import java.util.ArrayDeque;
import java.util.Deque;

import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Invite;
import com.javahost.client.friends.Friends.Request;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.gui.FriendPopup;
import com.javahost.client.gui.JavaHostScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

/** Shows friend invites and requests as popups at sensible moments (never on top of loading screens). */
public final class Popups {
	private static final Deque<Invite> INVITES = new ArrayDeque<>();
	private static final Deque<Request> REQUESTS = new ArrayDeque<>();

	private Popups() {
	}

	public static void startFriends() {
		Friends.start(invite -> {
			INVITES.removeIf(old -> old.from().equals(invite.from()));
			INVITES.add(invite);
			Toasts.player(invite.from(), invite.name() + " invited you!", "Join their world: " + invite.world());
		}, request -> {
			REQUESTS.add(request);
			Toasts.player(request.id(), request.name() + " wants to be friends", "Answer in the JavaHost menu (J)");
		});
		Friends.selfCheck();
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

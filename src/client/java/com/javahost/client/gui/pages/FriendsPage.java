package com.javahost.client.gui.pages;

import java.util.List;

import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Friends.Outgoing;
import com.javahost.client.friends.Friends.Request;
import com.javahost.client.friends.Identity;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.util.Mailbox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Add friends by name, answer requests, and invite friends into your world with one click. */
public class FriendsPage extends Page {
	private static final int ROW = 24;

	private TextField nameField;
	private String message;
	private int messageColor = Theme.MUTED;
	private boolean busy;
	private String lastSignature = "";

	private int requestsY = -1;
	private int sentY = -1;
	private int friendsY;
	private int footerY;
	private List<Request> incoming = List.of();
	private List<Outgoing> outgoing = List.of();
	private List<Friend> friends = List.of();

	@Override
	public String title() {
		return "Friends";
	}

	@Override
	public String subtitle() {
		return InviteFlow.canInvite() ? "Invite a friend and they can join with one click." : "Add friends, then invite them from your world.";
	}

	@Override
	protected void build() {
		lastSignature = signature();
		incoming = Friends.incoming();
		outgoing = Friends.outgoing();
		friends = Friends.friends();

		int y = 12;
		if (Identity.canSign()) {
			nameField = add(new TextField(width - 86, Component.literal("Friend's Minecraft name")), 0, y);
			nameField.setMaxLength(16);
			nameField.setHint(Component.literal("Their Minecraft name").withStyle(s -> s.withColor(Theme.FAINT)));
			nameField.setResponder(v -> message = null);
			FlatButton addButton = add(new FlatButton(78, 20, Component.literal(busy ? "Adding..." : "Add"), FlatButton.Style.PRIMARY,
					Icons.USER, b -> submit()), width - 78, y);
			addButton.active = !busy;
		}
		y += 44;

		if (!incoming.isEmpty()) {
			requestsY = y;
			y += 12;
			for (Request request : incoming) {
				add(new FlatButton(60, 16, Component.literal("Accept"), FlatButton.Style.PRIMARY, null,
						b -> Friends.accept(request)), width - 92, y + 4);
				add(new FlatButton(18, 16, Component.empty(), FlatButton.Style.GHOST, Icons.CROSS,
						b -> Friends.decline(request)), width - 26, y + 4);
				y += ROW;
			}
			y += 12;
		} else {
			requestsY = -1;
		}

		friendsY = y;
		y += 12;
		if (friends.isEmpty()) {
			y += ROW;
		}
		boolean canInvite = InviteFlow.canInvite();
		for (Friend friend : friends) {
			String status = InviteFlow.status(friend.id());
			boolean inviting = "Inviting...".equals(status);
			FlatButton invite = add(new FlatButton(64, 16, Component.literal(inviting ? "..." : "Invite"),
					FlatButton.Style.PRIMARY, inviting ? null : Icons.PLAY, b -> {
						InviteFlow.invite(friend);
						screen.refresh();
					}), width - 96, y + 4);
			invite.active = canInvite && !inviting;
			add(new FlatButton(18, 16, Component.empty(), FlatButton.Style.GHOST, Icons.CROSS, b -> {
				Friends.remove(friend);
				screen.refresh();
			}), width - 26, y + 4);
			y += ROW;
		}
		y += 12;

		if (!outgoing.isEmpty()) {
			sentY = y;
			y += 12;
			for (Outgoing sent : outgoing) {
				add(new FlatButton(18, 16, Component.empty(), FlatButton.Style.GHOST, Icons.CROSS, b -> Friends.cancel(sent)),
						width - 26, y + 4);
				y += ROW;
			}
			y += 12;
		} else {
			sentY = -1;
		}

		footerY = y;
		add(new FlatButton(130, 16, Component.literal("Join with a code"), FlatButton.Style.GHOST, Icons.JOIN,
				b -> screen.switchTo(JavaHostScreen.Tab.JOIN)), width - 130, footerY);
		reserve(footerY + 24);
		screen.focusOn(nameField != null ? nameField : null);
	}

	private void submit() {
		if (nameField == null || busy) {
			return;
		}
		String name = nameField.getValue();
		busy = true;
		screen.refresh();
		Friends.add(name).whenComplete((result, error) -> minecraft.execute(() -> {
			busy = false;
			message = error != null ? "Something went wrong. Try again." : result;
			messageColor = message.startsWith("Friend request sent") || message.startsWith("You're now") ? Theme.ACCENT : Theme.WARNING;
			if (nameField != null && messageColor == Theme.ACCENT) {
				nameField.setValue("");
			}
			screen.refresh();
		}));
	}

	/** Enter in the name box adds the friend. */
	public void submitFromKeyboard() {
		submit();
	}

	private String signature() {
		StringBuilder s = new StringBuilder().append(Friends.status()).append(InviteFlow.canInvite());
		Friends.incoming().forEach(r -> s.append('i').append(r.id()));
		Friends.outgoing().forEach(o -> s.append('o').append(o.id()));
		Friends.friends().forEach(f -> s.append('f').append(f.id()).append(InviteFlow.status(f.id())));
		return s.toString();
	}

	@Override
	public void tick() {
		if (!signature().equals(lastSignature)) {
			String typed = nameField != null ? nameField.getValue() : "";
			screen.refresh();
			if (nameField != null) {
				nameField.setValue(typed);
			}
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "Add a friend", left, top);
		if (!Identity.canSign()) {
			card(g, left, top + 12, width, 24);
			Icons.WARNING.draw(g, left + 8, top + 19, Theme.WARNING);
			Draw.text(g, "Sign in with a Microsoft account to use friends.", left + 22, top + 20, Theme.WARNING);
		} else if (message != null) {
			Draw.text(g, Draw.fit(message, width), left, top + 37, messageColor);
		} else {
			Draw.text(g, "They'll get a request next time they play.", left, top + 37, Theme.FAINT);
		}

		if (requestsY >= 0) {
			sectionLabel(g, "Friend requests", left, top + requestsY);
			int y = top + requestsY + 12;
			card(g, left, y - 2, width, incoming.size() * ROW + 4);
			for (Request request : incoming) {
				Faces.draw(g, request.id(), left + 8, y + 4, 16);
				Draw.text(g, request.name(), left + 30, y + 4, Theme.TEXT);
				Draw.text(g, "wants to be friends", left + 30, y + 14, Theme.MUTED);
				y += ROW;
			}
		}

		sectionLabel(g, "Your friends (" + friends.size() + ")", left, top + friendsY);
		int y = top + friendsY + 12;
		card(g, left, y - 2, width, Math.max(1, friends.size()) * ROW + 4);
		if (friends.isEmpty()) {
			Icons.USER.draw(g, left + 9, y + 7, Theme.FAINT);
			Draw.text(g, "No friends yet. Add someone by their Minecraft name.", left + 24, y + 8, Theme.MUTED);
		}
		boolean canInvite = InviteFlow.canInvite();
		for (Friend friend : friends) {
			Faces.draw(g, friend.id(), left + 8, y + 4, 16);
			Draw.text(g, friend.name(), left + 30, y + 4, Theme.TEXT);
			String status = InviteFlow.status(friend.id());
			String line = status != null ? status : canInvite ? "Ready to invite" : "Open a world to invite";
			int color = "Invited".equals(status) ? Theme.ACCENT : status != null ? Theme.INFO : Theme.FAINT;
			Draw.text(g, Draw.fit(line, width - 140), left + 30, y + 14, color);
			y += ROW;
		}

		if (sentY >= 0) {
			sectionLabel(g, "Waiting for them to accept", left, top + sentY);
			y = top + sentY + 12;
			card(g, left, y - 2, width, outgoing.size() * ROW + 4);
			for (Outgoing sent : outgoing) {
				Faces.draw(g, sent.id(), left + 8, y + 4, 16);
				Draw.text(g, sent.name(), left + 30, y + 4, Theme.TEXT);
				Draw.text(g, "Request sent", left + 30, y + 14, Theme.FAINT);
				y += ROW;
			}
		}

		Mailbox.Status status = Friends.status();
		int dot = status == Mailbox.Status.ONLINE ? Theme.ACCENT : status == Mailbox.Status.CONNECTING ? Theme.INFO : Theme.DANGER;
		String text = status == Mailbox.Status.ONLINE ? "Connected" : status == Mailbox.Status.CONNECTING ? "Connecting..." : "Offline: retrying";
		status(g, left, top + footerY + 4, dot, text, Theme.FAINT);
	}
}

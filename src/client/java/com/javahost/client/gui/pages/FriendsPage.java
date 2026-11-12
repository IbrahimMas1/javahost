package com.javahost.client.gui.pages;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.javahost.client.friends.Chats;
import com.javahost.client.friends.FriendLog;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Friends.Outgoing;
import com.javahost.client.friends.Friends.Request;
import com.javahost.client.friends.Identity;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.RowButton;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.util.Mailbox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Add friends, see who's online, join or invite with one click, and open chats and profiles. */
public class FriendsPage extends Page {
	private static final int ROW = 28;

	private TextField nameField;
	private String message;
	private int messageColor = Theme.MUTED;
	private boolean busy;
	private String lastSignature = "";

	private int requestsY = -1;
	private int sentY = -1;
	private int friendsY;
	private int footerY;
	private int activityY = -1;
	private List<FriendLog.Entry> activity = List.of();
	private static final int ACTIVITY_ROW = 14;
	/** The gold of a favourite's star. */
	private static final int FAVOURITE = 0xFFF2C14E;

	/** Online friends you could invite to your world right now. */
	private static List<Friend> invitable() {
		if (!InviteFlow.canInvite()) {
			return List.of();
		}
		return Presence.onlineFriends().stream().filter(f -> !InviteFlow.playingTogether(f.id()) && !InviteFlow.busy(f.id())
				&& Presence.otherMinecraft(f.id()) == null).toList();
	}
	private List<Request> incoming = List.of();
	private List<Outgoing> outgoing = List.of();
	private List<Friend> friends = List.of();

	@Override
	public String title() {
		return "Friends";
	}

	@Override
	public String subtitle() {
		long online = Presence.onlineFriends().size();
		return online > 0 ? online + " online. Join them or invite them." : "Add friends, then play together.";
	}

	/** Hosting friends first, then online, then offline; alphabetical within each. */
	static int rank(Friend friend) {
		Optional<Presence.Status> status = Presence.of(friend.id());
		if (status.isEmpty()) {
			return 3;
		}
		return status.get().hosting() ? 0 : status.get().state() == Presence.State.PLAYING ? 1 : 2;
	}

	@Override
	protected void build() {
		lastSignature = signature();
		incoming = Friends.incoming();
		outgoing = Friends.outgoing();
		friends = Friends.friends().stream()
				.sorted(Comparator.comparing((Friend f) -> !FriendLog.isFavourite(f.id())).thenComparingInt(FriendsPage::rank)
						.thenComparing(f -> f.name().toLowerCase()))
				.toList();

		int y = 12;
		nameField = null;
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
						b -> Friends.accept(request)), width - 92, y + 6);
				FlatButton decline = add(new FlatButton(18, 16, Component.literal("Decline"), FlatButton.Style.GHOST, Icons.CROSS,
						b -> Friends.decline(request)).iconOnly(), width - 26, y + 6);
				decline.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled("Decline")));
				FlatButton block = add(new FlatButton(44, 16, Component.literal("Block"), FlatButton.Style.GHOST, null,
						b -> Friends.block(request.id(), request.name())), width - 140, y + 6);
				block.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled(
						"They can't send you requests or messages, or join your worlds. Undo in Settings.")));
				y += ROW;
			}
			y += 12;
		} else {
			requestsY = -1;
		}

		friendsY = y;
		// Hosting with a few friends online: one button invites them all.
		List<Friend> invitable = invitable();
		if (invitable.size() >= 2) {
			add(new FlatButton(104, 14, Component.literal("Invite all online"), FlatButton.Style.SECONDARY, null, b -> {
				invitable.forEach(InviteFlow::invite);
				screen.refresh();
			}), width - 104, y - 4).setTooltip(net.minecraft.client.gui.components.Tooltip.create(
					Draw.styled("Send an invite to " + Draw.count(invitable.size(), "friend") + " at once")));
		}
		y += 12;
		if (friends.isEmpty()) {
			y += ROW;
		}
		for (Friend friend : friends) {
			boolean favourite = FriendLog.isFavourite(friend.id());
			add(new RowButton(width - 152, ROW - 2, Component.literal(friend.name()), b -> screen.openProfile(friend.id())), 2, y + 1);
			FlatButton star = add(new FlatButton(20, 18, Component.literal(favourite ? "Remove from favourites" : "Add to favourites"),
					FlatButton.Style.GHOST, Icons.STAR, b -> {
						FriendLog.toggleFavourite(friend.id());
						screen.refresh();
					}).iconOnly().iconColor(favourite ? FAVOURITE : Theme.withAlpha(Theme.FAINT, 0.7f)), width - 120, y + 5);
			star.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
					Draw.styled(favourite ? "A favourite: listed first. Click to undo." : "Make a favourite: listed first")));
			add(action(screen, friend, 64), width - 96, y + 5);
			add(new FlatButton(22, 18, Component.empty(), FlatButton.Style.GHOST, Icons.CHAT, b -> screen.openChat(friend.id())),
					width - 28, y + 5);
			y += ROW;
		}
		y += 12;

		if (!outgoing.isEmpty()) {
			sentY = y;
			y += 12;
			for (Outgoing sent : outgoing) {
				add(new FlatButton(18, 16, Component.empty(), FlatButton.Style.GHOST, Icons.CROSS, b -> Friends.cancel(sent)),
						width - 26, y + 6);
				y += ROW;
			}
			y += 12;
		} else {
			sentY = -1;
		}

		// What friends have been up to lately.
		activity = FriendLog.activity(5);
		if (!activity.isEmpty()) {
			activityY = y;
			y += 12 + activity.size() * ACTIVITY_ROW + 4 + 12;
		} else {
			activityY = -1;
		}

		footerY = y;
		add(new FlatButton(130, 16, Component.literal("Join with a code"), FlatButton.Style.GHOST, Icons.JOIN,
				b -> screen.switchTo(JavaHostScreen.Tab.JOIN)), width - 130, footerY);
		reserve(footerY + 24);
		if (nameField != null) {
			screen.focusOn(nameField);
		}
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
			screen.refresh();
			if (nameField != null && messageColor == Theme.ACCENT) {
				nameField.setValue("");
			}
		}));
	}

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (isEnter(event) && nameField != null && nameField.isFocused()) {
			submit();
			return true;
		}
		return false;
	}

	private String signature() {
		StringBuilder s = new StringBuilder().append(Friends.status()).append(InviteFlow.canInvite()).append(busy);
		Friends.incoming().forEach(r -> s.append('i').append(r.id()));
		Friends.outgoing().forEach(o -> s.append('o').append(o.id()));
		Friends.friends().forEach(f -> s.append('f').append(f.id()).append(InviteFlow.status(f.id())).append(rank(f)).append(InviteFlow.playingTogether(f.id()))
				.append(Chats.unread(f.id())));
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

	/** Explains a version mismatch on a friend's Join/Invite button. */
	/**
	 * The button for playing with a friend right now: Join when they're hosting, Invite when we can, or "Here" when
	 * they're already in our world. Shared with Home.
	 */
	public static FlatButton action(JavaHostScreen screen, Friend friend, int w) {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		Optional<Presence.Status> status = Presence.of(friend.id());
		if (InviteFlow.playingTogether(friend.id())) {
			FlatButton together = new FlatButton(w, 18, Component.literal("Here"), FlatButton.Style.SECONDARY, Icons.CHECK, b -> {
			});
			together.active = false;
			together.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled("You're in the same world")));
			return together;
		}
		if (InviteFlow.joinable(friend.id())) {
			FlatButton join = new FlatButton(w, 18, Component.literal("Join"), FlatButton.Style.PRIMARY, Icons.JOIN,
					b -> InviteFlow.join(status.get().asInvite(friend.name())));
			versionTooltip(join, friend);
			return join;
		}
		boolean canInvite = InviteFlow.canInvite();
		boolean busyInviting = InviteFlow.busy(friend.id());
		FlatButton invite = new FlatButton(w, 18, Component.literal(busyInviting ? "..." : "Invite"),
				FlatButton.Style.PRIMARY, busyInviting ? null : Icons.PLAY, b -> {
					InviteFlow.invite(friend);
					screen.refresh();
				});
		String other = Presence.otherMinecraft(friend.id());
		invite.active = canInvite && !busyInviting && other == null;
		if (canInvite) {
			versionTooltip(invite, friend);
		}
		if (other != null) {
			invite.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled(friend.name() + " plays Minecraft "
					+ other + ". Worlds can only be joined from the same Minecraft version.")));
		}
		if (!canInvite) {
			invite.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled(
					minecraft.level == null ? "Open one of your worlds to invite friends" : "You can only invite people into your own world")));
		}
		return invite;
	}

	private static void versionTooltip(FlatButton button, Friend friend) {
		String note = Presence.versionNote(friend.id());
		if (note != null) {
			String advice = note.startsWith("Needs") ? friend.name() + " should run the latest JavaHost installer."
					: "Run the latest JavaHost installer to update.";
			button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled(
					friend.name() + ": " + note + ". Some features (party voice, fast sending) need the same version. " + advice)));
		}
	}

	/** A small amber "Needs update" / "Update JavaHost" chip when this friend runs a different version. */
	static void versionChip(GuiGraphicsExtractor g, java.util.UUID id, int x, int y, int room) {
		String note = Presence.versionNote(id);
		if (note == null) {
			return;
		}
		String text = note.startsWith("Needs") ? "Needs update" : "Update JavaHost";
		int w = Draw.width(text) + 8;
		if (w > room) {
			return;
		}
		Draw.roundRect(g, x, y, w, 11, 3, Theme.withAlpha(Theme.WARNING, 0.18f));
		Draw.text(g, text, x + 4, y + 2, Theme.WARNING);
	}

	/** Friend face with an online dot in the corner. */
	public static void faceWithDot(GuiGraphicsExtractor g, java.util.UUID id, int x, int y, int size) {
		Faces.draw(g, id, x, y, size);
		Optional<Presence.Status> status = Presence.of(id);
		int dot = status.isEmpty() ? Theme.FAINT : status.get().hosting() ? Theme.ACCENT
				: status.get().state() == Presence.State.PLAYING ? Theme.INFO : Theme.ACCENT;
		Draw.roundRect(g, x + size - 5, y + size - 5, 7, 7, 3, Theme.WINDOW);
		Draw.roundRect(g, x + size - 4, y + size - 4, 5, 5, 2, dot);
	}

	/** One line describing what a friend is up to. */
	public static String statusLine(Friend friend) {
		if (InviteFlow.playingTogether(friend.id())) {
			return "Playing with you";
		}
		Optional<Presence.Status> status = Presence.of(friend.id());
		if (status.isEmpty()) {
			return FriendLog.lastSeenText(friend.id());
		}
		Presence.Status s = status.get();
		String other = Presence.otherMinecraft(friend.id());
		if (other != null) {
			// Worlds can only be joined from the same Minecraft version.
			return switch (s.state()) {
				case HOSTING -> s.hosting() ? "Hosting \"" + s.world() + "\" on " + other : "Playing on " + other;
				case PLAYING -> "Playing on " + other;
				default -> "Online · Minecraft " + other;
			};
		}
		return switch (s.state()) {
			case HOSTING -> s.hosting() ? "Hosting \"" + s.world() + "\" · " + s.players() + "/" + s.max()
					+ (Presence.modsOf(s.player()) > 0 ? " · " + Draw.count(Presence.modsOf(s.player()), "mod") : "") : "Online";
			case PLAYING -> "Playing";
			default -> "Online";
		};
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "Add a friend", left, top);
		if (!Identity.canSign()) {
			card(g, left, top + 12, width, 24);
			Icons.WARNING.draw(g, left + 8, top + 19, Theme.WARNING);
			Draw.textFit(g, "Sign in with Microsoft to use friends.", width - 30, left + 22, top + 20, Theme.WARNING);
		} else if (message != null) {
			Draw.textFit(g, message, width, left, top + 37, messageColor);
		} else {
			Draw.text(g, "They'll get a request next time they play.", left, top + 37, Theme.FAINT);
		}

		if (requestsY >= 0) {
			sectionLabel(g, "Friend requests", left, top + requestsY);
			int y = top + requestsY + 12;
			card(g, left, y - 2, width, incoming.size() * ROW + 4);
			for (Request request : incoming) {
				Faces.draw(g, request.id(), left + 8, y + 5, 18);
				Draw.textFit(g, request.name(), width - 180, left + 32, y + 5, Theme.TEXT);
				boolean warn = request.note() != null && !request.note().isEmpty();
				Draw.textFit(g, warn ? request.note() : "wants to be friends", width - 180, left + 32, y + 15, warn ? Theme.WARNING : Theme.MUTED);
				y += ROW;
			}
		}

		sectionLabel(g, "Your friends (" + friends.size() + ")", left, top + friendsY);
		int y = top + friendsY + 12;
		card(g, left, y - 2, width, Math.max(1, friends.size()) * ROW + 4);
		if (friends.isEmpty()) {
			Icons.USERS.draw(g, left + 9, y + 9, Theme.FAINT);
			Draw.textFit(g, "No friends yet. Add someone by name above.", width - 34, left + 24, y + 10, Theme.MUTED);
		}
		for (Friend friend : friends) {
			faceWithDot(g, friend.id(), left + 8, y + 5, 18);
			String name = Draw.fit(friend.name(), width - 162);
			Draw.text(g, name, left + 32, y + 5, Theme.TEXT);
			versionChip(g, friend.id(), left + 36 + Draw.width(name), y + 4, width - 198 - Draw.width(name));
			String invite = InviteFlow.status(friend.id());
			String line = invite != null ? invite : statusLine(friend);
			int color = invite != null
					? (invite.startsWith("Delivered") || invite.startsWith("In your world") ? Theme.ACCENT
							: invite.startsWith("Not delivered") || invite.startsWith("Couldn't") ? Theme.WARNING : Theme.INFO)
					: Presence.of(friend.id()).isPresent() ? Theme.ACCENT : Theme.FAINT;
			Draw.textFit(g, line, width - 162, left + 32, y + 15, color);
			int unread = Chats.unread(friend.id());
			if (unread > 0) {
				Draw.roundRect(g, left + width - 12, y + 4, 8, 8, 4, Theme.DANGER);
			}
			y += ROW;
		}

		if (sentY >= 0) {
			sectionLabel(g, "Waiting for them to accept", left, top + sentY);
			y = top + sentY + 12;
			card(g, left, y - 2, width, outgoing.size() * ROW + 4);
			for (Outgoing sent : outgoing) {
				Faces.draw(g, sent.id(), left + 8, y + 5, 18);
				Draw.text(g, sent.name(), left + 32, y + 5, Theme.TEXT);
				Draw.text(g, "Request sent", left + 32, y + 15, Theme.FAINT);
				y += ROW;
			}
		}

		if (activityY >= 0) {
			sectionLabel(g, "Recent activity", left, top + activityY);
			y = top + activityY + 12;
			card(g, left, y - 2, width, activity.size() * ACTIVITY_ROW + 8);
			y += 2;
			for (FriendLog.Entry entry : activity) {
				String when = FriendLog.ago(entry.time);
				int whenW = Draw.width(when);
				try {
					Faces.draw(g, java.util.UUID.fromString(entry.friend), left + 8, y + 1, 10);
				} catch (IllegalArgumentException e) {
					// no face for a broken id
				}
				String name = Draw.fit(entry.name, Math.max(30, width / 3));
				Draw.text(g, name, left + 23, y + 2, Theme.TEXT);
				Draw.textFit(g, entry.what, width - 44 - Draw.width(name) - whenW, left + 27 + Draw.width(name), y + 2,
						entry.what.startsWith("started hosting") ? Theme.ACCENT : Theme.MUTED);
				Draw.text(g, when, left + width - 8 - whenW, y + 2, Theme.FAINT);
				y += ACTIVITY_ROW;
			}
		}

		Mailbox.Status status = Friends.status();
		int dot = status == Mailbox.Status.ONLINE ? Theme.ACCENT : status == Mailbox.Status.CONNECTING ? Theme.INFO : Theme.DANGER;
		String text = status == Mailbox.Status.ONLINE ? "Connected" : status == Mailbox.Status.CONNECTING ? "Connecting..." : "Offline: retrying";
		status(g, left, top + footerY + 4, dot, text, Theme.FAINT);
	}
}

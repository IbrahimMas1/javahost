package com.javahost.client.gui.pages;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.javahost.client.friends.Friends;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Notifications;
import com.javahost.client.friends.Notifications.Item;
import com.javahost.client.friends.Notifications.Kind;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Everything that happened recently, each with its action, so a missed popup is never lost. */
public class NotificationsPage extends Page {
	private List<Item> items = List.of();
	private int seen = -1;

	@Override
	public String title() {
		return "Notifications";
	}

	@Override
	public String subtitle() {
		return "Messages, invites and news, in full.";
	}

	@Override
	protected void build() {
		// Requests already answered (from a popup or the Friends tab) have nothing left to do.
		Notifications.items().stream()
				.filter(item -> item.kind() == Kind.REQUEST && Friends.incoming().stream().noneMatch(r -> r.id().equals(item.player())))
				.toList().forEach(Notifications::remove);
		items = Notifications.items();
		seen = items.size();
		hasAction.clear();
		rowHeights.clear();
		if (!items.isEmpty()) {
			add(new FlatButton(80, 16, Component.literal("Clear all"), FlatButton.Style.GHOST, Icons.CROSS, b -> {
				Notifications.clear();
				screen.refresh();
			}), width - 80, 0);
		}
		int y = 22;
		for (Item item : items) {
			FlatButton action = action(item);
			int h = rowHeight(item, action != null);
			rowHeights.add(h);
			hasAction.add(action != null);
			if (action != null) {
				add(action, width - 96, y + (h - 18) / 2);
			}
			FlatButton dismiss = add(new FlatButton(18, 16, Component.literal("Dismiss"), FlatButton.Style.GHOST, Icons.CROSS, b -> {
				Notifications.remove(item);
				screen.refresh();
			}).iconOnly(), width - 24, y + (h - 16) / 2);
			dismiss.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Dismiss")));
			y += h;
		}
		reserve(Math.max(y, 60) + 10);
	}

	private final java.util.List<Integer> rowHeights = new java.util.ArrayList<>();
	private final java.util.List<Boolean> hasAction = new java.util.ArrayList<>();

	private int textWidth(boolean action) {
		return width - 45 - (action ? 104 : 30);
	}

	/** The full text, wrapped: friends' messages get the sender's name, JavaHost's own notices their title. */
	private static String body(Item item) {
		return item.kind() == Kind.MESSAGE ? item.name() + ": " + item.text() : item.text();
	}

	/** Height of a row's text block: optional title, the wrapped text, and the "2m ago" line. */
	private int contentHeight(Item item, boolean action) {
		int title = item.kind() == Kind.INFO ? 11 : 0;
		return title + wrappedHeight(body(item), textWidth(action)) + 2 + 8;
	}

	/** Even padding above and below the text, and never shorter than the face and buttons need. */
	private int rowHeight(Item item, boolean action) {
		return Math.max(30, contentHeight(item, action) + 16);
	}

	/** The one thing you'd want to do with this notification, or null if it's no longer actionable. */
	private FlatButton action(Item item) {
		return switch (item.kind()) {
			case INVITE -> item.payload() instanceof Friends.Invite invite && !invite.expired()
					? button("Join", Icons.JOIN, FlatButton.Style.PRIMARY, () -> {
						Notifications.remove(item);
						InviteFlow.join(invite);
					})
					: null;
			case HOSTING -> {
				Optional<Presence.Status> status = Presence.of(item.player());
				yield status.isPresent() && InviteFlow.joinable(item.player())
						? button("Join", Icons.JOIN, FlatButton.Style.PRIMARY, () -> InviteFlow.join(status.get().asInvite(item.name())))
						: null;
			}
			case REQUEST -> Friends.incoming().stream().filter(r -> r.id().equals(item.player())).findFirst()
					.map(request -> button("Accept", Icons.CHECK, FlatButton.Style.PRIMARY, () -> {
						Friends.accept(request);
						Notifications.remove(item);
						screen.refresh();
					})).orElse(null);
			case MESSAGE -> button("Reply", Icons.CHAT, FlatButton.Style.SECONDARY, () -> screen.openChat(item.player()));
			case SCREENSHOT -> item.payload() instanceof Path file
					? button("View", Icons.CAMERA, FlatButton.Style.SECONDARY, () -> {
						ScreenshotsPage.sendTarget = null;
						screen.openScreenshot(file);
					})
					: null;
			case ACCEPTED -> button("Profile", Icons.USER, FlatButton.Style.SECONDARY, () -> screen.openProfile(item.player()));
			case INFO -> null;
		};
	}

	private static FlatButton button(String label, Icons icon, FlatButton.Style style, Runnable action) {
		return new FlatButton(70, 18, Component.literal(label), style, icon, b -> action.run());
	}

	@Override
	public void tick() {
		if (Notifications.items().size() != seen) {
			screen.refresh();
		}
	}

	private static String ago(long time) {
		Duration d = Duration.ofMillis(System.currentTimeMillis() - time);
		if (d.toMinutes() < 1) {
			return "just now";
		}
		if (d.toHours() < 1) {
			return d.toMinutes() + "m ago";
		}
		return d.toHours() + "h ago";
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		sectionLabel(g, "Recent", left, top + 4);
		int y = top + 22;
		if (items.isEmpty()) {
			card(g, left, y, width, 34);
			Icons.BELL.draw(g, left + 10, y + 12, Theme.FAINT);
			Draw.text(g, "You're all caught up.", left + 26, y + 13, Theme.MUTED);
			return;
		}
		int total = rowHeights.stream().mapToInt(Integer::intValue).sum();
		card(g, left, y - 2, width, total + 4);
		for (int i = 0; i < items.size() && i < rowHeights.size(); i++) {
			Item item = items.get(i);
			int h = rowHeights.get(i);
			if (i > 0) {
				g.fill(left + 8, y - 1, left + width - 8, y, Theme.withAlpha(Theme.BORDER, 0.6f));
			}
			if (item.kind() == Kind.INFO) {
				Icons.logo(g, left + 9, y + (h - 18) / 2, 18);
			} else {
				Faces.draw(g, item.player(), left + 8, y + (h - 18) / 2, 18);
			}
			Icons kindIcon = switch (item.kind()) {
				case INVITE, HOSTING -> Icons.PLAY;
				case REQUEST, ACCEPTED -> Icons.USER;
				case MESSAGE -> Icons.CHAT;
				case SCREENSHOT -> Icons.CAMERA;
				case INFO -> null;
			};
			int textX = left + (kindIcon == null ? 34 : 45);
			int textY = y + (h - contentHeight(item, hasAction.get(i))) / 2;
			if (kindIcon != null) {
				kindIcon.draw(g, left + 32, textY - 1, item.kind() == Kind.INVITE || item.kind() == Kind.HOSTING ? Theme.ACCENT : Theme.MUTED);
			}
			if (item.kind() == Kind.INFO) {
				Draw.textFit(g, item.name(), textWidth(hasAction.get(i)), textX, textY, Theme.TEXT);
				textY += 11;
			}
			int used = wrap(g, body(item), textX, textY, textWidth(hasAction.get(i)),
					item.kind() == Kind.INFO ? Theme.MUTED : Theme.TEXT);
			Draw.text(g, ago(item.time()), textX, textY + used + 2, Theme.FAINT);
			y += h;
		}
	}
}

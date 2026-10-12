package com.javahost.client.gui.pages;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.javahost.client.friends.Chats;
import com.javahost.client.friends.Chats.Line;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A private, encrypted conversation with one friend. */
public class ChatPage extends Page {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	private static final long MIN_GAP_MS = 2000;
	private static long lastSent;

	private record Bubble(Line line, List<FormattedCharSequence> wrapped, int y, int width, int height, String timeLabel) {
	}

	private Friend friend;
	private TextField input;
	private String error;
	private boolean sending;
	private int seen = -1;
	private final List<Bubble> bubbles = new ArrayList<>();
	private int inputY;

	@Override
	public String title() {
		return friend == null ? "Chat" : friend.name();
	}

	/** Back from a chat goes to that friend's profile. */
	@Override
	protected boolean back() {
		if (friend == null) {
			return false;
		}
		screen.openProfile(friend.id());
		return true;
	}

	@Override
	public String subtitle() {
		return "Encrypted: only you two can read it.";
	}

	@Override
	protected void build() {
		friend = Friends.friend(screen.subject()).orElse(null);
		if (friend == null) {
			reserve(20);
			return;
		}
		Chats.markRead(friend.id());
		List<Line> lines = Chats.with(friend.id());
		seen = lines.size();

		bubbles.clear();
		int y = 2;
		int maxW = (int) (width * 0.72);
		long previous = 0;
		if (lines.isEmpty()) {
			y += 30;
		}
		for (Line line : lines) {
			String label = line.time() - previous > 5 * 60 * 1000L ? TIME.format(Instant.ofEpochMilli(line.time())) : null;
			if (label != null) {
				y += 12;
			}
			List<FormattedCharSequence> wrapped = Draw.font().split(Component.literal(line.text()), maxW - 12);
			int textW = 0;
			for (FormattedCharSequence part : wrapped) {
				textW = Math.max(textW, Draw.font().width(part));
			}
			int h = wrapped.size() * 10 + 8;
			bubbles.add(new Bubble(line, wrapped, y, textW + 12, h, label));
			y += h + 4;
			previous = line.time();
		}

		inputY = y + 8;
		input = add(new TextField(width - 74, Component.literal("Message")), 0, inputY);
		input.setMaxLength(Chats.MAX_LENGTH);
		input.setHint(Component.literal("Message " + friend.name()).withStyle(s -> s.withColor(Theme.FAINT)));
		input.setResponder(v -> error = null);
		FlatButton send = add(new FlatButton(68, 20, Component.literal(sending ? "..." : "Send"), FlatButton.Style.PRIMARY, Icons.SEND,
				b -> send()), width - 68, inputY);
		send.active = !sending;
		reserve(inputY + 36);
		screen.focusOn(input);
	}

	private void send() {
		if (friend == null || input == null || sending) {
			return;
		}
		String text = input.getValue().strip();
		if (text.isEmpty()) {
			return;
		}
		if (System.currentTimeMillis() - lastSent < MIN_GAP_MS) {
			error = "Slow down a little.";
			return;
		}
		lastSent = System.currentTimeMillis();
		sending = true;
		Friends.sendChat(friend, text).thenAccept(sent -> minecraft.execute(() -> {
			sending = false;
			if (sent.ok()) {
				screen.scrollToBottom();
				screen.refresh();
			} else {
				error = Friends.describeFailure(sent);
				String typed = text;
				screen.refresh();
				if (input != null) {
					input.setValue(typed);
				}
			}
		}));
		screen.refresh();
	}

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (isEnter(event) && input != null && input.isFocused()) {
			send();
			return true;
		}
		return false;
	}

	@Override
	public void tick() {
		if (friend != null && Chats.with(friend.id()).size() != seen) {
			String typed = input != null ? input.getValue() : "";
			screen.scrollToBottom();
			screen.refresh();
			if (input != null) {
				input.setValue(typed);
			}
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (friend == null) {
			Draw.text(g, "This friend is no longer on your list.", left, top + 2, Theme.MUTED);
			return;
		}
		if (bubbles.isEmpty()) {
			Draw.centered(g, "Say hi to " + friend.name() + "!", left + width / 2, top + 12, Theme.FAINT);
		}
		for (Bubble bubble : bubbles) {
			int y = top + bubble.y();
			if (bubble.timeLabel() != null) {
				Draw.centered(g, bubble.timeLabel(), left + width / 2, y - 11, Theme.FAINT);
			}
			boolean mine = bubble.line().mine();
			int x = mine ? left + width - bubble.width() : left;
			Draw.roundRect(g, x, y, bubble.width(), bubble.height(), 6, mine ? Theme.ACCENT_DARK : Theme.CARD);
			int textY = y + 4;
			for (FormattedCharSequence part : bubble.wrapped()) {
				g.text(Draw.font(), part, x + 6, textY, Theme.TEXT, false);
				textY += 10;
			}
		}
		if (error != null) {
			Draw.text(g, Draw.fit(error, width), left, top + inputY + 24, Theme.WARNING);
		}
	}
}

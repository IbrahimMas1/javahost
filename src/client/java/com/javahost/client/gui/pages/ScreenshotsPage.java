package com.javahost.client.gui.pages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.javahost.client.JavaHostClient;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.Screenshots;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.ImageButton;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Your screenshots and the ones friends sent. Pick one to see it big, send it to a friend, or show it in
 * Finder/Explorer. Each thumbnail also has a send shortcut.
 */
public class ScreenshotsPage extends Page {
	/** Set by a friend's profile ("Screenshot" button): who to offer first when sending. */
	public static UUID sendTarget;
	private static boolean showFriends;
	/** Open the friend list straight away on the next preview (from a thumbnail's send shortcut). */
	private static boolean openPicker;
	private static final Map<String, String> SEND_STATUS = new HashMap<>();

	private static final int COLUMNS = 4;
	private static final int ROW = 24;
	private List<Path> files = List.of();
	private final List<ImageButton> thumbs = new ArrayList<>();
	private final List<FlatButton> thumbSends = new ArrayList<>();
	private Path selected;
	private boolean picking;
	private int gridY;
	private int previewH;
	private int pickerY;
	private List<Friend> pickerFriends = List.of();
	private String lastSignature = "";
	private int ticks;
	private int lastMouseX;
	private int lastMouseY;

	@Override
	public String title() {
		return selected != null ? "Screenshot" : "Screenshots";
	}

	@Override
	public String subtitle() {
		if (selected != null) {
			return selected.getFileName().toString();
		}
		String key = JavaHostClient.SCREENSHOT_SHARE.getTranslatedKeyMessage().getString();
		return "Press " + key + " in game to snap one for friends.";
	}

	@Override
	protected boolean back() {
		if (selected == null) {
			return false;
		}
		screen.selectScreenshot(null);
		picking = false;
		screen.refresh();
		return true;
	}

	@Override
	protected boolean canGoBack() {
		return screen.selectedScreenshot() != null;
	}

	@Override
	protected void build() {
		Path before = selected;
		selected = screen.selectedScreenshot();
		if (selected == null || !selected.equals(before)) {
			picking = false;
		}
		if (selected != null && openPicker) {
			picking = true;
			openPicker = false;
		}
		lastSignature = signature();
		thumbs.clear();
		thumbSends.clear();
		if (selected != null) {
			buildPreview();
		} else {
			buildGrid();
		}
	}

	private void buildGrid() {
		add(new Segmented<>(160, List.of(false, true), showFriends, f -> f ? "From friends" : "Mine", f -> {
			showFriends = f;
			screen.refresh();
		}), 0, 0);
		add(new FlatButton(width - 166, 18, Component.literal("Open folder"), FlatButton.Style.GHOST, Icons.PACKAGE,
				b -> com.mojang.blaze3d.Blaze3D.openPath(showFriends ? Screenshots.fromFriendsDir() : Screenshots.dir())), 166, 0);

		files = Screenshots.list(showFriends);
		gridY = 26 + (sendTarget != null ? 22 : 0);
		int gap = 6;
		int cellW = (width - gap * (COLUMNS - 1)) / COLUMNS;
		int cellH = cellW * 9 / 16;
		for (int i = 0; i < files.size(); i++) {
			Path file = files.get(i);
			int x = (i % COLUMNS) * (cellW + gap);
			int y = gridY + (i / COLUMNS) * (cellH + gap);
			int index = i;
			// The thumbnail gets clicks first, so it checks whether the click was on its send shortcut.
			thumbs.add(add(new ImageButton(cellW, cellH, Component.literal(file.getFileName().toString()), () -> Screenshots.thumbnail(file),
					b -> open(file, sendTarget != null || overSend(index))), x, y));
			// Send shortcut in the corner, shown while the thumbnail is hovered.
			FlatButton send = add(new FlatButton(16, 14, Component.literal("Send"), FlatButton.Style.PRIMARY, Icons.SEND,
					b -> open(file, true)).iconOnly(), x + cellW - 19, y + 3);
			send.setTooltip(Tooltip.create(Component.literal("Send to a friend")));
			send.visible = false;
			thumbSends.add(send);
		}
		int rows = (files.size() + COLUMNS - 1) / COLUMNS;
		reserve(gridY + Math.max(1, rows) * (cellH + gap) + 30);
	}

	private boolean overSend(int index) {
		FlatButton send = thumbSends.get(index);
		return send.visible && lastMouseX >= send.getX() && lastMouseX < send.getRight()
				&& lastMouseY >= send.getY() && lastMouseY < send.getBottom();
	}

	private void open(Path file, boolean withPicker) {
		openPicker = withPicker;
		screen.selectScreenshot(file);
		screen.refresh();
	}

	private void buildPreview() {
		// Sized so the buttons under it (and, while picking, the first friends) are always on screen.
		previewH = Math.max(50, Math.min(width * 9 / 16, screen.contentHeight() - (picking ? 100 : 34)));
		int actionsY = previewH + 6;
		int half = (width - 6) / 2;
		add(new FlatButton(half, 20, Component.literal(picking ? "Cancel" : "Send to a friend"),
				picking ? FlatButton.Style.SECONDARY : FlatButton.Style.PRIMARY, picking ? Icons.CROSS : Icons.SEND, b -> {
					picking = !picking;
					screen.refresh();
				}), 0, actionsY);
		add(new FlatButton(half, 20, Component.literal("Show in folder"), FlatButton.Style.SECONDARY, Icons.PACKAGE,
				b -> Screenshots.reveal(selected)), half + 6, actionsY);

		pickerY = actionsY + 30;
		pickerFriends = List.of();
		if (!picking) {
			reserve(actionsY + 26);
			return;
		}
		List<Friend> friends = new ArrayList<>(Friends.friends());
		// The friend we came from goes first.
		if (sendTarget != null) {
			friends.sort((a, b) -> a.id().equals(sendTarget) ? -1 : b.id().equals(sendTarget) ? 1 : 0);
		}
		pickerFriends = friends;
		if (friends.isEmpty()) {
			add(new FlatButton(110, 18, Component.literal("Add friends"), FlatButton.Style.PRIMARY, Icons.USERS,
					b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), width - 120, pickerY + 19);
			reserve(pickerY + 50);
			return;
		}
		int y = pickerY + 14;
		for (Friend friend : friends) {
			String status = SEND_STATUS.get(key(friend));
			boolean sending = "Sending...".equals(status);
			boolean sent = "Sent".equals(status);
			FlatButton send = add(new FlatButton(64, 18, Component.literal(sending ? "..." : sent ? "Sent" : "Send"),
					sent ? FlatButton.Style.SECONDARY : FlatButton.Style.PRIMARY, sent ? Icons.CHECK : Icons.SEND, b -> send(friend)),
					width - 72, y + 3);
			send.active = !sending;
			y += ROW;
		}
		reserve(y + 12);
	}

	private String key(Friend friend) {
		return friend.id() + "|" + selected;
	}

	private void send(Friend friend) {
		Path file = selected;
		String key = key(friend);
		SEND_STATUS.put(key, "Sending...");
		screen.refresh();
		CompletableFuture.supplyAsync(() -> {
			try {
				return Screenshots.prepareForSending(file);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).thenCompose(png -> Friends.sendScreenshot(friend, png, file.getFileName().toString()))
				.whenComplete((sent, error) -> minecraft.execute(() -> {
					SEND_STATUS.put(key, error == null && sent.ok() ? "Sent" : error == null ? Friends.describeFailure(sent) : "Couldn't send");
					screen.refresh();
				}));
	}

	private String signature() {
		return String.valueOf(Screenshots.list(showFriends).size()) + SEND_STATUS.hashCode() + screen.selectedScreenshot();
	}

	@Override
	public void tick() {
		// Listing the folder is cheap but not free: check once a second.
		if (++ticks % 20 == 0 && !signature().equals(lastSignature)) {
			screen.refresh();
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (selected != null) {
			Screenshots.Thumbnail image = Screenshots.preview(selected);
			Draw.roundRect(g, left, top, width, previewH, 6, Theme.BORDER);
			if (image != null) {
				ImageButton.drawCover(g, image, left + 1, top + 1, width - 2, previewH - 2);
			}
			if (!picking) {
				return;
			}
			int y = top + pickerY;
			sectionLabel(g, "Send to", left, y);
			if (pickerFriends.isEmpty()) {
				card(g, left, y + 12, width, 32);
				Icons.USERS.draw(g, left + 10, y + 22, Theme.FAINT);
				Draw.text(g, Draw.fit("No friends yet.", width - 160), left + 26, y + 24, Theme.MUTED);
				return;
			}
			card(g, left, y + 12, width, pickerFriends.size() * ROW + 4);
			y += 14;
			for (Friend friend : pickerFriends) {
				FriendsPage.faceWithDot(g, friend.id(), left + 8, y + 4, 16);
				boolean target = friend.id().equals(sendTarget);
				Draw.text(g, Draw.fit(friend.name(), width - 120), left + 32, y + 3, target ? Theme.ACCENT : Theme.TEXT);
				String status = SEND_STATUS.get(key(friend));
				String line = status != null && !status.equals("Sent") && !status.equals("Sending...") ? status : FriendsPage.statusLine(friend);
				Draw.text(g, Draw.fit(line, width - 120), left + 32, y + 13, status != null && !status.startsWith("Sen") ? Theme.WARNING : Theme.FAINT);
				y += ROW;
			}
			return;
		}
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		// Each thumbnail's send shortcut shows only while the mouse is over that thumbnail.
		for (int i = 0; i < thumbs.size(); i++) {
			ImageButton thumb = thumbs.get(i);
			thumbSends.get(i).visible = mouseX >= thumb.getX() && mouseX < thumb.getRight() && mouseY >= thumb.getY() && mouseY < thumb.getBottom();
		}
		if (sendTarget != null) {
			Optional<Friend> target = Friends.friend(sendTarget);
			target.ifPresent(friend -> {
				Icons.SEND.draw(g, left, top + 28, Theme.ACCENT);
				Draw.text(g, "Pick a screenshot to send to " + friend.name(), left + 14, top + 29, Theme.ACCENT);
			});
		}
		if (files.isEmpty()) {
			card(g, left, top + gridY, width, 40);
			Draw.centered(g, showFriends ? "Nothing from friends yet." : "No screenshots yet.", left + width / 2, top + gridY + 16, Theme.MUTED);
		}
	}
}

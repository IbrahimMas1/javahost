package com.javahost.client.gui.pages;

import java.nio.file.Path;
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
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.ImageButton;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Your screenshots and the ones friends sent: browse, preview, and send to a friend in one click. */
public class ScreenshotsPage extends Page {
	/** Set by a friend's profile ("Screenshot" button): who to offer first when sending. */
	public static UUID sendTarget;
	private static boolean showFriends;
	private static final Map<String, String> SEND_STATUS = new HashMap<>();

	private static final int COLUMNS = 4;
	private List<Path> files = List.of();
	private Path selected;
	private int gridY;
	private int previewH;
	private String lastSignature = "";

	@Override
	public String title() {
		return "Screenshots";
	}

	@Override
	public String subtitle() {
		String key = JavaHostClient.SCREENSHOT_SHARE.getTranslatedKeyMessage().getString();
		return "Press " + key + " in game to snap one for friends.";
	}

	@Override
	protected void build() {
		selected = screen.selectedScreenshot();
		lastSignature = signature();
		if (selected != null) {
			buildPreview();
		} else {
			buildGrid();
		}
	}

	private void buildGrid() {
		add(new Segmented<>(180, List.of(false, true), showFriends, f -> f ? "From friends" : "Mine", f -> {
			showFriends = f;
			screen.refresh();
		}), 0, 0);
		add(new FlatButton(width - 190, 18, Component.literal("Open folder"), FlatButton.Style.GHOST, Icons.PACKAGE,
				b -> com.mojang.blaze3d.Blaze3D.openPath(showFriends ? Screenshots.fromFriendsDir() : Screenshots.dir())), 190, 0);

		files = Screenshots.list(showFriends);
		gridY = 26 + (sendTarget != null ? 22 : 0);
		int gap = 6;
		int cellW = (width - gap * (COLUMNS - 1)) / COLUMNS;
		int cellH = cellW * 9 / 16;
		for (int i = 0; i < files.size(); i++) {
			Path file = files.get(i);
			int col = i % COLUMNS;
			int row = i / COLUMNS;
			add(new ImageButton(cellW, cellH, Component.literal(file.getFileName().toString()), () -> Screenshots.thumbnail(file),
					b -> {
						screen.selectScreenshot(file);
						screen.refresh();
					}), col * (cellW + gap), gridY + row * (cellH + gap));
		}
		int rows = (files.size() + COLUMNS - 1) / COLUMNS;
		reserve(gridY + Math.max(1, rows) * (cellH + gap) + 30);
	}

	private void buildPreview() {
		add(new FlatButton(80, 16, Component.literal("All"), FlatButton.Style.GHOST, Icons.BACK, b -> {
			screen.selectScreenshot(null);
			screen.refresh();
		}), 0, 0);
		previewH = Math.min(width * 9 / 16, 170);
		int y = 24 + previewH + 16;
		List<Friend> friends = Friends.friends();
		// The friend we came from goes first.
		if (sendTarget != null) {
			friends = new java.util.ArrayList<>(friends);
			friends.sort((a, b) -> a.id().equals(sendTarget) ? -1 : b.id().equals(sendTarget) ? 1 : 0);
		}
		int buttonW = (width - 6) / 2;
		for (int i = 0; i < friends.size(); i++) {
			Friend friend = friends.get(i);
			String status = SEND_STATUS.get(key(friend));
			boolean sending = "Sending...".equals(status);
			FlatButton send = add(new FlatButton(buttonW, 20, Component.literal(status != null ? friend.name() + ": " + status : "Send to " + friend.name()),
					friend.id().equals(sendTarget) ? FlatButton.Style.PRIMARY : FlatButton.Style.SECONDARY,
					status != null && status.startsWith("Sent") ? Icons.CHECK : Icons.SEND, b -> send(friend)),
					(i % 2) * (buttonW + 6), y + (i / 2) * 24);
			send.active = !sending;
		}
		reserve(y + ((friends.size() + 1) / 2) * 24 + 20);
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

	private int ticks;

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
			int w = width;
			Draw.roundRect(g, left, top + 24, w, previewH, 6, Theme.BORDER);
			if (image != null) {
				ImageButton.drawCover(g, image, left + 1, top + 25, w - 2, previewH - 2);
			}
			Draw.text(g, Draw.fit(selected.getFileName().toString(), width), left, top + 24 + previewH + 4, Theme.FAINT);
			if (Friends.friends().isEmpty()) {
				Draw.text(g, "Add friends to send them screenshots.", left, top + 24 + previewH + 18, Theme.MUTED);
			}
			return;
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

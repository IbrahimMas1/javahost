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
	/** Which screenshots the grid shows: "MINE", "FRIENDS" or "FAVOURITES". */
	private static String view = "MINE";
	private boolean renaming;
	private long confirmDeleteUntil;
	private String actionError;
	private com.javahost.client.gui.ui.TextField renameField;
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
		add(new Segmented<>(Math.min(240, width - 110), List.of("MINE", "FRIENDS", "FAVOURITES"), view,
				v -> switch (v) {
					case "FRIENDS" -> "From friends";
					case "FAVOURITES" -> "★ Favourites";
					default -> "Mine";
				}, v -> {
			view = v;
			screen.refresh();
		}), 0, 0);
		add(new FlatButton(width - 166, 18, Component.literal("Open folder"), FlatButton.Style.GHOST, Icons.PACKAGE,
				b -> com.mojang.blaze3d.Blaze3D.openPath(view.equals("FRIENDS") ? Screenshots.fromFriendsDir() : Screenshots.dir())), Math.min(240, width - 110) + 6, 0);

		files = currentFiles();
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
		previewH = Math.max(50, Math.min(width * 9 / 16, screen.contentHeight() - (picking ? 124 : 58)));
		int actionsY = previewH + 6;
		int half = (width - 6) / 2;
		add(new FlatButton(half, 20, Component.literal(picking ? "Cancel" : "Send to a friend"),
				picking ? FlatButton.Style.SECONDARY : FlatButton.Style.PRIMARY, picking ? Icons.CROSS : Icons.SEND, b -> {
					picking = !picking;
					screen.refresh();
				}), 0, actionsY);
		add(new FlatButton(half, 20, Component.literal("Show in folder"), FlatButton.Style.SECONDARY, Icons.PACKAGE,
				b -> Screenshots.reveal(selected)), half + 6, actionsY);
		buildTools(actionsY + 24);

		pickerY = actionsY + 54;
		pickerFriends = List.of();
		if (!picking) {
			reserve(actionsY + 60);
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

	/** Favourite, Rename and Delete, under the preview. */
	private void buildTools(int y) {
		int third = (width - 12) / 3;
		if (renaming) {
			renameField = add(new com.javahost.client.gui.ui.TextField(width - 128, Component.literal("New name")), 0, y);
			String name = selected.getFileName().toString();
			renameField.setValue(name.substring(0, name.length() - 4));
			add(new FlatButton(58, 20, Component.literal("Save"), FlatButton.Style.PRIMARY, Icons.CHECK, b -> rename()), width - 122, y);
			add(new FlatButton(58, 20, Component.literal("Cancel"), FlatButton.Style.GHOST, null, b -> {
				renaming = false;
				actionError = null;
				screen.refresh();
			}), width - 58, y);
			screen.focusOn(renameField);
			return;
		}
		boolean favourite = Screenshots.isFavourite(selected);
		add(new FlatButton(third, 20, Component.literal(favourite ? "Favourite" : "Add to favourites"),
				favourite ? FlatButton.Style.PRIMARY : FlatButton.Style.SECONDARY, Icons.STAR, b -> {
					Screenshots.setFavourite(selected, !Screenshots.isFavourite(selected));
					screen.refresh();
				}), 0, y);
		add(new FlatButton(third, 20, Component.literal("Rename"), FlatButton.Style.SECONDARY, Icons.EDIT, b -> {
			renaming = true;
			actionError = null;
			screen.refresh();
		}), third + 6, y);
		boolean confirming = System.currentTimeMillis() < confirmDeleteUntil;
		add(new FlatButton(width - third * 2 - 12, 20, Component.literal(confirming ? "Click again" : "Delete"), FlatButton.Style.DANGER, Icons.TRASH, b -> {
			if (System.currentTimeMillis() >= confirmDeleteUntil) {
				confirmDeleteUntil = System.currentTimeMillis() + 3000;
				screen.refresh();
				return;
			}
			try {
				Screenshots.delete(selected);
				screen.selectScreenshot(null);
			} catch (java.io.IOException e) {
				actionError = "Couldn't delete: " + e.getMessage();
			}
			confirmDeleteUntil = 0;
			screen.refresh();
		}), third * 2 + 12, y);
	}

	private void rename() {
		try {
			java.nio.file.Path renamed = Screenshots.rename(selected, renameField.getValue());
			renaming = false;
			actionError = null;
			screen.selectScreenshot(renamed);
		} catch (java.io.IOException e) {
			actionError = e.getMessage();
		}
		screen.refresh();
	}

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (renaming && isEnter(event) && renameField != null && renameField.isFocused()) {
			rename();
			return true;
		}
		return false;
	}

	private List<Path> currentFiles() {
		return switch (view) {
			case "FRIENDS" -> Screenshots.list(true);
			case "FAVOURITES" -> Screenshots.favourites();
			default -> Screenshots.list(false);
		};
	}

	private String key(Friend friend) {
		return friend.id() + "|" + selected;
	}

	private void send(Friend friend) {
		Path file = selected;
		String key = key(friend);
		SEND_STATUS.put(key, "Sending...");
		screen.refresh();
		CompletableFuture.supplyAsync(() -> null).thenCompose(ignored -> Friends.sendScreenshotFile(friend, file))
				.whenComplete((sent, error) -> minecraft.execute(() -> {
					SEND_STATUS.put(key, error == null && sent.ok() ? "Sent" : error == null ? Friends.describeFailure(sent) : "Couldn't send");
					screen.refresh();
				}));
	}

	private String signature() {
		return String.valueOf(currentFiles().size()) + SEND_STATUS.hashCode() + screen.selectedScreenshot();
	}

	@Override
	public void tick() {
		// Listing the folder is cheap but not free: check once a second.
		if (++ticks % 20 == 0 && !signature().equals(lastSignature)) {
			screen.refresh();
		}
	}

	/** Stars on favourite screenshots, drawn over the thumbnails and the preview. */
	@Override
	protected void drawOverlay(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (selected != null) {
			if (Screenshots.isFavourite(selected)) {
				star(g, left + 4, top + 4);
			}
			return;
		}
		for (int i = 0; i < thumbs.size() && i < files.size(); i++) {
			if (Screenshots.isFavourite(files.get(i))) {
				ImageButton thumb = thumbs.get(i);
				star(g, thumb.getX() + 3, thumb.getY() + 3);
			}
		}
	}

	private static void star(GuiGraphicsExtractor g, int x, int y) {
		Draw.roundRect(g, x, y, 14, 14, 4, 0xCC101419);
		Icons.STAR.drawCentred(g, x + 7, y + 7, 10, 0xFFF2C14E);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (selected != null) {
			Screenshots.Thumbnail image = Screenshots.preview(selected);
			Draw.roundRect(g, left, top, width, previewH, 6, Theme.BORDER);
			if (image != null) {
				ImageButton.drawCover(g, image, left + 1, top + 1, width - 2, previewH - 2);
			}
			if (actionError != null) {
				Draw.textFit(g, actionError, width, left, top + previewH + 53, Theme.WARNING);
			}
			if (!picking) {
				return;
			}
			int y = top + pickerY;
			sectionLabel(g, "Send to", left, y);
			if (pickerFriends.isEmpty()) {
				card(g, left, y + 12, width, 32);
				Icons.USERS.draw(g, left + 10, y + 22, Theme.FAINT);
				Draw.textFit(g, "No friends yet.", width - 160, left + 26, y + 24, Theme.MUTED);
				return;
			}
			card(g, left, y + 12, width, pickerFriends.size() * ROW + 4);
			y += 14;
			for (Friend friend : pickerFriends) {
				FriendsPage.faceWithDot(g, friend.id(), left + 8, y + 4, 16);
				boolean target = friend.id().equals(sendTarget);
				Draw.textFit(g, friend.name(), width - 120, left + 32, y + 3, target ? Theme.ACCENT : Theme.TEXT);
				String status = SEND_STATUS.get(key(friend));
				String line = status != null && !status.equals("Sent") && !status.equals("Sending...") ? status : FriendsPage.statusLine(friend);
				Draw.textFit(g, line, width - 120, left + 32, y + 13, status != null && !status.startsWith("Sen") ? Theme.WARNING : Theme.FAINT);
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
			Draw.centered(g, switch (view) {
				case "FRIENDS" -> "Nothing from friends yet.";
				case "FAVOURITES" -> "Star screenshots to keep them here.";
				default -> "No screenshots yet.";
			}, left + width / 2, top + gridY + 16, Theme.MUTED);
		}
	}
}

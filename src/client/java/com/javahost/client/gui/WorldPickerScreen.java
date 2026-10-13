package com.javahost.client.gui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.share.WorldShare;
import com.javahost.client.share.WorldShare.Source;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Pick a world (the one you're in, another saved world, or a backup) and a friend in this world, then send them a
 * copy. Glass, like the friend popups.
 */
public class WorldPickerScreen extends Screen {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault());
	private static final int ROW = 26;
	/** Folder sizes, measured in the background (walking a big world takes a moment). */
	private static final Map<Path, Long> SIZES = new ConcurrentHashMap<>();

	private final Screen behind;
	private final Runnable onSent;
	private final List<Source> sources;
	private final List<Friend> friends;
	private Friend target;
	private Source selected;
	private int boxX;
	private int boxY;
	private int boxW;
	private int boxH;
	private int listY;
	private int listH;
	private float scroll;
	private FlatButton sendButton;

	/**
	 * @param friend the friend to send to, or null to choose among friends in this world
	 * @param preselect a world or backup to select first (its path), or null for the first one
	 */
	public WorldPickerScreen(Screen behind, Friend friend, Path preselect, Runnable onSent) {
		super(Component.literal("Send a world"));
		this.behind = behind;
		this.onSent = onSent;
		this.sources = WorldShare.sources();
		this.friends = WorldShare.reachableFriends();
		this.target = friend != null ? friend : friends.isEmpty() ? null : friends.getFirst();
		this.selected = sources.stream().filter(s -> s.path().equals(preselect)).findFirst()
				.orElse(sources.isEmpty() ? null : sources.getFirst());
		for (Source source : sources) {
			if (source.size() < 0 && !SIZES.containsKey(source.path())) {
				SIZES.put(source.path(), -1L);
				CompletableFuture.runAsync(() -> SIZES.put(source.path(), WorldShare.measure(source)));
			}
		}
	}

	@Override
	protected void init() {
		boxW = Math.min(width - 20, 340);
		// As tall as the list needs (at least two rows), up to the window.
		boxH = Math.min(height - 20, 64 + Math.max(2, sources.size()) * ROW + 58 + 4);
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;
		int top = boxY + 40;
		if (friends.size() > 1) {
			List<UUID> ids = friends.stream().map(Friend::id).toList();
			addRenderableWidget(new Segmented<>(boxW - 60, ids, target.id(),
					id -> friends.stream().filter(f -> f.id().equals(id)).findFirst().map(Friend::name).orElse("?"),
					id -> target = friends.stream().filter(f -> f.id().equals(id)).findFirst().orElse(target)))
					.setPosition(boxX + 44, top - 2);
		}
		listY = top + 24;
		listH = boxH - (listY - boxY) - 58;
		int buttonW = (boxW - 40) / 2;
		addRenderableWidget(new FlatButton(boxX + 16, boxY + boxH - 32, buttonW, 22, Component.literal("Cancel"),
				FlatButton.Style.SECONDARY, null, b -> onClose()));
		sendButton = addRenderableWidget(new FlatButton(boxX + boxW - 16 - buttonW, boxY + boxH - 32, buttonW, 22, Component.literal("Send"),
				FlatButton.Style.PRIMARY, Icons.UPLOAD, b -> send()));
		clampScroll();
	}

	private String problem() {
		if (target == null) {
			return "No friends in this world to send to";
		}
		if (selected == null) {
			return "You have no worlds to send";
		}
		return WorldShare.cannotSendTo(target.id());
	}

	private void send() {
		if (problem() != null) {
			return;
		}
		WorldShare.send(target, selected);
		minecraft.gui.setScreen(behind);
		if (onSent != null) {
			onSent.run();
		}
	}

	@Override
	public void tick() {
		sendButton.active = problem() == null;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(behind);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int maxScroll() {
		return Math.max(0, sources.size() * ROW - listH);
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x >= boxX && x < boxX + boxW && y >= listY && y < listY + listH) {
			scroll -= (float) scrollY * ROW;
			clampScroll();
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() >= boxX + 12 && event.x() < boxX + boxW - 12
				&& event.y() >= listY && event.y() < listY + listH) {
			int index = (int) ((event.y() - listY + scroll) / ROW);
			if (index >= 0 && index < sources.size()) {
				selected = sources.get(index);
				if (doubleClick) {
					send();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, Theme.SCRIM_GLASS);
		Draw.glass(g, boxX, boxY, boxW, boxH, 10);
		Icons.UPLOAD.draw(g, boxX + 16, boxY + 15, Theme.ACCENT);
		Draw.scaledText(g, "Send a world", boxX + 32, boxY + 15, 1.15f, Theme.TEXT);

		// Who it's for.
		int top = boxY + 40;
		Draw.text(g, "To", boxX + 16, top + 4, Theme.MUTED);
		if (target == null) {
			Draw.textFit(g, "Nobody: be in the same world as a friend", boxW - 60, boxX + 44, top + 4, Theme.FAINT);
		} else if (friends.size() <= 1) {
			Faces.draw(g, target.id(), boxX + 44, top, 14);
			Draw.textFit(g, target.name(), boxW - 80, boxX + 64, top + 4, Theme.TEXT);
		}

		// The worlds.
		Draw.panel(g, boxX + 12, listY - 2, boxW - 24, listH + 4, 6, Theme.CARD);
		g.enableScissor(boxX + 12, listY, boxX + boxW - 12, listY + listH);
		Draw.clip(boxX + 12, listY, boxX + boxW - 12, listY + listH);
		int y = listY - Math.round(scroll);
		for (Source source : sources) {
			if (y + ROW > listY && y < listY + listH) {
				drawRow(g, source, y, mouseX, mouseY);
			}
			y += ROW;
		}
		Draw.unclip();
		g.disableScissor();
		if (sources.isEmpty()) {
			Draw.textFit(g, "No worlds yet.", boxW - 40, boxX + 22, listY + 8, Theme.FAINT);
		}

		// What happens, or why not.
		String problem = problem();
		int noteY = boxY + boxH - 52;
		if (problem != null) {
			Icons.WARNING.draw(g, boxX + 16, noteY - 1, Theme.WARNING);
			Draw.textFit(g, problem, boxW - 50, boxX + 32, noteY, Theme.WARNING);
		} else {
			Draw.textFit(g, "Sent encrypted. They'll play as themselves in it.", boxW - 32,
					boxX + 16, noteY, Theme.FAINT);
		}

		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private void drawRow(GuiGraphicsExtractor g, Source source, int y, int mouseX, int mouseY) {
		int x = boxX + 14;
		int w = boxW - 28;
		boolean isSelected = source == selected;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, listY) && mouseY < Math.min(y + ROW, listY + listH);
		if (isSelected) {
			Draw.bordered(g, x, y + 1, w, ROW - 2, 5, Theme.CARD_HOVER, Theme.ACCENT);
		} else if (hover) {
			Draw.roundRect(g, x, y + 1, w, ROW - 2, 5, Theme.CARD_HOVER);
		}
		Icons icon = switch (source.kind()) {
			case LIVE -> Icons.HOST;
			case SAVE -> Icons.PLAY;
			case BACKUP -> Icons.PACKAGE;
		};
		icon.draw(g, x + 7, y + 8, isSelected ? Theme.ACCENT : Theme.MUTED);
		long size = source.size() >= 0 ? source.size() : SIZES.getOrDefault(source.path(), -1L);
		String sizeText = size < 0 ? "measuring..." : WorldShare.megabytes(size);
		String detail = switch (source.kind()) {
			case LIVE -> "The world you're in, as it is now · " + sizeText;
			case SAVE -> "Played " + DATE.format(Instant.ofEpochMilli(source.time())) + " · " + sizeText;
			case BACKUP -> "Backup from " + DATE.format(Instant.ofEpochMilli(source.time())) + " · " + sizeText;
		};
		Draw.textFit(g, source.name(), w - 34, x + 24, y + 4, Theme.TEXT);
		Draw.textFit(g, detail, w - 34, x + 24, y + 14, Theme.FAINT);
	}
}

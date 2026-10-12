package com.javahost.client.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.javahost.client.Config;
import com.javahost.client.friends.Chats;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Invite;
import com.javahost.client.friends.Identity;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Notifications;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.HostController;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The JavaHost dock on the title and pause screens: a column of icon buttons down the right edge, each opening
 * a part of the menu. Hovering slides out a label; badges show what needs attention, and the Friends button glows
 * when a friend's world is ready to join.
 */
public final class MenuDock {
	static final int SIZE = 20;
	private static final int GAP = 4;
	private static final int SEPARATOR = 8;
	static final int MARGIN = 6;

	private record Item(String label, Supplier<Icons> icon, Tab tab, IntSupplier badge, BooleanSupplier glow, boolean separatorBefore) {
	}

	private MenuDock() {
	}

	private static List<Item> items() {
		return List.of(
				new Item("Notifications", () -> Icons.BELL, Tab.NOTIFICATIONS, Notifications::unread, () -> false, false),
				new Item("Host", () -> Icons.HOST, Tab.HOST, () -> 0, HostController::isHosting, true),
				new Item("Friends", () -> Icons.USER, Tab.FRIENDS, () -> Friends.incoming().size() + Chats.unreadTotal(), MenuDock::joinable, false),
				new Item("Voice", () -> Config.get().muted ? Icons.MIC_OFF : Icons.MIC, Tab.VOICE, () -> 0, () -> false, false),
				new Item("Screenshots", () -> Icons.CAMERA, Tab.SCREENSHOTS, () -> 0, () -> false, false),
				new Item("Performance", () -> Icons.BOLT, Tab.PERFORMANCE, () -> 0, () -> false, false),
				new Item("Settings", () -> Icons.GEAR, Tab.SETTINGS, () -> 0, () -> false, false),
				new Item("Account", () -> null, Tab.ACCOUNT, () -> 0, () -> false, true));
	}

	/** A friend is hosting (and we're not there yet), or an invite is waiting. */
	static boolean joinable() {
		return Presence.onlineFriends().stream().anyMatch(f -> InviteFlow.joinable(f.id()))
				|| Notifications.items().stream().anyMatch(item -> item.payload() instanceof Invite invite && !invite.expired());
	}

	/** Adds the dock down the right edge of {@code screen}; on the title screen, also your skin in the corner. */
	public static void attach(Minecraft minecraft, Screen screen, int width, int height, boolean titleScreen) {
		List<Item> items = items();
		int total = items.size() * SIZE + (items.size() - 1) * GAP + (int) items.stream().filter(Item::separatorBefore).count() * SEPARATOR;
		int x = width - SIZE - MARGIN;
		int y = Math.max(4, (height - total) / 2);
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		for (Item item : items) {
			if (item.separatorBefore()) {
				y += SEPARATOR;
			}
			widgets.add(new DockButton(minecraft, screen, x, y, item));
			y += SIZE + GAP;
		}
		// Your own skin in the bottom-left corner of the title screen; drag to turn it.
		if (titleScreen && width / 2 - 100 >= 80 && height >= 200) {
			int skinW = 56;
			int skinH = 84;
			widgets.add(new PlayerSkinWidget(skinW, skinH, minecraft.getEntityModels(), () -> Faces.skinOf(Identity.id())));
			AbstractWidget skin = widgets.getLast();
			skin.setPosition(14, height - skinH - 16);
		}
	}

	/** Left edge of the dock, so other title-screen panels can sit beside it. */
	static int left(int screenWidth) {
		return screenWidth - SIZE - MARGIN;
	}

	private static final class DockButton extends Button {
		private final Item item;
		private float hover;
		private int ticks;

		DockButton(Minecraft minecraft, Screen parent, int x, int y, Item item) {
			super(x, y, SIZE, SIZE, Component.literal(item.label()),
					b -> minecraft.gui.setScreen(new JavaHostScreen(parent, item.tab())), DEFAULT_NARRATION);
			this.item = item;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.3f);
			ticks++;
			int x = getX();
			int y = getY();
			boolean glow = item.glow().getAsBoolean();

			if (glow) {
				// A soft pulsing ring: something is ready (a world to join, or you're hosting).
				float pulse = 0.35f + 0.25f * (float) Math.sin(ticks / 9.0);
				Draw.roundRect(g, x - 2, y - 2, SIZE + 4, SIZE + 4, 7, Theme.withAlpha(Theme.ACCENT, pulse));
			}
			int border = Theme.mix(glow ? Theme.ACCENT_DARK : Theme.BORDER, Theme.ACCENT, hover);
			Draw.roundRect(g, x, y, SIZE, SIZE, 5, border);
			Draw.roundRect(g, x + 1, y + 1, SIZE - 2, SIZE - 2, 4, Theme.mix(Theme.withAlpha(Theme.WINDOW, 0.92f), Theme.CARD_HOVER, hover));

			Icons icon = item.icon().get();
			if (icon == null) {
				Faces.draw(g, Identity.id(), x + 4, y + 4, SIZE - 8);
			} else {
				int color = item.tab() == Tab.VOICE && Config.get().muted ? Theme.DANGER
						: glow ? Theme.ACCENT : Theme.mix(Theme.MUTED, Theme.TEXT, hover);
				icon.draw(g, x + (SIZE - Icons.SIZE) / 2, y + (SIZE - Icons.SIZE) / 2, color);
			}

			int count = item.badge().getAsInt();
			if (count > 0) {
				String text = count > 9 ? "9+" : String.valueOf(count);
				int w = Math.max(9, Draw.font().width(text) + 4);
				Draw.roundRect(g, x + SIZE - w + 3, y - 3, w, 9, 4, Theme.DANGER);
				Draw.text(g, text, x + SIZE - w + 3 + (w - Draw.font().width(text)) / 2 + 1, y - 2, 0xFFFFFFFF);
			}
			if (item.tab() == Tab.FRIENDS) {
				int online = Presence.onlineFriends().size();
				if (online > 0) {
					Draw.roundRect(g, x + SIZE - 6, y + SIZE - 6, 8, 8, 4, Theme.WINDOW);
					Draw.roundRect(g, x + SIZE - 5, y + SIZE - 5, 6, 6, 3, Theme.ACCENT);
				}
			}

			if (hover > 0.02f) {
				String label = label();
				int w = Draw.font().width(label) + 12;
				int lx = x - 6 - w + Math.round((1 - hover) * 6);
				int ly = y + (SIZE - 14) / 2;
				int fill = Theme.withAlpha(Theme.WINDOW, 0.95f * hover);
				Draw.roundRect(g, lx, ly, w, 14, 4, fill);
				// Little arrow pointing at the button.
				Draw.roundRect(g, lx + w - 1, ly + 5, 3, 4, 1, fill);
				Draw.text(g, label, lx + 6, ly + 3, Theme.withAlpha(Theme.TEXT, hover));
			}
		}

		/** The hover label, with a hint of what's going on. */
		private String label() {
			return switch (item.tab()) {
				case FRIENDS -> {
					int online = Presence.onlineFriends().size();
					yield online > 0 ? "Friends · " + online + " online" : "Friends";
				}
				case HOST -> HostController.isHosting() ? "Host · open" : "Host";
				case VOICE -> Config.get().muted ? "Voice · muted" : "Voice";
				case ACCOUNT -> Identity.name();
				default -> item.label();
			};
		}
	}
}

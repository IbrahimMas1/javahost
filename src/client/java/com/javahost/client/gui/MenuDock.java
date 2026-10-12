package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.javahost.JavaHost;
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
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The JavaHost dock on the title and pause screens: a column of buttons down the right edge (starting level with
 * the first menu button, so it never covers the logo), plus, on the title screen, your 3D skin and the mod version.
 * Hovering a button slides out its label; badges show what needs attention, and Friends glows when a friend's
 * world is ready to join.
 */
public final class MenuDock {
	private static final int MAX_STEP = 26;
	private static final int SEPARATOR = 8;
	private static final int MARGIN = 6;

	private record Item(String label, Supplier<Icons> icon, Supplier<Tab> tab, IntSupplier badge, BooleanSupplier glow, boolean separatorBefore) {
	}

	private MenuDock() {
	}

	private static List<Item> items() {
		return List.of(
				new Item("Home", () -> Icons.HOME, () -> Tab.HOME, () -> 0, () -> false, false),
				new Item("Host", () -> Icons.HOST, () -> Tab.HOST, () -> 0, HostController::isHosting, false),
				// Notifications live under Friends: the badge counts them, and a click opens them while any are unread.
				new Item("Friends", () -> Icons.USERS, () -> Notifications.unread() > 0 ? Tab.NOTIFICATIONS : Tab.FRIENDS,
						() -> Notifications.unread() + Friends.incoming().size() + Chats.unreadTotal(), MenuDock::joinable, false),
				new Item("Screenshots", () -> Icons.CAMERA, () -> Tab.SCREENSHOTS, () -> 0, () -> false, false),
				new Item("Performance", () -> Icons.BOLT, () -> Tab.PERFORMANCE, () -> 0, () -> false, false),
				new Item("Settings", () -> Icons.GEAR, () -> Tab.SETTINGS, () -> 0, () -> false, false),
				new Item("Account", () -> null, () -> Tab.ACCOUNT, () -> 0, () -> false, true));
	}

	/** A friend is hosting (and we're not there yet), or an invite is waiting. */
	static boolean joinable() {
		return Presence.onlineFriends().stream().anyMatch(f -> InviteFlow.joinable(f.id()))
				|| Notifications.items().stream().anyMatch(item -> item.payload() instanceof Invite invite && !invite.expired());
	}

	/** Left edge of the dock on a screen this wide, so other title-screen panels can sit beside it. */
	static int left(int screenWidth) {
		return screenWidth - MAX_STEP + 4 - MARGIN;
	}

	/** Adds the dock to {@code screen}; on the title screen also your skin and the JavaHost version. */
	public static void attach(Minecraft minecraft, Screen screen, int width, int height, boolean titleScreen) {
		List<Item> items = items();
		int top;
		int step;
		int separator = SEPARATOR;
		if (titleScreen) {
			// Level with Singleplayer, and squeezed to fit above the bottom text on short screens.
			top = height / 4 + 48;
			int room = height - 14 - top;
			step = Math.min(MAX_STEP, (room - separator) / items.size());
			if (step < 19) {
				separator = 0; // short window: drop the gap above Account before shrinking the buttons further
				step = Math.max(18, Math.min(MAX_STEP, room / items.size()));
			}
		} else {
			step = MAX_STEP;
			top = Math.max(4, (height - items.size() * step - separator) / 2);
		}
		int size = Math.max(16, Math.min(22, step - 4));
		int x = width - size - MARGIN;
		int y = top;
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		List<DockButton> buttons = new ArrayList<>();
		for (Item item : items) {
			if (item.separatorBefore()) {
				y += separator;
			}
			DockButton button = new DockButton(minecraft, screen, x, y, size, item);
			buttons.add(button);
			widgets.add(button);
			y += step;
		}
		// Hover labels go on top of everything (the splash text included), so they're drawn after the screen.
		ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
			g.nextStratum();
			for (DockButton button : buttons) {
				button.drawLabel(g);
			}
		});

		if (titleScreen) {
			addSkin(minecraft, screen, widgets, width, height);
			String version = FabricLoader.getInstance().getModContainer(JavaHost.MOD_ID)
					.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
			widgets.add(new StringWidget(2, height - 20, 200, 9, Component.literal("JavaHost " + version), minecraft.font));
		}
	}

	/** Your own skin, big, in the empty space left of the menu buttons; drag to turn it. */
	private static void addSkin(Minecraft minecraft, Screen screen, List<AbstractWidget> widgets, int width, int height) {
		int room = width / 2 - 100 - 12; // free space left of the buttons
		int skinH = Math.round(height * 0.5f);
		int skinW = Math.round(skinH * 0.6f);
		if (skinW > room) {
			skinW = room;
			skinH = Math.round(skinW / 0.6f);
		}
		if (skinW < 50) {
			return;
		}
		int x = (room - skinW) / 2 + 6;
		int y = height - 32 - skinH;
		PlayerSkinWidget skin = new PlayerSkinWidget(skinW, skinH, minecraft.getEntityModels(), () -> Faces.skinOf(Identity.id()));
		skin.setPosition(x, y);
		widgets.add(skin);
	}

	private static final class DockButton extends Button {
		private final Item item;
		private float hover;
		private int ticks;

		DockButton(Minecraft minecraft, Screen parent, int x, int y, int size, Item item) {
			super(x, y, size, size, Component.literal(item.label()),
					b -> minecraft.gui.setScreen(new JavaHostScreen(parent, item.tab().get())), DEFAULT_NARRATION);
			this.item = item;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.3f);
			ticks++;
			int x = getX();
			int y = getY();
			int size = getWidth();
			boolean glow = item.glow().getAsBoolean();

			if (glow) {
				// A soft pulsing ring: something is ready (a world to join, or you're hosting).
				float pulse = 0.3f + 0.25f * (float) Math.sin(ticks / 9.0);
				Draw.roundRect(g, x - 2, y - 2, size + 4, size + 4, 6, Theme.withAlpha(Theme.ACCENT, pulse));
			}
			int border = Theme.mix(glow ? Theme.ACCENT_DARK : 0x40FFFFFF, Theme.ACCENT, hover);
			Draw.roundRect(g, x, y, size, size, 4, border);
			Draw.roundRect(g, x + 1, y + 1, size - 2, size - 2, 3, Theme.mix(0xE0101419, 0xF0222A33, hover));

			Icons icon = item.icon().get();
			if (icon == null) {
				int face = size - 8;
				Faces.draw(g, Identity.id(), x + 4, y + 4, face);
			} else {
				int box = size - 9;
				int color = glow ? Theme.ACCENT : Theme.mix(0xFFD8DEE6, 0xFFFFFFFF, hover);
				icon.draw(g, x + (size - box) / 2, y + (size - box) / 2, box, color);
			}

			int count = item.badge().getAsInt();
			if (count > 0) {
				String text = count > 9 ? "9+" : String.valueOf(count);
				int w = Math.max(9, Draw.font().width(text) + 4);
				Draw.roundRect(g, x + size - w + 3, y - 3, w, 9, 4, Theme.DANGER);
				Draw.text(g, text, x + size - w + 3 + (w - Draw.font().width(text)) / 2 + 1, y - 2, 0xFFFFFFFF);
			}
			if (item.label().equals("Friends") && !Presence.onlineFriends().isEmpty()) {
				Draw.roundRect(g, x + size - 6, y + size - 6, 8, 8, 4, 0xFF101419);
				Draw.roundRect(g, x + size - 5, y + size - 5, 6, 6, 3, Theme.ACCENT);
			}
		}

		/** The label that slides out to the left on hover (drawn after the screen, on top). */
		void drawLabel(GuiGraphicsExtractor g) {
			if (hover <= 0.02f) {
				return;
			}
			String label = label();
			int w = Draw.font().width(label) + 12;
			int x = getX() - 7 - w + Math.round((1 - hover) * 6);
			int y = getY() + (getHeight() - 14) / 2;
			int fill = Theme.withAlpha(0xFF101419, 0.95f * hover);
			Draw.roundRect(g, x, y, w, 14, 3, fill);
			// A small pointer towards the button.
			g.fill(x + w, y + 4, x + w + 2, y + 10, fill);
			g.fill(x + w + 2, y + 5, x + w + 3, y + 9, fill);
			Draw.text(g, label, x + 6, y + 3, Theme.withAlpha(0xFFFFFFFF, hover));
		}

		/** The hover label, with a hint of what's going on. */
		private String label() {
			return switch (item.label()) {
				case "Friends" -> {
					int unread = Notifications.unread();
					int online = Presence.onlineFriends().size();
					yield unread > 0 ? "Friends · " + unread + " new" : online > 0 ? "Friends · " + online + " online" : "Friends";
				}
				case "Host" -> HostController.isHosting() ? "Host · open" : "Host";
				case "Account" -> Identity.name();
				default -> item.label();
			};
		}
	}
}

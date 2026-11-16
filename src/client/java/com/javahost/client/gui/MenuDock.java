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
 * The JavaHost dock on the title and pause screens: a glass rail down the right edge with the logo on top and a
 * button for each part of the menu (starting level with the first menu button, so it never covers the game's logo),
 * plus, on the title screen, your 3D skin on a lit stage and the mod version. Hovering a button slides out its
 * label; badges show what needs attention, and Friends glows when a friend's world is ready to join.
 */
public final class MenuDock {
	private static final int MAX_STEP = 26;
	private static final int SEPARATOR = 8;
	private static final int MARGIN = 6;
	/** Space between the rail's edge and its buttons. */
	private static final int RAIL_PAD = 4;
	private static final int LOGO_H = 24;

	private record Item(String label, Supplier<Icons> icon, Supplier<Tab> tab, IntSupplier badge, BooleanSupplier glow, boolean separatorBefore) {
	}

	private MenuDock() {
	}

	/** Title screen: the five places and Settings. Pause screen: just Home, Friends and World. */
	private static List<Item> items(boolean titleScreen) {
		List<Item> items = new ArrayList<>(List.of(
				new Item("Home", () -> Icons.HOME, () -> Tab.HOME, () -> 0, () -> false, false),
				// Notifications live under Friends: the badge counts them, and a click opens them while any are unread.
				new Item("Friends", () -> Icons.USERS, () -> Notifications.unread() > 0 ? Tab.NOTIFICATIONS : Tab.FRIENDS,
						() -> Notifications.unread() + Friends.incoming().size() + Chats.unreadTotal(), MenuDock::joinable, false),
				new Item("World", () -> Icons.HOST, () -> Tab.HOST, () -> 0, HostController::isHosting, false)));
		if (titleScreen) {
			items.add(new Item("Public worlds", () -> Icons.GLOBE, () -> Tab.DISCOVER, () -> 0, () -> false, false));
			items.add(new Item("Mods", () -> Icons.PACKAGE, () -> Tab.MODS, () -> 0, () -> false, false));
			items.add(new Item("Settings", () -> Icons.GEAR, () -> Tab.SETTINGS, () -> 0, () -> false, true));
		}
		return items;
	}

	/**
	 * Adds JavaHost to the title screen as the player chose in Settings: the rail with the 3D skin and friends panel
	 * (full), just the rail, or (off) one small button in the top-left corner so the menu is always reachable.
	 */
	public static void attachTitle(Minecraft minecraft, Screen screen, int width, int height) {
		switch (com.javahost.client.Config.get().titleScreen) {
			case "OFF" -> Screens.getWidgets(screen).add(Button.builder(Component.literal("JavaHost"),
					b -> minecraft.gui.setScreen(new JavaHostScreen(screen, Tab.HOME))).bounds(4, 4, 64, 20).build());
			case "DOCK" -> attach(minecraft, screen, width, height, true, false);
			default -> {
				attach(minecraft, screen, width, height, true, true);
				TitleFriendsPanel.attach(minecraft, screen, width, height);
			}
		}
	}

	/** Adds the rail to the pause menu, unless the player turned it off in Settings. */
	public static void attachPause(Minecraft minecraft, Screen screen, int width, int height) {
		if (com.javahost.client.Config.get().pauseDock) {
			attach(minecraft, screen, width, height, false, false);
		}
	}

	/** A friend is hosting (and we're not there yet), or an invite is waiting. */
	static boolean joinable() {
		return Presence.onlineFriends().stream().anyMatch(f -> InviteFlow.joinable(f.id()))
				|| Notifications.items().stream().anyMatch(item -> item.payload() instanceof Invite invite && !invite.expired());
	}

	/** Left edge of the dock on a screen this wide, so other title-screen panels can sit beside it. */
	static int left(int screenWidth) {
		return screenWidth - MAX_STEP + 4 - MARGIN - RAIL_PAD;
	}

	/** Adds the dock to {@code screen}; with {@code stage} also your skin and the JavaHost version. */
	private static void attach(Minecraft minecraft, Screen screen, int width, int height, boolean titleScreen, boolean stage) {
		List<Item> items = items(titleScreen);
		int top;
		int step;
		int separator = SEPARATOR;
		if (titleScreen) {
			// Level with Singleplayer, and squeezed to fit above the bottom text on short screens.
			top = height / 4 + 48;
			int room = height - 14 - top;
			step = Math.min(MAX_STEP, (room - separator) / items.size());
			if (step < 19) {
				separator = 0; // short window: drop the gap above Settings before shrinking the buttons further
				step = Math.max(18, Math.min(MAX_STEP, room / items.size()));
				// Still too tall: start higher rather than run off the bottom of the window.
				int needed = step * items.size();
				if (needed > room) {
					top = Math.max(4, height - 14 - needed);
				}
			}
		} else {
			step = MAX_STEP;
			top = Math.max(4, (height - items.size() * step - separator) / 2);
		}
		int size = Math.max(16, Math.min(22, step - 4));
		int x = width - size - MARGIN - RAIL_PAD;
		int y = top;
		// The logo sits on top of the rail when there's room above the first button.
		boolean logo = step >= 22 && top - LOGO_H - RAIL_PAD >= (titleScreen ? height / 4 + 20 : 4);
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		List<DockButton> buttons = new ArrayList<>();
		int separatorY = -1;
		for (Item item : items) {
			if (item.separatorBefore()) {
				separatorY = separator > 0 ? y - (step - size) / 2 + separator / 2 - 1 : -1;
				y += separator;
			}
			buttons.add(new DockButton(minecraft, screen, x, y, size, item));
			y += step;
		}
		int railX = x - RAIL_PAD;
		int railY = top - RAIL_PAD - (logo ? LOGO_H : 0);
		int railW = size + RAIL_PAD * 2;
		int railH = y - step + size + RAIL_PAD - railY;
		int lineY = separatorY;
		widgets.add(new Decor(g -> {
			Draw.roundRect(g, railX - 1, railY + 1, railW + 2, railH + 2, 9, 0x50000000);
			Draw.roundRect(g, railX, railY, railW, railH, 8, 0x40FFFFFF);
			Draw.roundRect(g, railX + 1, railY + 1, railW - 2, railH - 2, 7, 0xEA0E1217);
			if (logo) {
				Draw.glow(g, railX + railW / 2, railY + 13, 20, 18, Theme.withAlpha(Theme.ACCENT, 0.32f));
				Icons.logo(g, railX + (railW - 16) / 2, railY + 5, 16);
				g.fill(railX + 6, railY + LOGO_H, railX + railW - 6, railY + LOGO_H + 1, 0x20FFFFFF);
			}
			if (lineY >= 0) {
				g.fill(railX + 6, lineY, railX + railW - 6, lineY + 1, 0x20FFFFFF);
			}
		}));
		widgets.addAll(buttons);
		// Hover labels go on top of everything (the splash text included), so they're drawn after the screen.
		ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
			g.nextStratum();
			for (DockButton button : buttons) {
				button.drawLabel(g);
			}
		});

		if (stage) {
			addSkin(minecraft, screen, widgets, width, height);
			String version = FabricLoader.getInstance().getModContainer(JavaHost.MOD_ID)
					.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
			widgets.add(new StringWidget(2, height - 20, 200, 9, Component.literal("JavaHost " + version), minecraft.font));
		}
	}

	/** Your own skin, big, in the empty space left of the menu buttons, on a lit stage with your name; drag to turn it. */
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
		int y = height - 48 - skinH;
		int cx = x + skinW / 2;
		int feet = y + skinH - 2;
		int plateY = height - 43;
		int spread = skinW;
		widgets.add(new Decor(g -> {
			// A pool of light and a shadow under the feet, then a name plate: who's playing, and friends online.
			Draw.glow(g, cx, feet, Math.round(spread * 0.62f), 11, 0xB0000000);
			Draw.glow(g, cx, feet, Math.round(spread * 0.50f), 8, Theme.withAlpha(Theme.ACCENT, 0.55f));
			Draw.glow(g, cx, feet, Math.round(spread * 0.26f), 4, 0xD0000000);
			String name = Identity.name();
			int online = Presence.onlineFriends().size();
			String extra = online > 0 ? online + " online" : "";
			int nameW = Draw.boldWidth(name);
			int w = 8 + 9 + nameW + (extra.isEmpty() ? 0 : 8 + Draw.width(extra)) + 8;
			int px = Math.max(3, cx - w / 2);
			Draw.roundRect(g, px, plateY, w, 15, 7, 0x40FFFFFF);
			Draw.roundRect(g, px + 1, plateY + 1, w - 2, 13, 6, 0xEA0E1217);
			Draw.roundRect(g, px + 7, plateY + 5, 5, 5, 2, Identity.canSign() ? Theme.ACCENT : Theme.WARNING);
			Draw.bold(g, name, px + 17, plateY + 4, Theme.TEXT);
			if (!extra.isEmpty()) {
				Draw.text(g, extra, px + 17 + nameW + 8, plateY + 4, Theme.ACCENT);
			}
		}));
		// A click (not a drag, which turns it) opens the Skins page.
		PlayerSkinWidget skin = com.javahost.client.gui.ui.IdleSkin.clickable(skinW, skinH, Identity.id(), true, "Change skin",
				() -> minecraft.gui.setScreen(new JavaHostScreen(screen, Tab.SKINS)));
		skin.setPosition(x, y);
		widgets.add(skin);
	}

	/** Something drawn behind the widgets added after it (the rail, the stage); it takes no input. */
	private static final class Decor extends AbstractWidget {
		private final java.util.function.Consumer<GuiGraphicsExtractor> painter;

		Decor(java.util.function.Consumer<GuiGraphicsExtractor> painter) {
			super(0, 0, 0, 0, Component.empty());
			this.painter = painter;
			this.active = false;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			painter.accept(g);
		}

		@Override
		protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
		}
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
		//? if >=26.1 {
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		//?} else if >=1.21.11 {
		/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?} else {
		/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?}
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.3f);
			ticks++;
			int x = getX();
			int y = getY();
			int size = getWidth();
			boolean glow = item.glow().getAsBoolean();

			if (glow) {
				// A soft pulsing light: something is ready (a world to join, or you're hosting).
				float pulse = 0.5f + 0.5f * (float) Math.sin(ticks / 9.0);
				Draw.glow(g, x + size / 2, y + size / 2, size, size, Theme.withAlpha(Theme.ACCENT, 0.25f + 0.20f * pulse));
				Draw.roundRect(g, x, y, size, size, 5, Theme.withAlpha(Theme.ACCENT, 0.16f + 0.08f * pulse));
			}
			if (hover > 0.01f) {
				Draw.roundRect(g, x, y, size, size, 5, Theme.withAlpha(0x2CFFFFFF, hover));
			}

			Icons icon = item.icon().get();
			if (icon == null) {
				int face = size - 8;
				Faces.draw(g, Identity.id(), x + 4, y + 4, face);
			} else {
				int color = glow ? Theme.ACCENT : Theme.mix(0xFFB4BFCC, 0xFFFFFFFF, hover);
				icon.drawCentred(g, x + size / 2f, y + size / 2f, size >= 21 ? 15 : 11, color);
			}

			int count = item.badge().getAsInt();
			if (count > 0) {
				String text = count > 9 ? "9+" : String.valueOf(count);
				int w = Math.max(9, Draw.width(text) + 4);
				Draw.roundRect(g, x + size - w + 3, y - 3, w, 9, 4, Theme.DANGER);
				Draw.text(g, text, x + size - w + 3 + (w - Draw.width(text)) / 2 + 1, y - 2, 0xFFFFFFFF);
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
			int w = Draw.width(label) + 12;
			int x = getX() - RAIL_PAD - 6 - w + Math.round((1 - hover) * 6);
			int y = getY() + (getHeight() - 14) / 2;
			Draw.roundRect(g, x - 1, y - 1, w + 2, 16, 5, Theme.withAlpha(0x40FFFFFF, hover));
			Draw.roundRect(g, x, y, w, 14, 4, Theme.withAlpha(0xFF0E1217, 0.96f * hover));
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
				case "World" -> HostController.isHosting() ? "World · hosting" : "World";
				default -> item.label();
			};
		}
	}
}

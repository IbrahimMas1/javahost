package com.javahost.client;

import java.util.List;
import java.util.UUID;

import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** JavaHost-styled pop-ups in the corner: dark card, icon or player face, title and a line of text. */
public final class Toasts {
	private static final long SHOW_MS = 4000;

	private Toasts() {
	}

	public static void show(String title, String message) {
		show(title, title, message);
	}

	/**
	 * Shows a toast; a newer toast with the same {@code key} replaces the one on screen (or waiting) instead of
	 * queuing behind it, so pressing a key repeatedly doesn't leave a backlog of toasts.
	 */
	public static void show(String key, String title, String message) {
		add(key, title, message, null);
	}

	/** A toast that's also kept, in full, in the notification center (for things worth reading later). */
	public static void notify(String title, String message) {
		show(title, message);
		Minecraft.getInstance().execute(() -> com.javahost.client.friends.Notifications.add(
				com.javahost.client.friends.Notifications.Kind.INFO, null, title, message, null));
	}

	/** A toast with a player's face, e.g. "Alex joined your world". */
	public static void player(UUID player, String title, String message) {
		add(player + "|" + title, title, message, player);
	}

	/** Toasts that arrived while the JavaHost menu was open (they'd cover its buttons); shown when it closes. */
	private static final java.util.ArrayDeque<JavaHostToast> WAITING = new java.util.ArrayDeque<>();

	private static void add(String key, String title, String message, UUID face) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			// Same kind of toast already showing or waiting: update it instead of adding another.
			for (JavaHostToast waiting : WAITING) {
				if (waiting.key.equals(key)) {
					waiting.update(title, message, face);
					return;
				}
			}
			JavaHostToast showing = minecraft.gui.toastManager().getToast(JavaHostToast.class, key);
			if (showing != null) {
				showing.update(title, message, face);
				return;
			}
			JavaHostToast toast = new JavaHostToast(key, title, message, face);
			if (menuOpen(minecraft)) {
				WAITING.add(toast);
				while (WAITING.size() > 3) {
					WAITING.poll(); // keep the newest few
				}
			} else {
				minecraft.gui.toastManager().addToast(toast);
			}
		});
	}

	private static boolean menuOpen(Minecraft minecraft) {
		return minecraft.gui.screen() instanceof com.javahost.client.gui.JavaHostScreen;
	}

	/** Called every client tick: shows waiting toasts once the menu is closed. */
	public static void tick(Minecraft minecraft) {
		if (!WAITING.isEmpty() && !menuOpen(minecraft)) {
			while (!WAITING.isEmpty()) {
				minecraft.gui.toastManager().addToast(WAITING.poll());
			}
		}
	}

	private static final class JavaHostToast implements Toast {
		private static final int WIDTH = 200;
		private final String key;
		private String title;
		private String message;
		private UUID face;
		private Visibility visibility = Visibility.SHOW;
		private List<net.minecraft.util.FormattedCharSequence> wrapped;
		/** Restart the display time on the next update (the text changed). */
		private boolean restart;
		private long shownFrom;
		private long elapsed;

		JavaHostToast(String key, String title, String message, UUID face) {
			this.key = key;
			this.title = title;
			this.message = message;
			this.face = face;
		}

		void update(String title, String message, UUID face) {
			this.title = title;
			this.message = message;
			this.face = face;
			this.wrapped = null;
			this.restart = true;
		}

		@Override
		public Object getToken() {
			return key;
		}

		/** One or two lines of message, cut with "..." if longer (the full text is in the notification center). */
		private List<net.minecraft.util.FormattedCharSequence> lines() {
			if (wrapped == null) {
				List<net.minecraft.util.FormattedCharSequence> all = Draw.font().split(net.minecraft.network.chat.Component.literal(message), WIDTH - 40);
				if (all.size() > 2) {
					all = List.of(all.get(0), net.minecraft.util.FormattedCharSequence.composite(all.get(1),
							net.minecraft.network.chat.Component.literal("...").getVisualOrderText()));
				}
				wrapped = all;
			}
			return wrapped;
		}

		@Override
		public int width() {
			return WIDTH;
		}

		@Override
		public int height() {
			return SLOT_HEIGHT; // one slot, so stacked toasts sit evenly with no gaps
		}

		@Override
		public Visibility getWantedVisibility() {
			return visibility;
		}

		@Override
		public void update(ToastManager manager, long fullyVisibleForMs) {
			if (restart) {
				restart = false;
				shownFrom = fullyVisibleForMs;
			}
			elapsed = Math.max(0, fullyVisibleForMs - shownFrom);
			visibility = elapsed >= SHOW_MS ? Visibility.HIDE : Visibility.SHOW;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, Font font, long fullyVisibleForMs) {
			if (menuOpen(Minecraft.getInstance())) {
				return; // don't cover the menu's buttons; it finishes its time out of sight
			}
			int h = height();
			Draw.roundRect(g, 0, 0, WIDTH, h, 6, Theme.GLASS_EDGE);
			Draw.roundRect(g, 1, 1, WIDTH - 2, h - 2, 5, 0xEC161B22);
			// The accent strip on the left shrinks to show how long the toast stays.
			float left = 1 - Math.min(1f, elapsed / (float) SHOW_MS);
			int strip = Math.max(2, Math.round((h - 8) * left));
			Draw.roundRect(g, 1, 4 + (h - 8 - strip) / 2, 2, strip, 1, Theme.ACCENT);
			if (face != null) {
				Faces.draw(g, face, 9, 8, 16);
			} else {
				Icons.logo(g, 9, 8, 16);
			}
			List<net.minecraft.util.FormattedCharSequence> lines = lines();
			if (lines.size() > 1) {
				Draw.text(g, Draw.fit(title, WIDTH - 40), 32, 3, Theme.TEXT);
				g.text(Draw.font(), lines.get(0), 32, 13, Theme.MUTED, false);
				g.text(Draw.font(), lines.get(1), 32, 22, Theme.MUTED, false);
			} else {
				Draw.text(g, Draw.fit(title, WIDTH - 40), 32, 7, Theme.TEXT);
				if (!lines.isEmpty()) {
					g.text(Draw.font(), lines.get(0), 32, 18, Theme.MUTED, false);
				}
			}
		}
	}
}

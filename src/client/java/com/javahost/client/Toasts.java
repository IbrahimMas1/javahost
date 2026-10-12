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
		add(new JavaHostToast(title, message, null));
	}

	/** A toast that's also kept, in full, in the notification center (for things worth reading later). */
	public static void notify(String title, String message) {
		show(title, message);
		Minecraft.getInstance().execute(() -> com.javahost.client.friends.Notifications.add(
				com.javahost.client.friends.Notifications.Kind.INFO, null, title, message, null));
	}

	/** A toast with a player's face, e.g. "Alex joined your world". */
	public static void player(UUID player, String title, String message) {
		add(new JavaHostToast(title, message, player));
	}

	/** Toasts that arrived while the JavaHost menu was open (they'd cover its buttons); shown when it closes. */
	private static final java.util.ArrayDeque<Toast> WAITING = new java.util.ArrayDeque<>();

	private static void add(Toast toast) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
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
		private final String title;
		private final String message;
		private final UUID face;
		private Visibility visibility = Visibility.SHOW;

		JavaHostToast(String title, String message, UUID face) {
			this.title = title;
			this.message = message;
			this.face = face;
		}

		private List<net.minecraft.util.FormattedCharSequence> wrapped;

		private List<net.minecraft.util.FormattedCharSequence> lines() {
			if (wrapped == null) {
				List<net.minecraft.util.FormattedCharSequence> all = Draw.font().split(net.minecraft.network.chat.Component.literal(message), width() - 40);
				if (all.size() > 2) {
					// Keep two lines; the second ends with an ellipsis.
					all = List.of(all.get(0), net.minecraft.util.FormattedCharSequence.composite(all.get(1),
							net.minecraft.network.chat.Component.literal("...").getVisualOrderText()));
				}
				wrapped = all;
			}
			return wrapped;
		}

		@Override
		public int height() {
			return lines().size() > 1 ? 42 : 32;
		}

		@Override
		public Visibility getWantedVisibility() {
			return visibility;
		}

		@Override
		public void update(ToastManager manager, long fullyVisibleForMs) {
			visibility = fullyVisibleForMs >= SHOW_MS ? Visibility.HIDE : Visibility.SHOW;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, Font font, long fullyVisibleForMs) {
			if (menuOpen(Minecraft.getInstance())) {
				return; // don't cover the menu's buttons; it finishes its time out of sight
			}
			Draw.roundRect(g, 0, 0, width(), height(), 6, Theme.GLASS_EDGE);
			Draw.roundRect(g, 1, 1, width() - 2, height() - 2, 5, 0xEC161B22);
			Draw.roundRect(g, 1, 6, 2, height() - 12, 1, Theme.ACCENT);
			// A thin line along the bottom shows how long the toast stays.
			float left = 1 - Math.min(1f, fullyVisibleForMs / (float) SHOW_MS);
			g.fill(6, height() - 2, 6 + Math.round((width() - 12) * left), height() - 1, Theme.withAlpha(Theme.ACCENT, 0.7f));
			if (face != null) {
				Faces.draw(g, face, 9, 8, 16);
			} else {
				Icons.logo(g, 9, 8, 16);
			}
			Draw.text(g, Draw.fit(title, width() - 40), 32, 7, Theme.TEXT);
			// Up to two lines of message (the full text is in the notification center when it matters).
			List<net.minecraft.util.FormattedCharSequence> lines = lines();
			for (int i = 0; i < lines.size(); i++) {
				g.text(Draw.font(), lines.get(i), 32, 18 + i * 10, Theme.MUTED, false);
			}
		}
	}
}

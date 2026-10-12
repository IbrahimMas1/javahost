package com.javahost.client;

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
			Draw.text(g, Draw.fit(message, width() - 40), 32, 18, Theme.MUTED);
		}
	}
}

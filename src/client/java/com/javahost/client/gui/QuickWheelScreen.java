package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.Toasts;
import com.javahost.client.friends.Presence;
import com.javahost.client.friends.Screenshots;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.HostController;
import com.javahost.client.voice.VoiceClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * The quick menu: hold its key (Z by default) while playing and six actions appear in a ring around the middle of
 * the screen. Move the mouse toward one and let go of the key to do it; a quick tap keeps the ring open so you can
 * click instead. The game keeps running behind it.
 */
public class QuickWheelScreen extends BaseScreen {
	private record Action(String label, String hint, Icons icon, int color, Runnable run) {
	}

	private static final int TILE_W = 60;
	private static final int TILE = 34;
	private final List<WheelButton> buttons = new ArrayList<>();
	private final long openedAt = System.currentTimeMillis();
	private float openAnim;
	private int selected = -1;

	public QuickWheelScreen() {
		super(Component.literal("JavaHost quick menu"));
	}

	private List<Action> actions() {
		Config config = Config.get();
		IntegratedServer server = minecraft.getSingleplayerServer();
		boolean owner = server != null && minecraft.player != null && com.javahost.compat.Compat.isOwner(server, minecraft.player);
		boolean hosting = HostController.isHosting();
		int online = Presence.onlineFriends().size();
		List<Action> actions = new ArrayList<>();
		actions.add(new Action("Menu", "Open JavaHost", Icons.HOME, Theme.TEXT, () -> minecraft.gui.setScreen(new JavaHostScreen(null, Tab.HOME))));
		if (hosting) {
			actions.add(new Action("Invite", online > 0 ? Draw.count(online, "friend") + " online" : "Invite friends to your world", Icons.USERS, Theme.ACCENT,
					() -> minecraft.gui.setScreen(new JavaHostScreen(null, Tab.FRIENDS))));
		} else if (owner) {
			actions.add(new Action("Host", "Open this world to friends", Icons.HOST, Theme.ACCENT, () -> {
				String error = HostController.start(server);
				Toasts.show("quick-host", error == null ? "You're hosting" : "Couldn't start hosting",
						error == null ? "Hold the quick menu key again to invite friends." : error);
			}));
		} else {
			actions.add(new Action("Friends", online > 0 ? Draw.count(online, "friend") + " online" : "Your friends", Icons.USERS, Theme.ACCENT,
					() -> minecraft.gui.setScreen(new JavaHostScreen(null, Tab.FRIENDS))));
		}
		actions.add(new Action("Capture", "Take a screenshot to send to a friend", Icons.CAMERA, Theme.TEXT, () -> {
			minecraft.gui.setScreen(null);
			Screenshots.take(file -> {
				JavaHostScreen menu = new JavaHostScreen(null, Tab.SCREENSHOTS);
				menu.selectScreenshot(file);
				minecraft.gui.setScreen(menu);
			});
		}));
		if (!config.voiceEnabled || !VoiceClient.isAvailable()) {
			actions.add(new Action("Voice", "Voice chat is off: set it up", Icons.MIC_OFF, Theme.MUTED, () -> minecraft.gui.setScreen(new JavaHostScreen(null, Tab.VOICE))));
		} else {
			actions.add(new Action(config.muted ? "Unmute" : "Mute", config.muted ? "Your microphone is muted" : "Mute your microphone",
					config.muted ? Icons.MIC_OFF : Icons.MIC, config.muted ? Theme.DANGER : Theme.TEXT, () -> {
						config.muted = !config.muted;
						config.save();
						Toasts.show("mic", config.muted ? "Microphone muted" : "Microphone on", "");
					}));
		}
		boolean party = VoiceClient.partyMode();
		actions.add(new Action(party ? "Nearby" : "Party", party ? "Voice: back to nearby players only" : "Voice: let the whole world hear",
				Icons.SPEAKER, Theme.INFO, JavaHostClient::toggleParty));
		if (hosting && owner) {
			Connectivity.Route best = Connectivity.recommended();
			if (best != null && best.invite() != null && !best.invite().isEmpty()) {
				actions.add(new Action("Copy code", best.invite(), Icons.COPY, Theme.TEXT, () -> {
					minecraft.keyboardHandler.setClipboard(best.invite());
					Toasts.show("quick-copy", "Invite code copied", "Send it to anyone you want to join.");
				}));
			} else {
				actions.add(new Action("World", "Players, rules and backups", Icons.GEAR, Theme.TEXT, () -> minecraft.gui.setScreen(new JavaHostScreen(null, Tab.HOST))));
			}
		} else {
			actions.add(new Action("Settings", "JavaHost settings", Icons.GEAR, Theme.TEXT, () -> minecraft.gui.setScreen(new JavaHostScreen(null, Tab.SETTINGS))));
		}
		return actions;
	}

	@Override
	protected void init() {
		buttons.clear();
		List<Action> actions = actions();
		int radius = Math.max(44, Math.min(62, Math.min(width, height) / 4));
		for (int i = 0; i < actions.size(); i++) {
			double angle = -Math.PI / 2 + i * 2 * Math.PI / actions.size();
			int cx = width / 2 + (int) Math.round(Math.cos(angle) * radius * 1.25);
			int cy = height / 2 + (int) Math.round(Math.sin(angle) * radius);
			WheelButton button = new WheelButton(cx - TILE_W / 2, cy - TILE / 2, actions.get(i), i);
			buttons.add(button);
			addRenderableWidget(button);
		}
	}

	private void choose(int index) {
		if (index < 0 || index >= buttons.size()) {
			return;
		}
		Runnable run = buttons.get(index).action.run();
		minecraft.gui.setScreen(null);
		run.run();
	}

	/** Letting go of the key does what the mouse points at; after a quick tap the ring stays open for a click. */
	private boolean released() {
		if (selected >= 0) {
			choose(selected);
		} else if (System.currentTimeMillis() - openedAt > 350) {
			minecraft.gui.setScreen(null);
		}
		return true;
	}

	//? if >=1.21.9 {
	@Override
	public boolean keyReleased(KeyEvent event) {
		return JavaHostClient.QUICK_MENU.matches(event) ? released() : super.keyReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (JavaHostClient.QUICK_MENU.matches(event) && System.currentTimeMillis() - openedAt > 350) {
			minecraft.gui.setScreen(null); // pressing the key again closes a ring left open
			return true;
		}
		return super.keyPressed(event);
	}
	//?} else {
	/*@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		return JavaHostClient.QUICK_MENU.matches(key, scancode) ? released() : super.keyReleased(key, scancode, modifiers);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (JavaHostClient.QUICK_MENU.matches(key, scancode) && System.currentTimeMillis() - openedAt > 350) {
			minecraft.gui.setScreen(null);
			return true;
		}
		return super.keyPressed(key, scancode, modifiers);
	}
	*///?}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		background(g, mouseX, mouseY, a);
		openAnim = Draw.approach(openAnim, 1, 0.35f);
		g.fill(0, 0, width, height, Theme.withAlpha(0x66080B0F, openAnim));
		int cx = width / 2;
		int cy = height / 2;
		Draw.glow(g, cx, cy, 150, 110, Theme.withAlpha(Theme.ACCENT, 0.16f * openAnim));

		// Which action the mouse points toward: the nearest tile by direction, once it has left the middle.
		double dx = mouseX - cx;
		double dy = mouseY - cy;
		selected = -1;
		if (dx * dx + dy * dy > 14 * 14) {
			double best = Double.MAX_VALUE;
			for (WheelButton button : buttons) {
				double bx = button.getX() + TILE_W / 2.0 - cx;
				double by = button.getY() + TILE / 2.0 - cy;
				double apart = Math.abs(Math.atan2(Math.sin(Math.atan2(dy, dx) - Math.atan2(by, bx)), Math.cos(Math.atan2(dy, dx) - Math.atan2(by, bx))));
				if (apart < best) {
					best = apart;
					selected = button.index;
				}
			}
		}

		// The middle says what the pointed-at action does.
		String title = selected >= 0 ? buttons.get(selected).action.label() : "JavaHost";
		String hint = selected >= 0 ? buttons.get(selected).action.hint() : "Point, then let go";
		int w = Math.max(74, Math.min(130, Math.max(Draw.width(title), Draw.width(hint)) + 18));
		Draw.roundRect(g, cx - w / 2, cy - 13, w, 26, 8, Theme.withAlpha(0x40FFFFFF, openAnim));
		Draw.roundRect(g, cx - w / 2 + 1, cy - 12, w - 2, 24, 7, Theme.withAlpha(0xF00E1217, openAnim));
		Draw.centered(g, title, cx, cy - 9, Theme.withAlpha(selected >= 0 ? Theme.ACCENT : Theme.TEXT, openAnim));
		String shown = Draw.fit(hint, w - 10);
		Draw.centered(g, shown, cx, cy + 2, Theme.withAlpha(Theme.MUTED, openAnim));

		g.nextStratum();
		for (WheelButton button : buttons) {
			button.extractRenderState(g, mouseX, mouseY, a);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** One action in the ring: a glass tile with its icon and name. A real button, so a click (or a test) can press it. */
	private final class WheelButton extends Button {
		private final Action action;
		private final int index;
		private float glow;

		WheelButton(int x, int y, Action action, int index) {
			super(x, y, TILE_W, TILE, Component.literal(action.label()), b -> choose(index), DEFAULT_NARRATION);
			this.action = action;
			this.index = index;
		}

		@Override
		//? if >=26.1 {
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		//?} else if >=1.21.11 {
		/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?} else {
		/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?}
			glow = Draw.approach(glow, selected == index ? 1 : 0, 0.4f);
			// The tiles fly out from the middle as the ring opens.
			int x = Math.round(width / 2f - TILE_W / 2f + (getX() - (width / 2f - TILE_W / 2f)) * openAnim);
			int y = Math.round(height / 2f - TILE / 2f + (getY() - (height / 2f - TILE / 2f)) * openAnim);
			if (glow > 0.01f) {
				Draw.glow(g, x + TILE_W / 2, y + TILE / 2, TILE_W, TILE, Theme.withAlpha(Theme.ACCENT, 0.45f * glow));
			}
			Draw.roundRect(g, x, y, TILE_W, TILE, 8, Theme.mix(Theme.withAlpha(0x40FFFFFF, openAnim), Theme.ACCENT, glow));
			Draw.roundRect(g, x + 1, y + 1, TILE_W - 2, TILE - 2, 7, Theme.withAlpha(Theme.mix(0xF00E1217, 0xF018222A, glow), openAnim));
			int color = Theme.withAlpha(Theme.mix(action.color(), Theme.ACCENT, glow * 0.6f), openAnim);
			action.icon().drawCentred(g, x + TILE_W / 2f, y + 12.5f, 15, color);
			String label = Draw.fit(action.label(), TILE_W - 6);
			Draw.centered(g, label, x + TILE_W / 2, y + 23, Theme.withAlpha(Theme.mix(Theme.MUTED, Theme.TEXT, glow), openAnim));
		}
	}
}

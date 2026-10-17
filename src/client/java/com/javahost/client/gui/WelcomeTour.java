package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * A short first-time guide over the JavaHost menu: each step opens a tab, highlights it in the sidebar, and explains
 * it in a card with Next / Skip. Shown once (Settings has "Welcome tour" to see it again).
 */
final class WelcomeTour {
	private record Step(Tab tab, String title, String body) {
	}

	private final JavaHostScreen screen;
	private final List<Step> steps;
	private int index;
	private final List<AbstractWidget> buttons = new ArrayList<>();
	private int cardX;
	private int cardY;
	private int cardW;
	private int cardH;

	WelcomeTour(JavaHostScreen screen) {
		this.screen = screen;
		String talk = JavaHostClient.PUSH_TO_TALK.getTranslatedKeyMessage().getString();
		String party = JavaHostClient.PARTY_VOICE.getTranslatedKeyMessage().getString();
		String menu = JavaHostClient.OPEN_MENU.getTranslatedKeyMessage().getString();
		steps = List.of(
				new Step(Tab.HOME, "Welcome to JavaHost", "Play in your own worlds with friends, no server needed. Here's a 30-second tour."),
				new Step(Tab.FRIENDS, "Add your friends", "Type a friend's Minecraft name to add them. Once they accept, invite them or join their world in one click."),
				new Step(Tab.HOST, "Host your world", "Open one of your worlds, then press Start hosting. Friends get in through the best connection automatically."),
				new Step(Tab.VOICE, "Talk while you play", "Hold " + talk + " to talk to players near you. Hosts press " + party + " to let everyone in the world hear each other."),
				new Step(Tab.HOME, "Open JavaHost any time", "Press " + menu + " in game, or use the buttons down the right of the title screen and pause menu."));
	}

	Tab tab() {
		return steps.get(index).tab();
	}

	/** Adds the Next/Skip buttons; called from the menu's init. */
	void init(int winX, int winY, int winW, int winH, int contentX, int contentW) {
		buttons.clear();
		cardW = Math.min(contentW - 20, 260);
		cardH = 96;
		cardX = contentX + (contentW - cardW) / 2;
		cardY = winY + (winH - cardH) / 2 + 10;
		boolean last = index == steps.size() - 1;
		int buttonW = 70;
		buttons.add(new FlatButton(cardX + cardW - buttonW - 12, cardY + cardH - 30, buttonW, 18, Component.literal(last ? "Done" : "Next"),
				FlatButton.Style.PRIMARY, null, b -> next()));
		if (!last) {
			buttons.add(new FlatButton(cardX + cardW - buttonW * 2 - 18, cardY + cardH - 30, buttonW, 18, Component.literal("Skip tour"),
					FlatButton.Style.GHOST, null, b -> finish()));
		}
	}

	List<AbstractWidget> buttons() {
		return buttons;
	}

	private void next() {
		if (index == steps.size() - 1) {
			finish();
			return;
		}
		index++;
		screen.showTourStep();
	}

	private void finish() {
		Config config = Config.get();
		config.tourDone = true;
		config.save();
		screen.endTour();
	}

	/** Dims the window except the highlighted sidebar tab, then draws the card. */
	void draw(GuiGraphicsExtractor g, int winX, int winY, int winW, int winH, int[] highlight) {
		g.fill(winX, winY, winX + winW, winY + winH, 0x99000000);
		if (highlight != null) {
			Draw.roundRect(g, highlight[0] - 2, highlight[1] - 2, highlight[2] + 4, highlight[3] + 4, 6, Theme.ACCENT);
			Draw.roundRect(g, highlight[0], highlight[1], highlight[2], highlight[3], 5, 0xFF1B222B);
		}
		Draw.glass(g, cardX, cardY, cardW, cardH, 8);
		Step step = steps.get(index);
		Draw.scaledText(g, Draw.fit(step.title(), (int) ((cardW - 24) / 1.15f)), cardX + 12, cardY + 10, 1.15f, Theme.TEXT);
		Draw.wrapped(g, Component.literal(step.body()), cardX + 12, cardY + 26, cardW - 24, Theme.MUTED);
		// Step dots.
		for (int i = 0; i < steps.size(); i++) {
			Draw.roundRect(g, cardX + 12 + i * 9, cardY + cardH - 23, 5, 5, 2, i == index ? Theme.ACCENT : Theme.FAINT);
		}
	}

	boolean isOn(double x, double y) {
		return x >= cardX && x < cardX + cardW && y >= cardY && y < cardY + cardH;
	}
}

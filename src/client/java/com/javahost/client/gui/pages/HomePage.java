package com.javahost.client.gui.pages;

import com.javahost.client.Config;
import com.javahost.client.JavaHostClient;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.CardButton;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.HostController;
import com.javahost.client.perf.BoostMods;
import com.javahost.client.voice.VoiceClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

public class HomePage extends Page {
	private static final int CARDS_Y = 40;
	private static final int TILES_Y = 104;

	@Override
	public String title() {
		return "Welcome back";
	}

	@Override
	public String subtitle() {
		return "Host, talk, and share your look.";
	}

	private boolean canHost() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null && minecraft.player != null && server.isSingleplayerOwner(minecraft.player.nameAndId());
	}

	private String lastState = "";

	private String state() {
		return HostController.isHosting() + ":" + HostController.guests().size();
	}

	@Override
	public void tick() {
		if (!state().equals(lastState)) {
			screen.refresh();
		}
	}

	@Override
	protected void build() {
		lastState = state();
		int half = (width - 8) / 2;
		boolean hosting = HostController.isHosting();
		CardButton host = add(new CardButton(half, 52, Icons.HOST,
				Component.literal(hosting ? "You're hosting" : "Host this world"),
				hosting ? HostController.guests().size() + " friend(s) online" : canHost() ? "Let friends join you" : "Open a singleplayer world first",
				b -> screen.switchTo(JavaHostScreen.Tab.HOST)), 0, CARDS_Y);
		host.active = hosting || canHost();
		add(new CardButton(half, 52, Icons.JOIN, Component.literal("Join a friend"), "Paste their invite code",
				b -> screen.switchTo(JavaHostScreen.Tab.JOIN)), width - half, CARDS_Y);
		reserve(TILES_Y + 46);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		// Greeting with the player's face.
		String name = minecraft.getUser().getName();
		Faces.draw(g, minecraft.getUser().getProfileId(), left, top, 26);
		Draw.scaledText(g, "Hi, " + name, left + 34, top + 2, 1.25f, Theme.TEXT);
		boolean hosting = HostController.isHosting();
		status(g, left + 34, top + 16, hosting ? Theme.ACCENT : Theme.FAINT,
				hosting ? "Hosting · " + HostController.guests().size() + " friend(s) playing" : "Not hosting right now", Theme.MUTED);

		// Three status tiles.
		int tileW = (width - 16) / 3;
		int y = top + TILES_Y;
		Config config = Config.get();
		String voice = !config.voiceEnabled ? "Off" : config.muted ? "Muted"
				: config.pushToTalk ? "Push to talk (" + JavaHostClient.PUSH_TO_TALK.getTranslatedKeyMessage().getString() + ")" : "Voice activation";
		tile(g, left, y, tileW, Icons.MIC, "Voice", voice, VoiceClient.isAvailable() ? Theme.ACCENT : Theme.MUTED);

		long installed = BoostMods.MODS.stream().filter(BoostMods.BoostMod::recommended).filter(BoostMods::isInstalled).count();
		long total = BoostMods.MODS.stream().filter(BoostMods.BoostMod::recommended).count();
		tile(g, left + tileW + 8, y, tileW, Icons.BOLT, "FPS mods", installed + " of " + total,
				installed == total ? Theme.ACCENT : Theme.WARNING);

		tile(g, left + (tileW + 8) * 2, y, width - (tileW + 8) * 2, Icons.SPARKLE, "Frame rate", minecraft.getFps() + " FPS", Theme.INFO);

		Draw.text(g, "Tip: press " + JavaHostClient.OPEN_MENU.getTranslatedKeyMessage().getString() + " in game to open this menu.",
				left, y + 54, Theme.FAINT);
	}

	private static void tile(GuiGraphicsExtractor g, int x, int y, int w, Icons icon, String label, String value, int accent) {
		card(g, x, y, w, 44);
		icon.draw(g, x + 9, y + 9, accent);
		Draw.text(g, label, x + 23, y + 10, Theme.MUTED);
		Draw.text(g, Draw.fit(value, w - 18), x + 9, y + 27, Theme.TEXT);
	}
}

package com.javahost.client.gui.pages;

import java.net.URI;

import com.javahost.client.Config;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Identity;
import com.javahost.client.friends.MessageUsage;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.host.HostController;
import com.javahost.util.Mailbox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

/** You: your skin, how friends see you, today's friend-message usage, and what's kept private. */
public class AccountPage extends Page {
	private static final URI CHANGE_SKIN = URI.create("https://www.minecraft.net/msaprofile/mygames/editskin");
	private static final String[] PRIVACY = {
			"Invites, chat and screenshots: encrypted",
			"Requests signed by your Microsoft account",
			"Only friends see your status and worlds",
	};
	private String lastSignature = "";
	private int ticks;

	@Override
	public String title() {
		return "Account";
	}

	@Override
	public String subtitle() {
		return "You, as your friends see you.";
	}

	@Override
	protected void build() {
		Config config = Config.get();
		lastSignature = signature();
		add(new PlayerSkinWidget(86, 126, minecraft.getEntityModels(), () -> Faces.skinOf(Identity.id())), 5, 4);
		int right = 108;
		int rightW = width - right;
		int buttonW = (rightW - 6) / 2;
		add(new FlatButton(buttonW, 20, Component.literal("Skin"), FlatButton.Style.SECONDARY, Icons.SHIRT,
				b -> ConfirmLinkScreen.confirmLinkNow(screen, CHANGE_SKIN)), right, 52);
		add(new FlatButton(buttonW, 20, Component.literal("Friends"), FlatButton.Style.SECONDARY, Icons.USERS,
				b -> screen.switchTo(JavaHostScreen.Tab.FRIENDS)), right + buttonW + 6, 52);
		add(new ToggleSwitch(rightW, Component.literal("Appear offline"), "Hidden from friends",
				config.appearOffline, v -> {
					config.appearOffline = v;
					config.save();
				}), right, 84);
		reserve(232 + 12 + PRIVACY.length * 14 + 16);
	}

	/** What friends currently see for you. */
	private String seenAs() {
		if (Config.get().appearOffline) {
			return "Offline (hidden)";
		}
		if (HostController.isHosting()) {
			return "Hosting your world";
		}
		return minecraft.level != null ? "Playing" : "Online";
	}

	private String signature() {
		return seenAs() + Identity.canSign() + Friends.status() + MessageUsage.sentToday() + Presence.onlineFriends().size();
	}

	@Override
	public void tick() {
		if (++ticks % 10 == 0 && !signature().equals(lastSignature)) {
			screen.refresh();
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		card(g, left, top, 96, 134);
		int right = left + 108;
		int rightW = width - 108;
		Draw.scaledText(g, Draw.fit(Identity.name(), (int) (rightW / 1.5f)), right, top + 2, 1.5f, Theme.TEXT);
		if (Identity.canSign()) {
			Icons.CHECK.draw(g, right, top + 18, Theme.ACCENT);
			Draw.text(g, Draw.fit("Microsoft account", rightW - 14), right + 13, top + 19, Theme.ACCENT);
		} else {
			Icons.WARNING.draw(g, right, top + 18, Theme.WARNING);
			Draw.text(g, Draw.fit("Not signed in", rightW - 14), right + 13, top + 19, Theme.WARNING);
		}
		boolean hidden = Config.get().appearOffline;
		status(g, right, top + 33, hidden ? Theme.FAINT : Theme.ACCENT, Draw.fit("Friends see: " + seenAs(), rightW - 14), Theme.MUTED);

		int friends = Friends.friends().size();
		int online = Presence.onlineFriends().size();
		String counts = friends + (friends == 1 ? " friend" : " friends") + " · " + online + " online · "
				+ (Friends.status() == Mailbox.Status.ONLINE ? "connected" : Friends.status() == Mailbox.Status.CONNECTING ? "connecting..." : "offline");
		Draw.text(g, Draw.fit(counts, rightW), right, top + 118, Theme.FAINT);

		// Friend messages today, against the relay's daily allowance.
		int y = top + 146;
		sectionLabel(g, "Friend messages today", left, y);
		card(g, left, y + 12, width, 52);
		int sent = MessageUsage.sentToday();
		float used = Math.min(1f, sent / (float) MessageUsage.DAILY_LIMIT);
		int barColor = used > 0.9f ? Theme.DANGER : used > 0.7f ? Theme.WARNING : Theme.ACCENT;
		String amount = sent + " of about " + MessageUsage.DAILY_LIMIT;
		Draw.text(g, amount, left + 10, y + 20, Theme.TEXT);
		Draw.roundRect(g, left + 10, y + 32, width - 20, 4, 2, Theme.FIELD);
		Draw.roundRect(g, left + 10, y + 32, Math.max(4, Math.round((width - 20) * used)), 4, 2, barColor);
		Draw.text(g, Draw.fit("Only sending counts. Resets daily.", width - 20), left + 10, y + 42, Theme.FAINT);

		y += 76;
		sectionLabel(g, "Privacy", left, y);
		card(g, left, y + 12, width, PRIVACY.length * 14 + 8);
		int line = y + 18;
		for (String text : PRIVACY) {
			Icons.CHECK.draw(g, left + 10, line - 1, Theme.ACCENT);
			Draw.text(g, Draw.fit(text, width - 34), left + 24, line, Theme.MUTED);
			line += 14;
		}
	}
}

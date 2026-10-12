package com.javahost.client.gui.pages;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import com.javahost.client.Config;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Friends.Friend;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.friends.Presence;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.FlatSlider;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.network.chat.Component;

/** One friend: a 3D skin you can spin, what they're up to, and everything you can do with them. */
public class ProfilePage extends Page {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());
	private Friend friend;
	private long confirmRemoveUntil;
	private String lastSignature = "";

	@Override
	public String title() {
		return friend == null ? "Friend" : friend.name();
	}

	@Override
	public String subtitle() {
		return "Drag the model to spin it.";
	}

	@Override
	protected void build() {
		friend = Friends.friend(screen.subject()).orElse(null);
		if (friend == null) {
			reserve(20);
			return;
		}
		lastSignature = signature();
		Config config = Config.get();
		String id = friend.id().toString();

		add(new PlayerSkinWidget(86, 126, minecraft.getEntityModels(), () -> Faces.skinOf(friend.id())), 5, 4);

		int right = 108;
		int rightW = width - right;
		Optional<Presence.Status> status = Presence.of(friend.id());
		int buttonW = Math.max(60, (rightW - 12) / 3);
		if (InviteFlow.playingTogether(friend.id())) {
			FlatButton together = add(new FlatButton(buttonW, 20, Component.literal("Here"), FlatButton.Style.SECONDARY, Icons.CHECK, b -> {
			}), right, 48);
			together.active = false;
			together.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("You're in the same world")));
		} else if (InviteFlow.joinable(friend.id())) {
			add(new FlatButton(buttonW, 20, Component.literal("Join"), FlatButton.Style.PRIMARY, Icons.JOIN,
					b -> InviteFlow.join(status.get().asInvite(friend.name()))), right, 48);
		} else {
			FlatButton invite = add(new FlatButton(buttonW, 20, Component.literal("Invite"), FlatButton.Style.PRIMARY, Icons.PLAY,
					b -> {
						InviteFlow.invite(friend);
						screen.refresh();
					}), right, 48);
			invite.active = InviteFlow.canInvite() && !InviteFlow.busy(friend.id());
		}
		add(new FlatButton(buttonW, 20, Component.literal("Message"), FlatButton.Style.SECONDARY, Icons.CHAT,
				b -> screen.openChat(friend.id())), right + buttonW + 6, 48);
		add(new FlatButton(buttonW, 20, Component.literal("Screenshot"), FlatButton.Style.SECONDARY, Icons.CAMERA,
				b -> {
					ScreenshotsPage.sendTarget = friend.id();
					screen.switchTo(JavaHostScreen.Tab.SCREENSHOTS);
				}), right + (buttonW + 6) * 2, 48);

		add(new FlatSlider(rightW - 20, "Voice volume", config.playerVolumes.getOrDefault(id, 1.0), 0, 2,
				v -> config.playerVolumes.put(id, v), v -> Math.round(v * 100) + "%"), right + 10, 94);
		add(new ToggleSwitch(rightW - 20, Component.literal("Mute their voice"), null, config.mutedPlayers.contains(id), muted -> {
			if (muted) {
				config.mutedPlayers.add(id);
			} else {
				config.mutedPlayers.remove(id);
			}
			config.save();
		}), right + 10, 122);

		boolean confirming = System.currentTimeMillis() < confirmRemoveUntil;
		add(new FlatButton(rightW, 18, Component.literal(confirming ? "Click again to remove " + friend.name() : "Remove friend"),
				FlatButton.Style.DANGER, Icons.CROSS, b -> {
					if (System.currentTimeMillis() < confirmRemoveUntil) {
						Friends.remove(friend);
						screen.switchTo(JavaHostScreen.Tab.FRIENDS);
					} else {
						confirmRemoveUntil = System.currentTimeMillis() + 4000;
						screen.refresh();
					}
				}), right, 152);
		reserve(176);
	}

	private String signature() {
		return friend == null ? "" : FriendsPage.statusLine(friend) + InviteFlow.status(friend.id()) + InviteFlow.canInvite()
				+ (System.currentTimeMillis() < confirmRemoveUntil);
	}

	@Override
	public void tick() {
		if (friend != null && !signature().equals(lastSignature)) {
			screen.refresh();
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (friend == null) {
			Draw.text(g, "This friend is no longer on your list.", left, top + 2, Theme.MUTED);
			return;
		}
		card(g, left, top, 96, 134);
		int right = left + 108;
		int rightW = width - 108;
		Draw.scaledText(g, Draw.fit(friend.name(), (int) (rightW / 1.5f)), right, top + 2, 1.5f, Theme.TEXT);
		Optional<Presence.Status> status = Presence.of(friend.id());
		int dot = status.isPresent() ? Theme.ACCENT : Theme.FAINT;
		String invite = InviteFlow.status(friend.id());
		status(g, right, top + 18, dot, Draw.fit(invite != null ? invite : FriendsPage.statusLine(friend), rightW - 14), Theme.MUTED);
		Draw.text(g, "Friends since " + DATE.format(Instant.ofEpochMilli(friend.since())), right, top + 32, Theme.FAINT);

		sectionLabel(g, "Their voice", right, top + 78);
		card(g, right, top + 88, rightW, 58);
	}
}

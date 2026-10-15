package com.javahost.client.gui;

import java.util.UUID;

import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A friend's face, a question, and two buttons: used for invites ("Join") and friend requests ("Accept"). */
public class FriendPopup extends BaseScreen {
	private final Screen behind;
	private final UUID player;
	private final String headline;
	private final String detail;
	private final String yesLabel;
	private final String noLabel;
	private final Runnable onYes;
	private final Runnable onNo;
	private String extraLabel;
	private Runnable extraAction;
	private int boxX;
	private int boxY;
	private int boxW;
	private int boxH;

	public FriendPopup(Screen behind, UUID player, String headline, String detail, String yesLabel, String noLabel, Runnable onYes, Runnable onNo) {
		super(Component.literal(headline));
		this.behind = behind;
		this.player = player;
		this.headline = headline;
		this.detail = detail;
		this.yesLabel = yesLabel;
		this.noLabel = noLabel;
		this.onYes = onYes;
		this.onNo = onNo;
	}

	/** A small third choice under the two buttons (e.g. "Block" on a friend request). */
	public FriendPopup withExtra(String label, Runnable action) {
		this.extraLabel = label;
		this.extraAction = action;
		return this;
	}

	@Override
	protected void init() {
		boxW = Math.min(width - 20, 280);
		boxH = extraAction != null ? 122 : 104;
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;
		int buttonW = (boxW - 40) / 2;
		int buttonsY = boxY + 72;
		addRenderableWidget(new FlatButton(boxX + 16, buttonsY, buttonW, 22, Component.literal(noLabel),
				FlatButton.Style.SECONDARY, null, b -> close(onNo)));
		addRenderableWidget(new FlatButton(boxX + boxW - 16 - buttonW, buttonsY, buttonW, 22, Component.literal(yesLabel),
				FlatButton.Style.PRIMARY, Icons.CHECK, b -> close(onYes)));
		if (extraAction != null) {
			addRenderableWidget(new FlatButton(boxX + (boxW - 64) / 2, buttonsY + 26, 64, 14, Component.literal(extraLabel),
					FlatButton.Style.GHOST, null, b -> close(extraAction)));
		}
	}

	private void close(Runnable action) {
		minecraft.gui.setScreen(behind);
		action.run();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		background(g, mouseX, mouseY, a);
		g.fill(0, 0, width, height, Theme.SCRIM_GLASS);
		Draw.glass(g, boxX, boxY, boxW, boxH, 10);

		int face = 32;
		Draw.roundRect(g, boxX + 15, boxY + 15, face + 2, face + 2, 4, Theme.ACCENT);
		Faces.draw(g, player, boxX + 16, boxY + 16, face);
		int textX = boxX + 16 + face + 12;
		int textW = boxX + boxW - 16 - textX;
		Draw.scaledText(g, Draw.fit(headline, (int) (textW / 1.15f)), textX, boxY + 18, 1.15f, Theme.TEXT);
		Draw.wrapped(g, Component.literal(detail), textX, boxY + 33, textW, Theme.MUTED);

		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public void onClose() {
		close(onNo);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

package com.javahost.client.gui;

import java.util.UUID;

import com.javahost.client.Toasts;
import com.javahost.client.discover.Directory;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reporting a public world: pick a reason. Three reports from different players hide it until it's reviewed. */
public class ReportScreen extends BaseScreen {
	private static final String[][] REASONS = {
			{"offensive", "Offensive title or description"},
			{"griefing", "Griefing or harassment"},
			{"scam", "Scam or pretending to be someone"},
			{"other", "Something else"},
	};
	private final Screen behind;
	private final UUID host;
	private final String name;
	private int boxX;
	private int boxY;
	private int boxW;
	private int boxH;

	public ReportScreen(Screen behind, UUID host, String name) {
		super(Component.literal("Report " + name));
		this.behind = behind;
		this.host = host;
		this.name = name;
	}

	@Override
	protected void init() {
		boxW = Math.min(width - 20, 260);
		boxH = 52 + REASONS.length * 24 + 30;
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;
		int y = boxY + 46;
		for (String[] reason : REASONS) {
			addRenderableWidget(new FlatButton(boxX + 16, y, boxW - 32, 20, Component.literal(reason[1]), FlatButton.Style.SECONDARY, null,
					b -> send(reason[0])));
			y += 24;
		}
		addRenderableWidget(new FlatButton(boxX + 16, boxY + boxH - 28, boxW - 32, 20, Component.literal("Cancel"), FlatButton.Style.GHOST,
				null, b -> onClose()));
	}

	private void send(String reason) {
		Directory.report(host, reason).thenAccept(result -> minecraft.execute(() -> Toasts.show("report",
				result.ok() ? "Thanks for reporting" : "Couldn't send the report",
				result.ok() ? "We'll take a look. You can also hide " + name + "'s worlds." : result.message())));
		onClose();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(behind);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		background(g, mouseX, mouseY, a);
		g.fill(0, 0, width, height, Theme.SCRIM_GLASS);
		Draw.glass(g, boxX, boxY, boxW, boxH, 10);
		Faces.draw(g, host, boxX + 16, boxY + 14, 18);
		Draw.scaledText(g, Draw.fit("Report " + name, (int) ((boxW - 60) / 1.15f)), boxX + 40, boxY + 14, 1.15f, Theme.TEXT);
		Draw.text(g, "What's wrong?", boxX + 40, boxY + 28, Theme.MUTED);
		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}
}

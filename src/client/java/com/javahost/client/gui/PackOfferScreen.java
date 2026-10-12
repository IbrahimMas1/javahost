package com.javahost.client.gui;

import java.util.List;
import java.util.function.Consumer;

import com.javahost.client.Config;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.net.Payloads.PackInfo;
import com.javahost.net.Payloads.PackKind;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Asks a guest whether to use the packs and shader the host is sharing. */
public class PackOfferScreen extends Screen {
	private static final int ROW = 20;
	private final List<PackInfo> packs;
	private final long downloadBytes;
	private final Consumer<Boolean> answer;
	private ToggleSwitch always;
	private int boxX;
	private int boxY;
	private int boxW;
	private int boxH;

	public PackOfferScreen(List<PackInfo> packs, long downloadBytes, Consumer<Boolean> answer) {
		super(Component.literal("Use the host's packs?"));
		this.packs = packs;
		this.downloadBytes = downloadBytes;
		this.answer = answer;
	}

	@Override
	protected void init() {
		boxW = Math.min(width - 20, 300);
		int listH = Math.min(packs.size(), 5) * ROW;
		boxH = 92 + listH + 52;
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;

		int y = boxY + 70 + listH + 10;
		always = addRenderableWidget(new ToggleSwitch(boxW - 32, Component.literal("Always use host packs"), null,
				Config.get().autoAcceptHostPacks, v -> {
				}));
		always.setPosition(boxX + 16, y);

		int buttonW = (boxW - 40) / 2;
		addRenderableWidget(new FlatButton(boxX + 16, boxY + boxH - 30, buttonW, 20, Component.literal("No thanks"),
				FlatButton.Style.SECONDARY, null, b -> decide(false)));
		addRenderableWidget(new FlatButton(boxX + boxW - 16 - buttonW, boxY + boxH - 30, buttonW, 20, Component.literal("Use them"),
				FlatButton.Style.PRIMARY, Icons.CHECK, b -> decide(true)));
	}

	private void decide(boolean yes) {
		if (yes && always.value()) {
			Config.get().autoAcceptHostPacks = true;
			Config.get().save();
		}
		minecraft.gui.setScreen(null);
		answer.accept(yes);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, Theme.SCRIM_GLASS);
		Draw.glass(g, boxX, boxY, boxW, boxH, 10);

		Icons.PACKAGE.draw(g, boxX + 16, boxY + 16, 18, Theme.ACCENT);
		Draw.scaledText(g, "Use the host's packs?", boxX + 42, boxY + 16, 1.25f, Theme.TEXT);
		Draw.text(g, Draw.fit("See the world exactly the way the host does.", boxW - 58), boxX + 42, boxY + 30, Theme.MUTED);

		String size = downloadBytes == 0 ? "Already downloaded" : String.format("%.1f MB to download", downloadBytes / 1048576.0);
		Draw.text(g, size, boxX + 16, boxY + 52, Theme.FAINT);

		int y = boxY + 66;
		Draw.panel(g, boxX + 12, y, boxW - 24, Math.min(packs.size(), 5) * ROW + 4, 6, Theme.CARD);
		y += 2;
		for (int i = 0; i < Math.min(packs.size(), 5); i++) {
			PackInfo pack = packs.get(i);
			boolean shader = pack.kind() == PackKind.SHADER_PACK;
			(shader ? Icons.SPARKLE : Icons.PACKAGE).draw(g, boxX + 20, y + 5, shader ? Theme.INFO : Theme.ACCENT);
			Draw.text(g, Draw.fit(pack.name(), boxW - 120), boxX + 35, y + 6, Theme.TEXT);
			String kind = shader ? "Shader" : String.format("%.1f MB", pack.size() / 1048576.0);
			Draw.text(g, kind, boxX + boxW - 22 - Draw.font().width(kind), y + 6, Theme.MUTED);
			y += ROW;
		}
		if (packs.size() > 5) {
			Draw.text(g, "and " + (packs.size() - 5) + " more", boxX + 16, y + 4, Theme.FAINT);
		}

		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

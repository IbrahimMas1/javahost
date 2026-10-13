package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.discover.PublicHosting;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.util.ListingText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Opening your world to the public: what it's called, what it's about, how many can join, and voice for visitors. */
public class PublicWorldScreen extends Screen {
	private final Screen behind;
	private final List<String> tags;
	private TextField titleField;
	private TextField descriptionField;
	private int max;
	private boolean voice;
	private String error;
	private int boxX;
	private int boxY;
	private int boxW;
	private int boxH;

	public PublicWorldScreen(Screen behind) {
		super(Component.literal("Open to the public"));
		this.behind = behind;
		Config config = Config.get();
		this.tags = new ArrayList<>(config.publicTags);
		this.max = config.publicMax;
		this.voice = config.publicVoice;
	}

	@Override
	protected void init() {
		String title = titleField != null ? titleField.getValue() : Config.get().publicTitle;
		String description = descriptionField != null ? descriptionField.getValue() : Config.get().publicDescription;
		boxW = Math.min(width - 20, 360);
		boxH = Math.min(height - 12, 276);
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;
		int inner = boxW - 32;
		int x = boxX + 16;
		int y = boxY + 34;

		titleField = addRenderableWidget(new TextField(inner, Component.literal("Title")));
		titleField.setPosition(x, y);
		titleField.setMaxLength(ListingText.TITLE_MAX);
		titleField.setValue(title);
		titleField.setHint(Component.literal("Title, e.g. Help me build a castle").withStyle(s -> s.withColor(Theme.FAINT)));
		titleField.setResponder(v -> error = null);
		y += 24;
		descriptionField = addRenderableWidget(new TextField(inner, Component.literal("Description")));
		descriptionField.setPosition(x, y);
		descriptionField.setMaxLength(ListingText.DESCRIPTION_MAX);
		descriptionField.setValue(description);
		descriptionField.setHint(Component.literal("What's it about? (optional)").withStyle(s -> s.withColor(Theme.FAINT)));
		descriptionField.setResponder(v -> error = null);
		y += 26;

		// Tags: up to four, three to a row.
		int tagW = (inner - 2 * 4) / 3;
		int tx = x;
		int column = 0;
		for (String tag : ListingText.TAGS) {
			if (column == 3) {
				column = 0;
				tx = x;
				y += 20;
			}
			column++;
			boolean on = tags.contains(tag);
			addRenderableWidget(new FlatButton(tx, y, tagW, 16, Component.literal(tag.equals("help") ? "Help" : ListingText.tagLabel(tag)),
					on ? FlatButton.Style.PRIMARY : FlatButton.Style.SECONDARY, null, b -> {
						if (!tags.remove(tag) && tags.size() < 4) {
							tags.add(tag);
						}
						rebuildWidgets();
					}));
			tx += tagW + 4;
		}
		y += 22;
		Segmented<Integer> players = addRenderableWidget(new Segmented<>(132, List.of(2, 4, 8, 16), max, String::valueOf, n -> max = n));
		players.setPosition(boxX + boxW - 16 - 132, y);
		y += 22;
		ToggleSwitch voiceSwitch = addRenderableWidget(new ToggleSwitch(inner, Component.literal("Voice for visitors"),
				"Off: only you and your friends talk", voice, v -> voice = v));
		voiceSwitch.setPosition(x, y);

		int buttonW = (boxW - 40) / 2;
		addRenderableWidget(new FlatButton(boxX + 16, boxY + boxH - 30, buttonW, 22, Component.literal("Cancel"),
				FlatButton.Style.SECONDARY, null, b -> onClose()));
		addRenderableWidget(new FlatButton(boxX + boxW - 16 - buttonW, boxY + boxH - 30, buttonW, 22, Component.literal("Open to the public"),
				FlatButton.Style.PRIMARY, Icons.GLOBE, b -> open()));
	}

	private void open() {
		error = PublicHosting.open(new PublicHosting.Settings(titleField.getValue(), descriptionField.getValue(), List.copyOf(tags), max, voice));
		if (error == null) {
			minecraft.gui.setScreen(behind);
			if (behind instanceof JavaHostScreen screen) {
				screen.switchTo(JavaHostScreen.Tab.HOST);
			}
		}
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
		g.fill(0, 0, width, height, Theme.SCRIM_GLASS);
		Draw.glass(g, boxX, boxY, boxW, boxH, 10);
		Icons.GLOBE.draw(g, boxX + 16, boxY + 13, Theme.ACCENT);
		Draw.scaledText(g, "Open to the public", boxX + 32, boxY + 13, 1.15f, Theme.TEXT);
		int beta = boxX + 32 + (int) (Draw.font().width("Open to the public") * 1.15f) + 6;
		Draw.roundRect(g, beta, boxY + 13, Draw.font().width("Beta") + 8, 11, 3, Theme.withAlpha(Theme.INFO, 0.2f));
		Draw.text(g, "Beta", beta + 4, boxY + 15, Theme.INFO);

		int y = boxY + 34 + 24 + 26 + 20 + 22;
		Draw.text(g, "Players (including you)", boxX + 16, y + 5, Theme.TEXT);
		int noteY = y + 22 + 30;
		String note = error != null ? error
				: "Everyone with JavaHost sees your name, title and description. Your address stays hidden: visitors join through "
						+ "the relay. They can't build until you Trust them, and a backup is made first.";
		Draw.wrapped(g, Component.literal(note), boxX + 16, noteY, boxW - 32, error != null ? Theme.WARNING : Theme.FAINT);

		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}
}

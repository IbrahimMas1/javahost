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

	private static final String NOTE = "Visitors join through the relay, so your address stays hidden, and can't build until you Trust them. "
			+ "A backup is made first.";
	private int playersY;
	private int noteY;
	private boolean showNote;

	@Override
	protected void init() {
		String title = titleField != null ? titleField.getValue() : Config.get().publicTitle;
		String description = descriptionField != null ? descriptionField.getValue() : Config.get().publicDescription;
		boxW = Math.min(width - 20, 360);
		int inner = boxW - 32;
		// Lay out from the content: roomy when there's space, tighter on short windows, and the note moves into the
		// button's tooltip if even that doesn't fit.
		int available = height - 8;
		int noteH = Draw.wrappedHeight(Component.literal(NOTE), inner) + 6;
		int gap = 4;
		int content = 30 + 24 + 26 + 40 + 22 + 30 + noteH + 32;
		if (content > available) {
			gap = 2;
			content -= 10;
		}
		showNote = content <= available;
		if (!showNote) {
			content -= noteH - 12; // one line kept for an error message
		}
		boxH = Math.min(available, content);
		boxX = (width - boxW) / 2;
		boxY = (height - boxH) / 2;
		int x = boxX + 16;
		int y = boxY + 30;

		titleField = addRenderableWidget(new TextField(inner, Component.literal("Title")));
		titleField.place(x, y);
		titleField.setMaxLength(ListingText.TITLE_MAX);
		titleField.setValue(title);
		titleField.setHint(Component.literal("Title, e.g. Help me build a castle").withStyle(st -> st.withColor(Theme.FAINT)));
		titleField.setResponder(v -> error = null);
		y += 20 + gap;
		descriptionField = addRenderableWidget(new TextField(inner, Component.literal("Description")));
		descriptionField.place(x, y);
		descriptionField.setMaxLength(ListingText.DESCRIPTION_MAX);
		descriptionField.setValue(description);
		descriptionField.setHint(Component.literal("What's it about? (optional)").withStyle(st -> st.withColor(Theme.FAINT)));
		descriptionField.setResponder(v -> error = null);
		y += 20 + gap + 4;

		// Tags: up to four, three to a row.
		int tagW = (inner - 2 * 4) / 3;
		int column = 0;
		int tx = x;
		for (String tag : ListingText.TAGS) {
			if (column == 3) {
				column = 0;
				tx = x;
				y += 16 + gap;
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
		y += 16 + gap + 2;
		playersY = y;
		Segmented<Integer> players = addRenderableWidget(new Segmented<>(132, List.of(2, 4, 8, 16), max, String::valueOf, n -> max = n));
		players.setPosition(boxX + boxW - 16 - 132, y);
		y += 18 + gap + 2;
		ToggleSwitch voiceSwitch = addRenderableWidget(new ToggleSwitch(inner, Component.literal("Voice for visitors"),
				"Off: only you and your friends talk", voice, v -> voice = v));
		voiceSwitch.setPosition(x, y);
		y += 24 + gap + 2;
		noteY = y;

		int buttonW = (boxW - 40) / 2;
		addRenderableWidget(new FlatButton(boxX + 16, boxY + boxH - 28, buttonW, 22, Component.literal("Cancel"),
				FlatButton.Style.SECONDARY, null, b -> onClose()));
		FlatButton open = addRenderableWidget(new FlatButton(boxX + boxW - 16 - buttonW, boxY + boxH - 28, buttonW, 22,
				Component.literal("Open to the public"), FlatButton.Style.PRIMARY, Icons.GLOBE, b -> open()));
		open.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Everyone with JavaHost will see your name, title and description. " + NOTE)));
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

		Draw.text(g, "Players (including you)", boxX + 16, playersY + 5, Theme.TEXT);
		if (error != null && !showNote) {
			Draw.textFit(g, error, boxW - 32, boxX + 16, noteY, Theme.WARNING);
		} else if (error != null) {
			Draw.wrapped(g, Component.literal(error), boxX + 16, noteY, boxW - 32, Theme.WARNING);
		} else if (showNote) {
			Draw.wrapped(g, Component.literal(NOTE), boxX + 16, noteY, boxW - 32, Theme.FAINT);
		}

		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, a);
	}
}

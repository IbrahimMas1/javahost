package com.javahost.client.gui.pages.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.WorldOptions;
import com.javahost.rules.WorldRules;
import com.javahost.util.WorldOptionsData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The world's game rules: a search field and (for the host) the lock on top, then every rule as a row with its control
 * on the right, the common ones first and the rest under the game's own category names. Values come from the server
 * ({@link WorldOptions}); a change is a request, and the rows rebuild from what the server says back. With an older
 * host, its few old world rules in the same rows.
 */
public final class RulesSection extends WorldSection {
	private static final int ROW = 28;
	private static final int FIELD_H = TextField.HEIGHT;
	private static final int LOCK_W = 64;
	private static final int SWITCH_W = 22;
	private static final int STEP_W = 16;
	private static final int NUMBER_W = 44;
	private static final int NUMBER_GROUP = STEP_W + 2 + NUMBER_W + 2 + STEP_W;
	private static final int STEP_INSET = 4;
	private static final int CHOICE_W = 132;
	private static final int EMPTY_H = 44;
	private static final long TYPING_PAUSE = 150;
	private static final long REFUSED_SHOWN = 3000;

	/** One row as laid out: where it sits in the section and how wide its control is. */
	private record Row(RuleList.Entry entry, int y, int controlW) {
	}

	/** One card: a label above it (or none) and its rows. */
	private record Card(String label, int labelY, int y, int rows) {
	}

	/** The widgets of one row, for tests. */
	public record Controls(ToggleSwitch toggle, TextField field, FlatButton minus, FlatButton plus) {
	}

	private static RulesSection last;

	// What's typed and what's shown (the list follows the typing after a short pause).
	private String query = "";
	private String applied = "";
	private long typedAt;
	private boolean refocusSearch;
	private TextField searchField;

	// An integer being typed, kept across rebuilds (another player's change shouldn't wipe it).
	private String editingId;
	private String editingText;
	private boolean refocusEditing;
	private boolean quiet;
	private String refusedId;
	private long refusedAt;

	private long confirmUnlockUntil;

	private final List<Row> rows = new ArrayList<>();
	private final List<Card> cards = new ArrayList<>();
	private final Map<String, Controls> controls = new HashMap<>();
	private final Map<String, int[]> drawnText = new HashMap<>();
	private String noteText;
	private Icons noteIcon;
	private int noteColor;
	private int noteIconColor;
	private int noteY = -1;
	private int emptyY = -1;
	private String emptyTitle;
	private String emptyDetail;
	private boolean canChange;

	public RulesSection(Host host) {
		super(host);
	}

	// ---------------------------------------------------------------- test hooks

	/** For tests: show the rules matching {@code text} right away (the menu must be open on Rules). */
	public static void showSearch(String text) {
		RulesSection section = last;
		if (section != null) {
			section.query = section.applied = text;
			section.typedAt = 0;
			section.screen.refresh();
		}
	}

	/** For tests: the rule ids in the order they're listed. */
	public static List<String> shownIds() {
		RulesSection section = last;
		return section == null ? List.of() : section.rows.stream().map(r -> r.entry().id()).toList();
	}

	/** For tests: whether the "nothing matches" card shows. */
	public static boolean emptyShown() {
		RulesSection section = last;
		return section != null && section.emptyY >= 0 && section.emptyTitle != null && section.emptyTitle.startsWith("No rules match");
	}

	/** For tests: the quiet line under the search (lock, read-only, older host), or null. */
	public static String note() {
		RulesSection section = last;
		return section == null ? null : section.noteText;
	}

	/** For tests: a row's widgets. */
	public static Controls controls(String id) {
		RulesSection section = last;
		return section == null ? null : section.controls.get(id);
	}

	/**
	 * For tests: where a row's text ended and its control started when it was last drawn ({@code {textRight,
	 * controlLeft}} in screen coordinates), or null if it wasn't drawn.
	 */
	public static int[] textAndControl(String id) {
		RulesSection section = last;
		return section == null ? null : section.drawnText.get(id);
	}

	// ---------------------------------------------------------------- state

	private static boolean locked() {
		RulesClient.State old = RulesClient.state();
		return WorldOptions.available() ? WorldOptions.locked() : old != null && old.locked();
	}

	@Override
	public String signature() {
		return WorldOptions.version() + "/" + RulesClient.version() + "/" + applied + "/" + (confirmUnlockUntil > 0);
	}

	@Override
	public void tick() {
		if (typedAt > 0 && System.currentTimeMillis() - typedAt > TYPING_PAUSE) {
			typedAt = 0;
			applied = query; // the page sees the new signature and rebuilds
		}
		if (confirmUnlockUntil > 0 && System.currentTimeMillis() > confirmUnlockUntil) {
			confirmUnlockUntil = 0;
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (!event.isConfirmation()) {
			return false;
		}
		if (searchField != null && searchField.isFocused()) {
			typedAt = 0;
			applied = query;
			return true;
		}
		if (screen.getFocused() instanceof NumberField) {
			screen.setFocused(null); // leaving the field sends what was typed
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- layout

	@Override
	protected int layout(int y) {
		last = this;
		rows.clear();
		cards.clear();
		controls.clear();
		noteText = null;
		noteY = -1;
		emptyY = -1;
		searchField = null;

		boolean available = WorldOptions.available();
		RulesClient.State old = RulesClient.state();
		boolean locked = locked();
		boolean isHost = available && WorldOptions.role() == WorldOptions.Role.HOST;
		canChange = available ? WorldOptions.canChangeRules() : old != null && old.canEdit() && !old.locked();

		if (available) {
			y = buildTop(y, isHost, locked);
		}

		// One quiet line: why the controls can't be used, or that the host is on an older JavaHost.
		if (locked) {
			noteText = isHost ? "Locked for a challenge run" : "Locked by the host for a challenge run";
			noteIcon = Icons.LOCK;
			noteColor = Theme.MUTED;
		} else if (!available && old != null) {
			noteText = "The host has an older JavaHost";
			noteIcon = Icons.WARNING;
			noteColor = Theme.FAINT;
		} else if (available && !canChange) {
			noteText = "Set by the host and operators";
			noteIcon = Icons.SHIELD;
			noteColor = Theme.FAINT;
		}
		if (noteText != null) {
			noteIconColor = locked ? Theme.WARNING : noteColor;
			noteY = y;
			y += 10 + 8;
		}

		if (available) {
			if (applied.isBlank()) {
				boolean first = true;
				for (RuleList.Group group : RuleList.grouped()) {
					y = buildCard(first ? null : group.label(), group.entries(), first ? y : y + GAP);
					first = false;
				}
			} else {
				List<RuleList.Entry> found = RuleList.search(applied);
				if (found.isEmpty()) {
					emptyY = y;
					emptyTitle = "No rules match \"" + applied.strip() + "\"";
					emptyDetail = "Try another word, or a rule's id";
					y += EMPTY_H;
				} else {
					y = buildCard(null, found, y);
				}
			}
		} else if (old != null) {
			y = buildCard(null, RuleList.olderHost(old), y);
		} else {
			emptyY = y;
			emptyTitle = minecraft.getSingleplayerServer() != null ? "Loading..." : "The host doesn't have JavaHost";
			emptyDetail = minecraft.getSingleplayerServer() != null ? "" : "Its rules can't be shown here";
			y += EMPTY_H;
		}
		return y;
	}

	/** The search field, and the host's lock beside it. */
	private int buildTop(int y, boolean isHost, boolean locked) {
		int searchW = isHost ? width - LOCK_W - 8 : width;
		searchField = add(new KeptField(searchW, Component.literal("Search rules")), 0, y);
		searchField.setMaxLength(48);
		searchField.setValue(query);
		searchField.setHint(Component.literal(Draw.fit("Search rules", searchW - 14)).withStyle(s -> s.withColor(Theme.FAINT)));
		searchField.setResponder(value -> {
			if (!value.equals(query)) {
				query = value;
				typedAt = System.currentTimeMillis();
			}
		});
		if (refocusSearch) {
			refocusSearch = false;
			screen.focusOn(searchField);
		}
		if (isHost) {
			boolean confirming = locked && confirmUnlockUntil > 0;
			String label = !locked ? "Lock" : confirming ? "Confirm" : "Unlock";
			FlatButton lock = add(new FlatButton(LOCK_W, FIELD_H, Component.literal(label),
					confirming ? FlatButton.Style.PRIMARY : FlatButton.Style.SECONDARY, locked ? Icons.UNLOCK : Icons.LOCK, b -> {
						if (locked && confirmUnlockUntil == 0) {
							confirmUnlockUntil = System.currentTimeMillis() + 3000;
							screen.refresh();
							return;
						}
						confirmUnlockUntil = 0;
						WorldOptions.setLocked(!locked);
						host.sent();
					}), width - LOCK_W, y);
			lock.setTooltip(tip(!locked ? "Lock the rules for a challenge run: nobody can change them until you unlock them."
					: confirming ? "Click again to unlock the rules" : "Let you and your operators change rules again"));
		}
		return y + FIELD_H + 8;
	}

	private int buildCard(String label, List<RuleList.Entry> entries, int y) {
		int labelY = label == null ? -1 : y;
		int top = label == null ? y : y + LABEL;
		cards.add(new Card(label, labelY, top, entries.size()));
		int rowY = top;
		for (RuleList.Entry entry : entries) {
			rows.add(new Row(entry, rowY, buildControl(entry, rowY)));
			rowY += ROW;
		}
		return rowY;
	}

	/** Adds a row's control at its right end; returns how wide it is. */
	private int buildControl(RuleList.Entry entry, int rowY) {
		int right = width - PAD;
		switch (entry.kind()) {
			case BOOL -> {
				// A little wider than the switch so it's easy to hit; it draws only the switch, at its right end.
				ToggleSwitch toggle = add(new ToggleSwitch(SWITCH_W + 8, Component.literal(entry.name()), null, entry.on(), on -> {
					if (entry.old()) {
						RulesClient.request(entry.id(), String.valueOf(on));
					} else {
						WorldOptions.setRule(entry.id(), on);
					}
					host.sent();
				}).bare(), right - SWITCH_W - 8, rowY + (ROW - 16) / 2);
				toggle.active = canChange;
				controls.put(entry.id(), new Controls(toggle, null, null, null));
				return SWITCH_W;
			}
			case CHOICE -> {
				Segmented<String> choice = add(new Segmented<>(CHOICE_W, entry.choices(), entry.value(),
						v -> WorldRules.describe(WorldRules.Rule.valueOf(entry.id()), v), v -> {
							RulesClient.request(entry.id(), v);
							host.sent();
						}), right - CHOICE_W, rowY + (ROW - 18) / 2);
				choice.active = canChange;
				return CHOICE_W;
			}
			default -> {
				// Nudged right so the "+" sits under the switches' right edge (the glyph is narrower than its button).
				right += STEP_INSET;
				int x = right - NUMBER_GROUP;
				int fieldY = rowY + (ROW - FIELD_H) / 2;
				NumberField field = add(new NumberField(NUMBER_W, entry), x + STEP_W + 2, fieldY);
				quiet = true;
				field.setValue(entry.id().equals(editingId) && editingText != null ? editingText : entry.value());
				quiet = false;
				field.setResponder(value -> {
					if (!quiet) {
						editingId = entry.id();
						editingText = value;
					}
				});
				field.setEditable(canChange);
				field.active = canChange;
				field.setTooltip(tip(range(entry)));
				if (entry.id().equals(editingId) && refocusEditing && canChange) {
					refocusEditing = false;
					screen.focusOn(field);
				}
				int value = entry.value().matches("-?[0-9]+") ? safeInt(entry.value()) : 0;
				FlatButton minus = add(new FlatButton(STEP_W, FIELD_H, Component.literal("-"), FlatButton.Style.GHOST, null,
						b -> step(field, -1)), x, fieldY);
				minus.active = canChange && value > entry.min();
				minus.setTooltip(tip("Lower"));
				FlatButton plus = add(new FlatButton(STEP_W, FIELD_H, Component.literal("+"), FlatButton.Style.GHOST, null,
						b -> step(field, 1)), right - STEP_W, fieldY);
				plus.active = canChange && value < entry.max();
				plus.setTooltip(tip("Raise"));
				controls.put(entry.id(), new Controls(null, field, minus, plus));
				return NUMBER_GROUP - STEP_INSET;
			}
		}
	}

	private static int safeInt(String text) {
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String range(RuleList.Entry entry) {
		boolean low = entry.min() != Integer.MIN_VALUE;
		boolean high = entry.max() != Integer.MAX_VALUE;
		return low && high ? "From " + entry.min() + " to " + entry.max()
				: low ? entry.min() + " or more"
				: high ? "Up to " + entry.max()
				: "A whole number";
	}

	// ---------------------------------------------------------------- integer rules

	/** A number field that sends what was typed when it loses focus (but not when the page is only being rebuilt). */
	private final class NumberField extends TextField {
		private final RuleList.Entry entry;

		NumberField(int width, RuleList.Entry entry) {
			super(width, Component.literal(entry.name()));
			this.entry = entry;
			setMaxLength(11);
		}

		@Override
		public void insertText(String input) {
			super.insertText(input.replaceAll("[^0-9-]", "")); // numbers only
		}

		@Override
		public void setFocused(boolean focused) {
			boolean was = isFocused();
			super.setFocused(focused);
			if (was && !focused) {
				if (screen.children().contains(this)) {
					commit(this);
				} else if (entry.id().equals(editingId)) {
					refocusEditing = true; // the page is rebuilding: carry on typing in the new field
				}
			}
		}
	}

	/** The search field keeps focus when the page rebuilds under it (as it does while you type). */
	private final class KeptField extends TextField {
		KeptField(int width, Component narration) {
			super(width, narration);
		}

		@Override
		public void setFocused(boolean focused) {
			boolean was = isFocused();
			super.setFocused(focused);
			if (was && !focused && !screen.children().contains(this)) {
				refocusSearch = true;
			}
		}
	}

	private void setQuietly(TextField field, String value) {
		quiet = true;
		field.setValue(value);
		quiet = false;
	}

	private void commit(NumberField field) {
		RuleList.Entry entry = field.entry;
		String text = field.getValue().strip();
		editingId = null;
		editingText = null;
		if (text.isEmpty() || text.equals(entry.value())) {
			setQuietly(field, entry.value());
			return;
		}
		String clean = WorldOptionsData.validate(false, entry.min(), entry.max(), text);
		if (clean == null || !canChange || !WorldOptions.setRule(entry.id(), clean)) {
			setQuietly(field, entry.value()); // not a value this rule takes: show the real one again
			refusedId = entry.id();
			refusedAt = System.currentTimeMillis();
			return;
		}
		setQuietly(field, clean);
		host.sent();
	}

	private void step(NumberField field, int delta) {
		RuleList.Entry entry = field.entry;
		String typed = field.getValue().strip();
		long base = typed.matches("-?[0-9]{1,10}") ? Long.parseLong(typed) : safeInt(entry.value());
		long next = Math.max(entry.min(), Math.min(entry.max(), base + delta));
		editingId = null;
		editingText = null;
		String value = Long.toString(next);
		setQuietly(field, value);
		if (!value.equals(entry.value()) && WorldOptions.setRule(entry.id(), value)) {
			host.sent();
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		drawnText.clear();
		if (noteY >= 0) {
			noteIcon.draw(g, left + 2, top + noteY, noteIconColor);
			Draw.textFit(g, noteText, width - 16, left + 15, top + noteY + 1, noteColor);
		}
		if (emptyY >= 0) {
			int y = top + emptyY;
			card(g, left, y, width, EMPTY_H);
			if (emptyDetail.isEmpty()) {
				centredFit(g, emptyTitle, left, y + 18, Theme.MUTED);
			} else {
				centredFit(g, emptyTitle, left, y + 12, Theme.MUTED);
				centredFit(g, emptyDetail, left, y + 24, Theme.FAINT);
			}
		}
		int screenH = minecraft.getWindow().getGuiScaledHeight();
		for (Card card : cards) {
			if (card.label() != null) {
				capsFit(g, card.label(), left, top + card.labelY(), width);
			}
			int y = top + card.y();
			int h = card.rows() * ROW;
			if (y > screenH || y + h < 0) {
				continue;
			}
			card(g, left, y, width, h);
			for (int i = 1; i < card.rows(); i++) {
				divider(g, left, y + i * ROW, width);
			}
		}
		long now = System.currentTimeMillis();
		for (Row row : rows) {
			int y = top + row.y();
			if (y > screenH || y + ROW < 0) {
				continue; // off screen: nothing to draw
			}
			RuleList.Entry entry = row.entry();
			int controlLeft = left + width - PAD - row.controlW();
			int textW = width - PAD - row.controlW() - 10 - PAD;
			String description = entry.description();
			int detailColor = Theme.FAINT;
			if (entry.id().equals(refusedId) && now - refusedAt < REFUSED_SHOWN) {
				description = range(entry) + " only";
				detailColor = Theme.WARNING;
			}
			int nameY = description.isEmpty() ? y + 10 : y + 5;
			String name = Draw.fit(entry.name(), textW);
			Draw.text(g, name, left + PAD, nameY, Theme.TEXT);
			if (!name.equals(entry.name())) {
				Draw.truncated(g, left + PAD, nameY - 1, Draw.width(name), 10, entry.name());
			}
			int textRight = left + PAD + Draw.width(name);
			if (!description.isEmpty()) {
				String shown = Draw.fit(description, textW);
				Draw.text(g, shown, left + PAD, y + 15, detailColor);
				if (!shown.equals(description)) {
					Draw.truncated(g, left + PAD, y + 14, Draw.width(shown), 10, description);
				}
				textRight = Math.max(textRight, left + PAD + Draw.width(shown));
			}
			if (entry.changed()) {
				// Not the game's default: a small dot in the margin, with the default on hover.
				Draw.roundRect(g, left + 4, nameY + 2, 3, 3, 1, Theme.ACCENT);
				Draw.truncated(g, left, y + 2, PAD - 1, ROW - 4, "Default: " + entry.describe(entry.defaultValue()));
			}
			drawnText.put(entry.id(), new int[] {textRight, controlLeft});
		}
	}

	private void centredFit(GuiGraphicsExtractor g, String text, int left, int y, int color) {
		String shown = Draw.fit(text, width - 2 * PAD);
		Draw.text(g, shown, left + (width - Draw.width(shown)) / 2, y, color);
		if (!shown.equals(text)) {
			Draw.truncated(g, left + (width - Draw.width(shown)) / 2, y - 1, Draw.width(shown), 10, text);
		}
	}

	/** A section label, cut to fit (category names can be long in other languages). */
	private static void capsFit(GuiGraphicsExtractor g, String text, int x, int y, int w) {
		String shown = text;
		while (shown.length() > 1 && capsWidth(shown) > w) {
			shown = shown.substring(0, shown.length() - 2) + "…";
		}
		label(g, shown, x, y);
		if (!shown.equals(text)) {
			Draw.truncated(g, x, y - 1, capsWidth(shown), 10, text);
		}
	}

	private static int capsWidth(String text) {
		String upper = text.toUpperCase(java.util.Locale.ROOT);
		return Draw.width(upper) + upper.length();
	}
}

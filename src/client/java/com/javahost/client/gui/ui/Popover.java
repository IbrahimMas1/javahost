package com.javahost.client.gui.ui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A small panel that opens over the menu next to a button (a list of actions, or the download list) and closes
 * when you click outside it or press Esc. The menu ({@code JavaHostScreen.openPopover}) places it, draws it on top
 * and gives it clicks first.
 */
public abstract class Popover {
	/** Where the menu placed it, in screen coordinates. */
	protected int x;
	protected int y;

	public abstract int width();

	public abstract int height();

	/** Draws the contents; the panel behind them is already drawn. */
	protected abstract void drawContents(GuiGraphicsExtractor g, int mouseX, int mouseY);

	/** A click inside. Returns true when the popover should close. */
	public abstract boolean click(double mouseX, double mouseY);

	public final void place(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public final int x() {
		return x;
	}

	public final int y() {
		return y;
	}

	public final boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + width() && mouseY >= y && mouseY < y + height();
	}

	public final void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Draw.shadow(g, x, y, width(), height(), 6);
		Draw.bordered(g, x, y, width(), height(), 6, Theme.WINDOW, Theme.BORDER);
		drawContents(g, mouseX, mouseY);
	}

	// ---------------------------------------------------------------- a list of actions

	/** One line of a {@link #menu}: {@code danger} draws it red; a disabled one says why in {@code note}. */
	public record Item(String label, Icons icon, boolean danger, boolean enabled, String note, Runnable action) {
		public static Item of(String label, Icons icon, Runnable action) {
			return new Item(label, icon, false, true, "", action);
		}

		public static Item danger(String label, Icons icon, Runnable action) {
			return new Item(label, icon, true, true, "", action);
		}

		public Item disabled(String why) {
			return new Item(label, icon, danger, false, why, action);
		}

		/** A tick on the right: the current choice, e.g. the player's game mode. */
		public Item selected(boolean selected) {
			return selected ? new Item(label, icon, danger, enabled, "✓", action) : this;
		}
	}

	private static final int ITEM = 18;
	private static final int PAD = 3;

	/** A list of actions; clicking one runs it and closes the popover. */
	public static Popover menu(List<Item> items) {
		return new Popover() {
			@Override
			public int width() {
				int widest = items.stream().mapToInt(i -> Draw.font().width(i.label()) + (i.note().isEmpty() ? 0 : Draw.font().width(i.note()) + 10))
						.max().orElse(40);
				return Math.max(96, widest + 34);
			}

			@Override
			public int height() {
				return items.size() * ITEM + PAD * 2;
			}

			private int index(double mouseX, double mouseY) {
				if (!contains(mouseX, mouseY)) {
					return -1;
				}
				int i = (int) ((mouseY - y - PAD) / ITEM);
				return i >= 0 && i < items.size() ? i : -1;
			}

			@Override
			protected void drawContents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
				int hovered = index(mouseX, mouseY);
				for (int i = 0; i < items.size(); i++) {
					Item item = items.get(i);
					int rowY = y + PAD + i * ITEM;
					if (i == hovered && item.enabled()) {
						Draw.roundRect(g, x + 3, rowY, width() - 6, ITEM, 4, item.danger() ? Theme.withAlpha(Theme.DANGER, 0.16f) : Theme.CARD_HOVER);
					}
					int color = !item.enabled() ? Theme.FAINT : item.danger() ? Theme.DANGER : Theme.TEXT;
					if (item.icon() != null) {
						item.icon().draw(g, x + 8, rowY + (ITEM - Icons.SIZE) / 2, item.enabled() ? (item.danger() ? Theme.DANGER : Theme.MUTED) : Theme.FAINT);
					}
					Draw.text(g, item.label(), x + 24, rowY + (ITEM - 8) / 2, color);
					if (!item.note().isEmpty()) {
						Draw.text(g, item.note(), x + width() - 8 - Draw.font().width(item.note()), rowY + (ITEM - 8) / 2,
								item.note().equals("✓") ? Theme.ACCENT : Theme.FAINT);
					}
				}
			}

			@Override
			public boolean click(double mouseX, double mouseY) {
				int i = index(mouseX, mouseY);
				if (i < 0 || !items.get(i).enabled()) {
					return false;
				}
				items.get(i).action().run();
				return true;
			}
		};
	}
}

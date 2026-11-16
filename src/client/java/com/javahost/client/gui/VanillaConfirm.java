package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Popover;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.mods.ModLibrary;
import com.javahost.client.mods.VanillaMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;

/**
 * "Switch to Vanilla mode?": how many mods are put aside, what that means for worlds, the "Keep performance mods"
 * choice, and Switch / Cancel. Opened from My mods and from Settings; drawn by hand (popovers take clicks first).
 */
public final class VanillaConfirm extends Popover {
	private static final int PAD = 10;
	private static final int BUTTON_W = 64;
	private static final int ROW_H = 18;
	private static final int PARAGRAPH_GAP = 4;

	private static VanillaConfirm open;

	private final int w;
	private final Runnable done;
	private boolean keepPerformance = Config.get().vanillaKeepPerformance;
	private List<List<FormattedCharSequence>> paragraphs = List.of();
	private int count;
	private String problem;

	/** {@code done}: after Switch (the page refreshes). */
	public VanillaConfirm(int maxWidth, Runnable done) {
		this.w = Math.max(180, Math.min(maxWidth, 260));
		this.done = done;
		open = this;
		layout();
	}

	private void layout() {
		count = VanillaMode.toPutAside(keepPerformance);
		List<String> lines = new ArrayList<>();
		lines.add(count == 0 ? "You have no other mods to put aside."
				: Draw.count(count, "mod") + (count == 1 ? " is" : " are") + " put aside when you close the game. Nothing is deleted.");
		lines.add("Worlds that use blocks or items from those mods can lose them if you open them in Vanilla mode.");
		lines.add("Fabric stays installed, so the title screen still says Modded.");
		if (ModLibrary.changeCount() > 0) {
			lines.add("Your waiting on/off changes are cancelled.");
		}
		List<List<FormattedCharSequence>> split = new ArrayList<>();
		for (String line : lines) {
			split.add(Draw.split(line, w - 2 * PAD));
		}
		paragraphs = split;
	}

	@Override
	public int width() {
		return w;
	}

	private int textHeight() {
		int h = 0;
		for (List<FormattedCharSequence> paragraph : paragraphs) {
			h += paragraph.size() * 10 + PARAGRAPH_GAP;
		}
		return h;
	}

	private int rowY() {
		return y + PAD + 14 + textHeight() + 2;
	}

	private int buttonsY() {
		return rowY() + ROW_H + 10 + (problem == null ? 0 : 12);
	}

	@Override
	public int height() {
		return buttonsY() - y + 16 + PAD;
	}

	private boolean in(double mouseX, double mouseY, int bx, int by, int bw, int bh) {
		return mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
	}

	private int switchX() {
		return x + w - PAD - BUTTON_W;
	}

	private int cancelX() {
		return switchX() - 6 - BUTTON_W;
	}

	@Override
	protected void drawContents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Draw.textFit(g, "Switch to Vanilla mode?", w - 2 * PAD, x + PAD, y + PAD, Theme.TEXT);
		int lineY = y + PAD + 14;
		for (List<FormattedCharSequence> paragraph : paragraphs) {
			for (FormattedCharSequence line : paragraph) {
				Draw.text(g, line, x + PAD, lineY, Theme.MUTED);
				lineY += 10;
			}
			lineY += PARAGRAPH_GAP;
		}
		// The choice, as a switch row.
		int rowY = rowY();
		boolean overRow = in(mouseX, mouseY, x + PAD - 4, rowY, w - 2 * PAD + 8, ROW_H);
		if (overRow) {
			Draw.roundRect(g, x + PAD - 4, rowY, w - 2 * PAD + 8, ROW_H, 4, Theme.CARD_HOVER);
		}
		Draw.textFit(g, "Keep performance mods", w - 2 * PAD - 30, x + PAD, rowY + 5, Theme.TEXT);
		int sx = x + w - PAD - 22;
		int sy = rowY + 3;
		Draw.bordered(g, sx, sy, 22, 12, 6, keepPerformance ? Theme.ACCENT_DARK : Theme.FIELD, keepPerformance ? Theme.ACCENT : Theme.BORDER);
		Draw.roundRect(g, sx + 2 + (keepPerformance ? 10 : 0), sy + 2, 8, 8, 4, keepPerformance ? Theme.ACCENT : Theme.MUTED);
		if (problem != null) {
			Draw.textFit(g, problem, w - 2 * PAD, x + PAD, rowY + ROW_H + 4, Theme.WARNING);
		}
		// Cancel, Switch.
		int by = buttonsY();
		boolean can = count > 0;
		Draw.bordered(g, cancelX(), by, BUTTON_W, 16, 4, in(mouseX, mouseY, cancelX(), by, BUTTON_W, 16) ? Theme.CARD_HOVER : Theme.CARD, Theme.BORDER);
		Draw.centered(g, "Cancel", cancelX() + BUTTON_W / 2, by + 4, Theme.TEXT);
		boolean overSwitch = can && in(mouseX, mouseY, switchX(), by, BUTTON_W, 16);
		Draw.roundRect(g, switchX(), by, BUTTON_W, 16, 4, !can ? Theme.CARD : overSwitch ? Theme.ACCENT_HOVER : Theme.ACCENT);
		Draw.centered(g, "Switch", switchX() + BUTTON_W / 2, by + 4, can ? Theme.ON_ACCENT : Theme.FAINT);
	}

	@Override
	public boolean click(double mouseX, double mouseY) {
		if (in(mouseX, mouseY, x + PAD - 4, rowY(), w - 2 * PAD + 8, ROW_H)) {
			keepPerformance = !keepPerformance;
			layout();
			return false;
		}
		int by = buttonsY();
		if (in(mouseX, mouseY, cancelX(), by, BUTTON_W, 16)) {
			open = null;
			return true;
		}
		if (count > 0 && in(mouseX, mouseY, switchX(), by, BUTTON_W, 16)) {
			try {
				VanillaMode.stageEnter(keepPerformance);
			} catch (java.io.IOException e) {
				JavaHost.LOGGER.warn("[Mods] couldn't stage Vanilla mode", e);
				problem = e.getMessage();
				return false;
			}
			open = null;
			done.run();
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- test hooks

	/** For tests: the open confirm, or null. */
	public static VanillaConfirm openForTests() {
		return open;
	}

	/** For tests: its text, every line in one string. */
	public String text() {
		StringBuilder text = new StringBuilder();
		for (List<FormattedCharSequence> paragraph : paragraphs) {
			for (FormattedCharSequence line : paragraph) {
				line.accept((index, style, codePoint) -> {
					text.appendCodePoint(codePoint);
					return true;
				});
				text.append(' ');
			}
		}
		return text.toString().replaceAll(" +", " ").strip();
	}

	public boolean keepPerformance() {
		return keepPerformance;
	}

	/** For tests: where the Switch button and the "Keep performance mods" row are, from the popover's top-left. */
	public int[] switchOffset() {
		return new int[] {switchX() - x + BUTTON_W / 2, buttonsY() - y + 8};
	}

	public int[] keepRowOffset() {
		return new int[] {w / 2, rowY() - y + ROW_H / 2};
	}

	public int[] cancelOffset() {
		return new int[] {cancelX() - x + BUTTON_W / 2, buttonsY() - y + 8};
	}

	/** Forgets the open one (closed by a click outside). */
	public static void forget() {
		open = null;
	}
}

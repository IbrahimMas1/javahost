package com.javahost.client.gui;

import java.util.List;

import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Popover;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.WebIcons;
import com.javahost.client.mods.Downloads;
import com.javahost.client.mods.Downloads.Job;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The list behind the menu's download icon: each download with its progress, and Close Minecraft once mods are ready. */
final class DownloadsPopover extends Popover {
	private static final int HEAD = 20;
	private static final int ROW = 32;
	private static final int FOOT = 28;
	private static final int MAX_ROWS = 6;
	private final int w;

	DownloadsPopover(int maxWidth) {
		this.w = Math.min(220, maxWidth);
	}

	private static List<Job> shown() {
		return Downloads.jobs().stream().limit(MAX_ROWS).toList();
	}

	@Override
	public int width() {
		return w;
	}

	@Override
	public int height() {
		return HEAD + Math.max(1, shown().size()) * ROW + (Downloads.restartNeeded() ? FOOT : 4);
	}

	private boolean hasFinished() {
		return Downloads.jobs().stream().anyMatch(j -> !j.active());
	}

	private int[] clearBounds() {
		int cw = Draw.font().width("Clear") + 8;
		return new int[] {x + w - 6 - cw, y + 4, cw, 12};
	}

	private int[] crossBounds(int row) {
		return new int[] {x + w - 20, y + HEAD + row * ROW + 9, 14, 14};
	}

	private int[] closeBounds() {
		return new int[] {x + 6, y + height() - FOOT + 4, w - 12, 18};
	}

	private static boolean inside(int[] b, double mx, double my) {
		return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
	}

	@Override
	protected void drawContents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Draw.text(g, "Downloads", x + 8, y + 6, Theme.TEXT);
		if (hasFinished()) {
			int[] c = clearBounds();
			Draw.text(g, "Clear", c[0] + 4, c[1] + 2, inside(c, mouseX, mouseY) ? Theme.TEXT : Theme.FAINT);
		}
		List<Job> jobs = shown();
		if (jobs.isEmpty()) {
			Draw.text(g, "Nothing downloading.", x + 8, y + HEAD + 10, Theme.FAINT);
		}
		for (int i = 0; i < jobs.size(); i++) {
			Job job = jobs.get(i);
			int rowY = y + HEAD + i * ROW;
			g.fill(x + 6, rowY, x + w - 6, rowY + 1, Theme.withAlpha(Theme.BORDER, 0.6f));
			WebIcons.draw(g, job.iconUrl, job.title, x + 8, rowY + 6, 20);
			int textX = x + 34;
			int textW = w - 34 - 24;
			Draw.textFit(g, job.title, textW, textX, rowY + 5, Theme.TEXT);
			int color = switch (job.stage()) {
				case DONE -> job.needsRestart() ? Theme.WARNING : Theme.ACCENT;
				case FAILED -> Theme.WARNING;
				case CANCELLED, QUEUED -> Theme.FAINT;
				default -> Theme.MUTED;
			};
			String status = switch (job.stage()) {
				case QUEUED -> "Waiting...";
				case CANCELLED -> "Cancelled";
				case DOWNLOADING -> Math.round(job.progress() * 100) + "% · " + job.statusText();
				default -> job.statusText();
			};
			Draw.textFit(g, status, textW, textX, rowY + 15, color);
			if (job.active()) {
				Draw.roundRect(g, textX, rowY + 26, textW, 2, 1, Theme.FIELD);
				int filled = (int) Math.round(textW * job.progress());
				if (filled > 0) {
					Draw.roundRect(g, textX, rowY + 26, Math.max(2, filled), 2, 1, Theme.ACCENT);
				}
			}
			int[] c = crossBounds(i);
			if (inside(c, mouseX, mouseY)) {
				Draw.roundRect(g, c[0], c[1], c[2], c[3], 3, Theme.CARD_HOVER);
			}
			Icons.CROSS.drawCentred(g, c[0] + c[2] / 2f, c[1] + c[3] / 2f, Icons.SIZE, inside(c, mouseX, mouseY) ? Theme.TEXT : Theme.FAINT);
		}
		if (Downloads.restartNeeded()) {
			int[] b = closeBounds();
			Draw.roundRect(g, b[0], b[1], b[2], b[3], 4, inside(b, mouseX, mouseY) ? Theme.ACCENT_HOVER : Theme.ACCENT);
			Draw.centered(g, "Close Minecraft to use them", b[0] + b[2] / 2, b[1] + 5, Theme.ON_ACCENT);
		}
	}

	/** The × on a row: stops a download, or takes a finished one off the list. */
	@Override
	public boolean click(double mouseX, double mouseY) {
		if (hasFinished() && inside(clearBounds(), mouseX, mouseY)) {
			Downloads.clearFinished();
			return Downloads.jobs().isEmpty();
		}
		List<Job> jobs = shown();
		for (int i = 0; i < jobs.size(); i++) {
			if (inside(crossBounds(i), mouseX, mouseY)) {
				Job job = jobs.get(i);
				if (job.active()) {
					Downloads.cancel(job);
				} else {
					Downloads.dismiss(job);
				}
				return Downloads.jobs().isEmpty();
			}
		}
		if (Downloads.restartNeeded() && inside(closeBounds(), mouseX, mouseY)) {
			Minecraft.getInstance().stop();
			return true;
		}
		return false;
	}
}

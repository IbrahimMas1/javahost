package com.javahost.client.gui.pages.world;

import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;

/**
 * One part of the World page ({@code HostPage}): it adds its widgets from a given height and draws its cards. The page
 * owns the scrolling and the widgets; a section only lays itself out and keeps its own state between rebuilds.
 */
public abstract class WorldSection {
	/** What a section needs from the page. */
	public interface Host {
		<T extends AbstractWidget> T add(T widget, int x, int y);

		void setShown(AbstractWidget widget, boolean shown);

		JavaHostScreen screen();

		int width();

		/** A change was asked of the server: the page re-reads the real values when they come back (or soon anyway). */
		void sent();
	}

	/** Gap between cards, and the usual padding inside one. */
	protected static final int GAP = 16;
	protected static final int PAD = 10;
	/** Height of a section label above a card. */
	protected static final int LABEL = 12;

	protected final Host host;
	protected final Minecraft minecraft = Minecraft.getInstance();
	protected JavaHostScreen screen;
	protected int width;

	protected WorldSection(Host host) {
		this.host = host;
	}

	/** Adds the section's widgets starting at {@code y} and returns where it ends. */
	public final int build(int y) {
		screen = host.screen();
		width = host.width();
		return layout(y);
	}

	protected abstract int layout(int y);

	/** {@code left}/{@code top} are the page's content origin, already scrolled. */
	public abstract void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY);

	/** What, if it changes, means the section should be rebuilt (checked every tick). */
	public String signature() {
		return "";
	}

	/** Called every tick while the section is shown (before the page checks whether to rebuild). */
	public void tick() {
	}

	/** Lets the section handle keys first (e.g. Enter in a text field). */
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		return false;
	}

	protected <T extends AbstractWidget> T add(T widget, int x, int y) {
		return host.add(widget, x, y);
	}

	// ---------------------------------------------------------------- drawing helpers

	protected static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Draw.glassCard(g, x, y, w, h, 6);
	}

	protected static void label(GuiGraphicsExtractor g, String text, int x, int y) {
		Draw.caps(g, text, x, y, Theme.FAINT);
	}

	/** A section label with a quiet note at the right end of its line. */
	protected static void label(GuiGraphicsExtractor g, String text, String note, int x, int y, int w) {
		Draw.caps(g, text, x, y, Theme.FAINT);
		String upper = text.toUpperCase(java.util.Locale.ROOT);
		int room = w - Draw.width(upper) - upper.length() - 16;
		String shown = Draw.fit(note, room);
		Draw.text(g, shown, x + w - Draw.width(shown), y, Theme.FAINT);
	}

	/** A hairline between rows inside a card. */
	protected static void divider(GuiGraphicsExtractor g, int x, int y, int w) {
		g.fill(x + 8, y, x + w - 8, y + 1, Theme.withAlpha(Theme.BORDER, 0.6f));
	}

	/** One quiet line with a small icon, e.g. why something can't be changed. */
	protected static void note(GuiGraphicsExtractor g, Icons icon, String text, int x, int y, int w, int color) {
		icon.draw(g, x, y - 1, color);
		Draw.textFit(g, text, w - 14, x + 13, y, color);
	}

	protected static Tooltip tip(String text) {
		return Tooltip.create(Draw.styled(text));
	}
}

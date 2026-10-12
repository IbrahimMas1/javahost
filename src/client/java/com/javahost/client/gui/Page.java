package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * One tab of {@link JavaHostScreen}. A page lays its widgets out in {@link #build()} using coordinates relative
 * to the top-left of its content area, and draws its panels and text in {@link #draw}. The screen handles
 * scrolling, so pages can be taller than the window.
 */
public abstract class Page {
	private record Placed(AbstractWidget widget, int relX, int relY) {
	}

	protected final Minecraft minecraft = Minecraft.getInstance();
	protected JavaHostScreen screen;
	/** Content width available to the page. */
	protected int width;
	private int contentHeight;
	private final List<Placed> placed = new ArrayList<>();

	public abstract String title();

	public abstract String subtitle();

	/** Adds widgets with {@link #add}. Called again whenever the screen rebuilds. */
	protected abstract void build();

	/** Draws everything that isn't a widget. {@code left}/{@code top} are the content origin, already scrolled. */
	protected abstract void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY);

	public void tick() {
	}

	/** Drawn above the page's widgets (e.g. a large preview). Same coordinates as {@link #draw}. */
	protected void drawOverlay(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
	}

	/**
	 * Back (the ← button or Esc) inside the page, e.g. a screenshot preview back to the grid. Returns false to let
	 * the menu go to the parent tab instead.
	 */
	protected boolean back() {
		return false;
	}

	/** Whether {@link #back()} has somewhere to go right now (shows the ← button on top-level tabs). */
	protected boolean canGoBack() {
		return false;
	}

	/** Lets a page handle keys first (e.g. Enter to send). */
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		return false;
	}

	protected static boolean isEnter(net.minecraft.client.input.KeyEvent event) {
		return event.isConfirmation(); // Enter or keypad Enter (26.3 uses its own key codes)
	}

	final void init(JavaHostScreen screen, int width) {
		this.screen = screen;
		this.width = width;
		this.contentHeight = 0;
		placed.clear();
		hidden.clear();
		build();
	}

	protected <T extends AbstractWidget> T add(T widget, int relX, int relY) {
		placed.add(new Placed(widget, relX, relY));
		int bottom = relY + (widget instanceof TextField ? TextField.HEIGHT : widget.getHeight());
		contentHeight = Math.max(contentHeight, bottom);
		screen.addPageWidget(widget);
		return widget;
	}

	/** Widgets the page has hidden on purpose (layout keeps them hidden even when scrolled into view). */
	private final java.util.Set<AbstractWidget> hidden = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	/** Shows or hides a widget; use this instead of setting {@code visible}, which scrolling overrides. */
	protected void setShown(AbstractWidget widget, boolean shown) {
		if (shown) {
			hidden.remove(widget);
		} else {
			hidden.add(widget);
			widget.visible = false;
		}
	}

	/** Reserves vertical space for something drawn by hand. */
	protected void reserve(int bottom) {
		contentHeight = Math.max(contentHeight, bottom);
	}

	int contentHeight() {
		return contentHeight;
	}

	/** Moves widgets to follow the scroll position and hides those outside the visible area. */
	final void layout(int left, int top, int viewTop, int viewBottom) {
		for (Placed p : placed) {
			int x = left + p.relX();
			int y = top + p.relY();
			if (p.widget() instanceof TextField field) {
				field.place(x, y);
			} else {
				p.widget().setPosition(x, y);
			}
			int height = p.widget() instanceof TextField ? TextField.HEIGHT : p.widget().getHeight();
			p.widget().visible = y + height > viewTop && y < viewBottom && !hidden.contains(p.widget());
		}
	}

	// ---------------------------------------------------------------- drawing helpers for pages

	protected static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Draw.panel(g, x, y, w, h, 6, Theme.CARD);
	}

	protected static void sectionLabel(GuiGraphicsExtractor g, String text, int x, int y) {
		Draw.text(g, text.toUpperCase(java.util.Locale.ROOT), x, y, Theme.FAINT);
	}

	/** A coloured dot followed by text. */
	protected static void status(GuiGraphicsExtractor g, int x, int y, int color, String text, int textColor) {
		Draw.roundRect(g, x, y + 1, 6, 6, 3, color);
		Draw.text(g, text, x + 10, y, textColor);
	}

	protected static int wrappedHeight(String text, int width) {
		return Draw.wrappedHeight(Component.literal(text), width);
	}

	protected static int wrap(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
		return Draw.wrapped(g, Component.literal(text), x, y, width, color);
	}
}

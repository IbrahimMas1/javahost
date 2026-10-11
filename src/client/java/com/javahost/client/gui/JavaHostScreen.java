package com.javahost.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.javahost.client.gui.pages.FriendsPage;
import com.javahost.client.gui.pages.HomePage;
import com.javahost.client.gui.pages.HostPage;
import com.javahost.client.gui.pages.JoinPage;
import com.javahost.client.gui.pages.PerformancePage;
import com.javahost.client.gui.pages.SettingsPage;
import com.javahost.client.gui.pages.VoicePage;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** The JavaHost menu: a dark window with a sidebar of tabs and the selected page on the right. */
public class JavaHostScreen extends Screen {
	public enum Tab {
		HOME("Home", Icons.HOME, HomePage::new),
		FRIENDS("Friends", Icons.USER, FriendsPage::new),
		HOST("Host", Icons.HOST, HostPage::new),
		JOIN("Join", Icons.JOIN, JoinPage::new),
		VOICE("Voice", Icons.MIC, VoicePage::new),
		PERFORMANCE("Performance", Icons.BOLT, PerformancePage::new),
		SETTINGS("Settings", Icons.GEAR, SettingsPage::new);

		final String label;
		final Icons icon;
		final Supplier<Page> factory;

		Tab(String label, Icons icon, Supplier<Page> factory) {
			this.label = label;
			this.icon = icon;
			this.factory = factory;
		}
	}

	private static Tab lastTab = Tab.HOME;
	private static final int HEADER = 34;

	private final Screen parent;
	private Tab tab;
	private Page page;
	private float scroll;
	private float scrollTarget;
	private final List<AbstractWidget> sidebarWidgets = new ArrayList<>();
	private final List<AbstractWidget> pageWidgets = new ArrayList<>();

	private int winX;
	private int winY;
	private int winW;
	private int winH;
	private int sideW;
	private int contentX;
	private int contentY;
	private int contentW;
	private int contentH;

	public JavaHostScreen(Screen parent) {
		this(parent, lastTab);
	}

	public JavaHostScreen(Screen parent, Tab tab) {
		super(Component.literal("JavaHost"));
		this.parent = parent;
		this.tab = tab;
	}

	public Tab tab() {
		return tab;
	}

	public void switchTo(Tab next) {
		if (tab == Tab.VOICE && next != Tab.VOICE) {
			com.javahost.client.voice.VoiceClient.setTestingMic(false);
		}
		tab = next;
		lastTab = next;
		page = null;
		scroll = scrollTarget = 0;
		rebuildWidgets();
	}

	/** Rebuilds the current page, e.g. after hosting starts. Keeps the scroll position. */
	public void refresh() {
		rebuildWidgets();
	}

	@Override
	protected void init() {
		sidebarWidgets.clear();
		pageWidgets.clear();

		winW = Math.min(width - 16, 470);
		winH = Math.min(height - 16, 300);
		winX = (width - winW) / 2;
		winY = (height - winH) / 2;
		boolean compact = winW < 380;
		sideW = compact ? 30 : 104;
		contentX = winX + sideW + 14;
		contentW = winX + winW - 14 - contentX;
		contentY = winY + HEADER + 8;
		contentH = winY + winH - 10 - contentY;

		int tabY = winY + 38;
		for (Tab t : Tab.values()) {
			if (t == Tab.JOIN) {
				continue; // reached from the Friends tab ("Join with a code"); invites make it rarely needed
			}
			SidebarTab button = new SidebarTab(winX + 6, tabY, sideW - 12, 20, t, compact);
			sidebarWidgets.add(addWidget(button));
			tabY += 22;
		}

		if (page == null) {
			page = tab.factory.get();
		}
		page.init(this, contentW);
		clampScroll();
		layoutPage();
	}

	void addPageWidget(AbstractWidget widget) {
		pageWidgets.add(addWidget(widget));
	}

	private int maxScroll() {
		return Math.max(0, page.contentHeight() - contentH + 4);
	}

	private void clampScroll() {
		scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
	}

	private void layoutPage() {
		page.layout(contentX, contentY - Math.round(scroll), contentY, contentY + contentH);
	}

	@Override
	public void tick() {
		if (page != null) {
			page.tick();
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x >= contentX && x < contentX + contentW && y >= contentY && y < contentY + contentH && maxScroll() > 0) {
			scrollTarget = (float) Math.max(0, Math.min(maxScroll(), scrollTarget - scrollY * 20));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		scroll = Draw.approach(scroll, scrollTarget, 0.4f);
		layoutPage();

		// Window and sidebar.
		g.fill(0, 0, width, height, Theme.SCRIM);
		Draw.shadow(g, winX, winY, winW, winH, 10);
		Draw.roundRect(g, winX, winY, winW, winH, 10, Theme.BORDER);
		Draw.roundRect(g, winX + 1, winY + 1, winW - 2, winH - 2, 9, Theme.WINDOW);
		Draw.roundRect(g, winX + 1, winY + 1, sideW, winH - 2, 9, Theme.SIDEBAR);
		g.fill(winX + sideW - 8, winY + 1, winX + sideW + 1, winY + winH - 1, Theme.SIDEBAR);
		g.fill(winX + sideW + 1, winY + 1, winX + sideW + 2, winY + winH - 1, Theme.BORDER);

		Icons.logo(g, winX + 9, winY + 9, 16);
		if (sideW > 40) {
			Draw.text(g, "JavaHost", winX + 31, winY + 13, Theme.TEXT);
		}

		// Page header.
		Draw.scaledText(g, page.title(), contentX, winY + 10, 1.25f, Theme.TEXT);
		Draw.text(g, Draw.fit(page.subtitle(), contentW), contentX, winY + 24, Theme.MUTED);
		g.fill(contentX, winY + HEADER + 2, contentX + contentW, winY + HEADER + 3, Theme.withAlpha(Theme.BORDER, 0.7f));

		g.nextStratum();
		for (AbstractWidget widget : sidebarWidgets) {
			widget.extractRenderState(g, mouseX, mouseY, a);
		}

		// Page contents, clipped to the content area so they can scroll.
		int top = contentY - Math.round(scroll);
		g.enableScissor(contentX - 4, contentY, contentX + contentW + 4, contentY + contentH);
		page.draw(g, contentX, top, mouseX, mouseY);
		g.nextStratum();
		boolean mouseInContent = mouseY >= contentY && mouseY < contentY + contentH;
		for (AbstractWidget widget : pageWidgets) {
			widget.extractRenderState(g, mouseInContent ? mouseX : -1, mouseInContent ? mouseY : -1, a);
		}
		g.disableScissor();

		if (maxScroll() > 0) {
			int barH = Math.max(16, contentH * contentH / (contentH + maxScroll()));
			int barY = contentY + Math.round((contentH - barH) * (scroll / maxScroll()));
			Draw.roundRect(g, winX + winW - 7, barY, 3, barH, 1, Theme.FAINT);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	/** Gives a page's text field keyboard focus. */
	public void focusOn(net.minecraft.client.gui.components.events.GuiEventListener widget) {
		setInitialFocus(widget);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		boolean enter = event.key() == 257 || event.key() == 335; // Enter / keypad Enter
		if (enter && page instanceof JoinPage join) {
			join.submit();
			return true;
		}
		if (enter && page instanceof FriendsPage friends) {
			friends.submitFromKeyboard();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void removed() {
		// The Voice tab keeps the mic open for its level meter; release it when the menu closes.
		com.javahost.client.voice.VoiceClient.setTestingMic(false);
		com.javahost.client.Config.get().save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** One entry in the sidebar. */
	private final class SidebarTab extends AbstractButton {
		private final Tab target;
		private final boolean compact;
		private float hover;

		SidebarTab(int x, int y, int w, int h, Tab target, boolean compact) {
			super(x, y, w, h, Component.literal(target.label));
			this.target = target;
			this.compact = compact;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			if (tab != target) {
				switchTo(target);
			}
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			boolean selected = tab == target || (tab == Tab.JOIN && target == Tab.FRIENDS);
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.35f);
			int fill = selected ? Theme.CARD_HOVER : Theme.withAlpha(Theme.CARD, hover);
			if (selected || hover > 0.01f) {
				Draw.roundRect(g, getX(), getY(), getWidth(), getHeight(), 5, fill);
			}
			if (selected) {
				Draw.roundRect(g, getX(), getY() + 5, 2, getHeight() - 10, 1, Theme.ACCENT);
			}
			int color = selected ? Theme.TEXT : Theme.mix(Theme.MUTED, Theme.TEXT, hover);
			int iconX = compact ? getX() + (getWidth() - Icons.SIZE) / 2 : getX() + 8;
			target.icon.draw(g, iconX, getY() + (getHeight() - Icons.SIZE) / 2, selected ? Theme.ACCENT : color);
			if (!compact) {
				Draw.text(g, target.label, getX() + 23, getY() + (getHeight() - 8) / 2 + 1, color);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}

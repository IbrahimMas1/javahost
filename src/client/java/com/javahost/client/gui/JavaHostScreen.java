package com.javahost.client.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.javahost.client.Config;
import com.javahost.client.friends.Chats;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Notifications;
import com.javahost.client.friends.Screenshots;
import com.javahost.client.gui.pages.ChatPage;
import com.javahost.client.gui.pages.FriendsPage;
import com.javahost.client.gui.pages.AccountPage;
import com.javahost.client.gui.pages.HomePage;
import com.javahost.client.friends.Identity;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.pages.HostPage;
import com.javahost.client.gui.pages.JoinPage;
import com.javahost.client.gui.pages.NotificationsPage;
import com.javahost.client.gui.pages.PerformancePage;
import com.javahost.client.gui.pages.ProfilePage;
import com.javahost.client.gui.pages.ScreenshotsPage;
import com.javahost.client.gui.pages.SettingsPage;
import com.javahost.client.gui.pages.VoicePage;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** The JavaHost menu: a dark window with a sidebar of tabs, a notification bell, and the selected page. */
public class JavaHostScreen extends BaseScreen {
	public enum Tab {
		HOME("Home", Icons.HOME, HomePage::new, null),
		FRIENDS("Friends", Icons.USERS, FriendsPage::new, null),
		HOST("Host", Icons.HOST, HostPage::new, null),
		DISCOVER("Discover", Icons.GLOBE, com.javahost.client.gui.pages.DiscoverPage::new, null),
		VOICE("Voice", Icons.MIC, VoicePage::new, null),
		SCREENSHOTS("Screenshots", Icons.CAMERA, ScreenshotsPage::new, null),
		PERFORMANCE("Performance", Icons.BOLT, PerformancePage::new, null),
		MODS("Mods", Icons.PACKAGE, com.javahost.client.gui.pages.ModsPage::new, null),
		SETTINGS("Settings", Icons.GEAR, SettingsPage::new, null),
		ACCOUNT("Account", Icons.USER, AccountPage::new, null),
		// Sub-pages, reached from other pages (they highlight their parent in the sidebar).
		JOIN("Join", Icons.JOIN, JoinPage::new, FRIENDS),
		RULES("World rules", Icons.SHIELD, com.javahost.client.gui.pages.WorldRulesPage::new, HOST),
		PROFILE("Profile", Icons.USER, ProfilePage::new, FRIENDS),
		CHAT("Chat", Icons.CHAT, ChatPage::new, FRIENDS),
		NOTIFICATIONS("Notifications", Icons.BELL, NotificationsPage::new, null);

		final String label;
		final Icons icon;
		final Supplier<Page> factory;
		final Tab parent;

		Tab(String label, Icons icon, Supplier<Page> factory, Tab parent) {
			this.label = label;
			this.icon = icon;
			this.factory = factory;
			this.parent = parent;
		}

		boolean inSidebar() {
			return parent == null && this != NOTIFICATIONS;
		}
	}

	private static Tab lastTab = Tab.HOME;
	private static final int HEADER = 34;

	private final Screen parent;
	private Tab tab;
	private Tab beforeNotifications = Tab.HOME;
	/** Opened straight onto this tab (e.g. from the dock): Esc there closes the menu. */
	private final Tab openedOn;
	private Page page;
	private UUID subject;
	private Path screenshot;
	private float scroll;
	private float scrollTarget;
	private boolean scrollToBottom;
	private float openAnim;
	/** Where the sidebar's selected-tab highlight is drawn; it slides to the new tab on a switch. */
	private float indicatorY = -1;
	private float indicatorH = 20;
	private float tabAnim = 1;
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
		this(parent, lastTab.inSidebar() ? lastTab : Tab.HOME);
	}

	public JavaHostScreen(Screen parent, Tab tab) {
		super(Component.literal("JavaHost"));
		this.parent = parent;
		this.tab = tab;
		this.openedOn = tab;
		Theme.apply(Config.get().accent);
	}

	/** Height of the visible page area (pages use it to keep key buttons on screen). */
	public int contentHeight() {
		return contentH;
	}

	public Tab tab() {
		return tab;
	}

	/** The friend a profile/chat page is about. */
	public UUID subject() {
		return subject;
	}

	/** The screenshot the Screenshots page should open on (e.g. one just taken). */
	public Path selectedScreenshot() {
		return screenshot;
	}

	public void selectScreenshot(Path file) {
		screenshot = file;
	}

	public void switchTo(Tab next) {
		if (tab == Tab.VOICE && next != Tab.VOICE) {
			com.javahost.client.voice.VoiceClient.setTestingMic(false);
		}
		if (tab == Tab.SCREENSHOTS && next != Tab.SCREENSHOTS) {
			Screenshots.releaseThumbnails();
		}
		if (next == Tab.NOTIFICATIONS && tab != Tab.NOTIFICATIONS) {
			beforeNotifications = tab;
		}
		tab = next;
		if (next.inSidebar()) {
			lastTab = next;
		}
		if (page != null) {
			page.removed();
		}
		page = null;
		scroll = scrollTarget = 0;
		tabAnim = 0;
		rebuildWidgets();
	}

	public void openProfile(UUID friend) {
		subject = friend;
		switchTo(Tab.PROFILE);
	}

	public void openChat(UUID friend) {
		subject = friend;
		scrollToBottom = true;
		switchTo(Tab.CHAT);
	}

	public void openScreenshot(Path file) {
		screenshot = file;
		switchTo(Tab.SCREENSHOTS);
	}

	public boolean isChattingWith(UUID friend) {
		return tab == Tab.CHAT && friend.equals(subject);
	}

	/** Rebuilds the current page, e.g. after hosting starts. Keeps the scroll position. */
	public void refresh() {
		rebuildWidgets();
	}

	/** Scrolls to the end on the next layout (chat: newest message). */
	public void scrollToBottom() {
		scrollToBottom = true;
	}

	@Override
	protected void init() {
		Page.clearTextCache();
		sidebarWidgets.clear();
		pageWidgets.clear();
		if (tour == null && !Config.get().tourDone && !tourOffered) {
			tourOffered = true; // first time ever: start the welcome tour
			tour = new WelcomeTour(this);
			tab = tour.tab();
			page = null;
		}

		// At most 480x310, and never more than ~96% of the screen wide, so big GUI scales keep a margin all round
		// (wide enough on a laptop at GUI scale 3-4 for the sidebar to show names, not just icons).
		winW = Math.min(480, Math.min(width - 16, Math.round(width * 0.96f)));
		winH = Math.min(310, Math.min(height - 16, Math.round(height * 0.86f)));
		winX = (width - winW) / 2;
		winY = (height - winH) / 2;
		int pad = winW < 440 ? 10 : 14;
		// Names in the sidebar when pages still get at least 244 wide; otherwise just icons (names on hover).
		boolean compact = winW - 108 - pad * 2 < 244;
		sideW = compact ? 30 : 108;
		contentX = winX + sideW + pad;
		contentW = winX + winW - pad - contentX;
		contentY = winY + HEADER + 10; // a little air between the header line and the first section
		contentH = winY + winH - 10 - contentY;

		// Tabs share the height between the logo and the account row, so the sidebar never overflows the window.
		int tabCount = (int) java.util.Arrays.stream(Tab.values()).filter(t -> t.inSidebar() && t != Tab.ACCOUNT).count();
		int accountY = winY + winH - 28;
		int tabTop = winY + 38;
		int step = Math.max(14, Math.min(22, (accountY - 4 - tabTop) / tabCount));
		int tabY = tabTop;
		for (Tab t : Tab.values()) {
			if (!t.inSidebar() || t == Tab.ACCOUNT) {
				continue;
			}
			SidebarTab button = new SidebarTab(winX + 6, tabY, sideW - 12, step - 2, t, compact);
			sidebarWidgets.add(addWidget(button));
			tabY += step;
		}
		// You, pinned to the bottom of the sidebar.
		sidebarWidgets.add(addWidget(new SidebarTab(winX + 6, accountY, sideW - 12, 22, Tab.ACCOUNT, compact)));

		if (page == null) {
			page = tab.factory.get();
		}
		page.init(this, contentW);

		// Top right of the window: notifications, then close. Top left of the page: back, when there's a way back.
		FlatButton bell = new FlatButton(winX + winW - 58, winY + 8, 22, 18, Component.literal("Notifications"),
				tab == Tab.NOTIFICATIONS ? FlatButton.Style.SECONDARY : FlatButton.Style.GHOST, Icons.BELL,
				b -> switchTo(tab == Tab.NOTIFICATIONS ? beforeNotifications : Tab.NOTIFICATIONS)).iconOnly();
		sidebarWidgets.add(addWidget(bell));
		FlatButton close = new FlatButton(winX + winW - 32, winY + 8, 22, 18, Component.literal("Close"),
				FlatButton.Style.GHOST, Icons.CROSS, b -> close()).iconOnly();
		close.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Close")));
		sidebarWidgets.add(addWidget(close));
		if (canGoBack()) {
			FlatButton back = new FlatButton(contentX - 4, winY + 9, 18, 18, Component.literal("Back"),
					FlatButton.Style.GHOST, Icons.BACK, b -> goBack()).iconOnly();
			back.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Back")));
			sidebarWidgets.add(addWidget(back));
		}
		if (scrollToBottom) {
			scrollToBottom = false;
			scrollTarget = scroll = maxScroll();
		}
		clampScroll();
		layoutPage();
		if (tour != null) {
			tour.init(winX, winY, winW, winH, contentX, contentW);
			tour.buttons().forEach(this::addWidget); // so keyboard and tests find them; drawn on top below
		}
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
		if (tab == Tab.NOTIFICATIONS) {
			Notifications.markRead();
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
		background(g, mouseX, mouseY, a);
		scroll = Draw.approach(scroll, scrollTarget, 0.4f);
		openAnim = Draw.approach(openAnim, 1, 0.3f);
		tabAnim = Draw.approach(tabAnim, 1, 0.35f);
		layoutPage();

		g.fill(0, 0, width, height, Theme.withAlpha(Theme.SCRIM_GLASS, openAnim));

		// Open animation: the window grows slightly into place.
		float scale = 0.94f + 0.06f * openAnim;
		Draw.push(g);
		Draw.translate(g, width / 2f, height / 2f);
		Draw.scale(g, scale, scale);
		Draw.translate(g, -width / 2f, -height / 2f);

		Draw.glass(g, winX, winY, winW, winH, 10);
		Draw.roundRect(g, winX + 1, winY + 1, sideW, winH - 2, 9, Theme.GLASS_SIDEBAR);
		g.fill(winX + sideW - 8, winY + 1, winX + sideW + 1, winY + winH - 1, Theme.GLASS_SIDEBAR);
		g.fill(winX + sideW + 1, winY + 1, winX + sideW + 2, winY + winH - 1, Theme.GLASS_EDGE);

		Icons.logo(g, winX + 9, winY + 9, 16);
		if (sideW > 40) {
			Draw.text(g, "JavaHost", winX + 31, winY + 13, Theme.TEXT);
		}

		// Page header (shifted right when there's a back button).
		int titleX = canGoBack() ? contentX + 18 : contentX;
		int titleW = contentX + contentW - 62 - titleX;
		String title = Draw.fit(page.title(), (int) (titleW / 1.25f));
		Draw.scaledText(g, title, titleX, winY + 10, 1.25f, Theme.TEXT);
		if (!title.equals(page.title())) {
			Draw.truncated(g, titleX, winY + 9, (int) (Draw.font().width(title) * 1.25f), 12, page.title());
		}
		Draw.textFit(g, page.subtitle(), titleW, titleX, winY + 24, Theme.MUTED);
		g.fill(contentX, winY + HEADER + 2, contentX + contentW, winY + HEADER + 3, Theme.withAlpha(Theme.BORDER, 0.7f));

		g.nextStratum();
		drawTabIndicator(g);
		for (AbstractWidget widget : sidebarWidgets) {
			widget.extractRenderState(g, mouseX, mouseY, a);
		}
		int unread = Notifications.unread();
		if (unread > 0 && tab != Tab.NOTIFICATIONS) {
			badge(g, winX + winW - 40, winY + 6, unread);
		}

		// Page contents, clipped to the content area so they can scroll; they slide in when the tab changes.
		int slide = Math.round((1 - tabAnim) * 12);
		int top = contentY - Math.round(scroll);
		g.enableScissor(contentX - 4, contentY, contentX + contentW + 4, contentY + contentH);
		Draw.clip(contentX - 4, contentY, contentX + contentW + 4, contentY + contentH);
		Draw.push(g);
		Draw.translate(g, slide, 0);
		page.draw(g, contentX, top, mouseX - slide, mouseY);
		g.nextStratum();
		boolean mouseInContent = mouseY >= contentY && mouseY < contentY + contentH;
		for (AbstractWidget widget : pageWidgets) {
			widget.extractRenderState(g, mouseInContent ? mouseX : -1, mouseInContent ? mouseY : -1, a);
		}
		g.nextStratum();
		page.drawOverlay(g, contentX, top, mouseX, mouseY);
		Draw.pop(g);
		if (tabAnim < 0.99f) {
			// The new page fades in as it slides.
			g.nextStratum();
			g.fill(contentX - 4, contentY, contentX + contentW + 4, contentY + contentH, Theme.withAlpha(Theme.WINDOW, (1 - tabAnim) * 0.9f));
		}
		g.disableScissor();
		Draw.unclip();

		if (maxScroll() > 0) {
			int barH = Math.max(16, contentH * contentH / (contentH + maxScroll()));
			int barY = contentY + Math.round((contentH - barH) * (scroll / maxScroll()));
			Draw.roundRect(g, winX + winW - 7, barY, 3, barH, 1, Theme.FAINT);
		}
		if (tour != null) {
			g.nextStratum();
			int[] highlight = tour.tab() == Tab.HOME && tour.buttons().size() == 1 ? null : sidebarBounds(tour.tab());
			tour.draw(g, winX, winY, winW, winH, highlight);
			if (highlight != null) {
				// Redraw the highlighted tab on top of the dimming.
				for (AbstractWidget widget : sidebarWidgets) {
					if (widget instanceof SidebarTab t && t.target == tour.tab()) {
						t.extractRenderState(g, -1, -1, a);
					}
				}
			}
			for (AbstractWidget button : tour.buttons()) {
				button.extractRenderState(g, mouseX, mouseY, a);
			}
		}
		Draw.pop(g);
	}

	/** A small red count bubble. */
	static void badge(GuiGraphicsExtractor g, int x, int y, int count) {
		String text = count > 9 ? "9+" : String.valueOf(count);
		int w = Math.max(9, Draw.font().width(text) + 5);
		Draw.roundRect(g, x - w / 2, y, w, 9, 4, Theme.DANGER);
		Draw.centered(g, text, x, y + 1, 0xFFFFFFFF);
	}

	@Override
	//? if >=1.21.9 {
	public boolean keyPressed(KeyEvent event) {
		if (page != null && page.keyPressed(event)) {
			return true;
		}
		return super.keyPressed(event);
	}
	//?} else {
	/*public boolean keyPressed(int key, int scancode, int modifiers) {
		if (page != null && page.keyPressed(new KeyEvent(key, scancode, modifiers))) {
			return true;
		}
		return super.keyPressed(key, scancode, modifiers);
	}
	*///?}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	/** Gives a page's text field keyboard focus. */
	public void focusOn(net.minecraft.client.gui.components.events.GuiEventListener widget) {
		setInitialFocus(widget);
	}

	@Override
	public void removed() {
		// The Voice tab keeps the mic open for its level meter; release it when the menu closes.
		com.javahost.client.voice.VoiceClient.setTestingMic(false);
		Screenshots.releaseThumbnails();
		if (page != null) {
			page.removed();
		}
		Config.get().save();
	}

	@Override
	public void onClose() {
		goBack(); // Esc does the same as the back button, and closes when there's nowhere to go back to
	}

	/** Whether ← has somewhere to go: inside the page, to a parent tab, or back from notifications. */
	public boolean canGoBack() {
		return (page != null && page.canGoBack()) || tab.parent != null
				|| (tab == Tab.NOTIFICATIONS && openedOn != Tab.NOTIFICATIONS);
	}

	public void goBack() {
		if (page != null && page.back()) {
			return;
		}
		if (tab.parent != null) {
			switchTo(tab.parent);
			return;
		}
		if (tab == Tab.NOTIFICATIONS && openedOn != Tab.NOTIFICATIONS) {
			switchTo(beforeNotifications);
			return;
		}
		close();
	}

	private WelcomeTour tour;
	private static boolean tourOffered;

	/** Starts the welcome tour (Settings, or automatically the first time). */
	public void startTour() {
		tour = new WelcomeTour(this);
		switchTo(tour.tab());
	}

	void showTourStep() {
		switchTo(tour.tab());
	}

	void endTour() {
		tour = null;
		rebuildWidgets();
	}

	/** The sidebar row for a tab (x, y, w, h), to highlight it during the tour. */
	private int[] sidebarBounds(Tab target) {
		for (AbstractWidget widget : sidebarWidgets) {
			if (widget instanceof SidebarTab t && t.target == target) {
				return new int[] {t.getX(), t.getY(), t.getWidth(), t.getHeight()};
			}
		}
		return null;
	}

	@Override
	//? if >=1.21.9 {
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (tour != null) {
			// During the tour only its own buttons respond.
			for (AbstractWidget button : tour.buttons()) {
				if (button.isMouseOver(event.x(), event.y())) {
					return button.mouseClicked(event, doubleClick);
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
	//?} else {
	/*public boolean mouseClicked(double x, double y, int mouseButton) {
		if (tour != null) {
			// During the tour only its own buttons respond.
			for (AbstractWidget button : tour.buttons()) {
				if (button.isMouseOver(x, y)) {
					return button.mouseClicked(x, y, mouseButton);
				}
			}
			return true;
		}
		return super.mouseClicked(x, y, mouseButton);
	}
	*///?}

	/** Leaves the menu, back to the screen it was opened from. */
	public void close() {
		if (tab == Tab.VOICE) {
			com.javahost.client.voice.VoiceClient.setTestingMic(false);
		}
		minecraft.gui.setScreen(parent);
	}

	/** The selected-tab highlight, sliding smoothly between tabs. */
	private void drawTabIndicator(GuiGraphicsExtractor g) {
		SidebarTab selected = null;
		for (AbstractWidget widget : sidebarWidgets) {
			if (widget instanceof SidebarTab t && (t.target == tab || t.target == tab.parent)) {
				selected = t;
			}
		}
		if (selected == null) {
			return; // e.g. notifications, which aren't in the sidebar
		}
		indicatorY = indicatorY < 0 ? selected.getY() : Draw.approach(indicatorY, selected.getY(), 0.3f);
		indicatorH = Draw.approach(indicatorH, selected.getHeight(), 0.3f);
		int y = Math.round(indicatorY);
		int h = Math.round(indicatorH);
		Draw.roundRect(g, selected.getX(), y, selected.getWidth(), h, 5, 0x24FFFFFF);
		Draw.roundRect(g, selected.getX(), y + 5, 2, h - 10, 1, Theme.ACCENT);
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
			if (compact) {
				// Only icons fit: the name shows on hover.
				setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(target == Tab.ACCOUNT ? "Your account" : target.label)));
			}
		}

		@Override
		//? if >=1.21.9 {
		public void onPress(InputWithModifiers input) {
		//?} else
		/*public void onPress() {*/
			if (tab != target) {
				switchTo(target);
			}
		}

		@Override
		//? if >=26.1 {
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		//?} else if >=1.21.11 {
		/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?} else {
		/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?}
			boolean selected = tab == target || tab.parent == target;
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.35f);
			// The selected highlight is drawn by the screen (it slides between tabs); here just the hover.
			if (!selected && hover > 0.01f) {
				Draw.roundRect(g, getX(), getY(), getWidth(), getHeight(), 5, Theme.withAlpha(0x14FFFFFF, hover));
			}
			int color = selected ? Theme.TEXT : Theme.mix(Theme.MUTED, Theme.TEXT, hover);
			int iconX = compact ? getX() + (getWidth() - Icons.SIZE) / 2 : getX() + 8;
			if (target == Tab.ACCOUNT) {
				// Your face and name instead of an icon.
				int face = 12;
				int faceX = compact ? getX() + (getWidth() - face) / 2 : getX() + 6;
				Faces.draw(g, Identity.id(), faceX, getY() + (getHeight() - face) / 2, face);
				if (!compact) {
					Draw.textFit(g, Identity.name(), getWidth() - 26, getX() + 23, getY() + (getHeight() - 8) / 2 + 1, color);
				}
				return;
			}
			float iconCentreX = compact ? getX() + getWidth() / 2f : iconX + Icons.SIZE / 2f;
			target.icon.drawCentred(g, iconCentreX, getY() + getHeight() / 2f, Icons.SIZE, selected ? Theme.ACCENT : color);
			if (!compact) {
				Draw.text(g, target.label, getX() + 23, getY() + (getHeight() - 8) / 2 + 1, color);
			}
			if (target == Tab.FRIENDS) {
				int count = Friends.incoming().size() + Chats.unreadTotal();
				if (count > 0) {
					badge(g, compact ? getX() + getWidth() - 4 : getX() + getWidth() - 10, getY() + 5, count);
				}
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}

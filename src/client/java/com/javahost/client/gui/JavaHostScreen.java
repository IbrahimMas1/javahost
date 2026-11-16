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
import com.javahost.client.gui.ui.Popover;
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
		HOST("World", Icons.HOST, HostPage::new, null),
		DISCOVER("Public worlds", Icons.GLOBE, com.javahost.client.gui.pages.DiscoverPage::new, null),
		VOICE("Voice", Icons.MIC, VoicePage::new, null),
		SCREENSHOTS("Screenshots", Icons.CAMERA, ScreenshotsPage::new, null),
		PERFORMANCE("Performance", Icons.BOLT, PerformancePage::new, null),
		MODS("Mods", Icons.PACKAGE, com.javahost.client.gui.pages.ModsPage::new, null),
		SETTINGS("Settings", Icons.GEAR, SettingsPage::new, null),
		ACCOUNT("Account", Icons.USER, AccountPage::new, null),
		SKINS("Skins", Icons.SHIRT, com.javahost.client.gui.pages.SkinsPage::new, null),
		// Sub-pages, reached from other pages (they highlight their parent in the sidebar).
		JOIN("Join", Icons.JOIN, JoinPage::new, FRIENDS),
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
	private static final int HEADER = 36;
	/** The sidebar's places; More, Settings and the account sit at the bottom. */
	private static final List<Tab> PLACES = List.of(Tab.HOME, Tab.FRIENDS, Tab.HOST, Tab.DISCOVER, Tab.MODS);
	/** The pages behind the sidebar's More (also linked from related pages); More is highlighted while one is open. */
	public static final List<Tab> MORE = List.of(Tab.SKINS, Tab.SCREENSHOTS, Tab.VOICE, Tab.PERFORMANCE);
	/** Pages never get wider than this: on big screens they sit centred beside the sidebar. */
	private static final int MAX_CONTENT = 440;

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
	private boolean compact;

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
		if (!(popover instanceof DownloadsPopover)) {
			popover = null;
		}
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

		// The hub fills the screen: the sidebar down the left, the page centred in the rest.
		winX = 0;
		winY = 0;
		winW = width;
		winH = height;
		// Names in the sidebar unless the window is very narrow; then just icons (names on hover).
		compact = width < 350;
		sideW = compact ? 32 : Math.max(104, Math.min(132, width / 4));
		int areaW = width - sideW;
		int pad = areaW < 340 ? 12 : 18;
		contentW = Math.min(areaW - pad * 2, MAX_CONTENT);
		contentX = sideW + (areaW - contentW) / 2;
		contentY = HEADER + 12; // a little air between the header line and the first section
		contentH = height - 8 - contentY;

		// Sidebar: the four places under the logo; More, Settings and you pinned to the bottom.
		int accountH = compact ? 22 : 26;
		int accountY = height - accountH - 6;
		int tabTop = 40;
		int rows = PLACES.size() + 2;
		int step = Math.max(13, Math.min(22, (accountY - 5 - tabTop - 8) / rows));
		int tabY = tabTop;
		for (Tab t : PLACES) {
			sidebarWidgets.add(addWidget(new SidebarTab(6, tabY, sideW - 12, step - 2, t, compact)));
			tabY += step;
		}
		sidebarWidgets.add(addWidget(new SidebarTab(6, accountY - 2 * step - 1, sideW - 12, step - 2, null, compact)));
		sidebarWidgets.add(addWidget(new SidebarTab(6, accountY - step - 1, sideW - 12, step - 2, Tab.SETTINGS, compact)));
		sidebarWidgets.add(addWidget(new SidebarTab(6, accountY, sideW - 12, accountH, Tab.ACCOUNT, compact)));

		if (page == null) {
			page = tab.factory.get();
		}
		page.init(this, contentW);

		// Top right of the window: notifications, then close. Top left of the page: back, when there's a way back.
		FlatButton bell = new FlatButton(width - 56, 9, 22, 18, Component.literal("Notifications"),
				tab == Tab.NOTIFICATIONS ? FlatButton.Style.SECONDARY : FlatButton.Style.GHOST, Icons.BELL,
				b -> switchTo(tab == Tab.NOTIFICATIONS ? beforeNotifications : Tab.NOTIFICATIONS)).iconOnly();
		sidebarWidgets.add(addWidget(bell));
		// Downloads: only while there are some; a ring on it shows how far they've got.
		downloadsButton = null;
		if (!com.javahost.client.mods.Downloads.jobs().isEmpty()) {
			downloadsButton = new FlatButton(width - 82, 9, 22, 18, Component.literal("Downloads"),
					popover instanceof DownloadsPopover ? FlatButton.Style.SECONDARY : FlatButton.Style.GHOST, Icons.DOWNLOAD,
					b -> toggleDownloads()).iconOnly();
			downloadsButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled("Downloads")));
			sidebarWidgets.add(addWidget(downloadsButton));
		}
		hadDownloads = downloadsButton != null;
		FlatButton close = new FlatButton(width - 30, 9, 22, 18, Component.literal("Close"),
				FlatButton.Style.GHOST, Icons.CROSS, b -> close()).iconOnly();
		close.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled("Close")));
		sidebarWidgets.add(addWidget(close));
		if (canGoBack()) {
			FlatButton back = new FlatButton(contentX - 4, 10, 18, 18, Component.literal("Back"),
					FlatButton.Style.GHOST, Icons.BACK, b -> goBack()).iconOnly();
			back.setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled("Back")));
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
		boolean hasDownloads = !com.javahost.client.mods.Downloads.jobs().isEmpty();
		if (hasDownloads != hadDownloads) {
			if (!hasDownloads && popover instanceof DownloadsPopover) {
				popover = null;
			}
			rebuildWidgets(); // show or hide the download icon
		}
		if (tab == Tab.NOTIFICATIONS) {
			Notifications.markRead();
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (popover != null && !(popover instanceof DownloadsPopover)) {
			popover = null; // it points at something on the page, which is about to move
		}
		if (x >= contentX && x < contentX + contentW && y >= contentY && y < contentY + contentH && maxScroll() > 0) {
			scrollTarget = (float) Math.max(0, Math.min(maxScroll(), scrollTarget - scrollY * 20));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int realMouseX, int realMouseY, float a) {
		// While a popover is open, only it reacts to the mouse (no hover, no tooltips drawn over it).
		int mouseX = popover != null ? -1 : realMouseX;
		int mouseY = popover != null ? -1 : realMouseY;
		background(g, mouseX, mouseY, a);
		scroll = Draw.approach(scroll, scrollTarget, 0.4f);
		openAnim = Draw.approach(openAnim, 1, 0.3f);
		tabAnim = Draw.approach(tabAnim, 1, 0.35f);
		layoutPage();

		// The hub: the blurred game behind a dark tint, a darker sidebar, and a soft glow of the accent colour on top.
		drawBackdrop(g);

		// Open animation: everything settles into place.
		float scale = 0.975f + 0.025f * openAnim;
		Draw.push(g);
		Draw.translate(g, width / 2f, height / 2f);
		Draw.scale(g, scale, scale);
		Draw.translate(g, -width / 2f, -height / 2f);

		g.fill(0, 0, sideW, height, Theme.HUB_SIDEBAR);
		g.fillGradient(sideW, 0, sideW + 1, height, Theme.withAlpha(Theme.ACCENT, 0.45f), 0x16FFFFFF);

		int logoX = compact ? (sideW - 16) / 2 : 10;
		Draw.glow(g, logoX + 8, 17, 24, 22, Theme.withAlpha(Theme.ACCENT, 0.30f));
		Icons.logo(g, logoX, 9, 16);
		if (!compact) {
			Draw.text(g, "JavaHost", 32, 13, Theme.TEXT);
		}

		// Page header (shifted right when there's a back button).
		int titleX = canGoBack() ? contentX + 18 : contentX;
		int titleW = Math.min(contentX + contentW, width - (downloadsButton != null ? 90 : 64)) - titleX;
		String title = Draw.fitScaled(page.title(), titleW, 1.25f);
		Draw.scaledText(g, title, titleX, 11, 1.25f, Theme.TEXT);
		if (!title.equals(page.title())) {
			Draw.truncated(g, titleX, 10, (int) (Draw.width(title) * 1.25f), 12, page.title());
		}
		Draw.textFit(g, page.subtitle(), titleW, titleX, 25, Theme.MUTED);
		g.fill(contentX, HEADER + 2, contentX + contentW, HEADER + 3, 0x16FFFFFF);
		g.fill(contentX, HEADER + 2, contentX + 28, HEADER + 3, Theme.withAlpha(Theme.ACCENT, 0.8f)); // a short accent rule under the title

		g.nextStratum();
		drawTabIndicator(g);
		for (AbstractWidget widget : sidebarWidgets) {
			widget.extractRenderState(g, mouseX, mouseY, a);
		}
		int unread = Notifications.unread();
		if (unread > 0 && tab != Tab.NOTIFICATIONS) {
			badge(g, width - 38, 7, unread);
		}
		if (downloadsButton != null) {
			float cx = downloadsButton.getX() + downloadsButton.getWidth() / 2f;
			float cy = downloadsButton.getY() + downloadsButton.getHeight() / 2f;
			if (com.javahost.client.mods.Downloads.active()) {
				Draw.ring(g, cx, cy, 8.5f, 1.5f, com.javahost.client.mods.Downloads.overall(), Theme.ACCENT, Theme.withAlpha(Theme.BORDER, 0.9f));
			} else if (com.javahost.client.mods.Downloads.unseen() > 0) {
				Draw.roundRect(g, Math.round(cx) + 5, downloadsButton.getY() + 1, 5, 5, 2, Theme.ACCENT); // done, not looked at yet
			}
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
			g.fill(contentX - 4, contentY, contentX + contentW + 4, contentY + contentH, Theme.withAlpha(Theme.HUB_SOLID, (1 - tabAnim) * 0.9f));
		}
		g.disableScissor();
		Draw.unclip();

		if (maxScroll() > 0) {
			int barH = Math.max(16, contentH * contentH / (contentH + maxScroll()));
			int barY = contentY + Math.round((contentH - barH) * (scroll / maxScroll()));
			Draw.roundRect(g, Math.min(width - 6, contentX + contentW + 6), barY, 3, barH, 1, Theme.FAINT);
		}
		if (tour != null) {
			g.nextStratum();
			int[] highlight = tour.tab() == Tab.HOME && tour.buttons().size() == 1 ? null : sidebarBounds(tour.tab());
			tour.draw(g, winX, winY, winW, winH, highlight);
			if (highlight != null) {
				// Redraw the highlighted tab on top of the dimming.
				for (AbstractWidget widget : sidebarWidgets) {
					if (widget instanceof SidebarTab t && (t.target == tour.tab() || (t.target == null && MORE.contains(tour.tab())))) {
						t.extractRenderState(g, -1, -1, a);
					}
				}
			}
			for (AbstractWidget button : tour.buttons()) {
				button.extractRenderState(g, mouseX, mouseY, a);
			}
		}
		if (popover != null) {
			g.nextStratum();
			placePopover();
			popover.draw(g, realMouseX, realMouseY);
		}
		Draw.pop(g);
	}

	// ---------------------------------------------------------------- popovers

	private Popover popover;
	/** What the popover hangs from (x, y, w, h): it opens below, right-aligned, or above when there's no room. */
	private int[] popoverAnchor;
	/** The popover hangs beside the sidebar (the More menu) rather than under its anchor. */
	private boolean popoverBeside;
	/** The popover's left edge lines up with its anchor's (it opens to the right) instead of its right edge. */
	private boolean popoverFromLeft;
	private FlatButton downloadsButton;
	private boolean hadDownloads;

	/** Opens a popover under {@code anchor} (replacing any open one). */
	public void openPopover(Popover next, AbstractWidget anchor) {
		openPopover(next, anchor, false);
	}

	/** As {@link #openPopover(Popover, AbstractWidget)}; with {@code fromLeft} it opens rightwards from the anchor's left edge. */
	public void openPopover(Popover next, AbstractWidget anchor, boolean fromLeft) {
		popover = next;
		popoverBeside = false;
		popoverFromLeft = fromLeft;
		popoverAnchor = new int[] {anchor.getX(), anchor.getY(), anchor.getWidth(), anchor.getHeight()};
		placePopover();
	}

	/** Opens the More menu beside its sidebar entry (or closes it when it's already open). */
	public void toggleMore() {
		if (popover != null && popoverBeside) {
			popover = null;
			return;
		}
		for (AbstractWidget widget : sidebarWidgets) {
			if (widget instanceof SidebarTab t && t.target == null) {
				List<Popover.Item> items = new ArrayList<>();
				for (Tab t2 : MORE) {
					items.add(Popover.Item.of(t2.label, t2.icon, () -> switchTo(t2)).selected(tab == t2 || tab.parent == t2));
				}
				popover = Popover.menu(items);
				popoverBeside = true;
				popoverFromLeft = false;
				// Flush with the sidebar's edge, its bottom level with the More row.
				popoverAnchor = new int[] {t.getX(), t.getY(), t.getWidth(), t.getHeight()};
				placePopover();
				return;
			}
		}
	}

	/** For tests: whether the open popover is the sidebar's More menu. */
	public boolean moreOpen() {
		return popover != null && popoverBeside;
	}

	/** For tests: the sidebar's entries, top to bottom (the account card last). */
	public List<String> sidebarLabels() {
		return sidebarWidgets.stream().filter(w -> w instanceof SidebarTab).map(w -> w.getMessage().getString()).toList();
	}

	/** For tests: the sidebar entry that's highlighted now. */
	public String highlightedSidebarEntry() {
		return sidebarWidgets.stream().filter(w -> w instanceof SidebarTab t && t.selected()).map(w -> w.getMessage().getString())
				.findFirst().orElse(null);
	}

	public void closePopover() {
		popover = null;
	}

	/** For tests: where the open popover is (x, y, w, h), or null. */
	public int[] popoverBounds() {
		return popover == null ? null : new int[] {popover.x(), popover.y(), popover.width(), popover.height()};
	}

	/** For tests: whether a popover is open. */
	public boolean popoverOpen() {
		return popover != null;
	}

	/** For tests: clicks the popover at a point relative to its top-left. */
	public void clickPopover(int dx, int dy) {
		if (popover != null && popover.click(popover.x() + dx, popover.y() + dy)) {
			popover = null;
		}
	}

	private void placePopover() {
		int w = popover.width();
		int h = popover.height();
		if (popoverBeside) {
			popover.place(sideW + 4, Math.max(winY + 4, Math.min(popoverAnchor[1] + popoverAnchor[3] - h, winY + winH - 4 - h)));
			return;
		}
		int x = Math.max(winX + 4, Math.min(popoverFromLeft ? popoverAnchor[0] : popoverAnchor[0] + popoverAnchor[2] - w, winX + winW - 4 - w));
		int y = popoverAnchor[1] + popoverAnchor[3] + 2;
		if (y + h > winY + winH - 4 && popoverAnchor[1] - h - 2 >= winY + 4) {
			y = popoverAnchor[1] - h - 2;
		}
		popover.place(x, y);
	}

	/** Opens (or closes) the download list under the header's download icon. */
	public void toggleDownloads() {
		if (popover instanceof DownloadsPopover) {
			popover = null;
		} else if (downloadsButton != null) {
			com.javahost.client.mods.Downloads.markSeen();
			openPopover(new DownloadsPopover(Math.min(winW - 8, contentW + 8)), downloadsButton);
		}
	}

	/** A small red count bubble. */
	static void badge(GuiGraphicsExtractor g, int x, int y, int count) {
		String text = count > 9 ? "9+" : String.valueOf(count);
		int w = Math.max(9, Draw.width(text) + 5);
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

	/** Files dropped onto the game window: the Skins page takes PNGs as new skins. */
	@Override
	public void onFilesDrop(List<Path> files) {
		if (page instanceof com.javahost.client.gui.pages.SkinsPage skins) {
			skins.filesDropped(files);
		}
	}

	/** For tests: the page shown now. */
	public Page page() {
		return page;
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
		if (popover != null) {
			popover = null; // Esc closes the popover first
			return;
		}
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
			if (widget instanceof SidebarTab t && (t.target == target || (t.target == null && MORE.contains(target)))) {
				return new int[] {t.getX(), t.getY(), t.getWidth(), t.getHeight()};
			}
		}
		return null;
	}

	@Override
	//? if >=1.21.9 {
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (popover != null) {
			// An open popover gets the click; a click outside just closes it.
			if (popover.contains(event.x(), event.y()) && !popover.click(event.x(), event.y())) {
				return true;
			}
			popover = null;
			return true;
		}
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
		if (popover != null) {
			if (popover.contains(x, y) && !popover.click(x, y)) {
				return true;
			}
			popover = null;
			return true;
		}
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
	private final long openedAt = System.nanoTime();
	private static final int MOTES = 16;

	private static float frac(float v) {
		return v - (float) Math.floor(v);
	}

	/**
	 * The hub's atmosphere: the blurred game under a dark tint, two big soft lights that drift slowly (the accent
	 * colour and its neighbour), a few motes rising like fireflies, and a darker floor.
	 */
	private void drawBackdrop(GuiGraphicsExtractor g) {
		float t = (System.nanoTime() - openedAt) / 1e9f;
		int areaW = width - sideW;
		g.fill(0, 0, width, height, Theme.withAlpha(Theme.HUB, openAnim));
		Draw.glow(g, sideW + Math.round(areaW * (0.76f + 0.07f * (float) Math.sin(t * 0.13f))),
				Math.round(height * (0.02f + 0.05f * (float) Math.sin(t * 0.09f + 1))),
				Math.round(areaW * 0.66f), Math.round(height * 0.74f), Theme.withAlpha(Theme.ACCENT, 0.18f * openAnim));
		Draw.glow(g, sideW + Math.round(areaW * (0.10f + 0.06f * (float) Math.sin(t * 0.1f + 2))),
				Math.round(height * (1.02f + 0.05f * (float) Math.sin(t * 0.12f))),
				Math.round(areaW * 0.58f), Math.round(height * 0.66f), Theme.withAlpha(Theme.ACCENT_ALT, 0.20f * openAnim));
		for (int i = 0; i < MOTES; i++) {
			float seed = (i + 1) * 0.6180339f;
			float rise = frac(seed * 3.1f + t * (0.010f + 0.018f * frac(seed * 7.3f))); // 0 at the floor, 1 at the top
			float x = frac(seed * 5.7f) + 0.015f * (float) Math.sin(t * 0.5f + i);
			float twinkle = 0.55f + 0.45f * (float) Math.sin(t * (0.7f + frac(seed * 2.3f)) + i * 1.7f);
			float fade = Math.min(1, Math.min(rise, 1 - rise) * 5); // appear and vanish gently
			int size = 2 + i % 3;
			Draw.glow(g, sideW + Math.round(x * areaW), Math.round((1 - rise) * height), size, size,
					Theme.withAlpha(i % 4 == 0 ? Theme.ACCENT_ALT : Theme.ACCENT, 0.55f * twinkle * fade * openAnim));
		}
		g.fillGradient(0, height / 2, width, height, 0x00000000, Theme.withAlpha(0x88000000, openAnim));
	}

	private void drawTabIndicator(GuiGraphicsExtractor g) {
		SidebarTab selected = null;
		for (AbstractWidget widget : sidebarWidgets) {
			if (widget instanceof SidebarTab t && t.selected()) {
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
		// A pill tinted with the accent colour, lit from its left edge.
		Draw.roundRect(g, selected.getX(), y, selected.getWidth(), h, 5, 0x18FFFFFF);
		g.enableScissor(selected.getX(), y, selected.getX() + selected.getWidth(), y + h);
		Draw.glow(g, selected.getX(), y + h / 2, Math.min(44, selected.getWidth()), h, Theme.withAlpha(Theme.ACCENT, 0.42f));
		g.disableScissor();
		Draw.roundRect(g, selected.getX(), y + 4, 2, h - 8, 1, Theme.ACCENT);
	}

	/** One entry in the sidebar; a null target is More, which opens a menu of the other pages. */
	private final class SidebarTab extends AbstractButton {
		private final Tab target;
		private final boolean compact;
		private float hover;

		SidebarTab(int x, int y, int w, int h, Tab target, boolean compact) {
			super(x, y, w, h, Component.literal(target == null ? "More" : target.label));
			this.target = target;
			this.compact = compact;
			if (compact) {
				// Only icons fit: the name shows on hover.
				setTooltip(net.minecraft.client.gui.components.Tooltip.create(com.javahost.client.gui.ui.Draw.styled(
						target == Tab.ACCOUNT ? "Your account" : target == null ? "More" : target.label)));
			}
		}

		/** Whether this entry is the highlighted one: its page (or a sub-page) is open; for More, one of its pages. */
		boolean selected() {
			if (target == null) {
				return MORE.contains(tab) || (tab.parent != null && MORE.contains(tab.parent));
			}
			return tab == target || tab.parent == target;
		}

		@Override
		//? if >=1.21.9 {
		public void onPress(InputWithModifiers input) {
		//?} else
		/*public void onPress() {*/
			if (target == null) {
				toggleMore();
			} else if (tab != target) {
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
			boolean selected = selected();
			// More stays lit while its menu is open.
			hover = Draw.approach(hover, (target == null ? isHovered() || moreOpen() : isHoveredOrFocused()) ? 1 : 0, 0.35f);
			// The selected highlight is drawn by the screen (it slides between tabs); here just the hover.
			if (!selected && hover > 0.01f) {
				Draw.roundRect(g, getX(), getY(), getWidth(), getHeight(), 5, Theme.withAlpha(0x14FFFFFF, hover));
			}
			int color = selected ? Theme.TEXT : Theme.mix(Theme.MUTED, Theme.TEXT, hover);
			int iconX = compact ? getX() + (getWidth() - Icons.SIZE) / 2 : getX() + 8;
			if (target == Tab.ACCOUNT) {
				// You: your face with a status dot, your name, and how friends see you right now.
				boolean hosting = com.javahost.client.host.HostController.isHosting();
				boolean hidden = Config.get().appearOffline;
				int dot = hidden ? Theme.FAINT : hosting ? Theme.ACCENT : minecraft.level != null ? Theme.INFO : Theme.ACCENT;
				int face = compact ? 12 : 16;
				int faceX = compact ? getX() + (getWidth() - face) / 2 : getX() + 5;
				int faceY = getY() + (getHeight() - face) / 2;
				if (!selected && !compact) {
					Draw.roundRect(g, getX(), getY(), getWidth(), getHeight(), 5, 0x0CFFFFFF); // a quiet card of its own
				}
				Faces.draw(g, Identity.id(), faceX, faceY, face);
				Draw.roundRect(g, faceX + face - 4, faceY + face - 4, 6, 6, 3, 0xFF0B0E13);
				Draw.roundRect(g, faceX + face - 3, faceY + face - 3, 4, 4, 2, dot);
				if (!compact) {
					String status = hidden ? "Appearing offline" : hosting ? "Hosting" : minecraft.level != null ? "In a world" : "Online";
					Draw.textFit(g, Identity.name(), getWidth() - 31, getX() + 26, getY() + 4, color);
					Draw.textFit(g, status, getWidth() - 31, getX() + 26, getY() + 14, Theme.mix(Theme.FAINT, Theme.MUTED, hover));
				}
				return;
			}
			float iconCentreX = compact ? getX() + getWidth() / 2f : iconX + Icons.SIZE / 2f;
			Icons icon = target == null ? Icons.MORE : target.icon;
			icon.drawCentred(g, iconCentreX, getY() + getHeight() / 2f, Icons.SIZE, selected ? Theme.ACCENT : color);
			if (!compact) {
				Draw.text(g, getMessage().getString(), getX() + 23, getY() + (getHeight() - 8) / 2 + 1, color);
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

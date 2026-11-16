package com.javahost.client.gui.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.javahost.client.Config;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.pages.world.AdvancedSection;
import com.javahost.client.gui.pages.world.BackupsSection;
import com.javahost.client.gui.pages.world.BannedSection;
import com.javahost.client.gui.pages.world.ConnectionSection;
import com.javahost.client.gui.pages.world.GeneralSection;
import com.javahost.client.gui.pages.world.HeroSection;
import com.javahost.client.gui.pages.world.MembersSection;
import com.javahost.client.gui.pages.world.RecentSection;
import com.javahost.client.gui.pages.world.RulesSection;
import com.javahost.client.gui.pages.world.SharingSection;
import com.javahost.client.gui.pages.world.WorldSection;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Popover;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.HostController;
import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.WorldOptions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The World place. The hero stays on top (your worlds, Start hosting, or the invite code with Invite / Stop); under
 * it, with a world open, World options in four parts (Members · General · Rules · Sharing) and a "more" menu for the
 * rest (Recent changes, Backups, Connection details, Advanced; a guest's has only Recent changes). With no world open:
 * Settings · Backups, as before.
 */
public class HostPage extends Page {
	private enum Section { MEMBERS, GENERAL, RULES, SHARING, RECENT, BACKUPS, CONNECTION, ADVANCED, SETTINGS }

	private static final List<Section> MAIN = List.of(Section.MEMBERS, Section.GENERAL, Section.RULES, Section.SHARING);
	private static final List<Section> GUEST_MAIN = List.of(Section.MEMBERS, Section.GENERAL, Section.RULES);
	private static final List<Section> NO_WORLD = List.of(Section.SETTINGS, Section.BACKUPS);
	private static final int MORE_W = 22;
	private static final int NOTICE_H = 36;

	/** The part shown under the hero (remembered while the game runs). */
	private static Section section = Section.GENERAL;
	/** Where Back goes from Backups, Connection details or Advanced. */
	private static Section lastMain = Section.GENERAL;
	private static boolean startRequested;
	/** The world (save folder) whose "co-hosts are now operators" note is showing, until dismissed. */
	private static String noticeWorld;

	private final WorldSection.Host host = new WorldSection.Host() {
		@Override
		public <T extends AbstractWidget> T add(T widget, int x, int y) {
			return HostPage.this.add(widget, x, y);
		}

		@Override
		public void setShown(AbstractWidget widget, boolean shown) {
			HostPage.this.setShown(widget, shown);
		}

		@Override
		public JavaHostScreen screen() {
			return screen;
		}

		@Override
		public int width() {
			return width;
		}

		@Override
		public void sent() {
			sentAt = System.currentTimeMillis();
		}
	};

	private final HeroSection hero = new HeroSection(host, () -> section = Section.MEMBERS);
	private final MembersSection members = new MembersSection(host);
	private final BannedSection banned = new BannedSection(host);
	private final GeneralSection general = new GeneralSection(host);
	private final RulesSection rules = new RulesSection(host);
	private final RecentSection recent = new RecentSection(host);
	private final SharingSection sharing = new SharingSection(host);
	private final BackupsSection backups = new BackupsSection(host);
	private final ConnectionSection connection = new ConnectionSection(host, hero);
	private final AdvancedSection advanced = new AdvancedSection(host);

	private HeroSection.Kind builtKind;
	private Section shown = Section.GENERAL;
	private final List<WorldSection> active = new ArrayList<>();
	private String lastSignature = "";
	/** When a change was last sent to the server (0: none waiting). */
	private long sentAt;
	private int noticeY = -1;

	@Override
	public String title() {
		return switch (kind()) {
			case HOSTING -> "Your world is open";
			case WORLDS -> "Host a world";
			case SETUP -> "Host this world";
			case GUEST -> "This world";
		};
	}

	@Override
	public String subtitle() {
		return switch (kind()) {
			case HOSTING -> "Invite friends, or share the code.";
			case WORLDS -> "Pick one: it opens and hosting starts.";
			case SETUP -> "Your PC is the server. No rental.";
			case GUEST -> "The host and operators run it.";
		};
	}

	/** Home's "Start hosting": the World tab starts hosting as soon as it opens. */
	public static void startWhenOpened() {
		startRequested = true;
	}

	/**
	 * Opens a part next time the page builds ("members", "general", "rules", "sharing", "recent", "backups",
	 * "connection", "advanced", or with no world open "settings"; the old "players" means members). Also for tests.
	 */
	public static void showSection(String name) {
		String key = name.toUpperCase(Locale.ROOT);
		section = key.equals("PLAYERS") ? Section.MEMBERS : Section.valueOf(key);
		if (MAIN.contains(section)) {
			lastMain = section;
		}
	}

	/** Opens the World page on its Rules part (what the old World rules page was). */
	public static void openRules(JavaHostScreen screen) {
		showSection("rules");
		screen.switchTo(JavaHostScreen.Tab.HOST);
	}

	/** For tests: the part that's open ("members", "general"...). */
	public static String currentSection() {
		return section.name().toLowerCase(Locale.ROOT);
	}

	/** For tests: show the page as a guest in someone else's world would see it. */
	private static boolean previewGuest;

	public static void previewGuest(boolean guest) {
		previewGuest = guest;
	}

	/** For tests: show the "co-hosts are now operators" note again for the open world. */
	public static void forgetOperatorNotice() {
		String world = worldKey();
		if (world != null) {
			Config.get().operatorNoticeWorlds.remove(world);
		}
		noticeWorld = null;
	}

	private HeroSection.Kind kind() {
		if (HostController.isHosting() && !previewGuest) {
			return HeroSection.Kind.HOSTING;
		}
		if (minecraft == null || minecraft.level == null) {
			return HeroSection.Kind.WORLDS;
		}
		return minecraft.getSingleplayerServer() != null && !previewGuest ? HeroSection.Kind.SETUP : HeroSection.Kind.GUEST;
	}

	private static Section resolve(HeroSection.Kind kind) {
		return switch (kind) {
			case WORLDS -> section == Section.BACKUPS ? Section.BACKUPS : Section.SETTINGS;
			case GUEST -> GUEST_MAIN.contains(section) || section == Section.RECENT ? section
					: section == Section.SETTINGS ? Section.GENERAL : Section.MEMBERS;
			default -> section == Section.SETTINGS ? Section.GENERAL : section;
		};
	}

	private static boolean overflow(Section s) {
		return s == Section.RECENT || s == Section.BACKUPS || s == Section.CONNECTION || s == Section.ADVANCED;
	}

	private static String sectionName(Section s) {
		return switch (s) {
			case MEMBERS -> "Members";
			case GENERAL -> "General";
			case RULES -> "Rules";
			case SHARING -> "Sharing";
			case RECENT -> "Recent changes";
			case BACKUPS -> "Backups";
			case CONNECTION -> "Connection details";
			case ADVANCED -> "Advanced";
			case SETTINGS -> "Settings";
		};
	}

	@Override
	protected void build() {
		builtKind = kind();
		boolean hosting = builtKind == HeroSection.Kind.HOSTING;
		boolean ownWorld = hosting || builtKind == HeroSection.Kind.SETUP;
		hero.show(builtKind);
		shown = resolve(builtKind);
		active.clear();
		active.add(hero);
		int y = hero.build(0) + 10;

		// The parts, and (in a world) "more" for the rest.
		boolean inWorld = builtKind != HeroSection.Kind.WORLDS;
		List<Section> options = !inWorld ? NO_WORLD : ownWorld ? MAIN : GUEST_MAIN;
		int segW = inWorld ? width - MORE_W - 6 : width;
		int people = HostController.guests().size() + 1;
		Segmented<Section> parts = add(new Segmented<>(segW, options, shown,
				s -> s == Section.MEMBERS && hosting && people > 1 ? "Members (" + people + ")" : sectionName(s), s -> {
					section = s;
					if (MAIN.contains(s)) {
						lastMain = s;
					}
					screen.refresh();
				}), 0, y);
		if (!options.contains(shown)) {
			parts.clearSelection();
		}
		if (inWorld) {
			boolean more = overflow(shown);
			List<Popover.Item> items = new ArrayList<>();
			items.add(Popover.Item.of("Recent changes", Icons.EDIT, () -> open(Section.RECENT)).selected(shown == Section.RECENT));
			if (ownWorld) {
				items.add(Popover.Item.of("Backups", Icons.PACKAGE, () -> open(Section.BACKUPS)).selected(shown == Section.BACKUPS));
				items.add(Popover.Item.of("Connection details", Icons.GLOBE, () -> open(Section.CONNECTION)).selected(shown == Section.CONNECTION));
				items.add(Popover.Item.of("Advanced", Icons.GEAR, () -> open(Section.ADVANCED)).selected(shown == Section.ADVANCED));
			}
			FlatButton moreButton = add(new FlatButton(MORE_W, 18, Component.literal("More options"), FlatButton.Style.SECONDARY,
					Icons.MORE, b -> screen.openPopover(Popover.menu(items), b)).iconOnly(), width - MORE_W, y);
			if (more) {
				moreButton.iconColor(Theme.ACCENT);
			}
			moreButton.setTooltip(Tooltip.create(Draw.styled(ownWorld ? "Recent changes, backups, connection details and advanced settings"
					: "Recent changes")));
		}
		y += 18 + 8;

		// Once per world: co-hosts made before 2.0 are operators now.
		noticeY = -1;
		if (ownWorld && showOperatorNotice()) {
			noticeY = y;
			add(new FlatButton(16, 16, Component.literal("Dismiss"), FlatButton.Style.GHOST, Icons.CROSS, b -> {
				noticeWorld = null;
				screen.refresh();
			}).iconOnly(), width - 16 - 8, y + (NOTICE_H - 16) / 2);
			y += NOTICE_H + 8;
		}

		switch (shown) {
			case MEMBERS -> {
				members.configure(ownWorld, hosting);
				y = place(members, y);
				if (ownWorld) {
					y = place(banned, y);
				}
			}
			case GENERAL -> {
				general.configure(true, ownWorld, ownWorld);
				y = place(general, y);
			}
			case RULES -> y = place(rules, y);
			case RECENT -> y = place(recent, y);
			case SHARING -> {
				sharing.configure(true, hosting);
				y = place(sharing, y);
			}
			case BACKUPS -> y = place(backups, y);
			case CONNECTION -> {
				connection.configure(hosting);
				y = place(connection, y);
			}
			case ADVANCED -> {
				advanced.configure(hosting);
				y = place(advanced, y);
			}
			case SETTINGS -> {
				// No world open: the host's own settings, all in one place.
				general.configure(false, true, false);
				y = place(general, y);
				y = place(banned, y);
				sharing.configure(false, false);
				y = place(sharing, y + 16);
				advanced.configure(false);
				y = place(advanced, y + 16);
			}
		}
		reserve(y + 10);
		lastSignature = signature();
		sentAt = 0;
	}

	private int place(WorldSection part, int y) {
		active.add(part);
		return part.build(y);
	}

	private void open(Section s) {
		section = s;
		screen.refresh();
	}

	@Override
	protected boolean canGoBack() {
		return overflow(shown) && builtKind != HeroSection.Kind.WORLDS;
	}

	@Override
	protected boolean back() {
		if (!canGoBack()) {
			return false;
		}
		section = lastMain;
		screen.refresh();
		return true;
	}

	// ---------------------------------------------------------------- the one-time operators note

	private static String worldKey() {
		IntegratedServer server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return null;
		}
		java.nio.file.Path name = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		return name == null ? null : name.toString();
	}

	/**
	 * The first time the host sees World options for a world since 2.0: if it already has operators, they were co-hosts
	 * before, so say once what changed.
	 */
	private boolean showOperatorNotice() {
		String world = worldKey();
		if (world == null || !WorldOptions.available() || WorldOptions.role() != WorldOptions.Role.HOST) {
			return false;
		}
		Config config = Config.get();
		if (!config.operatorNoticeWorlds.contains(world)) {
			config.operatorNoticeWorlds.add(world);
			config.save();
			if (!WorldOptions.operators().isEmpty()) {
				noticeWorld = world;
			}
		}
		return world.equals(noticeWorld);
	}

	// ---------------------------------------------------------------- keeping up with the world

	private String signature() {
		StringBuilder s = new StringBuilder().append(kind()).append('|').append(WorldOptions.version()).append('|').append(RulesClient.version());
		for (WorldSection part : active) {
			s.append('|').append(part.signature());
		}
		return s.toString();
	}

	@Override
	public void tick() {
		if (startRequested) {
			startRequested = false;
			if (hero.canHost() && !HostController.isHosting()) {
				hero.start(); // "Start hosting" on Home: straight to work
				return;
			}
		}
		if (shown == Section.RULES) {
			rules.tick(); // typing in the search, before the signature is checked
		}
		HeroSection.Kind now = kind();
		if (now != builtKind) {
			if (now == HeroSection.Kind.HOSTING) {
				section = Section.MEMBERS; // hosting just started: who's here matters most now
			}
			screen.refresh();
			return;
		}
		if (!signature().equals(lastSignature)) {
			screen.refresh(); // the server answered (or something else changed): show the real values
			return;
		}
		if (sentAt != 0 && System.currentTimeMillis() - sentAt > 1500) {
			screen.refresh(); // no answer: the server refused, so put the controls back as they are
			return;
		}
		if (shown == Section.MEMBERS) {
			members.tick(); // pings change without a rebuild
		}
	}

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		return shown == Section.RULES && rules.keyPressed(event);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		for (WorldSection part : active) {
			part.draw(g, left, top, mouseX, mouseY);
		}
		if (noticeY >= 0) {
			int y = top + noticeY;
			Draw.glassCard(g, left, y, width, NOTICE_H, 6);
			Draw.roundRect(g, left, y, 3, NOTICE_H, 1, Theme.ACCENT);
			Icons.SHIELD.draw(g, left + 12, y + 9, Theme.ACCENT);
			int textW = width - 28 - 32;
			Draw.textFit(g, "Co-hosts are now operators", textW, left + 28, y + 9, Theme.TEXT);
			Draw.textFit(g, "They can also use commands while Cheats is on.", textW, left + 28, y + 20, Theme.MUTED);
		}
	}
}

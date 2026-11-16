package com.javahost.client.gui.pages;

import java.util.ArrayList;
import java.util.List;

import com.javahost.JavaHost;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Chip;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.RowButton;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.gui.ui.WebIcons;
import com.javahost.client.mods.Downloads;
import com.javahost.client.mods.ModLibrary;
import com.javahost.client.mods.VanillaMode;
import com.javahost.client.mods.ModSets;
import com.javahost.client.mods.Modpacks;
import com.javahost.client.perf.BoostMods;
import com.javahost.util.Availability;
import com.javahost.util.IoExecutor;
import com.javahost.util.ModManifest;
import com.javahost.util.ModStates;
import com.javahost.util.Modrinth;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

/**
 * Mods, laid out like a store. <b>Discover</b>: search Modrinth for mods and modpacks for every Minecraft version, by
 * category; ones that can't run here say what they run on. A card opens the project's page in the menu, with Install.
 * <b>My mods</b>: every mod with a switch to turn it on or off (made when the game closes), downloads, which mod set is
 * in use, what friends need, and saved mod sets.
 */
public class ModsPage extends Page {
	private static final int ROW = 24;
	private static final int CARD = 40;
	private static final int JOB_ROW = 32;
	private static final int MAX_SAVED = 4;
	private static final int TOP = 26; // below the Discover / My mods switch
	private static final int BANNER = 30;
	/** Width of the right-hand column of a card (Install, or where it runs). */
	private static final int COLUMN = 72;
	/** The detail view's header card. */
	private static final int HERO = 58;
	private static final long TYPING_PAUSE = 400;

	private enum View { DISCOVER, INSTALLED }

	/** Where a project stands here: what its Install button says. */
	private enum State { INSTALL, QUEUED, DOWNLOADING, FAILED, RESTART, INSTALLED, OFF, READY, UNAVAILABLE }

	private record Category(String label, String id) {
	}

	private static final List<Category> MOD_CATEGORIES = List.of(new Category("All", ""), new Category("Adventure", "adventure"),
			new Category("Magic", "magic"), new Category("Technology", "technology"), new Category("Mobs", "mobs"),
			new Category("Decoration", "decoration"), new Category("Worldgen", "worldgen"), new Category("Food", "food"),
			new Category("Utility", "utility"));
	private static final List<Category> PACK_CATEGORIES = List.of(new Category("All", ""), new Category("Adventure", "adventure"),
			new Category("Magic", "magic"), new Category("Technology", "technology"), new Category("Quests", "quests"),
			new Category("Combat", "combat"), new Category("Kitchen sink", "kitchen-sink"), new Category("Lightweight", "lightweight"));

	private static View view = View.DISCOVER;
	private static Modrinth.Kind kind = Modrinth.Kind.MOD;
	private static String category = "";
	private static boolean runsHere;
	private static volatile List<Modrinth.Project> results = List.of();
	private static volatile boolean searching;
	private static volatile boolean more;
	private static volatile String searchProblem = "";
	private static volatile int searchVersion;
	private static String query = "";
	private static String searchedFor;
	/** When the player last typed in the search box (0: nothing waiting to be searched). */
	private static long typedAt;
	/** The project open in the detail view, or null for the list. */
	private static Modrinth.Project detail;

	private List<ModSets.Saved> saved = List.of();
	private List<BoostMods.Installed> installed = List.of();
	private List<Downloads.Job> jobs = List.of();
	/** My mods has been read afresh since this page opened. */
	private boolean openedMine;
	private int bannerY = -1;
	private int resultsY;
	private int mineY;
	private int savedY;
	private int aboutY;
	private String lastSignature = "";
	private TextField searchField;
	private boolean refocus;

	@Override
	public String title() {
		return detail != null ? detail.title() : "Mods";
	}

	@Override
	public String subtitle() {
		if (detail != null) {
			return (detail.kind() == Modrinth.Kind.MODPACK ? "Modpack" : "Mod") + (detail.author().isEmpty() ? "" : " by " + detail.author());
		}
		return "Find mods, then play them with friends.";
	}

	/** For tests and links from elsewhere: open Discover with this search (it runs when the page opens). */
	public static void showSearch(Modrinth.Kind searchKind, String text) {
		view = View.DISCOVER;
		kind = searchKind;
		category = "";
		query = text;
		detail = null;
		searchedFor = null;
	}

	/** For tests: open Discover in a category ("magic"), or "" for all. */
	public static void showCategory(String id) {
		category = id;
		searchedFor = null;
	}

	/** For tests: only projects that run here, or every version. */
	public static void showRunsHere(boolean only) {
		runsHere = only;
		searchedFor = null;
	}

	/** For tests and links from elsewhere: open on My mods instead of Discover. */
	public static void showMine(boolean mine) {
		view = mine ? View.INSTALLED : View.DISCOVER;
		detail = null;
	}

	/** For tests: open a project's page (null: back to the list). */
	public static void showDetail(Modrinth.Project project) {
		detail = project;
	}

	/** For tests: what the last search found. */
	public static List<Modrinth.Project> results() {
		return results;
	}

	/** For tests: whether a search is still running. */
	public static boolean searching() {
		return searching;
	}

	@Override
	protected boolean canGoBack() {
		return detail != null;
	}

	@Override
	protected boolean back() {
		if (detail == null) {
			return false;
		}
		detail = null;
		screen.refresh();
		return true;
	}

	@Override
	protected void build() {
		if (!openedMine) {
			openedMine = true;
			ModLibrary.refresh(); // a fresh look each time the page opens (Discover also shows which mods are off)
		}
		installed = BoostMods.installedByJavaHost();
		jobs = Downloads.jobs();
		lastSignature = signature();
		if (detail != null) {
			buildDetail();
			return;
		}
		add(new Segmented<>(width, List.of(View.DISCOVER, View.INSTALLED), view, v -> v == View.DISCOVER ? "Discover" : "My mods", v -> {
					view = v;
					screen.refresh();
				}), 0, 0);
		if (view == View.DISCOVER) {
			buildDiscover();
		} else {
			buildInstalled();
		}
	}

	private String searchKey() {
		return kind + "|" + category + "|" + runsHere + "|" + query.strip();
	}

	private String signature() {
		// Not the search text: typing searches after a pause, and the new results bump searchVersion.
		return view + "|" + kind + "|" + category + "|" + runsHere + "|" + searchVersion + "|" + searching + "|" + Downloads.version() + "|" + (detail == null ? "" : detail.id())
				+ "|" + ModSets.changes() + "|" + (ModSets.mine() != null) // no disk access here: this runs every tick
				+ (view == View.INSTALLED && detail == null ? "|" + mineSignature() : "|" + ModLibrary.version());
	}

	@Override
	public void tick() {
		// Search a moment after typing stops.
		if (typedAt > 0 && System.currentTimeMillis() - typedAt > TYPING_PAUSE) {
			typedAt = 0;
			if (!searchKey().equals(searchedFor)) {
				search(false);
			}
		}
		if (view == View.INSTALLED && detail == null) {
			ModLibrary.refreshIfStale(); // mods dropped in or deleted by hand show up
			if (mineTypedAt > 0 && System.currentTimeMillis() - mineTypedAt > MINE_TYPING_PAUSE) {
				mineTypedAt = 0;
				mineApplied = mineQuery;
			}
		}
		if (!signature().equals(lastSignature)) {
			refocus = searchField != null && searchField.isFocused(); // keep typing after the results update
			refocusMine = mineField != null && mineField.isFocused();
			screen.refresh();
		}
	}

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (searchField != null && searchField.isFocused() && isEnter(event)) {
			typedAt = 0;
			search(false);
			return true;
		}
		if (mineField != null && mineField.isFocused() && isEnter(event)) {
			mineTypedAt = 0;
			mineApplied = mineQuery;
			return true;
		}
		return false;
	}

	/** Where a project stands in this game: can it be installed, is it on its way, or is it already here. */
	private static State state(Modrinth.Project project, String minecraft) {
		if (!Availability.installable(project, minecraft)) {
			return State.UNAVAILABLE;
		}
		Downloads.Job job = Downloads.job(project.id());
		if (job != null && job.stage() == Downloads.Stage.QUEUED) {
			return State.QUEUED;
		}
		if (job != null && job.stage() == Downloads.Stage.DOWNLOADING) {
			return State.DOWNLOADING;
		}
		boolean mod = project.kind() == Modrinth.Kind.MOD;
		if (mod && ModLibrary.installedButOff(project.slug())) {
			return State.OFF; // switched off, or put aside by Vanilla mode: never a second copy
		}
		if (mod && BoostMods.waitingForRestart(project.slug())) {
			return State.RESTART;
		}
		if (mod && BoostMods.installedSlug(project.slug())) {
			return State.INSTALLED;
		}
		if (!mod && job != null && job.stage() == Downloads.Stage.DONE) {
			return State.READY;
		}
		return job != null && job.stage() == Downloads.Stage.FAILED ? State.FAILED : State.INSTALL;
	}

	private void install(Modrinth.Project project) {
		if (project.kind() == Modrinth.Kind.MODPACK) {
			Downloads.installPack(project);
		} else {
			Downloads.installMod(project.id(), project.slug(), project.title(), project.iconUrl());
		}
		screen.refresh();
	}

	/** My mods, showing this project (searched by its name when the list is long). */
	private void showInMine(Modrinth.Project project) {
		detail = null;
		view = View.INSTALLED;
		mineQuery = mineApplied = project.title();
		mineTypedAt = 0;
		screen.refresh();
	}

	private void openDetail(Modrinth.Project project) {
		detail = project;
		screen.refresh();
	}

	private boolean restartPending() {
		return Downloads.restartNeeded() || installed.stream().anyMatch(i -> !i.active());
	}

	// ================================================================ Discover

	private List<Category> categories() {
		return kind == Modrinth.Kind.MOD ? MOD_CATEGORIES : PACK_CATEGORIES;
	}

	private void buildDiscover() {
		String minecraft = Modpacks.gameVersion();
		// Search, with Mods / Packs beside it.
		int kindW = Math.min(110, width / 3);
		int searchW = width - kindW - 6;
		searchField = add(new TextField(searchW, Component.literal("Search")), 0, TOP - 1);
		searchField.setValue(query);
		searchField.setHint(Component.literal(Draw.fit(kind == Modrinth.Kind.MOD ? "Search mods..." : "Search modpacks...", searchW - 10))
				.withStyle(s -> s.withColor(Theme.FAINT)));
		searchField.setResponder(value -> {
			if (!value.equals(query)) {
				query = value;
				typedAt = System.currentTimeMillis();
			}
		});
		if (refocus) {
			refocus = false;
			screen.focusOn(searchField);
		}
		add(new Segmented<>(kindW, List.of(Modrinth.Kind.MOD, Modrinth.Kind.MODPACK), kind, k -> k == Modrinth.Kind.MOD ? "Mods" : "Packs", k -> {
			kind = k;
			category = "";
			search(false);
			screen.refresh();
		}), width - kindW, TOP);

		// Categories, wrapping onto a second row when they don't fit.
		int x = 0;
		int y = TOP + 27;
		for (Category c : categories()) {
			int w = Chip.width(c.label());
			if (x > 0 && x + w > width) {
				x = 0;
				y += Chip.HEIGHT + 4;
			}
			add(new Chip(c.label(), c.id().equals(category), b -> {
				category = c.id();
				search(false);
				screen.refresh();
			}), x, y);
			x += w + 4;
		}
		y += Chip.HEIGHT + 8;
		add(new ToggleSwitch(width, Component.literal("Only ones that run on " + minecraft), null, runsHere, on -> {
			runsHere = on;
			search(false);
			screen.refresh();
		}), 0, y);
		y += 16 + 8;

		// Downloaded mods only start working after a restart (Minecraft loads mods once, at startup).
		bannerY = -1;
		if (restartPending()) {
			bannerY = y;
			add(new FlatButton(98, 16, Component.literal("Close Minecraft"), FlatButton.Style.PRIMARY, null, b -> this.minecraft.stop()),
					width - 106, y + 4);
			y += BANNER;
		}

		resultsY = y;
		for (Modrinth.Project project : results) {
			int rowY = y;
			// The whole card opens the project's page; the button on the right installs.
			add(new RowButton(width - COLUMN - 4, CARD - 2, Component.literal(project.title()), b -> openDetail(project)), 0, rowY + 1);
			State state = state(project, minecraft);
			FlatButton button = switch (state) {
				case INSTALL, FAILED -> new FlatButton(COLUMN - 8, 16, Component.literal(state == State.FAILED ? "Retry" : "Install"),
						FlatButton.Style.PRIMARY, null, b -> install(project));
				case RESTART -> new FlatButton(COLUMN - 8, 16, Component.literal("Restart"), FlatButton.Style.SECONDARY, null, b -> {
				});
				case INSTALLED -> new FlatButton(COLUMN - 8, 16, Component.literal("Installed"), FlatButton.Style.SECONDARY, Icons.CHECK, b -> {
				});
				case OFF -> new FlatButton(COLUMN - 8, 16, Component.literal("Off"), FlatButton.Style.SECONDARY, null, b -> showInMine(project));
				case READY -> new FlatButton(COLUMN - 8, 16, Component.literal("Use"), FlatButton.Style.SECONDARY, null, b -> {
					view = View.INSTALLED;
					screen.refresh();
				});
				default -> null; // on its way (drawn), or runs elsewhere
			};
			if (button != null) {
				add(button, width - COLUMN, rowY + 11);
				if (state == State.RESTART || state == State.INSTALLED) {
					button.active = false;
				}
				if (state == State.OFF) {
					button.setTooltip(Tooltip.create(Draw.styled("Installed, but switched off. Switch it on in My mods.")));
				}
				if (state == State.RESTART) {
					button.setTooltip(Tooltip.create(com.javahost.client.gui.ui.Draw.styled("Downloaded. Minecraft loads mods when it starts, so close it and press Play to use this.")));
				}
			}
			y += CARD;
		}
		if (more && !searching) {
			add(new FlatButton(120, 18, Component.literal("Show more"), FlatButton.Style.GHOST, null, b -> search(true)), (width - 120) / 2, y + 6);
			y += 30;
		}
		reserve(y + 24);
		if (searchedFor == null || (!searchKey().equals(searchedFor) && typedAt == 0)) {
			search(false);
		}
	}

	private void search(boolean nextPage) {
		if (searching) {
			return;
		}
		searching = true;
		searchProblem = "";
		Modrinth.Kind searchKind = kind;
		String text = query.strip();
		String searchCategory = category;
		String runsOn = runsHere ? Modpacks.gameVersion() : null;
		String key = searchKey();
		searchedFor = key;
		List<Modrinth.Project> before = nextPage ? results : List.of();
		if (!nextPage) {
			results = List.of();
		}
		IoExecutor.NET.execute(() -> {
			try {
				List<Modrinth.Project> found = Modrinth.search(searchKind, text, searchCategory, runsOn, before.size());
				if (!key.equals(searchKey())) {
					// The player changed the search meanwhile: drop these, and the page searches again for the new one.
					searchedFor = null;
					searching = false;
					searchVersion++;
					return;
				}
				List<Modrinth.Project> all = new ArrayList<>(before);
				found.stream().filter(p -> all.stream().noneMatch(q -> q.id().equals(p.id()))).forEach(all::add);
				results = List.copyOf(all);
				more = found.size() == Modrinth.PAGE && all.size() < 200;
			} catch (Exception e) {
				searchProblem = "Couldn't reach Modrinth. Check your connection.";
				JavaHost.LOGGER.warn("[Mods] search failed", e);
			}
			searching = false;
			searchVersion++;
		});
	}

	private void drawDiscover(GuiGraphicsExtractor g, int left, int top) {
		String minecraft = Modpacks.gameVersion();
		if (bannerY >= 0) {
			restartBanner(g, left, top + bannerY);
		}
		int y = top + resultsY;
		if (results.isEmpty()) {
			String message = searching ? "Searching Modrinth..." : !searchProblem.isEmpty() ? searchProblem : "Nothing on Modrinth matches that.";
			Draw.textFit(g, message, width, left, y + 4, !searchProblem.isEmpty() ? Theme.WARNING : Theme.FAINT);
			return;
		}
		for (Modrinth.Project project : results) {
			card(g, left, y, width, CARD - 2);
			WebIcons.draw(g, project.iconUrl(), project.title(), left + 4, y + 3, 32);
			int textX = left + 42;
			int textW = width - 42 - COLUMN - 4;
			Draw.textFit(g, project.title(), textW, textX, y + 5, Theme.TEXT);
			// Downloads first, so a narrow card cuts the author rather than the number.
			String meta = downloads(project.downloads()) + (project.author().isEmpty() ? "" : " · by " + project.author());
			Draw.textFit(g, meta, textW, textX, y + 16, Theme.MUTED);
			Draw.textFit(g, project.description(), textW, textX, y + 27, Theme.FAINT);
			int columnX = left + width - COLUMN;
			State state = state(project, minecraft);
			switch (state) {
				case UNAVAILABLE -> {
					// Where it does run, in the column the Install button would use: "Runs on" / loader / version.
					String[] runsOn = Availability.runsOn(project).split(" ", 2);
					Draw.textFit(g, "Runs on", COLUMN - 6, columnX, y + 5, Theme.FAINT);
					Draw.textFit(g, runsOn[0], COLUMN - 6, columnX, y + 16, Theme.WARNING);
					Draw.textFit(g, runsOn.length > 1 ? runsOn[1] : "", COLUMN - 6, columnX, y + 27, Theme.WARNING);
				}
				case QUEUED, DOWNLOADING -> {
					Downloads.Job job = Downloads.job(project.id());
					double progress = job == null ? 0 : job.progress();
					String label = state == State.QUEUED ? "Waiting" : Math.round(progress * 100) + "%";
					Draw.centered(g, label, columnX + (COLUMN - 8) / 2, y + 12, Theme.INFO);
					progressBar(g, columnX, y + 23, COLUMN - 8, 3, progress);
				}
				case FAILED -> Draw.textFit(g, "Failed", COLUMN - 6, columnX, y + 29, Theme.WARNING);
				default -> {
				}
			}
			y += CARD;
		}
	}

	private static String downloads(long n) {
		return n >= 1_000_000 ? String.format("%.0fM downloads", n / 1e6) : n >= 1000 ? (n / 1000) + "k downloads" : n + " downloads";
	}

	private void progressBar(GuiGraphicsExtractor g, int x, int y, int w, int h, double progress) {
		Draw.roundRect(g, x, y, w, h, h / 2, Theme.FIELD);
		int filled = (int) Math.round(w * Math.max(0, Math.min(1, progress)));
		if (filled > 0) {
			Draw.roundRect(g, x, y, Math.max(h, filled), h, h / 2, Theme.ACCENT);
		}
	}

	private void restartBanner(GuiGraphicsExtractor g, int left, int y) {
		Draw.panel(g, left, y, width, BANNER - 6, 6, Theme.withAlpha(Theme.ACCENT, 0.12f));
		Icons.CHECK.draw(g, left + 8, y + 7, Theme.ACCENT);
		Draw.textFit(g, "Restart to use new mods.", width - 132, left + 24, y + 8, Theme.TEXT);
	}

	// ================================================================ a project's page

	private void buildDetail() {
		Modrinth.Project project = detail;
		State state = state(project, Modpacks.gameVersion());
		int y = HERO + 8;
		int linkW = 92;
		int mainW = width - linkW - 6;
		FlatButton main = switch (state) {
			case INSTALL, FAILED -> new FlatButton(mainW, 20, Component.literal(state == State.FAILED ? "Try again" : "Install"),
					FlatButton.Style.PRIMARY, Icons.DOWNLOAD, b -> install(project));
			case RESTART -> new FlatButton(mainW, 20, Component.literal("Close Minecraft"), FlatButton.Style.PRIMARY, null, b -> minecraft.stop());
			case INSTALLED -> new FlatButton(mainW, 20, Component.literal("Installed"), FlatButton.Style.SECONDARY, Icons.CHECK, b -> {
			});
			case OFF -> new FlatButton(mainW, 20, Component.literal("Switch it on in My mods"), FlatButton.Style.SECONDARY, Icons.PACKAGE,
					b -> showInMine(project));
			case READY -> new FlatButton(mainW, 20, Component.literal("Use it from My mods"), FlatButton.Style.SECONDARY, Icons.PACKAGE, b -> {
				detail = null;
				view = View.INSTALLED;
				screen.refresh();
			});
			default -> null; // on its way, or runs elsewhere: drawn instead
		};
		if (main != null) {
			add(main, 0, y);
			main.active = state != State.INSTALLED;
		}
		add(new FlatButton(linkW, 20, Component.literal("Modrinth"), FlatButton.Style.SECONDARY, Icons.GLOBE,
				b -> ConfirmLinkScreen.confirmLinkNow(screen, java.net.URI.create(project.page()))), width - linkW, y);
		aboutY = y + 46;
		reserve(aboutY + 12 + wrappedHeight(project.description(), width - 20) + 16 + 22 + 12 + 4 * 12 + 16);
	}

	private void drawDetail(GuiGraphicsExtractor g, int left, int top) {
		Modrinth.Project project = detail;
		String minecraft = Modpacks.gameVersion();
		State state = state(project, minecraft);
		// Header: icon, name, downloads and author, categories.
		card(g, left, top, width, HERO);
		WebIcons.draw(g, project.iconUrl(), project.title(), left + 9, top + 9, 40);
		int textX = left + 58;
		int textW = width - 66;
		String name = Draw.fitScaled(project.title(), textW, 1.25f);
		Draw.scaledText(g, name, textX, top + 9, 1.25f, Theme.TEXT);
		Draw.textFit(g, downloads(project.downloads()) + (project.author().isEmpty() ? "" : " · by " + project.author()), textW, textX, top + 23,
				Theme.MUTED);
		int chipX = textX;
		for (String c : project.categories()) {
			String label = c.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + c.substring(1).replace('-', ' ');
			int w = Draw.width(label) + 10;
			if (chipX + w > left + width - 8) {
				break;
			}
			Draw.roundRect(g, chipX, top + 37, w, 12, 6, Theme.FIELD);
			Draw.text(g, label, chipX + 5, top + 39, Theme.MUTED);
			chipX += w + 3;
		}

		// The install row: progress or where it runs, when there's no button.
		int y = top + HERO + 8;
		int mainW = width - 92 - 6;
		if (state == State.QUEUED || state == State.DOWNLOADING) {
			Downloads.Job job = Downloads.job(project.id());
			double progress = job == null ? 0 : job.progress();
			Draw.roundRect(g, left, y, mainW, 20, 4, Theme.FIELD);
			int filled = (int) Math.round(mainW * progress);
			if (filled > 0) {
				Draw.roundRect(g, left, y, Math.max(8, filled), 20, 4, Theme.withAlpha(Theme.ACCENT, 0.35f));
			}
			String label = state == State.QUEUED ? "Waiting to download..." : "Downloading... " + Math.round(progress * 100) + "%";
			Draw.centered(g, Draw.fit(label, mainW - 8), left + mainW / 2, y + 6, Theme.TEXT);
		} else if (state == State.UNAVAILABLE) {
			Draw.panel(g, left, y, mainW, 20, 4, Theme.withAlpha(Theme.WARNING, 0.12f));
			Icons.WARNING.draw(g, left + 6, y + 5, Theme.WARNING);
			Draw.textFit(g, "Runs on " + Availability.runsOn(project) + ", not here", mainW - 26, left + 21, y + 6, Theme.WARNING);
		}
		String note = switch (state) {
			case INSTALL -> project.kind() == Modrinth.Kind.MODPACK ? "Saved as a mod set you can switch to." : "Restart Minecraft after installing to use it.";
			case RESTART -> "Installed. Minecraft loads mods when it starts.";
			case INSTALLED -> "It's running in this game.";
			case OFF -> "Installed, but switched off (or put aside by Vanilla mode).";
			case FAILED -> {
				Downloads.Job job = Downloads.job(project.id());
				yield job == null ? "Couldn't install it." : job.statusText();
			}
			case UNAVAILABLE -> "Pick that version in the JavaHost installer to play it.";
			case READY -> "Switch to it from My mods, then restart.";
			default -> "";
		};
		Draw.textFit(g, note, width, left, y + 26, state == State.FAILED ? Theme.WARNING : Theme.FAINT);

		// About, then details.
		y = top + aboutY;
		sectionLabel(g, "About", left, y);
		int aboutH = wrappedHeight(project.description(), width - 20) + 16;
		card(g, left, y + 12, width, aboutH);
		wrap(g, project.description(), left + 10, y + 20, width - 20, Theme.TEXT);
		y += 12 + aboutH + 12;
		sectionLabel(g, "Details", left, y);
		card(g, left, y + 12, width, 4 * 12 + 12);
		String versions = String.join(", ", Availability.newestReleases(project, 4));
		String[][] rows = {
				{"Type", project.kind() == Modrinth.Kind.MODPACK ? "Modpack" : "Mod"},
				{"Loaders", Availability.loaders(project).isEmpty() ? "Unknown" : Availability.loaders(project)},
				{"Minecraft", versions.isEmpty() ? "Unknown" : versions},
				{"Here", Availability.installable(project, minecraft) ? "Works on " + minecraft : "Not on " + minecraft + " with Fabric"},
		};
		int rowY = y + 18;
		for (String[] row : rows) {
			Draw.text(g, row[0], left + 10, rowY, Theme.FAINT);
			Draw.textFit(g, row[1], width - 80, left + 70, rowY, row[0].equals("Here") && !Availability.installable(project, minecraft) ? Theme.WARNING
					: Theme.TEXT);
			rowY += 12;
		}
	}

	// ================================================================ My mods

	private int downloadsY = -1;
	/** The pending bar (changes made when the game closes), or -1. */
	private int pendingY = -1;
	private int pendingCount;
	private int headerY;
	private boolean headerSearch;
	private int readOnlyY = -1;
	private String readOnlyText;
	private int listY;
	private int emptyListY = -1;
	private List<ModStates.Entry> shownMods = List.of();
	private List<ModStates.Entry> allMods = List.of();
	private TextField mineField;
	private boolean refocusMine;
	/** A refused switch: which row, why, and when (shown in the row for a few seconds). */
	private static String refusedFile;
	private static String refusedReason;
	private static long refusedAt;
	private static final long REFUSED_SHOWN = 4000;
	/** The open confirm (for tests), or null. */
	private static ConfirmPopover confirming;

	/** For tests and links from elsewhere: My mods with this search ("" for every mod). */
	public static void showMineSearch(String text) {
		view = View.INSTALLED;
		detail = null;
		mineQuery = mineApplied = text;
		mineTypedAt = 0;
	}

	/** For tests: the mods listed, by file name, in order. */
	public static List<String> shownFiles() {
		ModsPage page = lastMine;
		return page == null ? List.of() : page.shownMods.stream().map(ModStates.Entry::file).toList();
	}

	/** For tests: the caption under a mod's name, or null if it isn't listed. */
	public static String captionOf(String file) {
		ModsPage page = lastMine;
		if (page == null) {
			return null;
		}
		for (ModStates.Entry entry : page.shownMods) {
			if (entry.file().equals(file)) {
				return caption(entry);
			}
		}
		return null;
	}

	/** For tests: the pending bar's text, or null when it isn't shown. */
	public static String pendingText() {
		ModsPage page = lastMine;
		return page == null || page.pendingY < 0 ? null : pendingTitle(page.pendingCount);
	}

	/** For tests: how many put-aside mods the Vanilla mode card lists (-1 when it isn't shown). */
	public static int asideShown() {
		ModsPage page = lastMine;
		return page == null || page.bannerTop < 0 ? -1 : page.aside.size();
	}

	/** For tests: the read-only line, or null. */
	public static String readOnlyNote() {
		ModsPage page = lastMine;
		return page == null || page.readOnlyY < 0 ? null : page.readOnlyText;
	}

	/** For tests: what the open confirm says ("Also turns off: ..."), or null when none is open. */
	public static String confirmText() {
		ConfirmPopover open = confirming;
		return open == null ? null : open.detail;
	}

	/** For tests: where the confirm's Confirm button is, from the popover's top-left. */
	public static int[] confirmButtonOffset() {
		ConfirmPopover open = confirming;
		return open == null ? null : new int[] {open.width() - ConfirmPopover.PAD - ConfirmPopover.BUTTON_W / 2, open.height() - ConfirmPopover.PAD - 8};
	}

	private static ModsPage lastMine;
	private static String mineQuery = "";
	private static String mineApplied = "";
	private static long mineTypedAt;
	private static final long MINE_TYPING_PAUSE = 150;
	/** More mods than this get a search field. */
	private static final int SEARCH_FROM = 12;
	private static final int MOD_ROW = 28;
	private static final int PAD = 10;
	private static final int PENDING_H = 32;
	private static final int SWITCH_W = 22;
	private static final int MORE_W = 16;
	private static final int KEEP_W = 44;
	/** Space inside the list card above the first row and below the last (as World → Rules has). */
	private static final int LIST_PAD = 4;
	private static final int VANILLA_CARD = 32;
	private static final int BANNER_H = 36;
	private static final int ASIDE_ROW = 22;
	private int vanillaCardY = -1;
	private int bannerTop = -1;
	private int asideY;
	private List<com.javahost.util.ModJar> aside = List.of();

	private static String pendingTitle(int count) {
		return count == 1 ? "1 change waiting" : count + " changes waiting";
	}

	/** The faint line under a mod's name: its version and what it's doing, in words. */
	private static String caption(ModStates.Entry entry) {
		String version = entry.jar().version().isEmpty() ? "" : entry.jar().version();
		String words = switch (entry.state()) {
			case OFF -> "Off";
			case TURNING_OFF -> "Turns off when you close the game";
			case TURNING_ON -> "Turns on when you close the game";
			case NEW -> "New: loads next start";
			case REMOVING -> "Removed when you close the game";
			case ON -> entry.isProtected() ? "Always on" : !entry.jar().parsed() ? "Not a Fabric mod" : "";
		};
		if (version.isEmpty() && words.isEmpty()) {
			return entry.file();
		}
		return version.isEmpty() ? words : words.isEmpty() ? version : version + " · " + words;
	}

	private static int captionColor(ModStates.Entry entry) {
		return entry.pending() ? Theme.WARNING : Theme.FAINT;
	}

	private String mineSignature() {
		return ModLibrary.version() + "|" + ModLibrary.ready() + "|" + mineApplied + "|" + ModLibrary.readOnlyReason() + "|" + ModSets.changes()
				+ "|" + ModLibrary.changeCount() + "|" + VanillaMode.version() + "|" + VanillaMode.blockedReason();
	}

	private void buildInstalled() {
		lastMine = this;
		bannerTop = -1;
		vanillaCardY = -1;
		int y = TOP;
		// Downloads this session, with their progress.
		downloadsY = -1;
		if (!jobs.isEmpty()) {
			downloadsY = y;
			if (jobs.stream().anyMatch(j -> !j.active())) {
				add(new FlatButton(50, 14, Component.literal("Clear"), FlatButton.Style.GHOST, null, b -> {
					Downloads.clearFinished();
					screen.refresh();
				}), width - 50, y - 3);
			}
			int rowY = y + 12;
			for (Downloads.Job job : jobs) {
				FlatButton cross = add(new FlatButton(18, 16, Component.literal(job.active() ? "Cancel" : "Dismiss"), FlatButton.Style.GHOST, Icons.CROSS,
						b -> {
							if (job.active()) {
								Downloads.cancel(job);
							} else {
								Downloads.dismiss(job);
							}
							screen.refresh();
						}).iconOnly(), width - 26, rowY + 8);
				cross.setTooltip(Tooltip.create(Draw.styled(job.active() ? "Cancel" : "Dismiss")));
				rowY += JOB_ROW;
			}
			y = rowY + 6 + 8;
		}

		allMods = ModLibrary.entries();
		String readOnly = ModLibrary.readOnlyReason();

		// One bar for everything that waits for the game to close: switches, removals, new mods, a mod set switch.
		pendingY = -1;
		boolean vanillaActive = VanillaMode.active();
		VanillaMode.Staged vanillaStaged = VanillaMode.staged();
		pendingCount = (int) allMods.stream().filter(ModStates.Entry::pending).count() + (ModSets.staged() ? 1 : 0)
				+ (vanillaStaged != VanillaMode.Staged.NONE ? 1 : 0);
		if (pendingCount > 0) {
			pendingY = y;
			int buttonY = y + (PENDING_H - 16) / 2;
			int closeW = Draw.width("Close game") + 16;
			add(new FlatButton(closeW, 16, Component.literal("Close game"), FlatButton.Style.PRIMARY, null, b -> minecraft.stop()),
					width - PAD - closeW, buttonY).setTooltip(tip("Your changes are made as Minecraft closes. Press Play to start again."));
			if (undoable()) {
				int undoW = Draw.width("Undo") + 16;
				add(new FlatButton(undoW, 16, Component.literal("Undo"), FlatButton.Style.SECONDARY, null, b -> undoEverything()),
						width - PAD - closeW - 6 - undoW, buttonY).setTooltip(tip(vanillaStaged == VanillaMode.Staged.ENTER
								? "Stay with your mods. Switches changed before Vanilla mode was chosen stay cancelled."
								: "Keep everything as it is now"));
			}
			y += PENDING_H + 8;
		}

		if (vanillaActive) {
			buildVanillaOn(y, vanillaStaged);
			return;
		}

		// Vanilla mode: play without your mods for a while. On top, so it is easy to find however long the list is.
		vanillaCardY = y;
		String blocked = VanillaMode.blockedReason();
		if (vanillaStaged != VanillaMode.Staged.ENTER) {
			int buttonW = Draw.width("Vanilla mode") + 16;
			FlatButton vanilla = add(new FlatButton(buttonW, 16, Component.literal("Vanilla mode"), FlatButton.Style.SECONDARY, null,
					b -> screen.openPopover(new com.javahost.client.gui.VanillaConfirm(width, screen::refresh), b)),
					width - PAD - buttonW, y + (VANILLA_CARD - 16) / 2);
			vanilla.active = blocked == null;
			vanilla.setTooltip(tip(blocked != null ? blocked : "Every mod but JavaHost is put aside when you close the game. One click brings them back."));
		}
		y += VANILLA_CARD + 8;

		// A count, and a search once the list is long.
		headerY = y;
		headerSearch = allMods.size() > SEARCH_FROM;
		mineField = null;
		if (headerSearch) {
			int fieldW = Math.min(150, width / 2);
			mineField = add(new TextField(fieldW, Component.literal("Search my mods")), width - fieldW, y);
			mineField.setMaxLength(48);
			mineField.setValue(mineQuery);
			mineField.setHint(Component.literal(Draw.fit("Search", fieldW - 14)).withStyle(s -> s.withColor(Theme.FAINT)));
			mineField.setResponder(value -> {
				if (!value.equals(mineQuery)) {
					mineQuery = value;
					mineTypedAt = System.currentTimeMillis();
				}
			});
			if (refocusMine) {
				refocusMine = false;
				screen.focusOn(mineField);
			}
			y += TextField.HEIGHT + 8;
		} else {
			mineQuery = mineApplied = "";
			if (!allMods.isEmpty() || !ModLibrary.ready()) {
				y += 10 + 6;
			}
		}

		// Why the switches can't be used, when they can't.
		readOnlyY = -1;
		readOnlyText = readOnly;
		if (readOnly != null && vanillaStaged != VanillaMode.Staged.ENTER) { // the bar above already says Vanilla mode is coming
			readOnlyY = y;
			y += wrappedHeight(readOnly, width - 14) + 8;
		}

		// The list.
		listY = y;
		emptyListY = -1;
		shownMods = ModStates.search(allMods, headerSearch ? mineApplied : "");
		if (shownMods.isEmpty()) {
			emptyListY = y;
			y += 44;
		}
		int rowY = y + LIST_PAD;
		for (ModStates.Entry entry : shownMods) {
			buildModRow(entry, rowY, readOnly);
			rowY += MOD_ROW;
		}
		y = shownMods.isEmpty() ? y + 16 : rowY + LIST_PAD + 16;


		mineY = y;
		buildMine();
	}

	private boolean undoable() {
		return ModLibrary.changeCount() > 0 || allMods.stream().anyMatch(e -> e.state() == ModStates.State.REMOVING) || ModSets.staged()
				|| VanillaMode.staged() != VanillaMode.Staged.NONE;
	}

	/** Vanilla mode is on: one calm card with "Bring my mods back", and the mods put aside, read-only, under it. */
	private void buildVanillaOn(int y, VanillaMode.Staged staged) {
		headerY = -1;
		readOnlyY = -1;
		emptyListY = -1;
		shownMods = List.of();
		mineField = null;
		vanillaCardY = -1;
		bannerTop = y;
		if (staged != VanillaMode.Staged.LEAVE) {
			int buttonW = Draw.width("Bring my mods back") + 16;
			add(new FlatButton(buttonW, 18, Component.literal("Bring my mods back"), FlatButton.Style.PRIMARY, null, b -> bringBack()),
					width - PAD - buttonW, y + (BANNER_H - 18) / 2);
		}
		y += BANNER_H + 16;
		asideY = y;
		aside = VanillaMode.putAside();
		y += 12 + (aside.isEmpty() ? 44 : aside.size() * ASIDE_ROW + 2 * LIST_PAD) + 16;
		mineY = y;
		buildMine();
	}

	private void bringBack() {
		try {
			VanillaMode.stageLeave();
		} catch (java.io.IOException e) {
			JavaHost.LOGGER.warn("[Mods] couldn't stage bringing the mods back", e);
		}
		screen.refresh();
	}

	private void buildModRow(ModStates.Entry entry, int rowY, String readOnly) {
		int right = width - PAD;
		if (entry.state() == ModStates.State.REMOVING) {
			FlatButton keep = add(new FlatButton(KEEP_W, 16, Component.literal("Keep"), FlatButton.Style.SECONDARY, null, b -> {
				try {
					ModLibrary.keep(entry);
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't keep {}", entry.name(), e);
				}
			}), right - KEEP_W, rowY + (MOD_ROW - 16) / 2);
			keep.setTooltip(tip("Don't remove " + entry.name()));
			return;
		}
		// A little wider than the switch so it's easy to hit; it draws only the switch, at its right end.
		ToggleSwitch[] holder = new ToggleSwitch[1];
		ToggleSwitch toggle = add(new ToggleSwitch(SWITCH_W + 6, Component.literal(entry.name()), null, entry.wantedOn(),
				on -> flip(entry, on, holder[0])).bare(), right - SWITCH_W - 6, rowY + (MOD_ROW - 16) / 2);
		holder[0] = toggle;
		toggle.active = readOnly == null && !entry.isProtected();
		if (entry.isProtected()) {
			toggle.setTooltip(tip("Always on: " + entry.protectedReason()));
		} else if (readOnly != null) {
			toggle.setTooltip(tip(readOnly));
		}
		FlatButton more = add(new FlatButton(MORE_W, 16, Component.literal("More for " + entry.name()), FlatButton.Style.GHOST, Icons.MORE,
				b -> openMore(b, entry, readOnly)).iconOnly(), right - SWITCH_W - 6 - MORE_W, rowY + (MOD_ROW - 16) / 2);
		more.setTooltip(tip("More"));
	}

	/** A row's menu: Remove (mods JavaHost installed) and Show in folder. */
	private void openMore(FlatButton b, ModStates.Entry entry, String readOnly) {
		List<com.javahost.client.gui.ui.Popover.Item> items = new ArrayList<>();
		if (entry.installedByJavaHost()) {
			com.javahost.client.gui.ui.Popover.Item remove = com.javahost.client.gui.ui.Popover.Item.danger("Remove", Icons.TRASH, () -> {
				try {
					ModLibrary.remove(entry);
					com.javahost.client.Toasts.show("remove-" + entry.file(), entry.name() + " will be removed", "When you close Minecraft.");
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't remove {}", entry.name(), e);
				}
			});
			String why = whyNotRemove(entry, readOnly);
			items.add(why == null ? remove : remove.disabled(why));
		}
		items.add(com.javahost.client.gui.ui.Popover.Item.of("Show in folder", Icons.FOLDER,
				() -> com.javahost.client.friends.Screenshots.reveal(ModLibrary.pathOf(entry))));
		screen.openPopover(com.javahost.client.gui.ui.Popover.menu(items), b);
	}

	/** Why a mod JavaHost installed can't be removed now (a short note for its menu), or null. */
	private static String whyNotRemove(ModStates.Entry entry, String readOnly) {
		if (readOnly != null) {
			return "Not now";
		}
		if (entry.isProtected()) {
			return "Always on";
		}
		if (!entry.inMods()) {
			return "Switch it on first";
		}
		ModLibrary.Ask ask = ModLibrary.ask(entry.file(), false);
		if (entry.wantedOn() && ask.ok() && !ask.others().isEmpty()) {
			return ask.others().getFirst() + " needs it";
		}
		return null;
	}

	/** A switch was flipped: make the change, ask first if it takes other mods with it, or say why not. */
	private void flip(ModStates.Entry entry, boolean on, ToggleSwitch toggle) {
		ModLibrary.Ask ask = ModLibrary.ask(entry.file(), on);
		if (!ask.ok()) {
			toggle.setValue(!on);
			refusedFile = entry.file();
			refusedReason = ask.refusal();
			refusedAt = System.currentTimeMillis();
			return;
		}
		if (ask.needsConfirm()) {
			toggle.setValue(!on); // not yet: only once confirmed
			ConfirmPopover popover = new ConfirmPopover(entry.name(), ask);
			confirming = popover;
			screen.openPopover(popover, toggle);
			return;
		}
		ModLibrary.apply(ask);
		screen.refresh();
	}

	private void undoEverything() {
		ModLibrary.undoAll();
		VanillaMode.cancelStaged();
		for (ModStates.Entry entry : allMods) {
			if (entry.state() == ModStates.State.REMOVING) {
				try {
					com.javahost.client.mods.Removals.keep(entry.file(), entry.slug());
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't keep {}", entry.name(), e);
				}
			}
		}
		if (ModSets.staged()) {
			ModSets.cancelStaged();
		}
		ModLibrary.refresh();
		screen.refresh();
	}

	/**
	 * Asks before switching more than the clicked mod: "Turn off Cozylib?" with what goes with it, Cancel and the
	 * action. Drawn by hand (popovers take clicks before the page's widgets).
	 */
	private final class ConfirmPopover extends com.javahost.client.gui.ui.Popover {
		static final int PAD = 8;
		static final int BUTTON_W = 64;
		private final ModLibrary.Ask ask;
		private final String title;
		private final String detail;
		private final String action;
		private final int w;
		private final List<net.minecraft.util.FormattedCharSequence> lines;

		ConfirmPopover(String name, ModLibrary.Ask ask) {
			this.ask = ask;
			this.title = (ask.on() ? "Turn on " : "Turn off ") + name + "?";
			List<String> others = ask.others();
			StringBuilder text = new StringBuilder();
			if (!others.isEmpty()) {
				text.append(ask.on() ? "Also turns on: " : "Also turns off: ").append(list(others));
			}
			if (!ask.missing().isEmpty()) {
				text.append(text.length() == 0 ? "" : " ").append("It needs ").append(list(ask.missing()))
						.append(ask.missing().size() == 1 ? ", which isn't installed." : ", which aren't installed.");
			}
			this.detail = text.toString();
			this.action = ask.on() ? "Turn on" : "Turn off";
			this.w = Math.min(Math.max(160, ModsPage.this.width - 40), 220);
			List<net.minecraft.util.FormattedCharSequence> split = Draw.split(detail, w - 2 * PAD);
			this.lines = split.size() > 4 ? split.subList(0, 4) : split;
		}

		private static String list(List<String> names) {
			if (names.size() <= 4) {
				return String.join(", ", names);
			}
			return String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
		}

		@Override
		public int width() {
			return w;
		}

		@Override
		public int height() {
			return PAD + 10 + 4 + lines.size() * 10 + 8 + 16 + PAD;
		}

		private boolean over(double mouseX, double mouseY, int bx, int by) {
			return mouseX >= bx && mouseX < bx + BUTTON_W && mouseY >= by && mouseY < by + 16;
		}

		@Override
		protected void drawContents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
			Draw.textFit(g, title, w - 2 * PAD, x + PAD, y + PAD, Theme.TEXT);
			int lineY = y + PAD + 14;
			for (net.minecraft.util.FormattedCharSequence line : lines) {
				Draw.text(g, line, x + PAD, lineY, Theme.MUTED);
				lineY += 10;
			}
			int by = y + height() - PAD - 16;
			int confirmX = x + w - PAD - BUTTON_W;
			int cancelX = confirmX - 6 - BUTTON_W;
			boolean overCancel = over(mouseX, mouseY, cancelX, by);
			boolean overConfirm = over(mouseX, mouseY, confirmX, by);
			Draw.bordered(g, cancelX, by, BUTTON_W, 16, 4, overCancel ? Theme.CARD_HOVER : Theme.CARD, Theme.BORDER);
			Draw.centered(g, "Cancel", cancelX + BUTTON_W / 2, by + 4, Theme.TEXT);
			Draw.roundRect(g, confirmX, by, BUTTON_W, 16, 4, overConfirm ? Theme.ACCENT_HOVER : Theme.ACCENT);
			Draw.centered(g, action, confirmX + BUTTON_W / 2, by + 4, Theme.ON_ACCENT);
		}

		@Override
		public boolean click(double mouseX, double mouseY) {
			int by = y + height() - PAD - 16;
			int confirmX = x + w - PAD - BUTTON_W;
			if (over(mouseX, mouseY, confirmX, by)) {
				ModLibrary.apply(ask);
				confirming = null;
				screen.refresh();
				return true;
			}
			if (over(mouseX, mouseY, confirmX - 6 - BUTTON_W, by)) {
				confirming = null;
				return true;
			}
			return false;
		}
	}

	private static Tooltip tip(String text) {
		return Tooltip.create(Draw.styled(text));
	}

	private void drawInstalled(GuiGraphicsExtractor g, int left, int top) {
		if (com.javahost.client.gui.VanillaConfirm.openForTests() != null && !screen.popoverOpen()) {
			com.javahost.client.gui.VanillaConfirm.forget(); // closed by a click outside, Esc or scrolling
		}
		if (confirming != null && !screen.popoverOpen()) {
			confirming = null; // closed by a click outside, Esc or scrolling
		}
		if (downloadsY >= 0) {
			sectionLabel(g, "Downloads", left, top + downloadsY);
			int y = top + downloadsY + 12;
			card(g, left, y - 2, width, jobs.size() * JOB_ROW + 4);
			for (int i = 0; i < jobs.size(); i++) {
				Downloads.Job job = jobs.get(i);
				if (i > 0) {
					g.fill(left + 8, y - 1, left + width - 8, y, Theme.withAlpha(Theme.BORDER, 0.6f));
				}
				WebIcons.draw(g, job.iconUrl, job.title, left + 8, y + 6, 20);
				int textX = left + 36;
				int textW = width - 36 - 32;
				Draw.textFit(g, job.title, textW, textX, y + 5, Theme.TEXT);
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
				Draw.textFit(g, status, textW, textX, y + 15, color);
				if (job.active()) {
					progressBar(g, textX, y + 26, textW, 2, job.progress());
				}
				y += JOB_ROW;
			}
		}

		if (pendingY >= 0) {
			int y = top + pendingY;
			Draw.panel(g, left, y, width, PENDING_H, 6, Theme.withAlpha(Theme.ACCENT, 0.12f));
			int buttons = (Draw.width("Close game") + 16) + (undoable() ? Draw.width("Undo") + 16 + 6 : 0);
			int textW = width - 2 * PAD - buttons - 8;
			VanillaMode.Staged staged = VanillaMode.staged();
			String title = staged == VanillaMode.Staged.ENTER ? "Vanilla mode" : staged == VanillaMode.Staged.LEAVE ? "Your mods come back"
					: pendingTitle(pendingCount);
			String detail = "When you close the game";
			Draw.textFit(g, title, textW, left + PAD, y + 7, Theme.TEXT);
			Draw.textFit(g, detail, textW, left + PAD, y + 18, Theme.MUTED);
		}

		if (bannerTop >= 0) {
			drawVanillaOn(g, left, top);
			drawMine(g, left, top);
			return;
		}

		// The count (and the search beside it).
		int headerTextY = top + headerY + (headerSearch ? (TextField.HEIGHT - 8) / 2 : 0);
		long off = allMods.stream().filter(e -> !e.inMods()).count();
		String count = !ModLibrary.ready() ? "Reading your mods..." : allMods.isEmpty() ? "" : Draw.count(allMods.size(), "mod") + (off > 0 ? " · " + off + " off" : "");
		int countW = headerSearch ? width - Math.min(150, width / 2) - 8 : width;
		Draw.textFit(g, count, countW, left, headerTextY, Theme.MUTED);

		if (readOnlyY >= 0) {
			Icons.LOCK.draw(g, left, top + readOnlyY - 1, Theme.MUTED);
			wrap(g, readOnlyText, left + 14, top + readOnlyY, width - 14, Theme.MUTED);
		}

		int screenH = minecraft.getWindow().getGuiScaledHeight();
		if (emptyListY >= 0) {
			int y = top + emptyListY;
			card(g, left, y, width, 44);
			String title = !ModLibrary.ready() ? "Reading your mods..." : allMods.isEmpty() ? "No mods yet" : "No mods match \"" + mineApplied.strip() + "\"";
			String hint = !ModLibrary.ready() ? "" : allMods.isEmpty() ? "Find some in Discover" : "Try another word";
			centredFit(g, title, left, y + (hint.isEmpty() ? 18 : 12), Theme.MUTED);
			if (!hint.isEmpty()) {
				centredFit(g, hint, left, y + 24, Theme.FAINT);
			}
		} else {
			int y = top + listY;
			int h = shownMods.size() * MOD_ROW + 2 * LIST_PAD;
			// Only the part of the card that's on screen (a long list is mostly off it).
			int visibleTop = Math.max(y, -MOD_ROW);
			int visibleBottom = Math.min(y + h, screenH + MOD_ROW);
			if (visibleBottom > visibleTop) {
				card(g, left, y, width, h);
			}
			long now = System.currentTimeMillis();
			for (int i = 0; i < shownMods.size(); i++) {
				int rowY = y + LIST_PAD + i * MOD_ROW;
				if (rowY > screenH || rowY + MOD_ROW < 0) {
					continue; // off screen: nothing to draw
				}
				if (i > 0) {
					g.fill(left + 8, rowY, left + width - 8, rowY + 1, Theme.withAlpha(Theme.BORDER, 0.6f));
				}
				drawModRow(g, shownMods.get(i), left, rowY, now);
			}
		}
		if (vanillaCardY >= 0) {
			int y = top + vanillaCardY;
			card(g, left, y, width, VANILLA_CARD);
			String blocked = VanillaMode.blockedReason();
			boolean staged = VanillaMode.staged() == VanillaMode.Staged.ENTER;
			int textW = width - 2 * PAD - (staged ? 0 : Draw.width("Vanilla mode") + 16 + 8);
			Draw.textFit(g, "Play without your mods", textW, left + PAD, y + 7, Theme.TEXT);
			Draw.textFit(g, staged ? "Starts when you close the game" : blocked != null ? blocked : "Put aside until you want them back",
					textW, left + PAD, y + 18, staged ? Theme.WARNING : Theme.FAINT);
		}
		drawMine(g, left, top);
	}

	private void drawVanillaOn(GuiGraphicsExtractor g, int left, int top) {
		int y = top + bannerTop;
		boolean leaving = VanillaMode.staged() == VanillaMode.Staged.LEAVE;
		Draw.panel(g, left, y, width, BANNER_H, 6, Theme.withAlpha(Theme.INFO, 0.12f));
		int textW = width - 2 * PAD - (leaving ? 0 : Draw.width("Bring my mods back") + 16 + 8);
		Draw.textFit(g, "Vanilla mode is on", textW, left + PAD, y + 8, Theme.TEXT);
		int count = aside.size();
		Draw.textFit(g, leaving ? "Your mods come back when you close the game" : Draw.count(count, "mod") + (count == 1 ? " is" : " are") + " put aside",
				textW, left + PAD, y + 20, leaving ? Theme.WARNING : Theme.MUTED);

		y = top + asideY;
		sectionLabel(g, "Put aside", left, y);
		y += 12;
		if (aside.isEmpty()) {
			card(g, left, y, width, 44);
			centredFit(g, "Nothing to bring back", left, y + 12, Theme.MUTED);
			centredFit(g, "The folder was emptied by hand", left, y + 24, Theme.FAINT);
			return;
		}
		card(g, left, y, width, aside.size() * ASIDE_ROW + 2 * LIST_PAD);
		int screenH = minecraft.getWindow().getGuiScaledHeight();
		int rowY = y + LIST_PAD;
		for (int i = 0; i < aside.size(); i++) {
			if (rowY < screenH && rowY + ASIDE_ROW > 0) {
				if (i > 0) {
					g.fill(left + 8, rowY, left + width - 8, rowY + 1, Theme.withAlpha(Theme.BORDER, 0.6f));
				}
				com.javahost.util.ModJar jar = aside.get(i);
				String version = jar.version().isEmpty() ? "" : jar.version();
				int nameW = Draw.width(Draw.fit(jar.name(), width - 2 * PAD - 60));
				Draw.textFit(g, jar.name(), width - 2 * PAD - 60, left + PAD, rowY + 7, Theme.MUTED);
				if (!version.isEmpty()) {
					Draw.textFit(g, version, width - 2 * PAD - nameW - 8, left + PAD + nameW + 8, rowY + 7, Theme.FAINT);
				}
			}
			rowY += ASIDE_ROW;
		}
	}

	private void drawModRow(GuiGraphicsExtractor g, ModStates.Entry entry, int left, int y, long now) {
		int controls = entry.state() == ModStates.State.REMOVING ? KEEP_W : SWITCH_W + 6 + MORE_W;
		int textW = width - 2 * PAD - controls - 8;
		boolean shownOn = entry.wantedOn() && entry.state() != ModStates.State.REMOVING;
		Draw.textFit(g, entry.name(), textW, left + PAD, y + 5, shownOn ? Theme.TEXT : Theme.MUTED);
		String caption = caption(entry);
		int color = captionColor(entry);
		if (entry.file().equals(refusedFile) && now - refusedAt < REFUSED_SHOWN) {
			caption = refusedReason;
			color = Theme.WARNING;
		}
		Draw.textFit(g, caption, textW, left + PAD, y + 15, color);
	}

	private void centredFit(GuiGraphicsExtractor g, String text, int left, int y, int color) {
		String shown = Draw.fit(text, width - 2 * PAD);
		Draw.text(g, shown, left + (width - Draw.width(shown)) / 2, y, color);
	}

	private void buildMine() {
		saved = ModSets.saved().stream().limit(MAX_SAVED).toList();
		// Performance mods have their own page (under More): a quiet link on the "In use" line.
		add(new FlatButton(96, 14, Component.literal("Performance"), FlatButton.Style.GHOST, Icons.BOLT,
				b -> screen.switchTo(com.javahost.client.gui.JavaHostScreen.Tab.PERFORMANCE)), width - 96, mineY - 4)
				.setTooltip(tip("Mods and settings for a higher frame rate"));
		boolean active = ModSets.activeLabel() != null;
		if (ModSets.staged()) {
			add(new FlatButton(70, 16, Component.literal("Cancel"), FlatButton.Style.SECONDARY, null, b -> {
				ModSets.cancelStaged();
				screen.refresh();
			}), width - 80, mineY + 22);
		} else if (active) {
			add(new FlatButton(108, 16, Component.literal("Back to my mods"), FlatButton.Style.SECONDARY, Icons.BACK, b -> {
				try {
					ModSets.stageRestore();
					com.javahost.client.Toasts.notify("Back to your own mods", "This happens when you close Minecraft.");
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't stage going back", e);
				}
				screen.refresh();
			}), width - 118, mineY + 22);
		}

		savedY = mineY + 12 + 44 + 52 + 12;
		int y = savedY + 14;
		for (ModSets.Saved set : saved) {
			boolean inUse = set.folder().equals(ModSets.activeFolder());
			FlatButton use = add(new FlatButton(46, 16, Component.literal(inUse ? "In use" : "Use"), FlatButton.Style.SECONDARY, null, b -> {
				try {
					ModSets.stageSwitch(set.folder());
					com.javahost.client.Toasts.notify("Switching to " + set.label(), "This happens when you close Minecraft.");
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't stage the switch", e);
				}
				screen.refresh();
			}), width - 80, y + 4);
			use.active = !inUse && !ModSets.staged() && ModSets.vanillaReason() == null;
			if (ModSets.vanillaReason() != null && !inUse) {
				use.setTooltip(tip(ModSets.vanillaReason()));
			}
			FlatButton delete = add(new FlatButton(22, 16, Component.literal("Delete"), FlatButton.Style.GHOST, Icons.TRASH, b -> {
				try {
					ModSets.delete(set);
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't delete {}", set.folder(), e);
				}
				screen.refresh();
			}).iconOnly(), width - 30, y + 4);
			delete.active = !inUse;
			y += ROW;
		}
		if (saved.isEmpty()) {
			y += ROW;
		}
		reserve(y + 24);
	}

	private void drawMine(GuiGraphicsExtractor g, int left, int top) {
		int base = top + mineY;
		// In use.
		sectionLabel(g, "In use", left, base);
		card(g, left, base + 12, width, 36);
		String active = ModSets.activeLabel();
		Icons.PACKAGE.draw(g, left + 10, base + 23, Theme.ACCENT);
		Draw.textFit(g, active == null ? "Your own mods" : active, width - 150, left + 28, base + 18, Theme.TEXT);
		String staged = ModSets.stagedLabel();
		if (staged != null) {
			Draw.textFit(g, "Switches to " + staged + " when Minecraft closes", width - 120, left + 28, base + 30, Theme.WARNING);
		} else {
			int count = loadedModCount();
			Draw.textFit(g, Draw.count(count, "mod") + " loaded" + (active == null ? "" : " · your own are kept safe"), width - 150, left + 28,
					base + 30, Theme.MUTED);
		}

		// What friends need to join you.
		sectionLabel(g, "Friends joining your world need", left, base + 56);
		card(g, left, base + 68, width, 40);
		ModManifest mine = ModSets.mine();
		if (mine == null) {
			Draw.textFit(g, "Checking your mods...", width - 20, left + 10, base + 78, Theme.FAINT);
		} else if (mine.empty()) {
			Icons.CHECK.draw(g, left + 10, base + 78, Theme.ACCENT);
			Draw.textFit(g, "Nothing: your mods only change your game.", width - 40, left + 28, base + 79, Theme.TEXT);
			Draw.textFit(g, "Anyone with JavaHost can join.", width - 40, left + 28, base + 91, Theme.FAINT);
		} else {
			long notOnModrinth = mine.mods().stream().filter(m -> !m.onModrinth()).count();
			Icons.USERS.draw(g, left + 10, base + 78, Theme.ACCENT);
			Draw.textFit(g, Draw.count(mine.mods().size(), "mod") + ": " + String.join(", ", mine.mods().stream().map(ModManifest.Entry::name).toList()),
					width - 40, left + 28, base + 79, Theme.TEXT);
			Draw.textFit(g, notOnModrinth == 0 ? "JavaHost downloads them for friends when they join."
					: notOnModrinth + " aren't on Modrinth: friends need to get those themselves.", width - 40, left + 28, base + 91,
					notOnModrinth == 0 ? Theme.FAINT : Theme.WARNING);
		}

		// Saved sets.
		sectionLabel(g, "Saved mod sets", left, top + savedY);
		int y = top + savedY + 14;
		card(g, left, y - 2, width, Math.max(1, saved.size()) * ROW + 4);
		if (saved.isEmpty()) {
			Draw.textFit(g, "Friends' mods and modpacks you install show up here.", width - 20, left + 10, y + 8, Theme.FAINT);
		}
		for (ModSets.Saved set : saved) {
			(set.kind().equals("pack") ? Icons.PACKAGE : Icons.USERS).draw(g, left + 10, y + 6, Theme.MUTED);
			Draw.textFit(g, set.label(), width - 130, left + 28, y + 3, Theme.TEXT);
			Draw.textFit(g, (set.kind().equals("pack") ? "Modpack · " : "From a friend's world · ") + Draw.count(set.mods(), "mod")
					+ String.format(" · %.0f MB", set.size() / 1048576.0), width - 130, left + 28, y + 13, Theme.FAINT);
			y += ROW;
		}
	}

	/** Mods loaded from their own jar (not bundled inside another). Fixed for the session, so counted once. */
	private static int loadedModCount() {
		if (loadedMods < 0) {
			loadedMods = (int) net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream()
					.filter(m -> m.getContainingMod().isEmpty() && com.javahost.mods.ModScanner.jarOf(m) != null).count();
		}
		return loadedMods;
	}

	private static int loadedMods = -1;

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (detail != null) {
			drawDetail(g, left, top);
		} else if (view == View.DISCOVER) {
			drawDiscover(g, left, top);
		} else {
			drawInstalled(g, left, top);
		}
	}
}

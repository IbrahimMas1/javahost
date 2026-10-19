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
import com.javahost.client.mods.ModSets;
import com.javahost.client.mods.Modpacks;
import com.javahost.client.perf.BoostMods;
import com.javahost.util.Availability;
import com.javahost.util.IoExecutor;
import com.javahost.util.ModManifest;
import com.javahost.util.Modrinth;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

/**
 * Mods, laid out like a store. <b>Discover</b>: search Modrinth for mods and modpacks for every Minecraft version, by
 * category; ones that can't run here say what they run on. A card opens the project's page in the menu, with Install.
 * <b>Installed</b>: downloads, mods JavaHost installed, which mods are in use, what friends need, and saved mod sets.
 */
public class ModsPage extends Page {
	private static final int ROW = 24;
	private static final int CARD = 40;
	private static final int JOB_ROW = 32;
	private static final int INSTALLED_ROW = 22;
	private static final int MAX_SAVED = 4;
	private static final int TOP = 26; // below the Discover / Installed switch
	private static final int BANNER = 30;
	/** Width of the right-hand column of a card (Install, or where it runs). */
	private static final int COLUMN = 72;
	/** The detail view's header card. */
	private static final int HERO = 58;
	private static final long TYPING_PAUSE = 400;

	private enum View { DISCOVER, INSTALLED }

	/** Where a project stands here: what its Install button says. */
	private enum State { INSTALL, QUEUED, DOWNLOADING, FAILED, RESTART, INSTALLED, READY, UNAVAILABLE }

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
	private int bannerY = -1;
	private int resultsY;
	private int installedY;
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

	/** For tests and links from elsewhere: open on Installed instead of Discover. */
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
		installed = BoostMods.installedByJavaHost();
		jobs = Downloads.jobs();
		lastSignature = signature();
		if (detail != null) {
			buildDetail();
			return;
		}
		int count = installed.size() + (int) jobs.stream().filter(Downloads.Job::active).count();
		add(new Segmented<>(width, List.of(View.DISCOVER, View.INSTALLED), view,
				v -> v == View.DISCOVER ? "Discover" : count > 0 ? "Installed (" + count + ")" : "Installed", v -> {
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
				+ "|" + ModSets.activeLabel() + "|" + ModSets.stagedLabel() + "|" + (ModSets.mine() != null) + "|" + ModSets.saved().size();
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
		if (!signature().equals(lastSignature)) {
			refocus = searchField != null && searchField.isFocused(); // keep typing after the results update
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
				if (state == State.RESTART) {
					button.setTooltip(Tooltip.create(Component.literal("Downloaded. Minecraft loads mods when it starts, so close it and press Play to use this.")));
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
			case READY -> new FlatButton(mainW, 20, Component.literal("Use it from Installed"), FlatButton.Style.SECONDARY, Icons.PACKAGE, b -> {
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
		String name = Draw.fit(project.title(), (int) (textW / 1.25f));
		Draw.scaledText(g, name, textX, top + 9, 1.25f, Theme.TEXT);
		Draw.textFit(g, downloads(project.downloads()) + (project.author().isEmpty() ? "" : " · by " + project.author()), textW, textX, top + 23,
				Theme.MUTED);
		int chipX = textX;
		for (String c : project.categories()) {
			String label = c.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + c.substring(1).replace('-', ' ');
			int w = Draw.font().width(label) + 10;
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
			case FAILED -> {
				Downloads.Job job = Downloads.job(project.id());
				yield job == null ? "Couldn't install it." : job.statusText();
			}
			case UNAVAILABLE -> "Pick that version in the JavaHost installer to play it.";
			case READY -> "Switch to it from Installed, then restart.";
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

	// ================================================================ Installed

	private int downloadsY = -1;

	private void buildInstalled() {
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
				cross.setTooltip(Tooltip.create(Component.literal(job.active() ? "Cancel" : "Dismiss")));
				rowY += JOB_ROW;
			}
			y = rowY + 6;
		}
		bannerY = -1;
		if (restartPending()) {
			bannerY = y;
			add(new FlatButton(98, 16, Component.literal("Close Minecraft"), FlatButton.Style.PRIMARY, null, b -> minecraft.stop()), width - 106, y + 4);
			y += BANNER;
		}

		// Mods JavaHost installed, each with Remove (or Keep, once it's marked to go).
		installedY = y;
		java.util.Set<String> removing = com.javahost.client.mods.Removals.pendingJars();
		int rowY = y + 12;
		for (BoostMods.Installed mod : installed) {
			if (removing.contains(mod.jarName())) {
				FlatButton keep = add(new FlatButton(46, 16, Component.literal("Keep"), FlatButton.Style.SECONDARY, null, b -> {
					try {
						com.javahost.client.mods.Removals.keep(mod);
					} catch (java.io.IOException e) {
						JavaHost.LOGGER.warn("[Mods] couldn't keep {}", mod.name(), e);
					}
					screen.refresh();
				}), width - 52, rowY + 3);
				keep.setTooltip(Tooltip.create(Component.literal("Don't remove " + mod.name())));
			} else {
				String why = ModSets.staged() ? "Wait until your mod set switch is done" : BoostMods.cannotRemove(mod, installed, removing);
				FlatButton remove = add(new FlatButton(20, 16, Component.literal("Remove"), FlatButton.Style.GHOST, Icons.TRASH, b -> {
					try {
						com.javahost.client.mods.Removals.remove(mod);
						com.javahost.client.Toasts.show("remove-" + mod.slug(), mod.name() + " will be removed", "When you close Minecraft.");
					} catch (java.io.IOException e) {
						JavaHost.LOGGER.warn("[Mods] couldn't remove {}", mod.name(), e);
					}
					screen.refresh();
				}).iconOnly(), width - 26, rowY + 3);
				remove.active = why == null;
				remove.setTooltip(Tooltip.create(Component.literal(why != null ? "Can't remove: " + why : "Remove " + mod.name())));
			}
			rowY += INSTALLED_ROW;
		}
		y += 12 + Math.max(1, installed.size()) * INSTALLED_ROW + 4 + 10;

		mineY = y;
		buildMine();
	}

	private void drawInstalled(GuiGraphicsExtractor g, int left, int top) {
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
		if (bannerY >= 0) {
			restartBanner(g, left, top + bannerY);
		}

		int y = top + installedY;
		sectionLabel(g, "Installed with JavaHost", left, y);
		card(g, left, y + 10, width, Math.max(1, installed.size()) * INSTALLED_ROW + 4);
		y += 12;
		if (installed.isEmpty()) {
			Draw.textFit(g, "Mods you install from Discover show up here.", width - 20, left + 10, y + 7, Theme.FAINT);
		}
		java.util.Set<String> removing = com.javahost.client.mods.Removals.pendingJars();
		for (BoostMods.Installed mod : installed) {
			boolean leaving = removing.contains(mod.jarName());
			Icons.PACKAGE.draw(g, left + 10, y + 5, Theme.MUTED);
			String badge = leaving ? "Removed on restart" : mod.active() ? "Active" : "Restart needed";
			int bw = Draw.font().width(badge) + 10;
			int buttonW = leaving ? 52 : 28;
			Draw.textFit(g, mod.name(), width - 40 - bw - buttonW, left + 28, y + 7, leaving ? Theme.MUTED : Theme.TEXT);
			int bx = left + width - bw - 4 - buttonW;
			int tint = leaving ? Theme.FAINT : mod.active() ? Theme.ACCENT : Theme.WARNING;
			Draw.roundRect(g, bx, y + 4, bw, 12, 6, Theme.withAlpha(tint, 0.18f));
			Draw.text(g, badge, bx + 5, y + 6, tint);
			y += INSTALLED_ROW;
		}
		drawMine(g, left, top);
	}

	private void buildMine() {
		saved = ModSets.saved().stream().limit(MAX_SAVED).toList();
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
			use.active = !inUse && !ModSets.staged();
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
			int count = (int) net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream()
					.filter(m -> m.getContainingMod().isEmpty() && com.javahost.mods.ModScanner.jarOf(m) != null).count();
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

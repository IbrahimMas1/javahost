package com.javahost.client.gui.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.javahost.JavaHost;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.RowButton;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.WebIcons;
import com.javahost.client.mods.ModSets;
import com.javahost.client.mods.Modpacks;
import com.javahost.client.perf.BoostMods;
import com.javahost.util.Availability;
import com.javahost.util.IoExecutor;
import com.javahost.util.ModManifest;
import com.javahost.util.Modrinth;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

/**
 * Mods: <b>Browse</b> mods and modpacks from Modrinth for every Minecraft version (ones that can't run here say what
 * they run on), and <b>My mods</b>: which mods are in use, what friends need to join you, and saved mod sets.
 */
public class ModsPage extends Page {
	private static final int ROW = 24;
	private static final int CARD = 40;
	private static final int MAX_SAVED = 4;
	private static final int TOP = 26; // below the Browse / My mods switch
	private static final int BANNER = 30;
	/** Width of the right-hand column of a card (Install, or where it runs). */
	private static final int COLUMN = 72;

	private enum View { BROWSE, MINE }

	private static View view = View.BROWSE;
	private static Modrinth.Kind kind = Modrinth.Kind.MOD;
	private static volatile List<Modrinth.Project> results = List.of();
	private static volatile boolean searching;
	private static volatile boolean more;
	private static volatile String searchProblem = "";
	private static volatile int searchVersion;
	private static String query = "";
	private static String searchedFor;
	/** What's happening with each project the player pressed Install on (project id to status line). */
	private static final Map<String, String> STATUS = new ConcurrentHashMap<>();
	private static volatile boolean installing;

	private List<ModSets.Saved> saved = List.of();
	private int savedY;
	private String lastSignature = "";
	private TextField searchField;

	@Override
	public String title() {
		return "Mods";
	}

	@Override
	public String subtitle() {
		return "Find mods, then play them with friends.";
	}

	/** For tests and links from elsewhere: open Browse with this search (it runs when the page opens). */
	public static void showSearch(Modrinth.Kind searchKind, String text) {
		view = View.BROWSE;
		kind = searchKind;
		query = text;
		searchedFor = null;
	}

	/** For tests and links from elsewhere: open on My mods instead of Browse. */
	public static void showMine(boolean mine) {
		view = mine ? View.MINE : View.BROWSE;
	}

	@Override
	protected void build() {
		lastSignature = signature();
		add(new Segmented<>(width, List.of(View.BROWSE, View.MINE), view, v -> v == View.BROWSE ? "Browse" : "My mods", v -> {
			view = v;
			screen.refresh();
		}), 0, 0);
		if (view == View.BROWSE) {
			buildBrowse();
		} else {
			buildMine();
		}
	}

	private String signature() {
		return view + "|" + kind + "|" + searchVersion + "|" + searching + "|" + installing + "|" + STATUS + "|" + ModSets.activeLabel() + "|"
				+ ModSets.stagedLabel() + "|" + (ModSets.mine() != null) + "|" + ModSets.saved().size();
	}

	@Override
	public void tick() {
		if (!signature().equals(lastSignature) && !(searchField != null && searchField.isFocused())) {
			screen.refresh();
		}
	}

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (searchField != null && searchField.isFocused() && isEnter(event)) {
			search(false);
			return true;
		}
		return false;
	}

	// ================================================================ Browse

	/** Narrow windows (big GUI scale): shorter labels and an icon-only Search button. */
	private boolean narrow() {
		return width < 330;
	}

	private void buildBrowse() {
		int kindW = narrow() ? 92 : Math.min(130, width / 3);
		add(new Segmented<>(kindW, List.of(Modrinth.Kind.MOD, Modrinth.Kind.MODPACK), kind, k -> k == Modrinth.Kind.MOD ? "Mods" : "Packs", k -> {
			kind = k;
			search(false);
			screen.refresh();
		}), 0, TOP);
		int buttonW = narrow() ? 20 : 64;
		int searchW = width - kindW - 6 - buttonW - 6;
		searchField = add(new TextField(searchW, Component.literal("Search Modrinth")), kindW + 6, TOP - 1);
		searchField.setValue(query);
		searchField.setHint(Component.literal(Draw.fit("Search Modrinth", searchW - 10)).withStyle(s -> s.withColor(Theme.FAINT)));
		searchField.setResponder(value -> query = value);
		FlatButton searchButton = add(new FlatButton(buttonW, 20, Component.literal("Search"), FlatButton.Style.SECONDARY, Icons.GLOBE,
				b -> search(false)), width - buttonW, TOP - 1);
		if (narrow()) {
			searchButton.iconOnly();
		}

		int y = TOP + 28;
		// Downloaded mods only start working after a restart (Minecraft loads mods once, at startup).
		if (STATUS.values().stream().anyMatch(s -> s.startsWith("Restart"))) {
			add(new FlatButton(98, 16, Component.literal("Close Minecraft"), FlatButton.Style.PRIMARY, null, b -> minecraft.stop()),
					width - 106, y + 7);
			y += BANNER;
		}
		String minecraft = Modpacks.gameVersion();
		for (Modrinth.Project project : results) {
			int rowY = y;
			// The whole card opens the project's page; the button on the right installs.
			add(new RowButton(width - COLUMN - 4, CARD - 2, Component.literal(project.title()), b -> ConfirmLinkScreen.confirmLinkNow(screen,
					java.net.URI.create(project.page()))), 0, rowY + 1);
			if (Availability.installable(project, minecraft)) {
				String status = STATUS.get(project.id());
				boolean mod = project.kind() == Modrinth.Kind.MOD;
				boolean waiting = mod && BoostMods.waitingForRestart(project.slug());
				boolean installed = mod && !waiting && BoostMods.installedSlug(project.slug());
				String label = waiting ? "Restart" : installed ? "Installed" : "Install";
				FlatButton install = add(new FlatButton(COLUMN - 8, 16, Component.literal(label), installed || waiting ? FlatButton.Style.SECONDARY
						: FlatButton.Style.PRIMARY, installed ? Icons.CHECK : null, b -> install(project)), width - COLUMN, rowY + 11);
				install.active = !installing && !installed && !waiting && status == null;
				if (waiting) {
					install.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
							"Downloaded. Minecraft loads mods when it starts, so close it and press Play to use this.")));
				}
			}
			y += CARD;
		}
		if (more && !searching) {
			add(new FlatButton(120, 18, Component.literal("Show more"), FlatButton.Style.GHOST, null, b -> search(true)), (width - 120) / 2, y + 6);
			y += 30;
		}
		reserve(y + 24);
		if (searchedFor == null || !searchedFor.equals(kind + "|" + query.strip())) {
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
		searchedFor = searchKind + "|" + text;
		List<Modrinth.Project> before = nextPage ? results : List.of();
		if (!nextPage) {
			results = List.of();
		}
		IoExecutor.NET.execute(() -> {
			try {
				List<Modrinth.Project> found = Modrinth.search(searchKind, text, before.size());
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

	private void install(Modrinth.Project project) {
		if (installing) {
			return;
		}
		installing = true;
		STATUS.put(project.id(), "Getting " + project.title() + "...");
		IoExecutor.NET.execute(() -> {
			try {
				if (project.kind() == Modrinth.Kind.MODPACK) {
					Modpacks.install(new Modrinth.Pack(project.id(), project.slug(), project.title(), project.description(), project.downloads(),
							project.author()), text -> STATUS.put(project.id(), text));
					STATUS.put(project.id(), "Ready: use it from My mods");
				} else {
					int count = BoostMods.install(List.of(new BoostMods.BoostMod(project.slug(), project.slug(), project.title(), "", false)),
							text -> STATUS.put(project.id(), text));
					STATUS.put(project.id(), count > 0 ? "Restart Minecraft to use it" : "Couldn't install it");
				}
			} catch (Exception e) {
				STATUS.put(project.id(), "Couldn't install: " + e.getMessage());
				JavaHost.LOGGER.warn("[Mods] install failed", e);
			}
			installing = false;
		});
		screen.refresh();
	}

	private void drawBrowse(GuiGraphicsExtractor g, int left, int top) {
		int y = top + TOP + 28;
		String minecraft = Modpacks.gameVersion();
		if (STATUS.values().stream().anyMatch(s -> s.startsWith("Restart"))) {
			Draw.panel(g, left, y, width, BANNER - 6, 6, Theme.withAlpha(Theme.ACCENT, 0.12f));
			Icons.CHECK.draw(g, left + 8, y + 8, Theme.ACCENT);
			Draw.textFit(g, "Installed. Restart Minecraft to use them.", width - 132, left + 24, y + 8, Theme.TEXT);
			y += BANNER;
		}
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
			String status = STATUS.get(project.id());
			int columnX = left + width - COLUMN;
			if (!Availability.installable(project, minecraft)) {
				// Where it does run, in the column the Install button would use: "Runs on" / loader / version.
				String[] runsOn = Availability.runsOn(project).split(" ", 2);
				Draw.textFit(g, "Runs on", COLUMN - 6, columnX, y + 5, Theme.FAINT);
				Draw.textFit(g, runsOn[0], COLUMN - 6, columnX, y + 16, Theme.WARNING);
				Draw.textFit(g, runsOn.length > 1 ? runsOn[1] : "", COLUMN - 6, columnX, y + 27, Theme.WARNING);
			} else if (status != null && !status.startsWith("Restart") && !status.startsWith("Ready")) {
				Draw.textFit(g, status, COLUMN - 6, columnX, y + 29, status.startsWith("Couldn't") ? Theme.WARNING : Theme.INFO);
			}
			y += CARD;
		}
	}

	private static String downloads(long n) {
		return n >= 1_000_000 ? String.format("%.0fM downloads", n / 1e6) : n >= 1000 ? (n / 1000) + "k downloads" : n + " downloads";
	}

	// ================================================================ My mods

	private void buildMine() {
		saved = ModSets.saved().stream().limit(MAX_SAVED).toList();
		boolean active = ModSets.activeLabel() != null;
		if (ModSets.staged()) {
			add(new FlatButton(70, 16, Component.literal("Cancel"), FlatButton.Style.SECONDARY, null, b -> {
				ModSets.cancelStaged();
				screen.refresh();
			}), width - 80, TOP + 22);
		} else if (active) {
			add(new FlatButton(108, 16, Component.literal("Back to my mods"), FlatButton.Style.SECONDARY, Icons.BACK, b -> {
				try {
					ModSets.stageRestore();
					com.javahost.client.Toasts.notify("Back to your own mods", "This happens when you close Minecraft.");
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't stage going back", e);
				}
				screen.refresh();
			}), width - 118, TOP + 22);
		}

		savedY = TOP + 12 + 44 + 52 + 12;
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
		int base = top + TOP;
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
		if (view == View.BROWSE) {
			drawBrowse(g, left, top);
		} else {
			drawMine(g, left, top);
		}
	}
}

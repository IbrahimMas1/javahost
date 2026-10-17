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
		return "Find mods, and play modded worlds with friends.";
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

	private void buildBrowse() {
		int kindW = Math.min(150, width / 3);
		add(new Segmented<>(kindW, List.of(Modrinth.Kind.MOD, Modrinth.Kind.MODPACK), kind, k -> k == Modrinth.Kind.MOD ? "Mods" : "Modpacks", k -> {
			kind = k;
			search(false);
			screen.refresh();
		}), 0, TOP);
		int searchW = width - kindW - 6 - 70;
		searchField = add(new TextField(searchW, Component.literal("Search Modrinth")), kindW + 6, TOP - 1);
		searchField.setValue(query);
		searchField.setHint(Component.literal("Search all of Modrinth").withStyle(s -> s.withColor(Theme.FAINT)));
		searchField.setResponder(value -> query = value);
		add(new FlatButton(64, 20, Component.literal("Search"), FlatButton.Style.SECONDARY, Icons.GLOBE, b -> search(false)), width - 64, TOP - 1);

		int y = TOP + 28;
		String minecraft = Modpacks.gameVersion();
		for (Modrinth.Project project : results) {
			int rowY = y;
			// The whole card opens the project's page; the button on the right installs.
			add(new RowButton(width - 100, CARD - 2, Component.literal(project.title()), b -> ConfirmLinkScreen.confirmLinkNow(screen,
					java.net.URI.create(project.page()))), 0, rowY + 1);
			if (Availability.installable(project, minecraft)) {
				String status = STATUS.get(project.id());
				boolean installed = project.kind() == Modrinth.Kind.MOD && BoostMods.installedSlug(project.slug());
				String label = installed ? "Installed" : status != null && status.startsWith("Restart") ? "Restart" : "Install";
				FlatButton install = add(new FlatButton(64, 16, Component.literal(label), installed ? FlatButton.Style.SECONDARY : FlatButton.Style.PRIMARY,
						installed ? Icons.CHECK : null, b -> install(project)), width - 74, rowY + 12);
				install.active = !installing && !installed && status == null;
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
		if (results.isEmpty()) {
			String message = searching ? "Searching Modrinth..." : !searchProblem.isEmpty() ? searchProblem : "Nothing on Modrinth matches that.";
			Draw.textFit(g, message, width, left, y + 4, !searchProblem.isEmpty() ? Theme.WARNING : Theme.FAINT);
			return;
		}
		for (Modrinth.Project project : results) {
			card(g, left, y, width, CARD - 2);
			WebIcons.draw(g, project.iconUrl(), project.title(), left + 6, y + 3, 32);
			int textW = width - 48 - 98;
			Draw.textFit(g, project.title(), textW, left + 46, y + 5, Theme.TEXT);
			String meta = (project.author().isEmpty() ? "" : "by " + project.author() + " · ") + downloads(project.downloads());
			Draw.textFit(g, meta, textW, left + 46, y + 16, Theme.MUTED);
			Draw.textFit(g, project.description(), textW, left + 46, y + 27, Theme.FAINT);
			String status = STATUS.get(project.id());
			if (!Availability.installable(project, minecraft)) {
				// Where it does run, in the column the Install button would use.
				Draw.textFit(g, "Runs on", 90, left + width - 96, y + 9, Theme.FAINT);
				badge(g, Availability.runsOn(project), left + width - 8, y + 21);
			} else if (status != null && !status.startsWith("Restart")) {
				Draw.textFit(g, status, 78, left + width - 80, y + 30, status.startsWith("Couldn't") ? Theme.WARNING : Theme.INFO);
			}
			y += CARD;
		}
	}

	/** A small amber pill, right-aligned at {@code right}. */
	private static void badge(GuiGraphicsExtractor g, String text, int right, int y) {
		String shown = Draw.fit(text, 86);
		int w = Draw.font().width(shown) + 10;
		Draw.roundRect(g, right - w, y - 2, w, 13, 4, Theme.withAlpha(Theme.WARNING, 0.16f));
		Draw.text(g, shown, right - w + 5, y + 1, Theme.WARNING);
		if (!shown.equals(text)) {
			Draw.truncated(g, right - w, y - 2, w, 13, text);
		}
	}

	private static String downloads(long n) {
		return n >= 1_000_000 ? String.format("%.1fM downloads", n / 1e6) : n >= 1000 ? (n / 1000) + "k downloads" : n + " downloads";
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

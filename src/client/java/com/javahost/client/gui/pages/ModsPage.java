package com.javahost.client.gui.pages;

import java.util.List;

import com.javahost.JavaHost;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.mods.ModSets;
import com.javahost.client.mods.Modpacks;
import com.javahost.util.IoExecutor;
import com.javahost.util.ModManifest;
import com.javahost.util.Modrinth;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Mods: which mods are in use (your own, a friend's world's, or a modpack), what friends need to join you, saved mod
 * sets to switch between, and Modrinth modpacks to install.
 */
public class ModsPage extends Page {
	private static final int ROW = 24;
	private static final int MAX_SAVED = 4;

	private static volatile List<Modrinth.Pack> results = List.of();
	private static volatile boolean searching;
	private static volatile String searchProblem = "";
	private static volatile boolean installing;
	private static volatile String installStatus = "";
	private static String query = "";
	private static boolean searchedOnce;

	private List<ModSets.Saved> saved = List.of();
	private int savedY;
	private int packsY;
	private String lastSignature = "";
	private TextField searchField;

	@Override
	public String title() {
		return "Mods";
	}

	@Override
	public String subtitle() {
		return "Play modded worlds with friends.";
	}

	@Override
	protected void build() {
		saved = ModSets.saved().stream().limit(MAX_SAVED).toList();
		lastSignature = signature();
		boolean active = ModSets.activeLabel() != null;
		if (ModSets.staged()) {
			add(new FlatButton(70, 16, Component.literal("Cancel"), FlatButton.Style.SECONDARY, null, b -> {
				ModSets.cancelStaged();
				screen.refresh();
			}), width - 80, 22);
		} else if (active) {
			add(new FlatButton(108, 16, Component.literal("Back to my mods"), FlatButton.Style.SECONDARY, Icons.BACK, b -> {
				try {
					ModSets.stageRestore();
					com.javahost.client.Toasts.notify("Back to your own mods", "This happens when you close Minecraft.");
				} catch (java.io.IOException e) {
					JavaHost.LOGGER.warn("[Mods] couldn't stage going back", e);
				}
				screen.refresh();
			}), width - 118, 22);
		}

		// Saved sets.
		savedY = 12 + 44 + 52 + 12;
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

		// Modpacks.
		packsY = y + 14;
		searchField = add(new TextField(width - 96, Component.literal("Search modpacks")), 0, packsY + 14);
		searchField.setValue(query);
		searchField.setHint(Component.literal("Search Modrinth modpacks").withStyle(s -> s.withColor(Theme.FAINT)));
		searchField.setResponder(value -> query = value);
		add(new FlatButton(88, 20, Component.literal("Search"), FlatButton.Style.SECONDARY, Icons.GLOBE, b -> search()), width - 88, packsY + 14);
		y = packsY + 42;
		for (Modrinth.Pack pack : results) {
			FlatButton install = add(new FlatButton(56, 16, Component.literal("Install"), FlatButton.Style.PRIMARY, null, b -> install(pack)),
					width - 66, y + 4);
			install.active = !installing;
			y += ROW;
		}
		reserve(y + 24);
		if (!searchedOnce) {
			searchedOnce = true;
			search();
		}
	}

	private String signature() {
		return ModSets.activeLabel() + "|" + ModSets.stagedLabel() + "|" + results.size() + "|" + searching + "|" + installing + "|"
				+ (ModSets.mine() != null) + "|" + ModSets.saved().size();
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
			search();
			return true;
		}
		return false;
	}

	private void search() {
		if (searching) {
			return;
		}
		searching = true;
		searchProblem = "";
		String text = query;
		IoExecutor.NET.execute(() -> {
			try {
				results = Modpacks.search(text).stream().limit(8).toList();
			} catch (Exception e) {
				searchProblem = "Couldn't reach Modrinth. Check your connection.";
				JavaHost.LOGGER.warn("[Mods] modpack search failed", e);
			}
			searching = false;
		});
	}

	private void install(Modrinth.Pack pack) {
		if (installing) {
			return;
		}
		installing = true;
		installStatus = "Getting " + pack.title() + "...";
		IoExecutor.NET.execute(() -> {
			try {
				Modpacks.install(pack, text -> installStatus = text);
				installStatus = pack.title() + " is ready: press Use to play it.";
			} catch (Exception e) {
				installStatus = "Couldn't install " + pack.title() + ": " + e.getMessage();
				JavaHost.LOGGER.warn("[Mods] modpack install failed", e);
			}
			installing = false;
		});
		screen.refresh();
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		// In use.
		sectionLabel(g, "In use", left, top);
		card(g, left, top + 12, width, 36);
		String active = ModSets.activeLabel();
		Icons.PACKAGE.draw(g, left + 10, top + 23, Theme.ACCENT);
		Draw.textFit(g, active == null ? "Your own mods" : active, width - 150, left + 28, top + 18, Theme.TEXT);
		String staged = ModSets.stagedLabel();
		if (staged != null) {
			Draw.textFit(g, "Switches to " + staged + " when Minecraft closes", width - 120, left + 28, top + 30, Theme.WARNING);
		} else {
			int count = (int) net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream()
					.filter(m -> m.getContainingMod().isEmpty() && com.javahost.mods.ModScanner.jarOf(m) != null).count();
			Draw.textFit(g, Draw.count(count, "mod") + " loaded" + (active == null ? "" : " · your own are kept safe"), width - 150, left + 28,
					top + 30, Theme.MUTED);
		}

		// What friends need to join you.
		sectionLabel(g, "Friends joining your world need", left, top + 56);
		card(g, left, top + 68, width, 40);
		ModManifest mine = ModSets.mine();
		if (mine == null) {
			Draw.textFit(g, "Checking your mods...", width - 20, left + 10, top + 78, Theme.FAINT);
		} else if (mine.empty()) {
			Icons.CHECK.draw(g, left + 10, top + 78, Theme.ACCENT);
			Draw.textFit(g, "Nothing: your mods only change your game.", width - 40, left + 28, top + 79, Theme.TEXT);
			Draw.textFit(g, "Anyone with JavaHost can join.", width - 40, left + 28, top + 91, Theme.FAINT);
		} else {
			long notOnModrinth = mine.mods().stream().filter(m -> !m.onModrinth()).count();
			Icons.USERS.draw(g, left + 10, top + 78, Theme.ACCENT);
			Draw.textFit(g, Draw.count(mine.mods().size(), "mod") + ": " + String.join(", ", mine.mods().stream().map(ModManifest.Entry::name).toList()),
					width - 40, left + 28, top + 79, Theme.TEXT);
			Draw.textFit(g, notOnModrinth == 0 ? "JavaHost downloads them for friends when they join."
					: notOnModrinth + " aren't on Modrinth: friends need to get those themselves.", width - 40, left + 28, top + 91,
					notOnModrinth == 0 ? Theme.FAINT : Theme.WARNING);
		}

		// Saved sets.
		sectionLabel(g, "Saved mod sets", left, top + savedY);
		int y = top + savedY + 14;
		card(g, left, y - 2, width, Math.max(1, saved.size()) * ROW + 4);
		if (saved.isEmpty()) {
			Draw.textFit(g, "Friends' mods and modpacks you install show up here.", width - 20, left + 10, y + 8, Theme.FAINT);
			y += ROW;
		}
		for (ModSets.Saved set : saved) {
			(set.kind().equals("pack") ? Icons.PACKAGE : Icons.USERS).draw(g, left + 10, y + 6, Theme.MUTED);
			Draw.textFit(g, set.label(), width - 130, left + 28, y + 3, Theme.TEXT);
			Draw.textFit(g, (set.kind().equals("pack") ? "Modpack · " : "From a friend's world · ") + Draw.count(set.mods(), "mod")
					+ String.format(" · %.0f MB", set.size() / 1048576.0), width - 130, left + 28, y + 13, Theme.FAINT);
			y += ROW;
		}

		// Modpacks.
		sectionLabel(g, "Find a modpack (Fabric, Minecraft " + Modpacks.gameVersion() + ")", left, top + packsY);
		y = top + packsY + 42;
		if (searching) {
			Draw.textFit(g, "Searching Modrinth...", width, left, y + 2, Theme.FAINT);
		} else if (!searchProblem.isEmpty()) {
			Draw.textFit(g, searchProblem, width, left, y + 2, Theme.WARNING);
		} else if (results.isEmpty()) {
			Draw.textFit(g, "No Fabric modpacks for this Minecraft version match yet.", width, left, y + 2, Theme.FAINT);
		} else {
			card(g, left, y - 2, width, results.size() * ROW + 4);
			for (Modrinth.Pack pack : results) {
				Draw.textFit(g, pack.title(), width - 90, left + 10, y + 3, Theme.TEXT);
				Draw.textFit(g, downloads(pack.downloads()) + (pack.description().isEmpty() ? "" : " · " + pack.description()), width - 90, left + 10,
						y + 13, Theme.FAINT);
				y += ROW;
			}
		}
		if (!installStatus.isEmpty()) {
			Draw.textFit(g, installStatus, width, left, y + 6, installing ? Theme.INFO : Theme.ACCENT);
		}
	}

	private static String downloads(long n) {
		return n >= 1_000_000 ? String.format("%.1fM downloads", n / 1e6) : n >= 1000 ? (n / 1000) + "k downloads" : n + " downloads";
	}
}

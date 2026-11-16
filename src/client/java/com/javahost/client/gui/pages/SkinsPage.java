package com.javahost.client.gui.pages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.javahost.client.compat.ClientCompat;
import com.javahost.client.friends.Identity;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.IdleSkin;
import com.javahost.client.gui.ui.Popover;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.HostController;
import com.javahost.client.skins.SkinLibrary;
import com.javahost.client.skins.SkinService;
import com.javahost.util.IoExecutor;
import com.javahost.util.SkinApi;
import com.javahost.util.SkinFolder;
import com.javahost.util.SkinImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Skins: the picked skin big on the lit stage (drag to turn it) with its name, arms and one Wear button on the left;
 * your saved skins as a grid of small front views on the right. Add one by dropping a PNG on the window, from a
 * player's name, or through the skins folder. Each tile's "more" (or a right click) renames or deletes it.
 */
public class SkinsPage extends Page {
	private static final int GAP = 5;
	private static final int HEADER = 14;
	/** Two lines: room for the longer messages (Minecraft's limits) without cutting them. */
	private static final int FOOTER = 20;
	private static final String BY_NAME_HELP = "Type someone's Minecraft name to see their skin here.";

	private enum Mode { GRID, BY_NAME }

	/** Remembered while the menu is open (and across rebuilds). */
	private static String selectedId;
	private static Mode mode = Mode.GRID;
	private static int gridPage;
	private static String renaming;
	private static String lookupName = "";
	private static boolean fetching;
	private static String fetchError;
	private static SkinService.Fetched fetched;
	private static boolean fetchedSlim;
	private static Identifier fetchedTexture;
	private static int fetchedCount;

	private int pageH;
	private int leftW;
	private int rightX;
	private int rightW;
	private int footerY;
	private int nameY;
	private int segY;
	private int wearY;
	private int skinH;
	private int gridY;
	private int cols;
	private int rows;
	private int tileW;
	private int tileH;
	private int scale;
	private int pages;
	private boolean firstBuild = true;
	private String shape = "";
	private int seenService = -1;
	private FlatButton wear;
	private net.minecraft.client.gui.components.PlayerSkinWidget model;
	private TextField nameField;
	private TextField renameField;
	private PlayerSkin lastShown;
	/** A popover to open on the next tick (one opened from inside a popover's click would be closed right away). */
	private Runnable pendingPopover;

	@Override
	public String title() {
		return "Skins";
	}

	@Override
	public String subtitle() {
		return mode == Mode.BY_NAME ? "Borrow the look of any Minecraft player." : "Pick a skin and put it on.";
	}

	// ---------------------------------------------------------------- state

	private static SkinFolder.Entry selected() {
		return SkinLibrary.get(selectedId);
	}

	/** Picks a sensible skin when none is picked: the one you wear, else the newest. */
	private static void ensureSelection() {
		if (selected() != null) {
			return;
		}
		List<SkinFolder.Entry> entries = SkinLibrary.entries();
		String worn = SkinLibrary.worn();
		selectedId = worn != null && SkinLibrary.get(worn) != null ? worn : entries.isEmpty() ? null : entries.get(0).id();
	}

	/** What decides the layout: rebuilt when it changes (not for renames or arms, which are drawn live). */
	private String shape() {
		StringBuilder s = new StringBuilder().append(SkinLibrary.loaded()).append(mode).append(renaming).append(fetched != null).append(gridPage)
				.append(selectedId).append(SkinService.profile() == null ? 0 : SkinService.profile().capes().size()).append(SkinService.canWear());
		for (SkinFolder.Entry entry : SkinLibrary.entries()) {
			s.append(entry.id());
		}
		return s.toString();
	}

	@Override
	public void tick() {
		if (pendingPopover != null) {
			Runnable open = pendingPopover;
			pendingPopover = null;
			open.run();
		}
		if (!shape().equals(shape)) {
			ensureSelection();
			screen.refresh();
			return;
		}
		if (SkinService.version() != seenService) {
			updateWear();
		}
	}

	// ---------------------------------------------------------------- layout

	@Override
	protected void build() {
		if (firstBuild) {
			firstBuild = false;
			SkinService.clearMessage(); // what happened last time the page was open is old news
			SkinLibrary.reload(); // also takes in PNGs dropped into the folder
			SkinService.refreshProfile();
		}
		ensureSelection();
		shape = shape();
		renameField = null;
		nameField = null;
		pageH = Math.max(160, screen.contentHeight() - 4);
		footerY = pageH - FOOTER;
		leftW = Math.max(104, Math.min(160, Math.round(width * 0.37f)));
		rightX = leftW + 12;
		rightW = width - rightX;

		gridGeometry();
		// The cape menu sits at the end of the "Your skins" row when its label fits there, else on its own line
		// between the arms switch and Wear.
		SkinApi.Profile profile = SkinService.profile();
		String capeLabel = null;
		boolean capeInHeader = false;
		if (profile != null && !profile.capes().isEmpty() && mode == Mode.GRID && SkinLibrary.loaded() && !SkinLibrary.entries().isEmpty()) {
			SkinApi.Cape active = profile.activeCape();
			capeLabel = "Cape: " + (active == null ? "none" : active.alias());
			int pagerW = pagerWidth();
			capeInHeader = Draw.width(capeLabel) + Icons.SIZE + 15 <= rightW - capsWidth(headerLabel()) - 6 - pagerW;
		}

		// Left: the stage, then name, arms and Wear from the bottom up.
		wearY = footerY - 6 - 20;
		int capeY = wearY - 4 - 13;
		segY = (capeLabel != null && !capeInHeader ? capeY - 4 : wearY - 6) - 18;
		nameY = segY - 6 - 9;
		skinH = Math.max(60, nameY - 8);
		int skinW = Math.min(leftW - 16, Math.round(skinH * 0.62f));
		// A rebuilt page (e.g. another skin picked) keeps the model turned the way it was.
		float[] angles = IdleSkin.angles(model);
		model = add(IdleSkin.create(skinW, skinH, this::shownSkin, false), (leftW - skinW) / 2, 2);
		IdleSkin.restoreAngles(model, angles);

		SkinFolder.Entry entry = selected();
		boolean preview = mode == Mode.BY_NAME && fetched != null;
		if (preview || entry != null) {
			if (!preview && entry.id().equals(renaming)) {
				renameField = add(new TextField(leftW, Component.literal("Skin name")), 0, nameY - 6);
				renameField.setMaxLength(SkinFolder.MAX_NAME);
				renameField.setValue(entry.name());
				screen.focusOn(renameField);
			}
			String model = preview ? (fetchedSlim ? SkinFolder.SLIM : SkinFolder.CLASSIC) : entry.model();
			add(new Segmented<>(leftW, List.of(SkinFolder.CLASSIC, SkinFolder.SLIM), model, m -> m.equals(SkinFolder.SLIM) ? "Slim" : "Classic",
					m -> {
						if (mode == Mode.BY_NAME && fetched != null) {
							fetchedSlim = m.equals(SkinFolder.SLIM);
						} else if (selected() != null) {
							SkinLibrary.setModel(selected().id(), m);
						}
					}), 0, segY);
			wear = add(new FlatButton(leftW, 20, Component.literal("Wear"), FlatButton.Style.PRIMARY, Icons.SHIRT, b -> pressWear()), 0, wearY);
			updateWear();
		} else {
			wear = null;
		}

		if (mode == Mode.BY_NAME) {
			buildByName();
		} else if (SkinLibrary.loaded() && SkinLibrary.entries().isEmpty()) {
			buildEmpty();
		} else if (SkinLibrary.loaded()) {
			buildGrid();
		}
		if (capeLabel != null) {
			int w = Draw.width(capeLabel) + Icons.SIZE + 15;
			if (capeInHeader) {
				addCape(capeLabel, w, rightX + rightW - pagerWidth() - w + 5, 0);
			} else {
				addCape(capeLabel, Math.min(leftW, w), (leftW - Math.min(leftW, w)) / 2, capeY);
			}
		}
		reserve(pageH);
	}

	private static FlatButton button(int w, int h, String label, FlatButton.Style style, Icons icon, Runnable action) {
		return new FlatButton(w, h, Component.literal(label), style, icon, b -> action.run());
	}

	private void buildGrid() {
		gridY = HEADER;
		int headerRight = rightX + rightW;
		List<SkinFolder.Entry> entries = SkinLibrary.entries();
		int per = cols * rows;
		pages = Math.max(1, (entries.size() + 1 + per - 1) / per);
		gridPage = Math.max(0, Math.min(gridPage, pages - 1));
		int first = gridPage * per - (gridPage > 0 ? 1 : 0);
		int slot = 0;
		if (gridPage == 0) {
			add(new Tile(null, tileW, tileH), rightX, gridY);
			slot++;
		}
		for (int i = Math.max(0, first); i < entries.size() && slot < per; i++, slot++) {
			add(new Tile(entries.get(i).id(), tileW, tileH), rightX + (slot % cols) * (tileW + GAP), gridY + (slot / cols) * (tileH + GAP));
		}
		if (pages > 1) {
			add(new Arrow(true, () -> gridPage = Math.min(pages - 1, gridPage + 1)), headerRight - 12, -1).active = gridPage < pages - 1;
			headerRight -= 12 + 2 + Draw.width(pageLabel()) + 2;
			add(new Arrow(false, () -> gridPage = Math.max(0, gridPage - 1)), headerRight - 12, -1).active = gridPage > 0;
			headerRight -= 12 + 8;
		}
	}

	/**
	 * Four columns of small tiles, three rows at least: just the figure, with even padding all round. Front views are
	 * drawn a whole number of screen pixels per skin pixel (twice the size where there's room).
	 */
	private void gridGeometry() {
		gridY = HEADER;
		int gridH = footerY - 6 - gridY;
		cols = 4;
		tileW = (rightW - GAP * (cols - 1)) / cols;
		scale = tileW >= 16 * 2 + 12 && gridH >= 3 * (32 * 2 + 12) + 2 * GAP ? 2 : 1;
		int pad = Math.max(3, Math.min((tileW - 16 * scale) / 2, ((gridH - 2 * GAP) / 3 - 32 * scale) / 2));
		tileH = 32 * scale + pad * 2;
		rows = Math.max(1, (gridH + GAP) / (tileH + GAP));
	}

	/** Room the page arrows take at the end of the header row (none on one page). */
	private int pagerWidth() {
		int per = Math.max(1, cols * rows);
		int count = (SkinLibrary.entries().size() + per) / per;
		return cols * rows == 0 || count <= 1 ? 0 : 12 + 2 + Draw.width(count + "/" + count) + 2 + 12 + 8;
	}

	private void addCape(String label, int w, int x, int y) {
		FlatButton cape = add(new FlatButton(w, 13, Component.literal(label), FlatButton.Style.GHOST, Icons.CHEVRON_DOWN,
				b -> screen.openPopover(capeMenu(), b, x < rightX)), x, y);
		cape.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Draw.styled("Choose the cape you show")));
	}

	private static String headerLabel() {
		return "Your skins (" + SkinLibrary.entries().size() + ")";
	}

	/** How wide {@code Page.sectionLabel} draws a label (capitals with a pixel between letters). */
	private static int capsWidth(String text) {
		int w = 0;
		for (char c : text.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
			w += Draw.font().width(String.valueOf(c)) + 1;
		}
		return w;
	}

	private String pageLabel() {
		return (gridPage + 1) + "/" + pages;
	}

	private void buildEmpty() {
		int cardH = footerY - 6;
		int y = cardH / 2 + 4;
		int w = Math.min(rightW - 24, 150);
		add(button(w, 20, "From a player's name", FlatButton.Style.SECONDARY, Icons.USER, this::openByName), rightX + (rightW - w) / 2, y);
		add(button(w, 18, "Open skins folder", FlatButton.Style.GHOST, Icons.FOLDER, this::openFolder), rightX + (rightW - w) / 2, y + 24);
	}

	private void buildByName() {
		int inner = rightW - 16;
		int findW = 46;
		int fieldY = 21 + wrappedHeight(BY_NAME_HELP, rightW - 16) + 6;
		nameField = add(new TextField(inner - findW - 6, Component.literal("Player name")), rightX + 8, fieldY);
		nameField.setMaxLength(16);
		nameField.setHint(Component.literal("Steve123"));
		nameField.setValue(lookupName);
		nameField.setResponder(value -> {
			lookupName = value;
			fetchError = null;
		});
		add(button(findW, 20, "Find", FlatButton.Style.SECONDARY, null, this::lookUp), rightX + 8 + inner - findW, fieldY);
		add(button(inner, 18, "Back to my skins", FlatButton.Style.GHOST, Icons.BACK, this::closeByName), rightX + 8, footerY - 6 - 8 - 18);
		if (fetched == null) {
			screen.focusOn(nameField);
		}
	}

	// ---------------------------------------------------------------- the model

	private PlayerSkin shownSkin() {
		PlayerSkin base = Faces.skinOf(Identity.id());
		if (mode == Mode.BY_NAME) {
			return fetchedTexture != null ? ClientCompat.localSkin(fetchedTexture, fetchedSlim, base) : base;
		}
		SkinFolder.Entry entry = selected();
		if (entry == null) {
			return base;
		}
		Identifier texture = SkinLibrary.full(entry);
		if (texture != null) {
			lastShown = ClientCompat.localSkin(texture, entry.slim(), base);
		}
		return lastShown != null ? lastShown : base;
	}

	// ---------------------------------------------------------------- Wear

	private void updateWear() {
		seenService = SkinService.version();
		if (wear == null) {
			return;
		}
		wear.setTooltip(null);
		if (mode == Mode.BY_NAME && fetched != null) {
			wear.setMessage(Component.literal("Save to my skins"));
			wear.setIcon(Icons.DOWNLOAD).setStyle(FlatButton.Style.PRIMARY);
			wear.active = true;
			return;
		}
		SkinFolder.Entry entry = selected();
		if (entry == null) {
			return;
		}
		if (entry.id().equals(SkinService.changing())) {
			wear.setMessage(Component.literal("Wearing…"));
			wear.setIcon(null).setStyle(FlatButton.Style.PRIMARY);
			wear.active = true; // stays lit while it goes; pressing again does nothing
		} else if (SkinService.wearing(entry)) {
			wear.setMessage(Component.literal("Wearing"));
			wear.setIcon(Icons.CHECK).setStyle(FlatButton.Style.SECONDARY).iconColor(Theme.ACCENT);
			wear.active = true;
		} else {
			wear.setMessage(Component.literal("Wear"));
			wear.setIcon(Icons.SHIRT).setStyle(FlatButton.Style.PRIMARY).iconColor(0);
			wear.active = SkinService.canWear() && SkinService.changing() == null;
			if (!SkinService.canWear()) {
				wear.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Draw.styled(SkinService.SIGN_IN)));
			}
		}
	}

	private void pressWear() {
		if (mode == Mode.BY_NAME && fetched != null) {
			saveFetched();
			return;
		}
		SkinFolder.Entry entry = selected();
		if (entry != null && !SkinService.wearing(entry) && SkinService.changing() == null) {
			SkinService.wear(entry);
			updateWear();
		}
	}

	// ---------------------------------------------------------------- menus

	private Popover addMenu() {
		List<Popover.Item> items = new ArrayList<>();
		items.add(Popover.Item.of("From a player's name…", Icons.USER, this::openByName));
		items.add(Popover.Item.of("Open skins folder", Icons.FOLDER, this::openFolder));
		Popover.Item reset = Popover.Item.of("Use Minecraft's default skin", Icons.CROSS, SkinService::wearDefault);
		items.add(SkinService.canWear() ? reset : reset.disabled(""));
		items.add(Popover.Item.of("Or drop a PNG on this window", Icons.UPLOAD, () -> {
		}).disabled(""));
		return Popover.menu(items);
	}

	private Popover moreMenu(String id, AbstractButton anchor) {
		SkinFolder.Entry entry = SkinLibrary.get(id);
		List<Popover.Item> items = new ArrayList<>();
		if (entry == null) {
			return Popover.menu(items);
		}
		Popover.Item wearItem = Popover.Item.of("Wear", Icons.SHIRT, () -> {
			selectedId = id;
			SkinService.wear(entry);
		});
		if (!SkinService.wearing(entry)) {
			items.add(SkinService.canWear() && SkinService.changing() == null ? wearItem : wearItem.disabled(""));
		}
		items.add(Popover.Item.of("Rename", Icons.EDIT, () -> {
			selectedId = id;
			renaming = id;
			screen.refresh();
		}));
		if (id.equals(SkinLibrary.worn())) {
			// The one you wear: ask first, it's your way back to it.
			items.add(Popover.Item.danger("Delete…", Icons.TRASH, () -> pendingPopover = () -> screen.openPopover(confirmDelete(entry), anchor, true)));
		} else {
			items.add(Popover.Item.danger("Delete", Icons.TRASH, () -> delete(entry)));
		}
		return Popover.menu(items);
	}

	private Popover confirmDelete(SkinFolder.Entry entry) {
		return Popover.menu(List.of(
				Popover.Item.of("You're wearing this skin", Icons.WARNING, () -> {
				}).disabled(""),
				Popover.Item.danger("Delete " + entry.name(), Icons.TRASH, () -> delete(entry)),
				Popover.Item.of("Keep it", Icons.CROSS, () -> {
				})));
	}

	private Popover capeMenu() {
		SkinApi.Profile profile = SkinService.profile();
		List<Popover.Item> items = new ArrayList<>();
		if (profile == null) {
			return Popover.menu(items);
		}
		SkinApi.Cape active = profile.activeCape();
		items.add(Popover.Item.of("No cape", Icons.CROSS, () -> SkinService.setCape(null)).selected(active == null));
		for (SkinApi.Cape cape : profile.capes()) {
			items.add(Popover.Item.of(cape.alias(), Icons.SPARKLE, () -> SkinService.setCape(cape.id())).selected(cape.active()));
		}
		return Popover.menu(items);
	}

	private void delete(SkinFolder.Entry entry) {
		SkinLibrary.delete(entry.id()).whenCompleteAsync((ok, error) -> {
			if (error == null && Boolean.TRUE.equals(ok)) {
				SkinService.say("Deleted " + entry.name() + ".", false);
				if (entry.id().equals(selectedId)) {
					selectedId = null;
				}
			} else if (error != null) {
				SkinService.say(SkinService.reason(error), true);
			}
		}, Minecraft.getInstance());
	}

	private void openFolder() {
		IoExecutor.run(() -> {
			try {
				java.nio.file.Files.createDirectories(SkinLibrary.dir());
			} catch (java.io.IOException e) {
				// opening it will say so
			}
		}).thenRunAsync(() -> ClientCompat.openPath(SkinLibrary.dir()), Minecraft.getInstance());
		SkinService.say("Put skin PNGs in that folder; they show up here when you come back.", false);
	}

	// ---------------------------------------------------------------- adding

	/** PNG files dropped onto the game window while this page is open. */
	public void filesDropped(List<Path> files) {
		int count = 0;
		for (Path file : files) {
			if (count++ >= 20) {
				break;
			}
			SkinLibrary.addFile(file).whenCompleteAsync((added, error) -> {
				if (error != null) {
					SkinService.say(SkinService.reason(error), true);
					return;
				}
				show(added);
			}, Minecraft.getInstance());
		}
	}

	private void show(SkinFolder.Added added) {
		selectedId = added.entry().id();
		mode = Mode.GRID;
		int index = SkinLibrary.entries().indexOf(SkinLibrary.get(selectedId));
		if (index >= 0 && cols * rows > 0) {
			gridPage = (index + 1) / (cols * rows);
		}
		SkinService.say(added.existed() ? added.entry().name() + " is already in your skins." : "Added " + added.entry().name() + ".", false);
	}

	private void openByName() {
		mode = Mode.BY_NAME;
		fetchError = null;
		renaming = null;
		screen.refresh();
	}

	private void closeByName() {
		mode = Mode.GRID;
		clearFetched();
		screen.refresh();
	}

	private void lookUp() {
		if (fetching) {
			return;
		}
		String name = lookupName.strip();
		fetching = true;
		fetchError = null;
		clearFetched();
		SkinService.fetchPlayer(name).thenApplyAsync(f -> {
			try {
				return new Object[] {f, SkinImage.png(SkinImage.read(f.png()))};
			} catch (SkinImage.Invalid e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, IoExecutor.POOL).whenCompleteAsync((result, error) -> {
			fetching = false;
			if (error != null) {
				fetchError = SkinService.reason(error);
				return;
			}
			try {
				fetched = (SkinService.Fetched) result[0];
				fetchedSlim = fetched.slim();
				fetchedTexture = Identifier.fromNamespaceAndPath("javahost", "skins/fetched_" + fetchedCount++);
				SkinLibrary.registerPng(fetchedTexture, (byte[]) result[1]);
			} catch (Exception e) {
				fetched = null;
				fetchedTexture = null;
				fetchError = "That player's skin couldn't be read.";
			}
		}, Minecraft.getInstance());
	}

	private static void clearFetched() {
		if (fetchedTexture != null) {
			Minecraft.getInstance().getTextureManager().release(fetchedTexture);
		}
		fetched = null;
		fetchedTexture = null;
	}

	private void saveFetched() {
		SkinService.Fetched f = fetched;
		if (f == null) {
			return;
		}
		SkinLibrary.add(f.png(), f.name(), fetchedSlim ? SkinFolder.SLIM : SkinFolder.CLASSIC, "player:" + f.name()).whenCompleteAsync((added, error) -> {
			if (error != null) {
				SkinService.say(SkinService.reason(error), true);
				return;
			}
			clearFetched();
			show(added);
		}, Minecraft.getInstance());
	}

	// ---------------------------------------------------------------- keys and back

	@Override
	protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (isEnter(event) && renameField != null && renameField.isFocused() && renaming != null) {
			String id = renaming;
			String name = renameField.getValue();
			renaming = null;
			SkinLibrary.rename(id, name);
			screen.refresh();
			return true;
		}
		if (isEnter(event) && nameField != null && nameField.isFocused()) {
			lookUp();
			return true;
		}
		return false;
	}

	@Override
	protected boolean canGoBack() {
		return mode == Mode.BY_NAME || renaming != null;
	}

	@Override
	protected boolean back() {
		if (renaming != null) {
			renaming = null;
			screen.refresh();
			return true;
		}
		if (mode == Mode.BY_NAME) {
			closeByName();
			return true;
		}
		return false;
	}

	@Override
	public void removed() {
		renaming = null;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int cx = left + leftW / 2;
		Draw.stage(g, cx, top + 2, skinH, Math.round(leftW * 0.78f));

		// Under the stage: whose skin this is.
		SkinFolder.Entry entry = selected();
		String name = mode == Mode.BY_NAME ? (fetched != null ? fetched.name() + "'s skin" : "") : entry != null ? entry.name() : Identity.name();
		if (renaming == null || mode == Mode.BY_NAME) {
			String shown = Draw.fit(name, leftW - 4);
			Draw.bold(g, shown, cx - Draw.boldWidth(shown) / 2, top + nameY, Theme.TEXT);
			if (!shown.equals(name)) {
				Draw.truncated(g, cx - Draw.boldWidth(shown) / 2, top + nameY - 1, Draw.boldWidth(shown), 10, name);
			}
		}
		if (entry == null && mode == Mode.GRID) {
			String line = SkinLibrary.loaded() ? "Your skin now" : "";
			Draw.centered(g, line, cx, top + segY + 5, Theme.MUTED);
		} else if (mode == Mode.BY_NAME && fetched == null) {
			Draw.centered(g, fetching ? "Looking them up…" : "Their skin shows here", cx, top + segY + 5, Theme.MUTED);
		}

		int x = left + rightX;
		if (mode == Mode.BY_NAME) {
			drawByName(g, x, top);
		} else if (!SkinLibrary.loaded()) {
			Draw.text(g, "Loading your skins…", x, top + 2, Theme.MUTED);
		} else if (SkinLibrary.entries().isEmpty()) {
			drawEmpty(g, x, top);
		} else {
			sectionLabel(g, headerLabel(), x, top + 3);
			if (pages > 1) {
				String label = pageLabel();
				Draw.text(g, label, x + rightW - 12 - 2 - Draw.width(label), top + 3, Theme.MUTED);
			}
		}

		// The footer: what just happened, else when friends will see a new skin.
		String message = SkinService.message();
		int color;
		String text;
		if (message != null) {
			text = message;
			color = SkinService.messageIsError() ? Theme.WARNING : Theme.ACCENT;
		} else if (!SkinService.canWear()) {
			text = SkinService.SIGN_IN;
			color = Theme.WARNING;
		} else if (SkinService.profileError() != null) {
			text = SkinService.profileError();
			color = Theme.WARNING;
		} else {
			text = note();
			color = Theme.FAINT;
		}
		int footer = top + footerY + 3;
		Icons icon = color == Theme.FAINT ? Icons.USERS : color == Theme.ACCENT ? Icons.CHECK : Icons.WARNING;
		icon.draw(g, left, footer - 2, color);
		List<net.minecraft.util.FormattedCharSequence> lines = Draw.split(text, width - Icons.SIZE - 4);
		if (lines.size() <= 2) {
			for (int i = 0; i < lines.size(); i++) {
				Draw.text(g, lines.get(i), left + Icons.SIZE + 4, footer + i * 10, color);
			}
		} else {
			Draw.textFit(g, text, width - Icons.SIZE - 4, left + Icons.SIZE + 4, footer, color);
		}
		if (color == Theme.FAINT) {
			Draw.truncated(g, left, footer - 2, width, 12, fullNote());
		}
	}

	/** When other players see a new skin, in one short line (the whole story on hover). */
	private String note() {
		if (minecraft.level == null) {
			return "Friends see a new skin when you next join them.";
		}
		if (minecraft.getSingleplayerServer() != null) {
			return HostController.isHosting() ? "Friends here see it after you restart the game." : "Others see it after you restart the game.";
		}
		return "Others here see it after you rejoin. You see it now.";
	}

	private static String fullNote() {
		return "You see a new skin at once. Other players see it when you next join their world. In a world you host, "
				+ "they see it after you restart the game: Minecraft reads your skin once, when it starts.";
	}

	private void drawEmpty(GuiGraphicsExtractor g, int x, int top) {
		int h = footerY - 6;
		card(g, x, top, rightW, h);
		int cx = x + rightW / 2;
		int y = top + h / 2 - 52; // the buttons start at the middle, 8 below the last line
		Draw.roundRect(g, cx - 11, y, 22, 22, 11, Theme.withAlpha(Theme.ACCENT, 0.16f));
		Icons.SHIRT.drawCentred(g, cx, y + 11, Icons.SIZE, Theme.ACCENT);
		String title = "No skins saved yet";
		Draw.bold(g, title, cx - Draw.boldWidth(title) / 2, y + 28, Theme.TEXT);
		String line = Draw.fit("Drop a skin PNG here, or:", rightW - 16);
		Draw.centered(g, line, cx, y + 40, Theme.MUTED);
	}

	private void drawByName(GuiGraphicsExtractor g, int x, int top) {
		card(g, x, top, rightW, footerY - 6);
		sectionLabel(g, "Copy a player's skin", x + 8, top + 8);
		wrap(g, BY_NAME_HELP, x + 8, top + 21, rightW - 16, Theme.MUTED);
		int y = top + 21 + wrappedHeight(BY_NAME_HELP, rightW - 16) + 6 + 20 + 10;
		if (fetching) {
			Draw.text(g, "Looking them up…", x + 8, y, Theme.MUTED);
		} else if (fetchError != null) {
			Icons.WARNING.draw(g, x + 8, y - 2, Theme.WARNING);
			wrap(g, fetchError, x + 8 + Icons.SIZE + 4, y, rightW - 31, Theme.WARNING);
		} else if (fetched != null) {
			Icons.CHECK.draw(g, x + 8, y - 2, Theme.ACCENT);
			wrap(g, "Found " + fetched.name() + ". Save it to your skins to wear it.", x + 8 + Icons.SIZE + 4, y, rightW - 31, Theme.ACCENT);
		}
	}

	// ---------------------------------------------------------------- tiles

	/** One saved skin (or, with a null id, the Add tile): its front view in a glass card, the name underneath. */
	private final class Tile extends AbstractButton {
		private final String id;
		private float hover;

		Tile(String id, int w, int h) {
			super(0, 0, w, h, Component.literal(id == null ? "Add a skin" : nameOf(id)));
			this.id = id;
			setTooltip(net.minecraft.client.gui.components.Tooltip.create(Draw.styled(getMessage().getString())));
		}

		private boolean overMore(double mouseX, double mouseY) {
			return id != null && mouseX >= getX() + getWidth() - 12 && mouseY < getY() + 12;
		}

		/** Opens this tile's menu (its "more" button, or a right click). */
		void openMenu() {
			selectedId = id;
			updateWear();
			screen.openPopover(moreMenu(id, this), this, true);
		}

		@Override
		//? if >=1.21.9 {
		public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
			double mx = event.x();
			double my = event.y();
			boolean right = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;
		//?} else {
		/*public boolean mouseClicked(double mx, double my, int button) {
			boolean right = button == 1;
		*///?}
			if (active && visible && isMouseOver(mx, my) && id != null && (right || overMore(mx, my))) {
				playDownSound(Minecraft.getInstance().getSoundManager());
				openMenu();
				return true;
			}
			//? if >=1.21.9 {
			return super.mouseClicked(event, doubleClick);
			//?} else
			/*return super.mouseClicked(mx, my, button);*/
		}

		@Override
		//? if >=1.21.9 {
		public void onPress(InputWithModifiers input) {
		//?} else
		/*public void onPress() {*/
			if (id == null) {
				screen.openPopover(addMenu(), this, true);
				return;
			}
			if (!id.equals(selectedId)) {
				selectedId = id;
				lastShown = null;
				renaming = null;
				updateWear();
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
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.35f);
			int x = getX();
			int y = getY();
			int w = getWidth();
			int h = getHeight();
			if (id == null) {
				drawAdd(g, x, y, w, h);
				return;
			}
			SkinFolder.Entry entry = SkinLibrary.get(id);
			if (entry == null) {
				return;
			}
			boolean picked = id.equals(selectedId);
			if (picked) {
				Draw.glow(g, x + w / 2, y + h / 2, w / 2 + 6, h / 2 + 6, Theme.withAlpha(Theme.ACCENT, 0.22f));
				Draw.roundRect(g, x - 1, y - 1, w + 2, h + 2, 7, Theme.withAlpha(Theme.ACCENT, 0.9f));
				Draw.roundRect(g, x, y, w, h, 6, 0xF0141A21);
				Draw.roundRect(g, x, y, w, h, 6, Theme.withAlpha(Theme.ACCENT, 0.10f));
			} else {
				Draw.glassCard(g, x, y, w, h, 6);
				if (hover > 0.01f) {
					Draw.roundRect(g, x, y, w, h, 6, Theme.withAlpha(0x14FFFFFF, hover));
				}
			}
			int pw = 16 * scale;
			int ph = 32 * scale;
			int px = x + (w - pw) / 2;
			int py = y + (h - ph) / 2;
			Draw.glow(g, x + w / 2, py + ph - 1, pw, 3, picked ? Theme.withAlpha(Theme.ACCENT, 0.55f) : 0x70000000);
			Identifier front = SkinLibrary.preview(entry);
			if (front != null) {
				Draw.image(g, front, px, py, pw, ph, SkinImage.FRONT_W, SkinImage.FRONT_H);
			} else {
				Draw.roundRect(g, px + 4 * scale, py, 8 * scale, 8 * scale, 2, 0x18FFFFFF); // a quiet outline while it loads
				Draw.roundRect(g, px, py + 8 * scale, pw, 24 * scale, 2, 0x10FFFFFF);
			}
			// The corners stay clear of the head: a small tick for the one you wear, "…" for the menu.
			if (SkinService.wearing(entry)) {
				Draw.roundRect(g, x + 2, y + 2, 9, 9, 4, Theme.ACCENT);
				Icons.CHECK.drawCentred(g, x + 6.5f, y + 6.5f, 7, Theme.ON_ACCENT);
			}
			if (hover > 0.01f || picked) {
				boolean overMore = isHovered() && overMore(mouseX, mouseY);
				float shown = picked ? 1 : hover;
				Draw.roundRect(g, x + w - 11, y + 2, 9, 9, 3, overMore ? 0x40FFFFFF : Theme.withAlpha(0x1CFFFFFF, shown));
				Icons.MORE.drawCentred(g, x + w - 6.5f, y + 6.5f, 7, Theme.withAlpha(overMore ? Theme.TEXT : Theme.MUTED, shown));
			}
		}

		private void drawAdd(GuiGraphicsExtractor g, int x, int y, int w, int h) {
			Draw.roundRect(g, x, y, w, h, 6, Theme.withAlpha(Theme.ACCENT, 0.30f + 0.35f * hover));
			Draw.roundRect(g, x + 1, y + 1, w - 2, h - 2, 5, 0xE60E1217);
			Draw.roundRect(g, x + 1, y + 1, w - 2, h - 2, 5, Theme.withAlpha(Theme.ACCENT, 0.06f + 0.08f * hover));
			int cx = x + w / 2;
			int cy = y + h / 2;
			Draw.glow(g, cx, cy, w / 2 + 2, h / 3 + 2, Theme.withAlpha(Theme.ACCENT, 0.16f + 0.15f * hover));
			int arm = 4 + scale * 2;
			int color = Theme.mix(Theme.ACCENT, Theme.ACCENT_HOVER, hover);
			g.fill(cx - arm, cy - 1, cx + arm, cy + 1, color);
			g.fill(cx - 1, cy - arm, cx + 1, cy + arm, color);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	private static String nameOf(String id) {
		SkinFolder.Entry entry = SkinLibrary.get(id);
		return entry == null ? "" : entry.name();
	}

	/** A small page arrow beside the grid's title. */
	private final class Arrow extends AbstractButton {
		private final boolean next;
		private final Runnable action;

		Arrow(boolean next, Runnable action) {
			super(0, 0, 12, 11, Component.literal(next ? "Next page" : "Previous page"));
			this.next = next;
			this.action = action;
		}

		@Override
		//? if >=1.21.9 {
		public void onPress(InputWithModifiers input) {
		//?} else
		/*public void onPress() {*/
			action.run();
			screen.refresh();
		}

		@Override
		//? if >=26.1 {
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		//?} else if >=1.21.11 {
		/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?} else {
		/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		*///?}
			int color = !active ? Theme.FAINT : isHoveredOrFocused() ? Theme.TEXT : Theme.MUTED;
			Draw.push(g);
			Draw.translate(g, getX() + getWidth() / 2f, getY() + getHeight() / 2f);
			if (next) {
				Draw.scale(g, -1, 1);
			}
			Icons.BACK.drawCentred(g, 0, 0, 9, color);
			Draw.pop(g);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	// ---------------------------------------------------------------- for tests

	/** For tests: picks a saved skin. */
	public static void select(String id) {
		selectedId = id;
	}

	public static String selectedForTests() {
		return selectedId;
	}

	/** For tests: opens "From a player's name" with {@code name} typed in. */
	public static void showByName(String name) {
		mode = Mode.BY_NAME;
		lookupName = name;
		fetchError = null;
		clearFetched();
	}

	/** For tests: back to the grid. */
	public static void showGrid() {
		mode = Mode.GRID;
		renaming = null;
		gridPage = 0;
		clearFetched();
	}

	/** For tests: what the lookup found (null until then). */
	public static String fetchedName() {
		return fetched == null ? null : fetched.name();
	}

	public static boolean fetching() {
		return fetching;
	}

	public static String fetchError() {
		return fetchError;
	}

	/** For tests: presses Find. */
	public void lookUpForTests() {
		lookUp();
	}

	/** For tests: opens the Add menu or a tile's menu. */
	public void openAddMenuForTests() {
		for (var child : screen.children()) {
			if (child instanceof Tile tile && tile.id == null && tile.visible) {
				screen.openPopover(addMenu(), tile, true);
				return;
			}
		}
	}

	public void openTileMenuForTests(String id) {
		for (var child : screen.children()) {
			if (child instanceof Tile tile && id.equals(tile.id) && tile.visible) {
				tile.openMenu();
				return;
			}
		}
	}

	/** For tests: the grid's columns and rows, and where the right column starts on screen. */
	public int[] gridForTests() {
		return new int[] {cols, rows, rightX};
	}

	/** For tests: starts renaming a skin. */
	public void renameForTests(String id) {
		selectedId = id;
		renaming = id;
		screen.refresh();
	}

	public TextField renameField() {
		return renameField;
	}
}

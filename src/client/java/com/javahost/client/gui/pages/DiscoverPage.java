package com.javahost.client.gui.pages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.javahost.client.Config;
import com.javahost.client.discover.Directory;
import com.javahost.client.discover.Directory.PublicWorld;
import com.javahost.client.discover.PublicHosting;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Identity;
import com.javahost.client.friends.InviteFlow;
import com.javahost.client.gui.Page;
import com.javahost.client.gui.PublicWorldScreen;
import com.javahost.client.gui.ReportScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.RowButton;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.util.IoExecutor;
import com.javahost.util.ListingText;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * Public worlds: worlds people opened to everyone, with who's hosting, players, ping and what it's about. Join,
 * Report or Hide each one. Strangers join as visitors until the host trusts them.
 */
public class DiscoverPage extends Page {
	private static final int ROW = 34;
	private static final long REFRESH_MS = 30_000;
	private static final int MAX_PINGS_AT_ONCE = 4;

	// Kept between visits to the tab, so switching tabs doesn't lose the list or pings.
	private static List<PublicWorld> worlds = List.of();
	private static long fetchedAt;
	private static boolean fetching;
	private static final Map<UUID, ServerData> PINGS = new HashMap<>();
	private static final Map<UUID, Long> PING_STARTED = new HashMap<>();
	private static ServerStatusPinger pinger;
	private static String search = "";
	private static String tagFilter = "all";
	private static String sort = "PLAYERS";
	private static UUID selected;

	private List<PublicWorld> shown = List.of();
	private final List<Integer> rowYs = new ArrayList<>();
	private int listY;
	private int detailY = -1;
	private int detailH;
	private TextField searchField;
	private int hereY = -1;
	private String lastSignature = "";

	@Override
	public String title() {
		return "Discover";
	}

	@Override
	public String subtitle() {
		return "Public worlds you can join. Beta.";
	}

	@Override
	protected void build() {
		rowYs.clear();
		detailY = -1;
		String blocked = Directory.blockedReason();
		if (blocked != null || !Directory.available()) {
			reserve(60);
			return;
		}
		if (System.currentTimeMillis() - fetchedAt > REFRESH_MS && !fetching) {
			refresh();
		}
		// Search, refresh, and open your own.
		int openW = 120;
		searchField = add(new TextField(width - openW - 32, Component.literal("Search public worlds")), 0, 0);
		searchField.setValue(search);
		searchField.setHint(Component.literal("Search").withStyle(s -> s.withColor(Theme.FAINT)));
		searchField.setResponder(value -> {
			search = value;
			applyFilters();
		});
		FlatButton reload = add(new FlatButton(22, 20, Component.literal("Refresh"), FlatButton.Style.GHOST, Icons.SPARKLE, b -> {
			refresh();
			screen.refresh();
		}).iconOnly(), width - openW - 26, 0);
		reload.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Refresh the list")));
		String cannotOpen = PublicHosting.cannotOpen();
		FlatButton open = add(new FlatButton(openW, 20, Component.literal(PublicHosting.isPublic() ? "Your world is public" : "Open your world"),
				FlatButton.Style.PRIMARY, Icons.GLOBE, b -> minecraft.gui.setScreen(new PublicWorldScreen(screen))), width - openW, 0);
		open.active = cannotOpen == null && !PublicHosting.isPublic();
		if (cannotOpen != null) {
			open.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(cannotOpen)));
		}

		// Filters and order.
		List<String> filters = List.of("all", "help", "building", "survival", "creative");
		int sortW = 64;
		add(new Segmented<>(width - sortW - 6, filters, tagFilter, tag -> switch (tag) {
			case "all" -> "All";
			case "help" -> "Help";
			case "building" -> "Build";
			default -> ListingText.tagLabel(tag);
		}, tag -> {
			tagFilter = tag;
			screen.refresh();
		}), 0, 26);
		FlatButton sortButton = add(new FlatButton(sortW, 18, Component.literal(sort.equals("PLAYERS") ? "Busiest" : "Closest"),
				FlatButton.Style.SECONDARY, null, b -> {
					sort = sort.equals("PLAYERS") ? "PING" : "PLAYERS";
					screen.refresh();
				}), width - sortW, 26);
		sortButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				sort.equals("PLAYERS") ? "Most players first. Click for lowest ping first" : "Lowest ping first. Click for most players first")));

		listY = 56;
		// Inside someone's public world: report it from here.
		PublicWorld here = InviteFlow.currentPublic();
		if (here != null) {
			hereY = listY;
			add(new FlatButton(80, 18, Component.literal("Report"), FlatButton.Style.SECONDARY, Icons.WARNING,
					b -> minecraft.gui.setScreen(new ReportScreen(screen, here.host(), here.name()))), width - 90, hereY + 9);
			listY += 46;
		} else {
			hereY = -1;
		}
		applyFilters();
		int y = listY + 12;
		for (PublicWorld world : shown) {
			rowYs.add(y);
			add(new RowButton(width - 4, ROW - 2, Component.literal(world.title()), b -> {
				selected = world.host().equals(selected) ? null : world.host();
				screen.refresh();
			}), 2, y + 1);
			y += ROW;
			if (world.host().equals(selected)) {
				y = buildDetail(world, y);
			}
		}
		if (shown.isEmpty()) {
			y += ROW + 10;
		}
		reserve(y + 16);
	}

	/** The open row: description, details and actions. */
	private int buildDetail(PublicWorld world, int y) {
		detailY = y;
		int lines = Math.max(1, wrappedHeight(world.description(), width - 24) / 10);
		detailH = 30 + lines * 10 + 28;
		int buttonsY = y + detailH - 26;
		boolean mine = world.host().equals(Identity.id());
		String why = joinProblem(world);
		FlatButton join = add(new FlatButton(90, 20, Component.literal(mine ? "Your world" : "Join"), FlatButton.Style.PRIMARY, Icons.JOIN,
				b -> InviteFlow.joinPublic(world)), 10, buttonsY);
		join.active = why == null;
		if (why != null) {
			join.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(why)));
		}
		if (!mine) {
			add(new FlatButton(80, 20, Component.literal("Report"), FlatButton.Style.SECONDARY, Icons.WARNING,
					b -> minecraft.gui.setScreen(new ReportScreen(screen, world.host(), world.name()))), 106, buttonsY);
			FlatButton hide = add(new FlatButton(80, 20, Component.literal("Hide host"), FlatButton.Style.GHOST, Icons.CROSS, b -> {
				Config config = Config.get();
				config.hiddenHosts.add(world.host().toString());
				config.save();
				selected = null;
				screen.refresh();
			}), 192, buttonsY);
			hide.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Stop showing " + world.name() + "'s worlds")));
		}
		return y + detailH + 4;
	}

	/** Why Join is off for this world, or null. */
	private String joinProblem(PublicWorld world) {
		if (world.host().equals(Identity.id())) {
			return "This is your own world";
		}
		if (!world.mc().equals(SharedConstants.getCurrentVersion().name())) {
			return "Needs Minecraft " + world.mc();
		}
		ServerData ping = PINGS.get(world.host());
		if (ping != null && ping.state() == ServerData.State.INCOMPATIBLE) {
			return "Needs a different Minecraft version";
		}
		if (ping != null && ping.state() == ServerData.State.UNREACHABLE) {
			return "Not reachable right now";
		}
		if (world.full() || (ping != null && ping.players != null && ping.players.online() >= ping.players.max())) {
			return "Full right now";
		}
		return null;
	}

	private void applyFilters() {
		String needle = search.toLowerCase(Locale.ROOT).strip();
		Config config = Config.get();
		Comparator<PublicWorld> order = sort.equals("PING")
				? Comparator.comparingLong(w -> pingOf(w) < 0 ? Long.MAX_VALUE : pingOf(w))
				: Comparator.comparingInt(PublicWorld::players).reversed();
		shown = worlds.stream()
				.filter(w -> !config.hiddenHosts.contains(w.host().toString()) && !Friends.isBlocked(w.host()))
				.filter(w -> tagFilter.equals("all") || w.tags().contains(tagFilter))
				.filter(w -> needle.isEmpty() || (w.title() + " " + w.name() + " " + w.description()).toLowerCase(Locale.ROOT).contains(needle))
				.sorted(order)
				.toList();
	}

	private static long pingOf(PublicWorld world) {
		ServerData data = PINGS.get(world.host());
		return data == null || data.state() != ServerData.State.SUCCESSFUL ? -1 : data.ping;
	}

	private void refresh() {
		fetching = true;
		Directory.list().thenAccept(found -> minecraft.execute(() -> {
			worlds = found;
			fetchedAt = System.currentTimeMillis();
			fetching = false;
			PINGS.keySet().removeIf(id -> found.stream().noneMatch(w -> w.host().equals(id)));
			PING_STARTED.clear();
			PINGS.clear(); // fresh pings for fresh numbers
			screen.refresh();
		}));
	}

	@Override
	public void tick() {
		if (pinger == null) {
			pinger = new ServerStatusPinger();
		}
		pinger.tick();
		// Ping a few worlds at a time, in the background, through their relay addresses.
		long now = System.currentTimeMillis();
		long running = PINGS.values().stream().filter(d -> d.state() == ServerData.State.PINGING).count();
		for (PublicWorld world : shown) {
			if (running >= MAX_PINGS_AT_ONCE) {
				break;
			}
			if (!PINGS.containsKey(world.host())) {
				ping(world);
				running++;
			}
		}
		PINGS.forEach((id, data) -> {
			if (data.state() == ServerData.State.PINGING && now - PING_STARTED.getOrDefault(id, now) > 10_000) {
				data.setState(ServerData.State.UNREACHABLE);
			}
		});
		if (now - fetchedAt > REFRESH_MS && !fetching) {
			refresh();
		}
		String signature = worlds.size() + "|" + fetching + "|" + PublicHosting.isPublic() + "|" + PINGS.values().stream()
				.map(d -> d.state().name()).reduce("", String::concat);
		if (!signature.equals(lastSignature)) {
			lastSignature = signature;
			if (!(searchField != null && searchField.isFocused())) {
				screen.refresh();
			}
		}
	}

	private void ping(PublicWorld world) {
		ServerData data = new ServerData(world.title(), world.relay(), ServerData.Type.OTHER);
		data.setState(ServerData.State.PINGING);
		PINGS.put(world.host(), data);
		PING_STARTED.put(world.host(), System.currentTimeMillis());
		ServerStatusPinger using = pinger;
		boolean nativeTransport = minecraft.options.useNativeTransport();
		IoExecutor.NET.execute(() -> {
			try {
				using.pingServer(data, () -> {
				}, () -> data.setState(data.players == null || data.protocol < 0 ? ServerData.State.UNREACHABLE
						: data.protocol == SharedConstants.getCurrentVersion().protocolVersion() ? ServerData.State.SUCCESSFUL
						: ServerData.State.INCOMPATIBLE), EventLoopGroupHolder.remote(nativeTransport));
			} catch (Exception e) {
				data.setState(ServerData.State.UNREACHABLE);
			}
		});
	}

	@Override
	public void removed() {
		if (pinger != null) {
			pinger.removeAll();
			pinger = null;
		}
	}

	// ================================================================ drawing

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		String blocked = Directory.blockedReason();
		if (blocked != null || !Directory.available()) {
			card(g, left, top, width, 40);
			Icons.GLOBE.draw(g, left + 10, top + 14, Theme.FAINT);
			Draw.textFit(g, blocked != null ? blocked : "Public worlds are coming soon.", width - 40, left + 28, top + 15, Theme.MUTED);
			return;
		}
		PublicWorld here = InviteFlow.currentPublic();
		if (hereY >= 0 && here != null) {
			int y = top + hereY;
			Draw.panel(g, left, y, width, 36, 8, Theme.CARD);
			Faces.draw(g, here.host(), left + 9, y + 9, 18);
			Draw.textFit(g, "You're in " + here.name() + "'s public world", width - 140, left + 34, y + 7, Theme.TEXT);
			Draw.textFit(g, "\"" + here.title() + "\"", width - 140, left + 34, y + 19, Theme.MUTED);
		}
		sectionLabel(g, fetching && worlds.isEmpty() ? "Looking for public worlds..." : "Public worlds (" + shown.size() + ")", left, top + listY);
		int y0 = top + listY + 12;
		if (shown.isEmpty()) {
			card(g, left, y0 - 2, width, ROW + 4);
			Icons.GLOBE.draw(g, left + 10, y0 + 11, Theme.FAINT);
			Draw.textFit(g, fetching ? "Loading..." : worlds.isEmpty() ? "No public worlds right now. Open yours and be the first!"
					: "Nothing matches. Try another filter.", width - 40, left + 28, y0 + 12, Theme.MUTED);
			return;
		}
		for (int i = 0; i < shown.size() && i < rowYs.size(); i++) {
			PublicWorld world = shown.get(i);
			int y = top + rowYs.get(i);
			boolean open = world.host().equals(selected);
			Draw.panel(g, left, y - 1, width, open ? ROW + detailH + 2 : ROW - 2, 6, open ? Theme.CARD_HOVER : Theme.CARD);
			Faces.draw(g, world.host(), left + 8, y + 7, 18);
			int textRight = width - 120;
			Draw.textFit(g, world.title(), textRight - 34, left + 34, y + 5, Theme.TEXT);
			String meta = world.name() + " · " + world.region() + (world.tags().isEmpty() ? "" : " · "
					+ String.join(", ", world.tags().stream().map(ListingText::tagLabel).toList()));
			Draw.textFit(g, meta, textRight - 34, left + 34, y + 17, Theme.FAINT);
			drawStatus(g, world, left + width - 112, y + 6);
			if (open && detailY >= 0) {
				int dy = top + detailY;
				wrap(g, world.description().isEmpty() ? "No description." : world.description(), left + 12, dy + 4, width - 24,
						world.description().isEmpty() ? Theme.FAINT : Theme.MUTED);
				int lines = Math.max(1, wrappedHeight(world.description(), width - 24) / 10);
				String facts = capital(world.mode()) + " · " + capital(world.difficulty()) + " · Voice for visitors: "
						+ (world.voice() ? "on" : "off") + " · JavaHost " + world.jv();
				Draw.textFit(g, facts, width - 24, left + 12, dy + 8 + lines * 10, Theme.FAINT);
				Draw.textFit(g, "You'll start as a visitor: look around and say hi; the host can trust you to build.", width - 24,
						left + 12, dy + 20 + lines * 10, Theme.FAINT);
			}
		}
	}

	/** Players and ping bars on the right of a row. */
	private void drawStatus(GuiGraphicsExtractor g, PublicWorld world, int x, int y) {
		ServerData ping = PINGS.get(world.host());
		int players = world.players();
		int max = world.max();
		if (ping != null && ping.state() == ServerData.State.SUCCESSFUL && ping.players != null) {
			players = ping.players.online();
		}
		Icons.USERS.draw(g, x, y, players >= max ? Theme.WARNING : Theme.MUTED);
		Draw.text(g, players + "/" + max, x + 14, y + 1, players >= max ? Theme.WARNING : Theme.TEXT);
		String pingText;
		int bars;
		int color;
		if (ping == null || ping.state() == ServerData.State.PINGING || ping.state() == ServerData.State.INITIAL) {
			pingText = "...";
			bars = 0;
			color = Theme.FAINT;
		} else if (ping.state() == ServerData.State.SUCCESSFUL) {
			pingText = ping.ping + " ms";
			bars = ping.ping < 80 ? 4 : ping.ping < 150 ? 3 : ping.ping < 300 ? 2 : 1;
			color = bars >= 3 ? Theme.ACCENT : bars == 2 ? Theme.WARNING : Theme.DANGER;
		} else {
			pingText = ping.state() == ServerData.State.INCOMPATIBLE ? "version" : "offline";
			bars = 0;
			color = Theme.DANGER;
		}
		for (int i = 0; i < 4; i++) {
			int h = 3 + i * 2;
			Draw.roundRect(g, x + 54 + i * 4, y + 9 - h, 3, h, 0, i < bars ? color : Theme.withAlpha(Theme.FAINT, 0.5f));
		}
		Draw.textFit(g, pingText, 60, x + 14, y + 12, color == Theme.FAINT ? Theme.FAINT : color);
	}

	private static String capital(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}

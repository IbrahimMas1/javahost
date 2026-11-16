package com.javahost.client.gui.pages.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.WorldPickerScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.IconToggle;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Popover;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.HostController;
import com.javahost.client.host.HostController.Guest;
import com.javahost.client.rules.WorldOptions;
import com.javahost.client.share.WorldShare;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

/**
 * Who's in the world, one calm row each: face, name, a quiet role caption, and for the host: game mode, Operator and
 * a "more" menu (trust, send a world, kick, ban). In someone else's world it's a read-only list.
 */
public final class MembersSection extends WorldSection {
	private static final int ROW = 28;

	/** One row: a guest of ours (with controls), or a player in someone else's world (read-only). */
	private record Member(UUID id, String name, Guest guest, boolean you) {
	}

	private boolean hostView;
	private boolean hosting;
	private final List<Member> members = new ArrayList<>();
	private int listY;
	private int listH;
	private int footerY;

	public MembersSection(Host host) {
		super(host);
	}

	/** {@code hostView}: the world is ours (controls on the rows); {@code hosting}: friends can join now. */
	public void configure(boolean hostView, boolean hosting) {
		this.hostView = hostView;
		this.hosting = hosting;
	}

	public static String modeName(GameType mode) {
		return switch (mode) {
			case CREATIVE -> "Creative";
			case ADVENTURE -> "Adventure";
			case SPECTATOR -> "Spectator";
			default -> "Survival";
		};
	}

	private void collect() {
		members.clear();
		UUID me = minecraft.player == null ? null : minecraft.player.getUUID();
		if (hostView) {
			if (me != null) {
				members.add(new Member(me, com.javahost.compat.Compat.name(minecraft.player), null, true));
			}
			for (Guest guest : HostController.guests()) {
				members.add(new Member(guest.id(), guest.name(), guest, false));
			}
		} else if (minecraft.getConnection() != null) {
			List<PlayerInfo> online = new ArrayList<>(minecraft.getConnection().getOnlinePlayers());
			online.sort(java.util.Comparator.comparing((PlayerInfo p) -> !com.javahost.compat.Compat.id(p.getProfile()).equals(me))
					.thenComparing(p -> com.javahost.compat.Compat.name(p.getProfile()).toLowerCase(java.util.Locale.ROOT)));
			for (PlayerInfo player : online.stream().limit(64).toList()) {
				UUID id = com.javahost.compat.Compat.id(player.getProfile());
				members.add(new Member(id, com.javahost.compat.Compat.name(player.getProfile()), null, id.equals(me)));
			}
		}
	}

	private boolean alone() {
		return hostView && members.size() <= 1;
	}

	@Override
	protected int layout(int y) {
		collect();
		boolean canOp = hostView && WorldOptions.available() && WorldOptions.role() == WorldOptions.Role.HOST;
		listY = y;
		int rowY = y + 2;
		for (Member member : members) {
			if (member.guest() != null) {
				addControls(member.guest(), rowY, canOp);
			}
			rowY += ROW;
		}
		if (alone()) {
			rowY += ROW;
		}
		listH = rowY - y + 2;
		y = rowY + 2;

		String packs = hostView ? HostController.packStatus() : "";
		footerY = -1;
		if (!packs.isEmpty()) {
			footerY = y + 8;
			y = footerY + 10;
		}
		return y;
	}

	private void addControls(Guest guest, int rowY, boolean canOp) {
		GameType mode = HostController.gameModeOf(guest.id());
		GameType current = mode != null ? mode : guest.gameMode() != null ? guest.gameMode() : GameType.SURVIVAL;
		FlatButton modeButton = add(new FlatButton(80, 18, Component.literal(modeName(current)), FlatButton.Style.SECONDARY, Icons.CHEVRON_DOWN,
				b -> screen.openPopover(Popover.menu(List.of(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR).stream()
						.map(m -> Popover.Item.of(modeName(m), null, () -> {
							HostController.setGameMode(guest.id(), m);
							screen.refresh();
						}).selected(m == current)).toList()), b)), width - 136, rowY + 5);
		modeButton.setTooltip(tip("Game mode for " + guest.name()));

		boolean operator = WorldOptions.isOperator(guest.id());
		IconToggle op = add(new IconToggle(18, Component.literal("Operator: " + guest.name()), Icons.SHIELD, operator, on -> {
			WorldOptions.setOperator(guest.id(), on);
			host.sent();
		}), width - 52, rowY + 5);
		op.active = canOp;
		op.setTooltip(tip(!canOp ? "Operators need JavaHost 2.0 on the host"
				: operator ? "Operator: can change world rules, and use commands while Cheats is on. Click to undo."
				: "Make " + guest.name() + " an operator: they can change world rules, and use commands while Cheats is on."));

		FlatButton more = add(new FlatButton(22, 18, Component.literal("More"), FlatButton.Style.GHOST, Icons.MORE,
				b -> screen.openPopover(Popover.menu(playerActions(guest)), b)).iconOnly(), width - 30, rowY + 5);
		more.setTooltip(tip("More for " + guest.name()));
	}

	/** What the host can do about a player, for the row's "more" menu. */
	private List<Popover.Item> playerActions(Guest guest) {
		List<Popover.Item> items = new ArrayList<>();
		if (guest.visitor() || guest.trusted()) {
			items.add(Popover.Item.of(guest.visitor() ? "Trust" : "Untrust", Icons.SHIELD,
					() -> HostController.trust(guest.id(), guest.name(), guest.visitor())));
		}
		if (com.javahost.client.friends.Friends.friend(guest.id()).isPresent()) {
			String problem = WorldShare.cannotSendTo(guest.id());
			var send = Popover.Item.of("Send a world", Icons.UPLOAD, () -> minecraft.gui.setScreen(new WorldPickerScreen(screen,
					com.javahost.client.friends.Friends.friend(guest.id()).orElse(null), null, () -> screen.switchTo(JavaHostScreen.Tab.NOTIFICATIONS))));
			items.add(problem == null ? send : send.disabled("Can't now"));
		}
		items.add(Popover.Item.of("Kick", Icons.CROSS, () -> HostController.kick(guest.id())));
		items.add(Popover.Item.danger("Ban", Icons.STOP, () -> HostController.ban(guest.id(), guest.name())));
		return items;
	}

	/** Pings change without a rebuild. */
	public void tick() {
		if (hostView) {
			collect();
		}
	}

	@Override
	public String signature() {
		StringBuilder s = new StringBuilder().append(hostView).append(hosting).append(HostController.packStatus().isEmpty());
		if (hostView) {
			for (Guest guest : HostController.guests()) {
				s.append(guest.id()).append(WorldShare.cannotSendTo(guest.id())).append(guest.visitor()).append(guest.trusted())
						.append(HostController.gameModeOf(guest.id()));
			}
		} else if (minecraft.getConnection() != null) {
			s.append(minecraft.getConnection().getOnlinePlayers().size());
		}
		return s.toString();
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int rowY = top + listY + 2;
		card(g, left, top + listY, width, listH);
		int controls = hostView ? 144 : 16;
		for (int i = 0; i < members.size(); i++) {
			Member member = members.get(i);
			if (i > 0) {
				divider(g, left, rowY - 1, width);
			}
			Faces.draw(g, member.id(), left + 10, rowY + 5, 18);
			int textW = width - 36 - controls;
			Draw.textFit(g, member.name(), textW, left + 36, rowY + 5, Theme.TEXT);
			drawCaption(g, member, left + 36, rowY + 16, textW);
			rowY += ROW;
		}
		if (alone()) {
			divider(g, left, rowY - 1, width);
			Icons.USERS.draw(g, left + 13, rowY + 9, Theme.FAINT);
			String empty = hosting ? "No one else yet. Invite friends to play." : "Just you. Start hosting to play with friends.";
			Draw.textFit(g, empty, width - 50, left + 36, rowY + 10, Theme.MUTED);
		}

		if (footerY >= 0) {
			Icons.PACKAGE.draw(g, left + 2, top + footerY - 1, Theme.FAINT);
			Draw.textFit(g, HostController.packStatus(), width - 16, left + 15, top + footerY, Theme.MUTED);
		}
	}

	/** "Host", "Operator", "Visitor"... and, for our guests, their ping. */
	private void drawCaption(GuiGraphicsExtractor g, Member member, int x, int y, int maxW) {
		String role;
		int color;
		if (member.you() && hostView) {
			role = "Host";
			color = Theme.ACCENT;
		} else if (WorldOptions.isOperator(member.id())) {
			role = "Operator";
			color = Theme.ACCENT;
		} else if (member.guest() != null && member.guest().visitor()) {
			role = "Visitor";
			color = Theme.WARNING;
		} else if (member.guest() != null && member.guest().trusted()) {
			role = "Trusted";
			color = Theme.MUTED;
		} else {
			role = "";
			color = Theme.MUTED;
		}
		if (member.you() && !hostView) {
			role = role.isEmpty() ? "You" : role + " · you";
		}
		int used = 0;
		if (!role.isEmpty()) {
			Draw.textFit(g, role, maxW, x, y, color);
			used = Draw.width(role) + 4;
		}
		if (member.guest() != null) {
			int ping = member.guest().pingMs();
			String pingText = (role.isEmpty() ? "" : "· ") + (ping <= 0 ? "ping —" : ping + " ms");
			Draw.textFit(g, pingText, maxW - used, x + used, y, ping >= 200 ? Theme.WARNING : Theme.FAINT);
		}
	}
}

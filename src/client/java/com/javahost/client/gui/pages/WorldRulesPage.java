package com.javahost.client.gui.pages;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.javahost.client.gui.Page;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.RulesClient.State;
import com.javahost.rules.WorldRules;
import com.javahost.rules.WorldRules.Rule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * World rules for everyone to see; the host and co-hosts can change them (unless locked). Every change is
 * announced in chat and listed here, so nothing changes quietly.
 */
public class WorldRulesPage extends Page {
	private static final int ROW = 26;
	private State state;
	private int seen = -1;
	private boolean confirmUnlock;
	private int rulesY;
	private int coHostsY;
	private List<PlayerInfo> others = List.of();
	private int historyY;

	@Override
	public String title() {
		return "World rules";
	}

	@Override
	public String subtitle() {
		return "Every change is announced to everyone.";
	}

	@Override
	protected void build() {
		state = RulesClient.state();
		seen = RulesClient.version();
		if (state == null) {
			reserve(60);
			return;
		}
		boolean editable = state.canEdit() && !state.locked();
		int inner = width - 20;

		// The lock (host only), right at the top.
		int y = 0;
		if (state.host()) {
			String label = state.locked() ? (confirmUnlock ? "Click again to unlock" : "Unlock rules") : "Lock rules";
			add(new FlatButton(124, 18, Component.literal(label), state.locked() ? FlatButton.Style.SECONDARY : FlatButton.Style.PRIMARY,
					state.locked() ? Icons.UNLOCK : Icons.LOCK, b -> {
						if (state.locked() && !confirmUnlock) {
							confirmUnlock = true;
							screen.refresh();
							return;
						}
						confirmUnlock = false;
						RulesClient.request("__lock", String.valueOf(!state.locked()));
					}), width - 124, y + 6);
		}

		rulesY = y + 40;
		y = rulesY + 12;
		for (Rule rule : Rule.values()) {
			String value = state.values().getOrDefault(rule.name(), rule.choices.getFirst());
			if (rule.choices.size() == 2 && rule.choices.contains("true")) {
				ToggleSwitch toggle = add(new ToggleSwitch(inner, Component.literal(rule.label), null, Boolean.parseBoolean(value),
						on -> RulesClient.request(rule.name(), String.valueOf(on))), 10, y + 5);
				toggle.active = editable;
			} else {
				int choiceW = Math.min(240, inner - 80);
				Segmented<String> choice = add(new Segmented<>(choiceW, rule.choices, value,
						v -> WorldRules.describe(rule, v), v -> RulesClient.request(rule.name(), v)), width - 10 - choiceW, y + 4);
				choice.active = editable;
			}
			y += ROW;
		}

		// Co-hosts (host only): players in the world who may change rules.
		coHostsY = -1;
		if (state.host()) {
			UUID me = minecraft.player == null ? null : minecraft.player.getUUID();
			others = minecraft.getConnection() == null ? List.of() : minecraft.getConnection().getOnlinePlayers().stream()
					.filter(p -> !p.getProfile().id().equals(me)).toList();
			coHostsY = y + 12;
			y = coHostsY + 12;
			for (PlayerInfo player : others) {
				UUID id = player.getProfile().id();
				boolean coHost = state.coHosts().stream().anyMatch(c -> c.id().equals(id));
				add(new ToggleSwitch(inner - 30, Component.literal(player.getProfile().name()), null, coHost,
						on -> RulesClient.request("__cohost:" + id, String.valueOf(on))), 40, y + 5);
				y += ROW;
			}
			if (others.isEmpty()) {
				y += ROW;
			}
		}
		historyY = y + 12;
		reserve(historyY + 12 + Math.max(1, Math.min(8, state.history().size())) * 14 + 16);
	}

	@Override
	public void tick() {
		if (RulesClient.version() != seen) {
			confirmUnlock = false;
			screen.refresh();
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (state == null) {
			card(g, left, top, width, 40);
			Icons.WARNING.draw(g, left + 10, top + 14, Theme.FAINT);
			Draw.textFit(g, minecraft.level == null ? "Open a world to see its rules." : "This world's host needs JavaHost 1.5 for world rules.",
					width - 34, left + 26, top + 16, Theme.MUTED);
			return;
		}
		// Status line: who can change, and the lock.
		Icons icon = state.locked() ? Icons.LOCK : state.canEdit() ? Icons.SHIELD : Icons.EDIT;
		int color = state.locked() ? Theme.WARNING : state.canEdit() ? Theme.ACCENT : Theme.MUTED;
		String status = state.locked() ? "Locked for a challenge run" : state.host() ? "You're the host" : state.canEdit() ? "You're a co-host" : "Only the host can change these";
		String detail = state.locked() ? "Nobody can change the rules until the host unlocks them."
				: state.host() ? "Make friends co-hosts below to let them change rules too." : "Ask the host if you'd like something changed.";
		icon.draw(g, left, top + 4, color);
		Draw.textFit(g, status, width - (state.host() ? 150 : 20), left + 16, top + 5, color);
		Draw.textFit(g, detail, width - (state.host() ? 150 : 20), left + 16, top + 17, Theme.FAINT);

		sectionLabel(g, "Rules", left, top + rulesY);
		card(g, left, top + rulesY + 12, width, Rule.values().length * ROW + 4);
		int y = top + rulesY + 12;
		for (Rule rule : Rule.values()) {
			if (!(rule.choices.size() == 2 && rule.choices.contains("true"))) {
				Draw.textFit(g, rule.label, width - Math.min(240, width - 100) - 30, left + 10, y + 9, Theme.TEXT);
			}
			y += ROW;
		}

		if (coHostsY >= 0) {
			sectionLabel(g, "Co-hosts can change rules", left, top + coHostsY);
			card(g, left, top + coHostsY + 12, width, Math.max(1, others.size()) * ROW + 4);
			int rowY = top + coHostsY + 12;
			if (others.isEmpty()) {
				Draw.textFit(g, "Nobody else is here right now.", width - 20, left + 10, rowY + 9, Theme.FAINT);
			}
			for (PlayerInfo player : others) {
				Faces.draw(g, player.getProfile().id(), left + 12, rowY + 6, 14);
				rowY += ROW;
			}
		}

		sectionLabel(g, "Recent changes", left, top + historyY);
		List<RulesClient.Change> history = state.history().stream().limit(8).toList();
		card(g, left, top + historyY + 12, width, Math.max(1, history.size()) * 14 + 8);
		int rowY = top + historyY + 18;
		if (history.isEmpty()) {
			Draw.textFit(g, "No changes yet.", width - 20, left + 10, rowY, Theme.FAINT);
		}
		for (RulesClient.Change change : history) {
			String when = ago(change.time());
			Draw.textFit(g, change.who() + " " + change.what(), width - 30 - Draw.font().width(when), left + 10, rowY, Theme.MUTED);
			Draw.text(g, when, left + width - 10 - Draw.font().width(when), rowY, Theme.FAINT);
			rowY += 14;
		}
	}

	private static String ago(long time) {
		Duration d = Duration.ofMillis(System.currentTimeMillis() - time);
		return d.toMinutes() < 1 ? "just now" : d.toHours() < 1 ? d.toMinutes() + "m ago" : d.toDays() < 1 ? d.toHours() + "h ago" : d.toDays() + "d ago";
	}
}

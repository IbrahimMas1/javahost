package com.javahost.client.gui.pages.world;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.WorldOptions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Recent changes to the world's options (who changed what, and when), as the host's server keeps them, newest first.
 * The host can also have every change announced in chat.
 */
public final class RecentSection extends WorldSection {
	private static final int ROW = 28;
	private static final int ANNOUNCE_H = 40;
	/** "changed keep_inventory: false → true", as the server records a game rule change. */
	private static final Pattern RULE_CHANGE = Pattern.compile("changed ([A-Za-z0-9_.:/-]+): (.*) → (.*)");

	private record Line(String what, String who, long time) {
	}

	private int sectionY;
	private int listTop;
	private boolean announce;
	private List<Line> lines = List.of();

	public RecentSection(Host host) {
		super(host);
	}

	private static RecentSection last;

	/** For tests: what the list shows, newest first ("Keep inventory: Off → On"...). */
	public static List<String> shown() {
		RecentSection section = last;
		return section == null ? List.of() : section.lines.stream().map(Line::what).toList();
	}

	@Override
	public String signature() {
		return WorldOptions.version() + "/" + RulesClient.version();
	}

	@Override
	protected int layout(int y) {
		last = this;
		sectionY = y;
		RulesClient.State state = RulesClient.state();
		lines = state == null ? List.of() : state.history().stream().map(c -> new Line(describe(c.what()), c.who(), c.time())).toList();
		int top = y + LABEL;
		announce = WorldOptions.available() && WorldOptions.role() == WorldOptions.Role.HOST;
		if (announce) {
			add(new ToggleSwitch(width - 2 * PAD, Component.literal("Announce changes in chat"), "Everyone sees each change as it happens",
					WorldOptions.announce(), on -> {
						WorldOptions.setAnnounce(on);
						host.sent();
					}), PAD, top + 8);
			top += ANNOUNCE_H;
		}
		listTop = top;
		return top + Math.max(1, lines.size()) * ROW;
	}

	/** The server's wording, made to read like a list: "changed keep_inventory: false → true" → "Keep inventory: Off → On". */
	static String describe(String what) {
		Matcher m = RULE_CHANGE.matcher(what);
		if (m.matches()) {
			RuleList.Entry rule = RuleList.find(m.group(1));
			if (rule != null) {
				return rule.name() + ": " + rule.describe(m.group(2)) + " → " + rule.describe(m.group(3));
			}
		}
		String text = what.startsWith("changed ") ? what.substring("changed ".length()) : what;
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static String ago(long time) {
		Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - time));
		return d.toMinutes() < 1 ? "just now" : d.toHours() < 1 ? d.toMinutes() + " min ago" : d.toDays() < 1 ? d.toHours() + " h ago" : d.toDays() + " d ago";
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int y = top + sectionY;
		label(g, "Recent changes", left, y);
		int cardTop = y + LABEL;
		int cardH = top + listTop - cardTop + Math.max(1, lines.size()) * ROW;
		card(g, left, cardTop, width, cardH);
		if (announce) {
			divider(g, left, top + listTop, width);
		}
		int rowY = top + listTop;
		int textW = width - 2 * PAD;
		if (lines.isEmpty()) {
			Draw.textFit(g, "No changes yet", textW, left + PAD, rowY + 5, Theme.MUTED);
			Draw.textFit(g, "Rules, operators and the lock: every change shows here", textW, left + PAD, rowY + 15, Theme.FAINT);
			return;
		}
		int screenH = minecraft.getWindow().getGuiScaledHeight();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				divider(g, left, rowY, width);
			}
			if (rowY < screenH && rowY + ROW > 0) {
				Line line = lines.get(i);
				Draw.textFit(g, line.what(), textW, left + PAD, rowY + 5, Theme.TEXT);
				Draw.textFit(g, line.who() + " · " + ago(line.time()), textW, left + PAD, rowY + 15, Theme.FAINT);
			}
			rowY += ROW;
		}
	}
}

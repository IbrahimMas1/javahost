package com.javahost.client.gui.pages.world;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.discover.PublicHosting;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.host.HostController;
import com.javahost.client.rules.RulesClient;
import com.javahost.client.rules.WorldOptions;
import com.javahost.util.WorldOptionsData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The world's main settings: difficulty, default game mode and Cheats (card "World"), then who can join your worlds
 * (card "Who can join", the host's own settings). Values come from the server ({@link WorldOptions}); a change is a
 * request, and the page rebuilds from what the server says back.
 */
public final class GeneralSection extends WorldSection {
	private static final int WORLD_CARD = 118;
	private static final int OLD_CARD = 46;
	private static final int JOIN_CARD = 74;
	private static final int PUBLIC_ROW = 38;

	private boolean worldCard;
	private boolean joinCard;
	private boolean publicRow;
	private int worldY = -1;
	private int worldH;
	private String worldNote;
	/** Where the card starts inside the section: under a label line only when there is a note to show on it. */
	private int worldTop;
	private int joinY = -1;
	private int joinH;

	public GeneralSection(Host host) {
		super(host);
	}

	/**
	 * {@code worldCard}: a world is open (its difficulty, game mode, Cheats); {@code joinCard}: show the host's "Who can
	 * join" settings; {@code publicRow}: with the public listing (needs your world open).
	 */
	public void configure(boolean worldCard, boolean joinCard, boolean publicRow) {
		this.worldCard = worldCard;
		this.joinCard = joinCard;
		this.publicRow = publicRow;
	}

	private static String difficultyName(String d) {
		return switch (d) {
			case "peaceful" -> "Peaceful";
			case "easy" -> "Easy";
			case "hard" -> "Hard";
			default -> "Normal";
		};
	}

	private static String gameModeName(String m) {
		return switch (m) {
			case "creative" -> "Creative";
			case "adventure" -> "Adventure";
			case "spectator" -> "Spectator";
			default -> "Survival";
		};
	}

	@Override
	protected int layout(int y) {
		worldY = -1;
		joinY = -1;
		worldNote = null;
		if (worldCard) {
			y = buildWorld(y);
		}
		if (joinCard) {
			y = buildJoin(worldCard ? y + GAP : y);
		}
		return y;
	}

	// ---------------------------------------------------------------- the world

	private int buildWorld(int y) {
		worldY = y;
		int inner = width - 2 * PAD;
		if (WorldOptions.available()) {
			WorldOptions.Role role = WorldOptions.role();
			boolean isHost = role == WorldOptions.Role.HOST;
			boolean canChange = WorldOptions.canChangeRules();
			if (WorldOptions.locked()) {
				worldNote = "Locked for a challenge run";
			} else if (!canChange) {
				worldNote = "Set by the host and operators";
			}
			// The tab already says "General": the card only gets a label line when there is a note to put on it.
			int top = worldTop = y + (worldNote != null ? LABEL : 0);
			Segmented<String> difficulty = add(new Segmented<>(inner, WorldOptionsData.DIFFICULTIES, WorldOptions.difficulty(),
					GeneralSection::difficultyName, d -> {
						WorldOptions.setDifficulty(d);
						host.sent();
					}), PAD, top + 19);
			difficulty.active = canChange;
			Segmented<String> mode = add(new Segmented<>(inner, WorldOptionsData.GAME_MODES, WorldOptions.defaultGameMode(),
					GeneralSection::gameModeName, m -> {
						WorldOptions.setDefaultGameMode(m);
						host.sent();
					}), PAD, top + 56);
			mode.active = canChange;
			mode.setTooltip(tip("The game mode players get when they first join"));

			boolean locked = WorldOptions.cheatsLocked();
			String hint = locked ? WorldOptions.cheatsLockedReason() : "Operators can use commands while it's on";
			ToggleSwitch cheats = add(new ToggleSwitch(inner, Component.literal("Cheats"), hint.isEmpty() ? "Always on in this world" : hint,
					WorldOptions.cheats() || locked, on -> {
						WorldOptions.setCheats(on);
						host.sent();
					}), PAD, top + 86);
			cheats.active = isHost && !locked;
			cheats.setTooltip(tip(locked ? (hint.isEmpty() ? "This world was made with cheats on." : hint)
					: isHost ? "Lets operators use commands (you always can). Turn it off for a fair game."
					: "Only the host can switch Cheats."));

			worldH = WORLD_CARD;
		} else {
			int top = worldTop = y + LABEL;
			// An older host: what its world rules can do (difficulty), or nothing.
			RulesClient.State old = RulesClient.state();
			if (old != null) {
				Segmented<String> difficulty = add(new Segmented<>(inner, WorldOptionsData.DIFFICULTIES,
						old.values().getOrDefault("DIFFICULTY", "normal"), GeneralSection::difficultyName, d -> {
							RulesClient.request("DIFFICULTY", d);
							host.sent();
						}), PAD, top + 19);
				difficulty.active = old.canEdit() && !old.locked();
				worldH = OLD_CARD;
			} else {
				worldH = 0;
			}
			worldNote = minecraft.getSingleplayerServer() != null ? "Loading..." : "The host has an older JavaHost";
		}
		return worldTop + worldH;
	}

	// ---------------------------------------------------------------- who can join (the host's own settings)

	private int buildJoin(int y) {
		Config config = Config.get();
		joinY = y;
		int top = y + LABEL;
		int inner = width - 2 * PAD;
		add(new ToggleSwitch(inner, Component.literal("Only friends can join"), "Others with the code are turned away",
				config.friendsOnly, v -> {
					config.friendsOnly = v;
					config.save();
					HostController.applyHostRules(config);
				}), PAD, top + 8);
		add(new Segmented<>(132, List.of(2, 4, 8, 16), config.maxPlayers, String::valueOf, n -> {
			config.maxPlayers = n;
			config.save();
			HostController.applyHostRules(config);
		}), width - 132 - PAD, top + 48);
		joinH = JOIN_CARD;
		if (publicRow) {
			int rowY = top + JOIN_CARD;
			if (PublicHosting.isPublic()) {
				add(new FlatButton(90, 18, Component.literal("Stop listing"), FlatButton.Style.DANGER, Icons.STOP, b -> {
					PublicHosting.close();
					screen.refresh();
				}), width - 90 - PAD, rowY + 10);
			} else {
				String problem = PublicHosting.cannotOpen();
				FlatButton open = add(new FlatButton(64, 18, Component.literal("List it"), FlatButton.Style.SECONDARY, Icons.GLOBE,
						b -> minecraft.gui.setScreen(new com.javahost.client.gui.PublicWorldScreen(screen))), width - 64 - PAD, rowY + 10);
				open.active = problem == null;
				open.setTooltip(tip(problem != null ? problem
						: "List your world so anyone with JavaHost can find it in Public worlds. Strangers visit until you trust them."));
			}
			joinH += PUBLIC_ROW;
		}
		return top + joinH;
	}

	@Override
	public String signature() {
		return WorldOptions.version() + "/" + RulesClient.version() + "/" + PublicHosting.isPublic() + PublicHosting.listed();
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		if (worldY >= 0) {
			if (worldNote != null) {
				label(g, "World", worldNote, left, top + worldY, width);
			}
			if (worldH > 0) {
				int c = top + worldTop;
				card(g, left, c, width, worldH);
				Draw.text(g, "Difficulty", left + PAD, c + 8, Theme.TEXT);
				if (worldH == WORLD_CARD) {
					Draw.text(g, "Default game mode", left + PAD, c + 45, Theme.TEXT);
					divider(g, left, c + 81, width);
				}
			}
		}
		if (joinY >= 0) {
			int y = top + joinY;
			label(g, "Who can join", "Applies to all your worlds", left, y, width);
			card(g, left, y + LABEL, width, joinH);
			int c = y + LABEL;
			divider(g, left, c + 40, width);
			Draw.text(g, "Player limit", left + PAD, c + 48, Theme.TEXT);
			Draw.text(g, "Including you", left + PAD, c + 59, Theme.FAINT);
			if (publicRow) {
				int r = c + JOIN_CARD;
				divider(g, left, r, width);
				int textW = width - 2 * PAD - 100;
				boolean isPublic = PublicHosting.isPublic();
				Draw.textFit(g, isPublic ? "Public: \"" + Config.get().publicTitle + "\"" : "Public listing (Beta)", textW, left + PAD, r + 10, Theme.TEXT);
				String detail = isPublic ? PublicHosting.status() : "Shown in Public worlds";
				Draw.textFit(g, detail, textW, left + PAD, r + 21, isPublic ? Theme.MUTED : Theme.FAINT);
			}
		}
	}
}

package com.javahost.util;

/**
 * Who may use commands in a world the host opened from their own game. The host keeps whatever the game gives them;
 * anyone else gets the "gamemasters" level (/gamemode, /give, /tp, /time...; not /stop, /op or /ban) only while they
 * are one of the host's operators and Cheats is on for the world, and nothing otherwise, whatever the game's own ops
 * list or "allow commands" says.
 */
public final class OperatorPolicy {
	/** Minecraft's "gamemasters" permission level. */
	public static final int GAMEMASTERS = 2;
	public static final int NONE = 0;

	private OperatorPolicy() {
	}

	/**
	 * @param owner    the player whose game runs the world
	 * @param operator made an operator by the host (JavaHost's list, kept with the world)
	 * @param cheats   Cheats is on for the world
	 * @param vanilla  the level the game itself would give
	 */
	public static int level(boolean owner, boolean operator, boolean cheats, int vanilla) {
		if (owner) {
			return vanilla;
		}
		return operator && cheats ? GAMEMASTERS : NONE;
	}
}

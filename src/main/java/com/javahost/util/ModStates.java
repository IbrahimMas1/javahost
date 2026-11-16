package com.javahost.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What each of the player's mods is doing, for the My mods list: every jar in mods/ and in the off store, with a state
 * worked out from what the game loaded, what's wanted, and what's being removed. Pure, so it's unit-tested.
 */
public final class ModStates {
	/**
	 * ON: in mods/ and running. OFF: in the off store. TURNING_OFF / TURNING_ON: a wanted change, made when the game
	 * closes. NEW: in mods/ but not loaded this session (put there since the game started): it loads next start.
	 * REMOVING: on the list of mods deleted when the game closes.
	 */
	public enum State { ON, OFF, TURNING_OFF, TURNING_ON, NEW, REMOVING }

	/**
	 * One row. {@code inMods}: the jar is in mods/ now (else in the off store). {@code protectedReason}: why it always
	 * stays on, or null. {@code slug}: the Modrinth name if JavaHost installed it (it left a marker), else null.
	 * {@code fromSet}: added by the mod set in use.
	 */
	public record Entry(ModJar jar, boolean inMods, State state, String protectedReason, String slug, boolean fromSet) {
		public String file() {
			return jar.file();
		}

		public String name() {
			return jar.name();
		}

		/** Whether it will be on once the game has closed (where the switch points). */
		public boolean wantedOn() {
			return state == State.ON || state == State.NEW || state == State.TURNING_ON;
		}

		public boolean isProtected() {
			return protectedReason != null;
		}

		public boolean installedByJavaHost() {
			return slug != null;
		}

		/** Waits for the game to close (a change, a removal, or a new mod). */
		public boolean pending() {
			return state == State.TURNING_OFF || state == State.TURNING_ON || state == State.NEW || state == State.REMOVING;
		}
	}

	/**
	 * Everything the states come from.
	 *
	 * @param on           the jars in mods/
	 * @param off          the jars in the off store
	 * @param loadedFiles  file names in mods/ that Fabric loaded this session
	 * @param loadedIds    mod ids loaded from somewhere other than a jar in mods/ (built in, or a test's pretend list):
	 *                     a jar in mods/ with one of these ids counts as loaded
	 * @param atStart      file names that were in mods/ when the game started (a jar that wasn't, and isn't loaded, is new)
	 * @param changes      the wanted changes
	 * @param removing     file names deleted when the game closes
	 * @param slugs        jar file name to the Modrinth name, for jars JavaHost installed
	 * @param protectedIds mod id to why it always stays on
	 * @param setFiles     jars the mod set in use added
	 */
	public record Inputs(List<ModJar> on, List<ModJar> off, Set<String> loadedFiles, Set<String> loadedIds, Set<String> atStart,
			ModChanges changes, Set<String> removing, Map<String, String> slugs, Map<String, String> protectedIds, Set<String> setFiles) {
	}

	private ModStates() {
	}

	/** Why this jar always stays on, or null. */
	public static String protectedReason(ModJar jar, Map<String, String> protectedIds) {
		for (String id : jar.ids()) {
			String reason = protectedIds.get(id);
			if (reason != null) {
				return reason;
			}
		}
		return null;
	}

	/** Every mod, on ones first, then off ones, each by name. A name in both places is listed once, from mods/. */
	public static List<Entry> derive(Inputs in) {
		List<Entry> out = new ArrayList<>();
		Set<String> inMods = new HashSet<>();
		for (ModJar jar : in.on()) {
			String file = jar.file();
			inMods.add(file);
			State state;
			if (in.removing().contains(file)) {
				state = State.REMOVING;
			} else if (in.changes().off().contains(file)) {
				state = State.TURNING_OFF;
			} else if (in.loadedFiles().contains(file) || jar.id() != null && in.loadedIds().contains(jar.id())) {
				state = State.ON;
			} else if (!in.atStart().contains(file)) {
				state = State.NEW;
			} else {
				state = State.ON; // there since the start, but not something Fabric loads (or loaded it nested)
			}
			out.add(new Entry(jar, true, state, protectedReason(jar, in.protectedIds()), in.slugs().get(file), in.setFiles().contains(file)));
		}
		for (ModJar jar : in.off()) {
			if (inMods.contains(jar.file())) {
				continue;
			}
			State state = in.changes().on().contains(jar.file()) ? State.TURNING_ON : State.OFF;
			out.add(new Entry(jar, false, state, protectedReason(jar, in.protectedIds()), in.slugs().get(jar.file()), false));
		}
		out.sort(ORDER);
		return out;
	}

	/** On (in mods/) first, then off; by name, ignoring case; then by file name. */
	public static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> !e.inMods())
			.thenComparing(e -> e.name(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Entry::file);

	/** Entries whose name, mod id or file name has {@code query} in it (case and spaces don't matter). */
	public static List<Entry> search(List<Entry> entries, String query) {
		String q = squash(query);
		if (q.isEmpty()) {
			return entries;
		}
		List<Entry> found = new ArrayList<>();
		for (Entry entry : entries) {
			if (squash(entry.name()).contains(q) || entry.jar().id() != null && squash(entry.jar().id()).contains(q)
					|| squash(entry.file()).contains(q)) {
				found.add(entry);
			}
		}
		return found;
	}

	private static String squash(String text) {
		return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}
}

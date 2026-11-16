package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The on/off changes the player wants, made when the game closes: jars in mods/ to turn off, and jars in the off store
 * to turn back on. Kept in a small file so they survive closing the menu (and a crash: they're then made at the next
 * normal exit). Names are plain jar file names; anything else is ignored.
 */
public final class ModChanges {
	/** At most this many wanted changes (a list longer than that wasn't written by JavaHost). */
	public static final int MAX = ModJar.MAX_JARS;
	private static final long MAX_FILE_BYTES = 1 << 20;

	private final Set<String> off = new TreeSet<>();
	private final Set<String> on = new TreeSet<>();

	public ModChanges() {
	}

	public ModChanges(ModChanges other) {
		off.addAll(other.off);
		on.addAll(other.on);
	}

	/** A plain jar file name: no folders, nothing hidden behind "..". */
	public static boolean safeName(String name) {
		return name != null && RemoveAfterExit.safeName(name) && name.toLowerCase(Locale.ROOT).endsWith(".jar") && !name.startsWith(".");
	}

	/** Jars in mods/ that should be off. */
	public Set<String> off() {
		return Collections.unmodifiableSet(off);
	}

	/** Jars in the off store that should be on. */
	public Set<String> on() {
		return Collections.unmodifiableSet(on);
	}

	public boolean isEmpty() {
		return off.isEmpty() && on.isEmpty();
	}

	public int size() {
		return off.size() + on.size();
	}

	public void clear() {
		off.clear();
		on.clear();
	}

	/**
	 * Wants {@code file} on or off. {@code onNow}: whether it's in mods/ now. Wanting what already is simply forgets
	 * the change. Returns false if the name isn't a plain jar name or the list is full.
	 */
	public boolean want(String file, boolean wantOn, boolean onNow) {
		if (!safeName(file)) {
			return false;
		}
		Set<String> list = onNow ? off : on;
		if (wantOn == onNow) {
			list.remove(file);
			return true;
		}
		if (size() >= MAX && !list.contains(file)) {
			return false;
		}
		list.add(file);
		return true;
	}

	/** Whether {@code file} will be on after the game closes. */
	public boolean wantsOn(String file, boolean onNow) {
		return onNow ? !off.contains(file) : on.contains(file);
	}

	/** Forgets changes for jars that are gone (deleted by hand, or moved meanwhile). Returns true if any were dropped. */
	public boolean prune(Collection<String> onNow, Collection<String> offNow) {
		Set<String> mods = new TreeSet<>();
		mods.addAll(onNow);
		Set<String> store = new TreeSet<>();
		store.addAll(offNow);
		boolean changed = off.removeIf(name -> !mods.contains(name));
		changed |= on.removeIf(name -> !store.contains(name));
		return changed;
	}

	/** Every jar that should be off once the game has closed: what's off now and stays so, plus what's wanted off. */
	public Set<String> wantOff(Collection<String> onNow, Collection<String> offNow) {
		Set<String> wantOff = new LinkedHashSet<>();
		for (String name : offNow) {
			if (!on.contains(name)) {
				wantOff.add(name);
			}
		}
		for (String name : onNow) {
			if (off.contains(name)) {
				wantOff.add(name);
			}
		}
		return wantOff;
	}

	/** The one swap that makes these changes ({@link ModToggle#plan}). {@code skip}: jars to leave alone (being removed). */
	public ModToggle.Plan plan(Collection<String> onNow, Collection<String> offNow, Set<String> skip) {
		List<String> mods = new ArrayList<>(onNow);
		mods.removeAll(skip);
		return ModToggle.plan(mods, offNow, wantOff(mods, offNow));
	}

	// ---------------------------------------------------------------- the file

	/** Reads the file; a missing, unreadable or oversized one gives no changes. */
	public static ModChanges read(Path file) {
		ModChanges changes = new ModChanges();
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
				return changes;
			}
			Properties properties = new Properties();
			try (InputStream in = Files.newInputStream(file)) {
				properties.load(in);
			}
			for (String name : ModSwap.list(properties, "off")) {
				if (safeName(name) && changes.size() < MAX) {
					changes.off.add(name);
				}
			}
			for (String name : ModSwap.list(properties, "on")) {
				if (safeName(name) && changes.size() < MAX && !changes.off.contains(name)) {
					changes.on.add(name);
				}
			}
		} catch (IOException | RuntimeException e) {
			changes.clear();
		}
		return changes;
	}

	/** Writes the file (next to it first, then moved over it); no changes deletes it. */
	public void write(Path file) throws IOException {
		if (isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		Properties properties = new Properties();
		ModSwap.store(properties, "off", new ArrayList<>(off));
		ModSwap.store(properties, "on", new ArrayList<>(on));
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream out = Files.newOutputStream(temp)) {
			properties.store(out, "JavaHost: mods to turn on and off when the game closes");
		}
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}

package com.javahost.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vanilla mode, worked out: which jars in mods/ stay (JavaHost, Fabric API, e4mc and, if asked, the performance mods,
 * plus everything those need) and which are put aside; and the swap that brings them back. Pure, so it's unit-tested.
 * Matched by mod id (and what a jar provides), never by file name, so a renamed or updated jar is still recognised.
 */
public final class VanillaPlan {
	private VanillaPlan() {
	}

	/**
	 * The jars that stay: every jar answering to one of {@code keepIds}, then, over and over, a jar for each id a kept
	 * jar depends on (its own id first, else the first that provides it). Ids nothing provides are skipped (built in, or
	 * missing anyway). Returns file names, in folder order.
	 */
	public static Set<String> keepFiles(Collection<ModJar> on, Set<String> keepIds) {
		Set<String> kept = new LinkedHashSet<>();
		List<String> wanted = new ArrayList<>(keepIds);
		Set<String> seen = new LinkedHashSet<>();
		for (int i = 0; i < wanted.size(); i++) {
			String id = wanted.get(i);
			if (!seen.add(id)) {
				continue;
			}
			List<ModJar> providers = providers(on, id);
			for (ModJar jar : providers) {
				if (kept.add(jar.file())) {
					wanted.addAll(jar.depends());
				}
			}
		}
		List<String> ordered = new ArrayList<>();
		for (ModJar jar : on) {
			if (kept.contains(jar.file())) {
				ordered.add(jar.file());
			}
		}
		return new LinkedHashSet<>(ordered);
	}

	/**
	 * Jars answering to {@code id}. For one of the ids asked to stay, every jar with that id stays (two copies of
	 * JavaHost mid-update both stay; the game sorts that out). For a dependency, the jar whose own id it is if there is
	 * one, else the first that provides it.
	 */
	private static List<ModJar> providers(Collection<ModJar> on, String id) {
		List<ModJar> own = new ArrayList<>();
		ModJar provider = null;
		for (ModJar jar : on) {
			if (id.equals(jar.id())) {
				own.add(jar);
			} else if (provider == null && jar.provides().contains(id)) {
				provider = jar;
			}
		}
		if (!own.isEmpty()) {
			return own;
		}
		return provider == null ? List.of() : List.of(provider);
	}

	/** The jars put aside: everything in mods/ that doesn't stay (jars that aren't Fabric mods too). */
	public static List<String> putAside(Collection<ModJar> on, Set<String> keepIds) {
		Set<String> keep = keepFiles(on, keepIds);
		List<String> out = new ArrayList<>();
		for (ModJar jar : on) {
			if (!keep.contains(jar.file())) {
				out.add(jar.file());
			}
		}
		return out;
	}

	/** The ids that always stay, plus the performance mods' ids when asked. */
	public static Set<String> keepIds(boolean keepPerformance, Collection<String> performanceIds) {
		Set<String> ids = new LinkedHashSet<>(ModToggle.VANILLA_KEEP);
		if (keepPerformance) {
			ids.addAll(performanceIds);
		}
		return ids;
	}

	/** Going into Vanilla mode: put aside (minus {@code skip}, jars being removed anyway); nothing comes back. */
	public static ModToggle.Plan enter(Collection<ModJar> on, Set<String> keepIds, Set<String> skip) {
		List<String> out = new ArrayList<>(putAside(on, keepIds));
		out.removeAll(skip);
		return new ModToggle.Plan(out, List.of());
	}

	/** Leaving Vanilla mode: every jar the store holds comes back; nothing goes out. */
	public static ModToggle.Plan leave(Collection<String> stored) {
		List<String> back = new ArrayList<>();
		for (String name : stored) {
			if (ModChanges.safeName(name) && !back.contains(name)) {
				back.add(name);
			}
		}
		return new ModToggle.Plan(List.of(), back);
	}
}

package com.javahost.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Plans turning the player's mods on and off. Nothing is moved here: the jars can only be moved once the game has
 * closed, by {@link ModSwap} (through {@link AfterExit}), with the plan {@link #toProperties} builds.
 *
 * <p>"On" jars are the ones in mods/, "off" jars the ones in the off store. Turning a library off takes the mods that
 * need it with it; turning a mod on brings back what it needs. Some mods always stay on (JavaHost, Fabric API, and any
 * the caller adds, such as the jars of the mod set in use).
 */
public final class ModToggle {
	/** Never turned off: JavaHost itself, and Fabric API, which it needs. */
	public static final Set<String> ALWAYS_ON = Set.of("javahost", "fabric-api");
	/** e4mc's mod id (the relay JavaHost uses; see {@code E4mcCompat}). */
	public static final String E4MC = "e4mc";
	/** What Vanilla mode keeps. */
	public static final Set<String> VANILLA_KEEP = Set.of("javahost", "fabric-api", E4MC);

	/** The files to move, or why not ({@code refusal} is null when it's fine). {@code missing}: ids nobody provides. */
	public record Result(List<String> files, String refusal, List<String> missing) {
		public Result {
			files = List.copyOf(files);
			missing = List.copyOf(missing);
		}

		public boolean ok() {
			return refusal == null;
		}

		static Result refused(String reason) {
			return new Result(List.of(), reason, List.of());
		}
	}

	/** One swap: on jars to put in the store ({@code out}) and stored jars to bring back ({@code back}). */
	public record Plan(List<String> out, List<String> back) {
		public Plan {
			out = List.copyOf(out);
			back = List.copyOf(back);
		}

		public boolean isEmpty() {
			return out.isEmpty() && back.isEmpty();
		}
	}

	private final Map<String, ModJar> on = new LinkedHashMap<>();
	private final Map<String, ModJar> off = new LinkedHashMap<>();
	private final Set<String> protectedIds = new LinkedHashSet<>(ALWAYS_ON);
	private final Set<String> protectedFiles = new LinkedHashSet<>();

	/**
	 * @param on             the jars in mods/
	 * @param off            the jars in the off store
	 * @param protectedIds   more mod ids that must stay on (JavaHost and Fabric API always do)
	 * @param protectedFiles file names that must stay on (e.g. the jars of the mod set in use)
	 */
	public ModToggle(Collection<ModJar> on, Collection<ModJar> off, Set<String> protectedIds, Set<String> protectedFiles) {
		on.forEach(jar -> this.on.put(jar.file(), jar));
		off.forEach(jar -> this.off.put(jar.file(), jar));
		this.protectedIds.addAll(protectedIds);
		this.protectedFiles.addAll(protectedFiles);
	}

	public ModToggle(Collection<ModJar> on, Collection<ModJar> off, Set<String> protectedIds) {
		this(on, off, protectedIds, Set.of());
	}

	/** Whether this jar must stay on. */
	public boolean isProtected(ModJar jar) {
		if (protectedFiles.contains(jar.file())) {
			return true;
		}
		for (String id : jar.ids()) {
			if (protectedIds.contains(id)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The on jars that must go off together with {@code file}: the jar itself and, over and over, every on jar that
	 * depends on an id only the going jars provide. Refused if any of them must stay on.
	 */
	public Result turnOff(String file) {
		ModJar jar = on.get(file);
		if (jar == null) {
			return Result.refused("That mod isn't on.");
		}
		if (isProtected(jar)) {
			return Result.refused(jar.name() + " always stays on.");
		}
		Set<String> going = new LinkedHashSet<>(List.of(file));
		Map<String, String> neededBy = new LinkedHashMap<>(); // dependant file -> the file it was taken off for
		boolean grew = true;
		while (grew) {
			grew = false;
			Set<String> stillProvided = new LinkedHashSet<>();
			for (ModJar staying : on.values()) {
				if (!going.contains(staying.file())) {
					stillProvided.addAll(staying.ids());
				}
			}
			Set<String> lost = new LinkedHashSet<>();
			for (String gone : going) {
				for (String id : on.get(gone).ids()) {
					if (!stillProvided.contains(id)) {
						lost.add(id);
					}
				}
			}
			for (ModJar staying : List.copyOf(on.values())) {
				if (going.contains(staying.file())) {
					continue;
				}
				for (String dep : staying.depends()) {
					if (lost.contains(dep)) {
						going.add(staying.file());
						neededBy.put(staying.file(), providerOf(dep, going));
						grew = true;
						break;
					}
				}
			}
		}
		for (String name : going) {
			ModJar dependant = on.get(name);
			if (isProtected(dependant)) {
				String via = neededBy.get(name);
				String needs = via == null || via.equals(file) ? jar.name() : on.get(via).name() + " (which needs " + jar.name() + ")";
				return Result.refused(dependant.name() + " needs " + needs + ", and " + dependant.name() + " always stays on.");
			}
		}
		return new Result(sorted(going), null, List.of());
	}

	private String providerOf(String id, Set<String> going) {
		for (String name : going) {
			if (on.get(name).ids().contains(id)) {
				return name;
			}
		}
		return null;
	}

	/**
	 * The off jars that must come back together with {@code file}: the jar itself and, over and over, an off jar
	 * providing each id they need that no on jar provides. Ids nothing provides are listed in {@code missing}.
	 */
	public Result turnOn(String file) {
		ModJar jar = off.get(file);
		if (jar == null) {
			return Result.refused("That mod isn't turned off.");
		}
		Set<String> coming = new LinkedHashSet<>(List.of(file));
		Set<String> missing = new LinkedHashSet<>();
		boolean grew = true;
		while (grew) {
			grew = false;
			Set<String> provided = new LinkedHashSet<>();
			on.values().forEach(j -> provided.addAll(j.ids()));
			coming.forEach(name -> provided.addAll(off.get(name).ids()));
			for (String name : List.copyOf(coming)) {
				for (String dep : off.get(name).depends()) {
					if (provided.contains(dep) || missing.contains(dep)) {
						continue;
					}
					ModJar provider = offProvider(dep);
					if (provider == null) {
						missing.add(dep);
					} else {
						coming.add(provider.file());
						provided.addAll(provider.ids());
						grew = true;
					}
				}
			}
		}
		return new Result(sorted(coming), null, List.copyOf(missing));
	}

	/** An off jar providing {@code id}: one whose own id it is first, else the first by name. */
	private ModJar offProvider(String id) {
		ModJar first = null;
		for (ModJar jar : off.values()) {
			if (id.equals(jar.id())) {
				return jar;
			}
			if (first == null && jar.provides().contains(id)) {
				first = jar;
			}
		}
		return first;
	}

	/**
	 * The one swap that takes the folder from {@code on}/{@code off} to "everything in {@code wantOff} is off, the rest
	 * is on": on jars in {@code wantOff} go out, off jars not in it come back.
	 */
	public static Plan plan(Collection<String> on, Collection<String> off, Set<String> wantOff) {
		List<String> out = new ArrayList<>();
		for (String name : on) {
			if (wantOff.contains(name) && !out.contains(name)) {
				out.add(name);
			}
		}
		List<String> back = new ArrayList<>();
		for (String name : off) {
			if (!wantOff.contains(name) && !back.contains(name)) {
				back.add(name);
			}
		}
		return new Plan(out, back);
	}

	/** Vanilla mode: every on jar whose id isn't in {@code keepIds} (jars that aren't Fabric mods go too). */
	public static List<String> vanilla(Collection<ModJar> on, Set<String> keepIds) {
		List<String> out = new ArrayList<>();
		for (ModJar jar : on) {
			if (jar.id() == null || !keepIds.contains(jar.id())) {
				out.add(jar.file());
			}
		}
		return out;
	}

	/**
	 * The {@link ModSwap} plan for {@code plan}. {@code kind}: "toggle" or "vanilla" (the state then records what is
	 * in {@code store/mods}, and is deleted when that is empty), or "restore" (bring everything back; the state file
	 * is always deleted on success, even if stray files stay in the store).
	 */
	public static Properties toProperties(Path mods, Path game, Path store, Path state, Path result, long pid, String label,
			String kind, Plan plan) {
		if ("switch".equals(kind)) {
			throw new IllegalArgumentException("\"switch\" is for mod sets");
		}
		Properties properties = new Properties();
		properties.setProperty("mods", mods.toString());
		properties.setProperty("game", game.toString());
		properties.setProperty("store", store.toString());
		properties.setProperty("state", state.toString());
		properties.setProperty("result", result.toString());
		properties.setProperty("pid", String.valueOf(pid));
		properties.setProperty("label", label == null ? "" : label);
		properties.setProperty("kind", kind);
		ModSwap.store(properties, "out", plan.out());
		ModSwap.store(properties, "back", plan.back());
		return properties;
	}

	private static List<String> sorted(Set<String> names) {
		List<String> list = new ArrayList<>(names);
		list.sort(String.CASE_INSENSITIVE_ORDER);
		return list;
	}
}

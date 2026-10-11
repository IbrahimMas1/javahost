package com.javahost.util;

import java.util.List;

/** The optional mods JavaHost offers, shared by the installer and the in-game Performance tab. */
public final class ModCatalog {
	public enum Group { PERFORMANCE, SHADERS, RELAY }

	/** {@code slug} is the Modrinth project; {@code modId} is the Fabric mod id it loads as. */
	public record Entry(String slug, String modId, String name, String benefit, Group group) {
	}

	public static final List<Entry> ENTRIES = List.of(
			new Entry("sodium", "sodium", "Sodium", "New chunk renderer: often 2-3x the FPS", Group.PERFORMANCE),
			new Entry("lithium", "lithium", "Lithium", "Faster game logic; smoother hosting", Group.PERFORMANCE),
			new Entry("ferrite-core", "ferritecore", "FerriteCore", "Uses far less memory", Group.PERFORMANCE),
			new Entry("immediatelyfast", "immediatelyfast", "ImmediatelyFast", "Faster HUD and text drawing", Group.PERFORMANCE),
			new Entry("entityculling", "entityculling", "Entity Culling", "Skips mobs you can't see", Group.PERFORMANCE),
			new Entry("iris", "iris", "Iris Shaders", "Shaders, and the host's shader", Group.SHADERS),
			new Entry("e4mc", "e4mc", "Relay (e4mc)", "Friends join when direct fails", Group.RELAY));

	private ModCatalog() {
	}

	public static List<Entry> group(Group group) {
		return ENTRIES.stream().filter(e -> e.group() == group).toList();
	}
}

package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class AvailabilityTest {
	private static final String SEARCH = """
			{"hits": [
			 {"project_id": "AANobbMI", "slug": "naturalist", "title": "Naturalist", "description": "Animals!", "author": "Starfish_Studios",
			  "downloads": 9000000, "icon_url": "https://cdn.modrinth.com/data/AANobbMI/icon.webp",
			  "categories": ["adventure", "fabric", "forge", "mobs"], "versions": ["1.20.1", "1.21.1", "26.3", "26.4-snapshot-2"]},
			 {"project_id": "rlcraft1", "slug": "old-pack", "title": "Old Forge pack", "description": "Hard", "author": "someone",
			  "downloads": 5, "icon_url": "https://evil.example/icon.png", "categories": ["adventure", "forge"], "versions": ["1.12.2"]},
			 {"project_id": "bad id!", "slug": "x", "title": "Broken"},
			 {"project_id": "NeoOnly1", "slug": "neo", "title": "Neo mod", "categories": ["neoforge", "magic"], "versions": ["1.21.1"]}
			]}
			""";

	private static List<Modrinth.Project> projects() {
		return Modrinth.parseSearch(Modrinth.Kind.MOD, JsonParser.parseString(SEARCH).getAsJsonObject());
	}

	@Test
	void readsSearchHitsSafely() {
		List<Modrinth.Project> projects = projects();
		assertEquals(3, projects.size(), "the hit with a bad id is skipped");
		Modrinth.Project naturalist = projects.getFirst();
		assertEquals(List.of("fabric", "forge"), naturalist.loaders(), "only loaders, not other categories");
		assertEquals("https://cdn.modrinth.com/data/AANobbMI/icon.webp", naturalist.iconUrl());
		assertEquals("", projects.get(1).iconUrl(), "icons only from Modrinth's CDN");
		assertEquals("https://modrinth.com/mod/naturalist", naturalist.page());
	}

	@Test
	void installableOnlyWithFabricForThisVersion() {
		List<Modrinth.Project> projects = projects();
		assertTrue(Availability.installable(projects.getFirst(), "26.3"));
		assertFalse(Availability.installable(projects.getFirst(), "1.21.4"), "no build for this version");
		assertFalse(Availability.installable(projects.get(1), "1.12.2"), "Forge only");
	}

	@Test
	void saysWhereItRuns() {
		List<Modrinth.Project> projects = projects();
		assertEquals("Fabric 26.3", Availability.runsOn(projects.getFirst()), "the newest release, snapshots left out");
		assertEquals("Forge 1.12.2", Availability.runsOn(projects.get(1)));
		assertEquals("NeoForge 1.21.1", Availability.runsOn(projects.get(2)));
	}
}

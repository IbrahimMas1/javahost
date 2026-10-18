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
			  "featured_gallery": "https://cdn.modrinth.com/data/AANobbMI/images/shot.png",
			  "categories": ["adventure", "fabric", "forge", "mobs"], "versions": ["1.20.1", "1.21.1", "26.3", "26.4-snapshot-2"]},
			 {"project_id": "rlcraft1", "slug": "old-pack", "title": "Old Forge pack", "description": "Hard", "author": "someone",
			  "downloads": 5, "icon_url": "https://evil.example/icon.png", "featured_gallery": "https://evil.example/shot.png", "categories": ["adventure", "forge"], "versions": ["1.12.2"]},
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
		assertEquals(List.of("adventure", "mobs"), naturalist.categories(), "categories without the loaders");
		assertEquals("https://cdn.modrinth.com/data/AANobbMI/images/shot.png", naturalist.imageUrl());
		assertEquals("", projects.get(1).imageUrl(), "images only from Modrinth's CDN");
	}

	@Test
	void searchFilters() {
		String all = Modrinth.facets(Modrinth.Kind.MOD, "", null);
		assertEquals("[[\"project_type:mod\"],[\"categories!=optimization\"],[\"categories!=library\"]]", all);
		String magicHere = Modrinth.facets(Modrinth.Kind.MODPACK, "magic", "26.3");
		assertTrue(magicHere.contains("[\"project_type:modpack\"]"));
		assertTrue(magicHere.contains("[\"categories:magic\"]"));
		assertTrue(magicHere.contains("[\"versions:26.3\"],[\"categories:fabric\"]"), "runs here: this version, on Fabric");
		assertEquals(all, Modrinth.facets(Modrinth.Kind.MOD, "x\"]],[[\"y", "1.21 \""), "odd input is left out, never spliced in");
	}

	@Test
	void downloadProgressAndCancel() throws Exception {
		byte[] data = new byte[200_000];
		java.util.List<Long> seen = new java.util.ArrayList<>();
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-512");
		long copied = Modrinth.copy(new java.io.ByteArrayInputStream(data), java.io.OutputStream.nullOutputStream(), digest, data.length,
				(read, total) -> seen.add(read), () -> false);
		assertEquals(data.length, copied);
		assertEquals((long) data.length, seen.getLast(), "progress reaches the total");
		assertTrue(seen.size() > 1, "progress is reported as it goes");
		int[] calls = {0};
		org.junit.jupiter.api.Assertions.assertThrows(Modrinth.Cancelled.class, () -> Modrinth.copy(new java.io.ByteArrayInputStream(data),
				java.io.OutputStream.nullOutputStream(), digest, data.length, (read, total) -> {
				}, () -> ++calls[0] > 1));
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
		assertEquals(List.of("26.3", "1.21.1"), Availability.newestReleases(projects.getFirst(), 2), "newest first, no snapshots");
		assertEquals("Fabric, Forge", Availability.loaders(projects.getFirst()));
	}
}

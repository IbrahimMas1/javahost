package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class DistributionTest {
	private static Properties platform(String value) {
		Properties p = new Properties();
		if (value != null) {
			p.setProperty("platform", value);
		}
		return p;
	}

	@Test
	void onlyTheTwoModSitesCount() {
		assertEquals("curseforge", Distribution.parse(platform("curseforge")));
		assertEquals("modrinth", Distribution.parse(platform(" Modrinth \n")));
		assertEquals("", Distribution.parse(platform(null)), "no file entry: the GitHub/installer build");
		assertEquals("", Distribution.parse(platform("github")));
		assertEquals("", Distribution.parse(platform("curseforge; rm -rf")));
	}

	@Test
	void theGithubBuildHasNoMarker() {
		// The test classpath has no javahost/distribution.properties, like the jars on GitHub.
		assertFalse(Distribution.fromModSite());
		assertEquals(Modrinth.PACK_HOSTS, Modrinth.packHosts());
	}
}

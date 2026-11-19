package com.javahost.util;

import java.util.List;

/**
 * The other hosts Modrinth allows a modpack's files to come from, besides its own CDN. Only the GitHub and installer
 * copies of JavaHost have this class: scripts/PublishMod.java takes it out of the copies for CurseForge and Modrinth,
 * which may only download through Modrinth itself. {@link Modrinth#packHosts()} looks it up by name.
 */
public final class OpenPackHosts {
	public static final List<String> HOSTS = List.of("github.com", "raw.githubusercontent.com", "gitlab.com");

	private OpenPackHosts() {
	}
}

package com.javahost.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Where this copy of JavaHost came from. The jars uploaded to CurseForge and Modrinth carry
 * {@code javahost/distribution.properties} ({@code platform=curseforge} or {@code modrinth}, added by
 * scripts/PublishMod.java). Those copies are kept up to date by the site and the player's launcher, so they never
 * check for or download JavaHost updates themselves, and modpack files only come from Modrinth's CDN, as both sites
 * require. Copies from GitHub and the installer have no such file.
 */
public final class Distribution {
	static final String RESOURCE = "/javahost/distribution.properties";
	private static final String PLATFORM = read();

	private Distribution() {
	}

	/** "curseforge", "modrinth", or "" for the GitHub/installer build. */
	public static String platform() {
		// Development runs (the game tests) can pretend to be a mod-site copy.
		String test = Boolean.getBoolean("fabric.development") ? System.getProperty("javahost.test.platform") : null;
		return test != null ? clean(test) : PLATFORM;
	}

	/** Downloaded from CurseForge or Modrinth: updates and modpack files only through those sites. */
	public static boolean fromModSite() {
		return !platform().isEmpty();
	}

	/** The site's name for messages, e.g. "CurseForge". */
	public static String siteName() {
		return switch (platform()) {
			case "curseforge" -> "CurseForge";
			case "modrinth" -> "Modrinth";
			default -> "GitHub";
		};
	}

	private static String read() {
		try (InputStream in = Distribution.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return "";
			}
			Properties properties = new Properties();
			properties.load(in);
			return parse(properties);
		} catch (IOException | IllegalArgumentException e) {
			return "";
		}
	}

	static String parse(Properties properties) {
		return clean(properties.getProperty("platform", ""));
	}

	private static String clean(String value) {
		String v = value.trim().toLowerCase(Locale.ROOT);
		return v.equals("curseforge") || v.equals("modrinth") ? v : "";
	}
}

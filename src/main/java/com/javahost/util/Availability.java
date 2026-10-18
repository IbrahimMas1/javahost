package com.javahost.util;

import java.util.List;

/**
 * Whether a mod or modpack from {@link Modrinth#search} can be installed in this game, and if not, a short note on
 * what it runs on ("Forge 1.12.2", "Fabric 1.21.1"). Pure Java, unit-tested.
 */
public final class Availability {
	/** Loaders in the order we'd mention them: ours first. */
	private static final List<String> LOADER_ORDER = List.of("fabric", "quilt", "neoforge", "forge");

	private Availability() {
	}

	/** It has a Fabric build for exactly this Minecraft version. */
	public static boolean installable(Modrinth.Project project, String minecraft) {
		return project.loaders().contains("fabric") && project.gameVersions().contains(minecraft);
	}

	/**
	 * "Forge 1.12.2", "Fabric 1.21.1" or "NeoForge 1.21.1": its loader (ours first) and the newest Minecraft release
	 * it supports, which is the version to pick in the installer. Modrinth lists versions for all loaders together, so
	 * a full range would mislead.
	 */
	public static String runsOn(Modrinth.Project project) {
		String loader = LOADER_ORDER.stream().filter(project.loaders()::contains).findFirst().map(Availability::loaderName).orElse("");
		String newest = project.gameVersions().stream().filter(v -> v.matches("[0-9]+\\.[0-9]+(\\.[0-9]+)?")).max(Versions::compare).orElse("");
		return (loader + " " + newest).strip();
	}

	/** The newest Minecraft releases it supports, newest first (snapshots left out), at most {@code max}. */
	public static List<String> newestReleases(Modrinth.Project project, int max) {
		return project.gameVersions().stream().filter(v -> v.matches("[0-9]+\\.[0-9]+(\\.[0-9]+)?")).distinct()
				.sorted((a, b) -> Versions.compare(b, a)).limit(max).toList();
	}

	/** Its loaders by name, ours first: "Fabric, Forge". */
	public static String loaders(Modrinth.Project project) {
		return String.join(", ", LOADER_ORDER.stream().filter(project.loaders()::contains).map(Availability::loaderName).toList());
	}

	private static String loaderName(String loader) {
		return switch (loader) {
			case "neoforge" -> "NeoForge";
			case "forge" -> "Forge";
			case "quilt" -> "Quilt";
			default -> "Fabric";
		};
	}
}

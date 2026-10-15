pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	// One source tree, built for every supported Minecraft version (comments like //? if >=1.21.9 pick the code).
	id("dev.kikugie.stonecutter") version "0.9.8"
	// Picks the right Loom for each version: 26.1+ is unobfuscated, 1.21.x is remapped with Mojang's names.
	id("dev.kikugie.loom-back-compat") version "0.4.2"
}

stonecutter {
	create(rootProject) {
		// Each build covers its version's hotfixes (see mod.mc_compat in stonecutter.properties.toml).
		versions("26.3", "26.2", "26.1.2", "1.21.11", "1.21.10", "1.21.8", "1.21.4", "1.21.1")
		vcsVersion = "26.3"
	}
}

rootProject.name = "javahost"
include("installer")

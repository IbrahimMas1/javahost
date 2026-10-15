// Built once per Minecraft version (see settings.gradle.kts): ./gradlew :26.3:build, :1.21.1:build, ...
// The sources in src/ are written for the active version (26.3); Stonecutter rewrites //? comments for the others.
plugins {
	// Applies fabric-loom (26.1+, unobfuscated) or fabric-loom-remap (older, Mojang's names) to match the version.
	id("dev.kikugie.loom-back-compat")
	`maven-publish`
}

val mc: String = sc.current.version
val modVersion = rootProject.property("version") as String
version = "$modVersion+$mc"
base.archivesName = "javahost"

val javaVersion: JavaVersion = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21
// Fabric's in-game test API exists from 1.21.4 on.
val hasGameTests = sc.current.parsed >= "1.21.4"
// The 26.3 build keeps the run folders it always had; other versions get their own under build/run/<version>/.
fun runDir(name: String) = if (mc == "26.3") "../../build/run/$name" else "../../build/run/$mc/$name"
fun optional(key: String): String? = sc.properties.rawOrNull("deps", key)?.toString()?.trim('"')

repositories {
	mavenCentral()
	// Only used by the automated tests (relay, shaders).
	exclusiveContent {
		forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
		filter { includeGroup("maven.modrinth") }
	}
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("javahost") {
			sourceSet(sourceSets["main"])
			sourceSet(sourceSets["client"])
		}
	}
}

if (hasGameTests) {
	fabricApi {
		// Automated in-game tests: ./gradlew :26.3:runClientGameTest launches the real client and a dedicated server.
		configureTests {
			createSourceSet = true
			modId = "javahost-gametest"
			enableGameTests = false
			enableClientGameTests = true
			eula = true
		}
	}
}

val testRelay: Configuration by configurations.creating
// Test mods whose bundled libraries the dev runs need on the classpath (see bundledTestLibs below).
val testModJars: Configuration by configurations.creating { isTransitive = false }

dependencies {
	minecraft("com.mojang:minecraft:$mc")
	// Mojang's names on obfuscated versions (26.1+ ships them already).
	loomx.applyMojangMappings()
	modImplementation("net.fabricmc:fabric-loader:${rootProject.property("loader_version")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties.get<String>("deps.fabric_api")}")

	// Pure-Java Opus codec for voice chat, bundled inside the mod jar.
	implementation("io.github.jaredmdobson:concentus:${rootProject.property("concentus_version")}")
	include("io.github.jaredmdobson:concentus:${rootProject.property("concentus_version")}")

	testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Real mods loaded only in the in-game tests, to test the relay and shader sharing for real.
	// e4mc 6.2.3 crashes 26.3 *dedicated* servers (it calls a removed permission method), and the single-client
	// test uses one, so the relay mod is only added to the two multiplayer test games (see below).
	// (26.1+ only: e4mc's mixins don't apply in remapped dev runs, so the 1.21.x tests cover the direct routes.)
	optional("test_e4mc")?.let { if (sc.current.parsed >= "26.1") testRelay("maven.modrinth:e4mc:$it") }
	if (hasGameTests) {
		optional("test_sodium")?.let { "modGametestRuntimeOnly"("maven.modrinth:sodium:$it") }
		optional("test_iris")?.let {
			"modGametestRuntimeOnly"("maven.modrinth:iris:$it")
			testModJars("maven.modrinth:iris:$it")
		}
	}
}

// On obfuscated versions Loom doesn't load the libraries bundled inside test mods (Iris's shader preprocessor),
// so take the plain library jars out of them; bundled Fabric API pieces are already there.
if (hasGameTests && sc.current.parsed < "26.1") {
	val bundledTestLibs = tasks.register<Sync>("bundledTestLibs") {
		from(testModJars.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
			include("META-INF/jars/*.jar")
			exclude("META-INF/jars/fabric-*")
			eachFile { path = name }
		}
		includeEmptyDirs = false
		into(layout.buildDirectory.dir("test-libs"))
	}
	dependencies {
		"gametestRuntimeOnly"(fileTree(layout.buildDirectory.dir("test-libs")).builtBy(bundledTestLibs))
	}
}

if (hasGameTests && mc != "26.3") {
	// Website screenshots are only taken on 26.3.
	sourceSets.named("gametest") { java.exclude("**/Showcase.java") }
}

if (hasGameTests) {
	loom.runs.named("clientGameTest") {
		runDir = runDir("clientGameTest")
		// The in-game tests run the public world list on this computer (Node), from directory/dev/server.js.
		vmArg("-Djavahost.directoryServer=" + rootProject.file("directory/dev/server.js").absolutePath)
		// Older test frameworks stop on the Discover page's server pings (they use Netty directly).
		if (sc.current.parsed < "26.1") {
			vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
		}
		// Optional: test alongside other mods, e.g. ./gradlew :26.3:runClientGameTest -PextraMods=/path/to/folder
		if (project.hasProperty("extraMods")) {
			vmArg("-Dfabric.addMods=" + project.property("extraMods"))
		}
	}

	// Two real game clients for the multiplayer test: one hosts its world, the other joins it.
	// Run both at once: ./gradlew :26.3:runMpHost :26.3:runMpGuest (scripts/multiplayer-test.sh does this).
	val mpShared = rootProject.file(runDir("mp-shared").removePrefix("../../")).absolutePath
	for ((runName, user) in listOf("mpHost" to "Host", "mpGuest" to "Guest")) {
		loom.runs.create(runName) {
			inherit(loom.runs["clientGameTest"])
			if (sc.current.parsed < "26.1") vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
			name = "Multiplayer test ($user)"
			runDir = runDir(runName)
			vmArg("-Djavahost.testRole=${user.lowercase()}")
			vmArg("-Djavahost.mpDir=$mpShared")
			if (!testRelay.isEmpty) {
				vmArg("-Dfabric.addMods=${testRelay.singleFile.absolutePath}")
			}
			// Test accounts are offline, so friend requests can't carry Mojang signatures in this test.
			vmArg("-Djavahost.insecureFriends=true")
			vmArg("-Djavahost.friendsNamespace=${project.findProperty("mpns") ?: "local"}")
			// The shared local public world list, started by scripts/multiplayer-test.sh.
			vmArg("-Djavahost.directoryUrl=http://127.0.0.1:8795")
			programArgs("--username", user)
		}
	}

	// Modded worlds with two games: the host plays with Naturalist, the guest gets it when joining.
	// Run with: sh scripts/modded-test.sh (it downloads the mod and starts the guest twice).
	val modShared = rootProject.file(runDir("mod-shared").removePrefix("../../")).absolutePath
	for ((runName, user, role) in listOf(Triple("modHost", "Host", "modhost"), Triple("modGuest", "Guest", "modguest"))) {
		loom.runs.create(runName) {
			inherit(loom.runs["clientGameTest"])
			if (sc.current.parsed < "26.1") vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
			name = "Modded test ($user)"
			runDir = runDir(runName)
			vmArg("-Djavahost.testRole=$role")
			vmArg("-Djavahost.modDir=$modShared")
			vmArg("-Djavahost.friendsNamespace=modtest")
			if (role == "modhost") {
				project.findProperty("hostMods")?.let { vmArg("-Dfabric.addMods=$it") }
			}
			programArgs("--username", user)
		}
	}

	// Website screenshots: a game driven from build/run/showcase/control/commands.txt (see scripts/showcase.sh).
	loom.runs.create("showcase") {
		inherit(loom.runs["clientGameTest"])
		name = "Showcase screenshots"
		runDir = runDir("showcase")
		vmArg("-Djavahost.testRole=showcase")
		vmArg("-Djavahost.showcaseDir=${rootProject.file(runDir("showcase").removePrefix("../../") + "/control").absolutePath}")
		vmArg("-Djavahost.friendsNamespace=showcase")
		// The test account is offline; this shows the menus as a signed-in player sees them.
		vmArg("-Djavahost.insecureFriends=true")
		programArgs("--username", "Steve123")
	}
}

// A quick in-game check where Fabric's test API doesn't exist (1.21.1): ./gradlew :1.21.1:runSmoke (see DevSmoke).
loom.runs.create("smoke") {
	client()
	name = "Smoke test"
	runDir = runDir("smoke")
	vmArg("-Djavahost.smoke=true")
	programArgs("--username", "Steve123")
}

// The shipped jar in a real (production) Minecraft with Fabric API, driven by the same smoke check:
// ./gradlew :1.21.1:runProdSmoke. On obfuscated versions this proves the remapped mixins and names work.
val prodMods: Configuration by configurations.creating { isTransitive = false }
dependencies {
	prodMods("net.fabricmc.fabric-api:fabric-api:${sc.properties.get<String>("deps.fabric_api")}")
}
tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProdSmoke") {
	mods.from(loomx.modJar.flatMap { it.archiveFile })
	mods.from(prodMods)
	jvmArgs.add("-Djavahost.smoke=true")
	programArgs.addAll("--username", "Steve123")
	runDir = rootProject.layout.projectDirectory.dir(runDir("prod-smoke").removePrefix("../../"))
}

tasks.withType<ProcessResources>().configureEach {
	val props = mapOf(
		"version" to modVersion,
		"minecraft" to sc.properties.get<String>("mod.mc_compat"),
		"java" to javaVersion.majorVersion,
	)
	inputs.properties(props)
	filesMatching("fabric.mod.json") { expand(props) }
	filesMatching("*.mixins.json") { expand(props) }
}

tasks.withType<JavaCompile>().configureEach {
	options.release = javaVersion.majorVersion.toInt()
	options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
}

java {
	withSourcesJar()
	sourceCompatibility = javaVersion
	targetCompatibility = javaVersion
}

tasks.withType<Jar>().configureEach {
	from(rootProject.file("LICENSE")) { rename { "${it}_javahost" } }
}

// Copies this version's finished jar to build/libs/<mod version>/ in the main folder (for releases and the installer).
tasks.register<Copy>("collectJar") {
	group = "build"
	from(loomx.modJar.flatMap { it.archiveFile })
	into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
}

tasks.test {
	useJUnitPlatform()
}

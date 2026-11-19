#!/bin/sh
# The CurseForge/Modrinth copy of the jar, for real: builds a version's jar, makes the site copy the way a publish does
# (java scripts/PublishMod.java --mark-only: marker in, self-updater out, then checked), checks it from the outside with
# unzip/grep, and then plays it: a production Minecraft with only that jar and Fabric API (the build's runProdSmoke,
# pointed at the site copy by a throwaway init script, so build.gradle.kts stays as it is). DevSmoke sees the marker
# and checks the title screen, that every JavaHost class in the jar loads and links, Settings → Updates and the bell,
# enters a world, and quits normally (the on-exit hooks run). The log must have no missing class or mixin failure.
#
#   sh scripts/site-jar-test.sh                # 26.3 and 1.21.4
#   sh scripts/site-jar-test.sh 26.3           # just one
#
# About 2-3 minutes per version, one game at a time. Results: build/site-jar-test/results.txt; game logs in
# build/run/site-smoke/<version>/logs.
cd "$(dirname "$0")/.." || exit 1
if pgrep -f runClientGameTest > /dev/null || pgrep -f "javahost.testRole" > /dev/null || pgrep -f "javahost.smoke" > /dev/null; then
	echo "Another test game is running; try again when it's done."
	exit 1
fi
NODES=${*:-"26.3 1.21.4"}
VERSION=$(sed -n 's/^version=//p' gradle.properties)
OUT=build/site-jar-test
RESULTS=$OUT/results.txt
FAILED=0
mkdir -p "$OUT"
: > "$RESULTS"

say() {
	echo "$1" | tee -a "$RESULTS"
}

fail() {
	say "FAILED: $1"
	FAILED=1
}

ok() {
	say "ok: $1"
}

# Points the build's runProdSmoke at the site copy (and its own run folder) for this one Gradle run.
INIT=$OUT/site-jar.init.gradle
cat > "$INIT" << 'EOF'
gradle.allprojects { p ->
	p.afterEvaluate {
		if (p.path == ":" + System.getenv("JAVAHOST_SITE_NODE")) {
			p.tasks.named("runProdSmoke") { t ->
				t.mods.setFrom(System.getenv("JAVAHOST_SITE_JAR"), p.configurations.getByName("prodMods"))
				t.runDir.set(p.rootProject.layout.projectDirectory.dir(System.getenv("JAVAHOST_SITE_RUN")))
			}
		}
	}
}
EOF

for v in $NODES; do
	say "== $v"
	DIR=$OUT/$v
	RUN=build/run/site-smoke/$v
	JAR=build/libs/$VERSION/javahost-$VERSION+$v.jar
	rm -rf "$DIR" "$RUN"
	mkdir -p "$DIR"
	if ! ./gradlew ":$v:collectJar" --no-configuration-cache -q > "$DIR/build.log" 2>&1; then
		fail "$v: building the jar (see $DIR/build.log)"
		continue
	fi
	if java scripts/PublishMod.java --mark-only "$JAR" curseforge "$DIR" > "$DIR/mark.log" 2>&1; then
		ok "$v: PublishMod made and checked the CurseForge copy ($(grep -c '^  - ' "$DIR/mark.log") entries removed)"
	else
		fail "$v: PublishMod refused the copy: $(cat "$DIR/mark.log")"
		continue
	fi
	SITE=$DIR/$(basename "$JAR")

	# The same checks from the outside. First make sure they'd notice: the GitHub jar has all of it.
	PATTERN='com/javahost/client/update/|com/javahost/util/Update(Swap|Manifest)'
	unzip -l "$JAR" | grep -qE "$PATTERN" && unzip -p "$JAR" '*.class' | LC_ALL=C grep -aqF 'api.github.com' \
		&& ok "$v: the GitHub jar has the updater (so the checks below can see it)" || fail "$v: the GitHub jar has no updater?"
	if unzip -l "$SITE" | grep -E "$PATTERN" > "$DIR/left.txt"; then
		fail "$v: self-update classes left in the site copy: $(cat "$DIR/left.txt")"
	else
		ok "$v: no self-update classes in the site copy"
	fi
	for text in api.github.com /releases/ com/javahost/client/update/ com/javahost/util/UpdateSwap com/javahost/util/UpdateManifest; do
		if unzip -p "$SITE" '*.class' | LC_ALL=C grep -aqF "$text"; then
			fail "$v: \"$text\" is still in the site copy's classes"
		else
			ok "$v: no \"$text\" in the site copy's classes"
		fi
	done
	unzip -p "$SITE" javahost/distribution.properties | grep -qx 'platform=curseforge' && ok "$v: marker says curseforge" || fail "$v: marker"

	# Play it.
	say "$v: starting Minecraft with the site copy..."
	if JAVAHOST_SITE_JAR="$(pwd)/$SITE" JAVAHOST_SITE_NODE="$v" JAVAHOST_SITE_RUN="$RUN" \
		./gradlew ":$v:runProdSmoke" --no-configuration-cache -q --init-script "$INIT" > "$DIR/game.log" 2>&1; then
		ok "$v: the game closed normally"
	else
		fail "$v: the game run failed (see $DIR/game.log)"
	fi
	LOG=$RUN/logs/latest.log
	if [ ! -f "$LOG" ]; then
		fail "$v: no game log at $LOG"
		continue
	fi
	grep -qF "[Smoke] a copy from CurseForge" "$LOG" && ok "$v: the game ran the CurseForge copy" || fail "$v: the game didn't see the marker"
	for step in "the title screen is up" "no self-updater in this copy" "JavaHost classes load and link" "Settings → Updates says where this copy came from" \
		"the bell opens" "a singleplayer world opened"; do
		grep -qF "[Smoke] ok: " "$LOG" && grep -F "[Smoke] ok: " "$LOG" | grep -qF "$step" && ok "$v: $step" || fail "$v: $step"
	done
	grep -qF "[Smoke] ALL STEPS DONE" "$LOG" && ok "$v: every smoke step done" || fail "$v: smoke steps: $(grep -F '[Smoke] FAILED' "$LOG" | head -3)"
	grep -qF "Stopping!" "$LOG" && ok "$v: quit normally (exit hooks ran)" || fail "$v: no normal quit in the log"
	if grep -nE "NoClassDefFoundError|ClassNotFoundException|MixinApplyError|InvalidMixinException|MixinTransformerError|Mixin apply.*failed" "$LOG" > "$DIR/class-errors.txt"; then
		fail "$v: class or mixin errors in the log: $(head -5 "$DIR/class-errors.txt")"
	else
		ok "$v: no missing class or mixin failure in the log"
	fi
	if grep -nE "Exception|Error" "$LOG" | grep -iE "javahost" > "$DIR/javahost-errors.txt"; then
		say "note: $v: log lines mentioning JavaHost and an exception (check them): $(head -5 "$DIR/javahost-errors.txt")"
	fi
done

if [ "$FAILED" = 0 ]; then
	say "ALL SITE JAR CHECKS PASSED"
else
	say "SOME SITE JAR CHECKS FAILED"
fi
exit "$FAILED"

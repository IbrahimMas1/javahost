#!/bin/sh
# My mods, with the game really closing in between: two made-up mods (Glowberry, which needs Cozylib) are put in the
# single-game test's mods/ folder. Run 1 switches Glowberry off and closes normally, so the real after-exit helper
# moves it to the off store. Run 2 sees it off (and the notice), switches it back on and closes. Afterwards mods/ must
# be exactly as it was before run 1. Takes about 3 minutes, one game at a time.
# runClientGameTest clears its folder on every start, so this drives the modded test's guest game (runModGuest,
# build/run/modGuest, whose folder is kept) with JAVAHOST_TEST_ONLY=toggle1 / toggle2. scripts/modded-test.sh wipes
# that folder when it starts, so the two don't get in each other's way.
# Results: build/run/modGuest/mods-toggle-results.txt
cd "$(dirname "$0")/.." || exit 1
if pgrep -f runClientGameTest > /dev/null || pgrep -f "javahost.testRole" > /dev/null; then
	echo "Another test game is running; try again when it's done."
	exit 1
fi
RUN=build/run/modGuest
MODS=$RUN/mods
STORE=$RUN/javahost/mods-off
RESULTS=$RUN/mods-toggle-results.txt
LOGS=build/mods-toggle-test
FAILED=0

fail() {
	echo "FAILED: $1"
	FAILED=1
}

ok() {
	echo "ok: $1"
}

fingerprint() {
	(cd "$MODS" && find . -maxdepth 1 -type f -exec shasum -a 256 {} + | sort)
}

# Waits up to a minute for a file to appear (the helper runs after the game has closed).
wait_for() {
	for i in $(seq 1 60); do [ -f "$1" ] && return 0; sleep 1; done
	return 1
}

clean() {
	rm -f "$MODS"/jhtest-* "$STORE"/mods/jhtest-* "$STORE"/replaced/*jhtest-* "$MODS"/.javahost-jhtest-*
	rm -f "$STORE"/wanted.properties "$STORE"/state.properties "$STORE"/result.properties "$STORE"/running.properties "$STORE"/dropped.properties
}

./gradlew :26.3:compileGametestJava --no-configuration-cache -q || exit 1
if [ -f "$RUN/javahost/modsets/active.properties" ] || [ -f "$RUN/javahost/modsets/plan.properties" ]; then
	echo "The modded test's guest is in the middle of a mod set switch; run scripts/modded-test.sh to the end (or delete $RUN) first."
	exit 1
fi
mkdir -p "$MODS" "$LOGS"
clean
rm -f "$RESULTS"

# Two tiny Fabric mods (just a fabric.mod.json), the same as the game test's.
python3 - "$MODS" << 'EOF'
import json, sys, zipfile
for file, mod in [("jhtest-cozylib.jar", {"id": "cozylib", "name": "Cozylib", "version": "2.1.0", "depends": {}}),
                  ("jhtest-glowberry.jar", {"id": "glowberry", "name": "Glowberry", "version": "1.4.2", "depends": {"cozylib": "*"}})]:
    meta = {"schemaVersion": 1, "id": mod["id"], "version": mod["version"], "name": mod["name"],
            "description": "A made-up mod for JavaHost's tests.", "environment": "*", "depends": mod["depends"]}
    with zipfile.ZipFile(sys.argv[1] + "/" + file, "w") as jar:
        jar.writestr("fabric.mod.json", json.dumps(meta))
        jar.writestr("README.txt", "Made up for a test. ")
EOF
START=$(fingerprint)

echo "Run 1: switch Glowberry off, then close the game."
JAVAHOST_TEST_ONLY=toggle1 ./gradlew :26.3:runModGuest --no-configuration-cache -q > $LOGS/run1.log 2>&1 || fail "run 1 (see $LOGS/run1.log)"
if wait_for "$STORE/result.properties"; then ok "the helper ran after the game closed"; else fail "no result from the helper"; fi
grep -q "^ok=true" "$STORE/result.properties" 2>/dev/null && ok "the swap worked" || fail "the swap: $(grep reason= "$STORE/result.properties" 2>/dev/null)"
grep -q "^kind=toggle" "$STORE/result.properties" 2>/dev/null && ok "it was an on/off swap" || fail "the result's kind"
[ ! -f "$MODS/jhtest-glowberry.jar" ] && ok "Glowberry left mods/" || fail "Glowberry is still in mods/"
[ -f "$STORE/mods/jhtest-glowberry.jar" ] && ok "Glowberry is in the off store" || fail "Glowberry isn't in the off store"
[ -f "$MODS/jhtest-cozylib.jar" ] && ok "Cozylib stayed on" || fail "Cozylib moved"
grep -q "^movedOut.0=jhtest-glowberry.jar" "$STORE/state.properties" 2>/dev/null && grep -q "^kind=toggle" "$STORE/state.properties" \
	&& ok "the state file lists what's off" || fail "the state file: $(cat "$STORE/state.properties" 2>/dev/null)"
[ ! -f "$STORE/wanted.properties" ] && ok "the wanted changes were handed over" || fail "wanted.properties is still there"

echo "Run 2: Glowberry is off; switch it back on, then close the game."
JAVAHOST_TEST_ONLY=toggle2 ./gradlew :26.3:runModGuest --no-configuration-cache -q > $LOGS/run2.log 2>&1 || fail "run 2 (see $LOGS/run2.log)"
if wait_for "$STORE/result.properties"; then ok "the helper ran again"; else fail "no result from the second helper"; fi
grep -q "^ok=true" "$STORE/result.properties" 2>/dev/null && ok "the swap back worked" || fail "the swap back: $(grep reason= "$STORE/result.properties" 2>/dev/null)"
[ "$(fingerprint)" = "$START" ] && ok "mods/ is exactly as it started" || fail "mods/ differs from the start"
[ -z "$(ls -A "$STORE/mods" 2>/dev/null)" ] && ok "the off store is empty" || fail "the off store still holds: $(ls "$STORE/mods")"
[ ! -f "$STORE/state.properties" ] && ok "no state file when nothing is off" || fail "the state file is still there"

echo "In the games:"
cat "$RESULTS" 2>/dev/null
grep -q "FAILED" "$RESULTS" 2>/dev/null && FAILED=1
clean
if [ $FAILED = 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi

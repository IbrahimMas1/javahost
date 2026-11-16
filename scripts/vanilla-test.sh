#!/bin/sh
# Vanilla mode, with the game really closing in between: two made-up mods (Glowberry, which needs Cozylib) are put in
# the test game's mods/ folder. Launch 1 switches to Vanilla mode and closes normally, so the real after-exit helper
# puts them aside. Launch 2 checks Vanilla mode is on and JavaHost hidden, brings the mods back and closes. Afterwards
# mods/ must be exactly as it was before launch 1. About 3 minutes, one game at a time.
# Like scripts/mods-toggle-test.sh this drives the modded test's guest game (runModGuest, build/run/modGuest, whose
# folder is kept between launches; runClientGameTest clears its own) with JAVAHOST_TEST_ONLY=vanilla1 / vanilla2.
# Results: build/run/modGuest/vanilla-results.txt
cd "$(dirname "$0")/.." || exit 1
if pgrep -f runClientGameTest > /dev/null || pgrep -f "javahost.testRole" > /dev/null; then
	echo "Another test game is running; try again when it's done."
	exit 1
fi
RUN=build/run/modGuest
MODS=$RUN/mods
STORE=$RUN/javahost/vanilla
RESULTS=$RUN/vanilla-results.txt
LOGS=build/vanilla-test
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
	rm -f "$MODS"/jhtest-* "$STORE"/mods/jhtest-* "$STORE"/replaced/*jhtest-*
	rm -f "$STORE"/state.properties "$STORE"/result.properties "$STORE"/running.properties "$STORE"/staged.properties
}

./gradlew :26.3:compileGametestJava --no-configuration-cache -q || exit 1
if [ -f "$RUN/javahost/modsets/active.properties" ] || [ -f "$RUN/javahost/modsets/plan.properties" ]; then
	echo "The modded test's guest is in the middle of a mod set switch; run scripts/modded-test.sh to the end (or delete $RUN) first."
	exit 1
fi
if [ -n "$(ls "$MODS"/*.jar 2> /dev/null | grep -v '/jhtest-')" ]; then
	echo "$MODS has jars of its own (left by another test); run scripts/modded-test.sh to the end (or delete $RUN) first."
	exit 1
fi
mkdir -p "$MODS" "$LOGS"
clean
rm -f "$RESULTS"

# Two tiny Fabric mods (just a fabric.mod.json).
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

echo "Launch 1: switch to Vanilla mode, then close the game."
JAVAHOST_TEST_ONLY=vanilla1 ./gradlew :26.3:runModGuest --no-configuration-cache -q > $LOGS/run1.log 2>&1 || fail "launch 1 (see $LOGS/run1.log)"
if wait_for "$STORE/result.properties"; then ok "the helper ran after the game closed"; else fail "no result from the helper"; fi
grep -q "^ok=true" "$STORE/result.properties" 2>/dev/null && ok "the swap worked" || fail "the swap: $(grep reason= "$STORE/result.properties" 2>/dev/null)"
grep -q "^kind=vanilla" "$STORE/result.properties" 2>/dev/null && ok "it was Vanilla mode" || fail "the result's kind"
# The test game has JavaHost and Fabric API on its classpath, so the keep set left in mods/ is nothing at all.
[ -z "$(ls "$MODS"/*.jar 2> /dev/null)" ] && ok "only the keep set is left in mods/ (none of it is a jar in this test game)" \
	|| fail "mods/ still holds: $(ls "$MODS")"
[ -f "$STORE/mods/jhtest-glowberry.jar" ] && [ -f "$STORE/mods/jhtest-cozylib.jar" ] && ok "both mods are put aside" || fail "the store holds: $(ls "$STORE/mods" 2> /dev/null)"
[ -f "$STORE/state.properties" ] && grep -q "^kind=vanilla" "$STORE/state.properties" && ok "the state file exists" || fail "no state file"
[ ! -f "$STORE/staged.properties" ] && ok "what was staged was handed over" || fail "staged.properties is still there"

echo "Launch 2: Vanilla mode is on; bring the mods back, then close the game."
JAVAHOST_TEST_ONLY=vanilla2 ./gradlew :26.3:runModGuest --no-configuration-cache -q > $LOGS/run2.log 2>&1 || fail "launch 2 (see $LOGS/run2.log)"
if wait_for "$STORE/result.properties"; then ok "the helper ran again"; else fail "no result from the second helper"; fi
grep -q "^ok=true" "$STORE/result.properties" 2>/dev/null && ok "the mods came back" || fail "the swap back: $(grep reason= "$STORE/result.properties" 2>/dev/null)"
[ "$(fingerprint)" = "$START" ] && ok "mods/ is exactly as it started" || fail "mods/ differs from the start"
[ ! -f "$STORE/state.properties" ] && ok "the state file is gone" || fail "the state file is still there"
[ -z "$(ls -A "$STORE/mods" 2>/dev/null)" ] && ok "nothing is left in the store" || fail "the store still holds: $(ls "$STORE/mods")"

echo "In the games:"
cat "$RESULTS" 2>/dev/null
grep -q "FAILED" "$RESULTS" 2>/dev/null && FAILED=1
clean
if [ $FAILED = 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi

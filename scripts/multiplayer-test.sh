#!/bin/sh
# Two real Minecraft clients: one hosts a world, the other joins it over the LAN, IPv6 and the relay,
# checking packs, shaders, voice (both ways) and kicking. Results: build/run/mp-shared/*-results.txt
#   sh scripts/multiplayer-test.sh            (26.3)
#   sh scripts/multiplayer-test.sh 1.21.4     (another Minecraft version; results in build/run/1.21.4/mp-shared)
cd "$(dirname "$0")/.." || exit 1
MC="${1:-26.3}"
RUN=build/run
[ "$MC" = "26.3" ] || RUN="build/run/$MC"
./gradlew ":$MC:compileGametestJava" --no-configuration-cache -q || exit 1
rm -rf "$RUN/mp-shared" "$RUN/mpHost" "$RUN/mpGuest"
mkdir -p "$RUN/mp-shared"
# A fresh mailbox namespace per run, shared by both games, so earlier test messages never leak in.
NS="mptest-$(date +%s)-$$"
# The public world list, run locally for both games (the same code as the real service).
node directory/dev/server.js 8795 > "$RUN/mp-shared/directory.log" 2>&1 &
DIRECTORY=$!
trap 'kill $DIRECTORY 2>/dev/null' EXIT
(./gradlew ":$MC:runMpHost" --no-configuration-cache -Pmpns="$NS" > "$RUN/mp-shared/host-gradle.log" 2>&1; echo "host exit $?" >> "$RUN/mp-shared/host-gradle.log") &
HOST=$!
sleep 15
(./gradlew ":$MC:runMpGuest" --no-configuration-cache -Pmpns="$NS" > "$RUN/mp-shared/guest-gradle.log" 2>&1; echo "guest exit $?" >> "$RUN/mp-shared/guest-gradle.log") &
GUEST=$!
wait $HOST $GUEST
grep -h "exit" "$RUN"/mp-shared/*-gradle.log

#!/bin/sh
# Two real Minecraft clients: one hosts a world, the other joins it over the LAN, IPv6 and the relay,
# checking packs, shaders, voice (both ways) and kicking. Results: build/run/mp-shared/*-results.txt
cd "$(dirname "$0")/.." || exit 1
./gradlew compileGametestJava --no-configuration-cache -q || exit 1
rm -rf build/run/mp-shared build/run/mpHost build/run/mpGuest
mkdir -p build/run/mp-shared
# A fresh mailbox namespace per run, shared by both games, so earlier test messages never leak in.
NS="mptest-$(date +%s)-$$"
# The public world list, run locally for both games (the same code as the real service).
node directory/dev/server.js 8795 > build/run/mp-shared/directory.log 2>&1 &
DIRECTORY=$!
trap 'kill $DIRECTORY 2>/dev/null' EXIT
(./gradlew runMpHost --no-configuration-cache -Pmpns="$NS" > build/run/mp-shared/host-gradle.log 2>&1; echo "host exit $?" >> build/run/mp-shared/host-gradle.log) &
HOST=$!
sleep 15
(./gradlew runMpGuest --no-configuration-cache -Pmpns="$NS" > build/run/mp-shared/guest-gradle.log 2>&1; echo "guest exit $?" >> build/run/mp-shared/guest-gradle.log) &
GUEST=$!
wait $HOST $GUEST
grep -h "exit" build/run/mp-shared/*-gradle.log

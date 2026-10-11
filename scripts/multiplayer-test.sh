#!/bin/sh
# Two real Minecraft clients: one hosts a world, the other joins it over the LAN, IPv6 and the relay,
# checking packs, shaders, voice (both ways) and kicking. Results: build/run/mp-shared/*-results.txt
cd "$(dirname "$0")/.." || exit 1
./gradlew compileGametestJava --no-configuration-cache -q || exit 1
rm -rf build/run/mp-shared build/run/mpHost build/run/mpGuest
mkdir -p build/run/mp-shared
(./gradlew runMpHost --no-configuration-cache > build/run/mp-shared/host-gradle.log 2>&1; echo "host exit $?" >> build/run/mp-shared/host-gradle.log) &
sleep 15
(./gradlew runMpGuest --no-configuration-cache > build/run/mp-shared/guest-gradle.log 2>&1; echo "guest exit $?" >> build/run/mp-shared/guest-gradle.log) &
wait
grep -h "exit" build/run/mp-shared/*-gradle.log

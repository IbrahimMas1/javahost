#!/bin/sh
# Modded worlds with two real games. The host plays with Naturalist (a real content mod from Modrinth); the guest
# doesn't have it, is told so when joining, downloads it, restarts and is taken back into the world.
# Results: build/run/mod-shared/*-results.txt
cd "$(dirname "$0")/.." || exit 1
./gradlew :26.3:compileGametestJava --no-configuration-cache -q || exit 1
MOD=build/test-mods/naturalist-2.0.6-fabric-26.3.jar
if [ ! -f "$MOD" ]; then
	mkdir -p build/test-mods
	# Naturalist 2.0.6 for Fabric 26.3, checked against Modrinth's hash.
	URL=$(curl -s https://api.modrinth.com/v2/version/35ZJ5hlX | python3 -c "import sys,json; f=json.load(sys.stdin)['files'][0]; print(f['url'], f['hashes']['sha512'])")
	curl -sL -o "$MOD" "${URL% *}"
	[ "$(shasum -a 512 "$MOD" | cut -d' ' -f1)" = "${URL#* }" ] || { echo "Naturalist download didn't match its hash"; rm -f "$MOD"; exit 1; }
fi
# Games left over from an interrupted run would hold the port.
pkill -f "javahost.testRole=mod" 2>/dev/null && sleep 3
rm -rf build/run/mod-shared build/run/modHost build/run/modGuest
mkdir -p build/run/mod-shared build/run/modGuest/mods
SHARED=build/run/mod-shared
(./gradlew :26.3:runModHost --no-configuration-cache -PhostMods="$PWD/build/test-mods/naturalist-2.0.6-fabric-26.3.jar" > $SHARED/host-gradle.log 2>&1; echo "host exit $?" >> $SHARED/host-gradle.log) &
HOST=$!
sleep 15
./gradlew :26.3:runModGuest --no-configuration-cache > $SHARED/guest1-gradle.log 2>&1; echo "guest (first visit) exit $?" >> $SHARED/guest1-gradle.log
# The switch runs once the game has closed; wait for it.
for i in $(seq 1 60); do [ -f build/run/modGuest/javahost/modsets/result.properties ] && break; sleep 1; done
echo "after the first visit, the guest's mods/: $(ls build/run/modGuest/mods | tr '\n' ' ')"
grep -h "ok=\|reason=" build/run/modGuest/javahost/modsets/result.properties
./gradlew :26.3:runModGuest --no-configuration-cache > $SHARED/guest2-gradle.log 2>&1; echo "guest (after restart) exit $?" >> $SHARED/guest2-gradle.log
for i in $(seq 1 60); do [ ! -f build/run/modGuest/javahost/modsets/active.properties ] && break; sleep 1; done
echo "after going back, the guest's mods/: $(ls build/run/modGuest/mods | tr '\n' ' ')"
wait $HOST
grep -h "exit" $SHARED/*-gradle.log

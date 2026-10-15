#!/bin/sh
# Website screenshots. Start the game:   sh scripts/showcase.sh start
# Send commands and wait for each:       sh scripts/showcase.sh "world 123" "me tp @s 0 120 0" "shot test"
# Shots land in build/run/showcase/control/shots. The commands are listed in src/gametest/.../Showcase.java.
cd "$(dirname "$0")/.." || exit 1
CONTROL=build/run/showcase/control
if [ "$1" = "start" ]; then
	mkdir -p "$CONTROL"
	rm -f "$CONTROL/done.txt"
	./gradlew :26.3:runShowcase --no-configuration-cache > build/run/showcase.log 2>&1 &
	until grep -q ready "$CONTROL/done.txt" 2>/dev/null; do
		if ! kill -0 $! 2>/dev/null; then
			echo "the game stopped; see build/run/showcase.log"
			exit 1
		fi
		sleep 2
	done
	echo "ready"
	exit 0
fi
for command in "$@"; do
	before=$(wc -l < "$CONTROL/done.txt")
	printf '%s\n' "$command" >> "$CONTROL/commands.txt"
	case "$command" in \#*|""|quit) continue ;; esac
	while [ "$(wc -l < "$CONTROL/done.txt")" -le "$before" ]; do sleep 0.3; done
	tail -n 1 "$CONTROL/done.txt"
done

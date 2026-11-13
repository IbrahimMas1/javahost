#!/bin/sh
# Builds website/index.html: stitches the parts together, then draws the page's pixel art into it.
#   sh website-src/tools/build.sh
cd "$(dirname "$0")/.." || exit 1
OUT="../website/index.html"
cat parts/1-head.html parts/2-hero.css parts/3-sections.css parts/4-body.html parts/5-script.html > "$OUT"
PAGE="$OUT" python3 tools/gen.py

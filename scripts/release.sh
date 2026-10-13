#!/bin/sh
# Tags a release so GitHub builds and publishes it (with the signed update.json the in-game updater reads).
#   scripts/release.sh "What's new in this version"
#   scripts/release.sh --important "What's new"   (players can't skip it, and older versions can't be restored)
# The version comes from gradle.properties; commit and push your changes first.
set -e
cd "$(dirname "$0")/.."
VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
IMPORTANT=0
if [ "$1" = "--important" ]; then
  IMPORTANT=1
  shift
fi
NOTES="${1:-JavaHost $VERSION}"
if [ -n "$(git status --porcelain)" ]; then
  echo "Commit your changes before releasing." >&2
  exit 1
fi
if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "v$VERSION already exists. Bump version= in gradle.properties first." >&2
  exit 1
fi
if [ "$IMPORTANT" = 1 ]; then
  # Raise the minimum safe version to this one; it's carried by every later release too.
  sed -i.bak "s/^minimum_version=.*/minimum_version=$VERSION/" gradle.properties && rm -f gradle.properties.bak
  git commit -q -am "Mark $VERSION as an important update"
  git push -q origin HEAD
fi
git tag -a "v$VERSION" -m "$NOTES"
git push origin "v$VERSION"
echo "Tagged v$VERSION. GitHub is building and publishing the release."

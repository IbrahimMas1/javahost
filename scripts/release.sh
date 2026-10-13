#!/bin/sh
# Tags a release so GitHub builds and publishes it (with the signed update.json the in-game updater reads).
#   scripts/release.sh "What's new in this version"
# The version comes from gradle.properties; commit and push your changes first.
set -e
cd "$(dirname "$0")/.."
VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
NOTES="${1:-JavaHost $VERSION}"
if [ -n "$(git status --porcelain)" ]; then
  echo "Commit your changes before releasing." >&2
  exit 1
fi
if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "v$VERSION already exists. Bump version= in gradle.properties first." >&2
  exit 1
fi
git tag -a "v$VERSION" -m "$NOTES"
git push origin "v$VERSION"
echo "Tagged v$VERSION. GitHub is building and publishing the release."

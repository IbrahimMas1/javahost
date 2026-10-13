#!/bin/sh
# Moderating the JavaHost public world list.
#   scripts/moderate.sh reports                    who's been reported, newest first
#   scripts/moderate.sh ban <uuid> "<reason>"      remove their listing and stop them listing again
#   scripts/moderate.sh unban <uuid>
#   scripts/moderate.sh unhide <uuid>              bring back a listing that reports hid (clears its reports)
# Needs the service address and the admin secret (kept outside the repo):
#   export JAVAHOST_DIRECTORY=https://javahost-directory.<you>.workers.dev
#   export JAVAHOST_ADMIN=$(cat ../keys/directory-admin-secret)
set -e
: "${JAVAHOST_DIRECTORY:?set JAVAHOST_DIRECTORY}"
: "${JAVAHOST_ADMIN:?set JAVAHOST_ADMIN}"
auth="authorization: Bearer $JAVAHOST_ADMIN"
case "$1" in
  reports)
    curl -fsS -H "$auth" "$JAVAHOST_DIRECTORY/admin/reports"; echo ;;
  ban)
    curl -fsS -X POST -H "$auth" -H "content-type: application/json" \
      -d "{\"uuid\":\"$2\",\"reason\":\"${3:-}\"}" "$JAVAHOST_DIRECTORY/admin/ban"; echo ;;
  unban|unhide)
    curl -fsS -X POST -H "$auth" -H "content-type: application/json" \
      -d "{\"uuid\":\"$2\"}" "$JAVAHOST_DIRECTORY/admin/$1"; echo ;;
  *)
    sed -n '2,9p' "$0"; exit 1 ;;
esac

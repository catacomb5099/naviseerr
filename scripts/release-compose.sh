#!/bin/sh
# Writes the copy-paste install file for a stable release: deploy/compose.yaml with its :develop tags
# replaced. naviseerr and naviseerr-setup get the major version (:1 for 1.2.0), so Update on TrueNAS
# or `docker compose pull` brings later 1.x fixes. The client, ytmusicapi and croissant only make
# develop builds, so each gets its newest numbered develop build, the one you have been testing
# against, pinned exactly. Run by .github/workflows/release.yml; works anywhere with curl.
# Usage: scripts/release-compose.sh 1.2.0 > compose.yaml   (the "tested with" line goes to stderr)
set -eu
version=$1
printf '%s\n' "$version" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+' \
  || { echo "release-compose: '$version' is not a version like 1.2.0" >&2; exit 1; }
major=${version%%.*}
here=$(cd "$(dirname "$0")/.." && pwd)

# The newest <version>-develop.<N> tag of a public ghcr.io image (largest N).
newest_develop() {
  token=$(curl -fsS "https://ghcr.io/token?scope=repository:catacomb5099/$1:pull" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  curl -fsS -H "Authorization: Bearer $token" "https://ghcr.io/v2/catacomb5099/$1/tags/list?n=1000" \
    | tr ',' '\n' | sed -n 's/.*"\([0-9][0-9.]*-develop\.[0-9][0-9]*\)".*/\1/p' \
    | sort -t. -k4,4n | tail -1
}
client=$(newest_develop naviseerr-client)
adapter=$(newest_develop ytmusicapi)
croissant=$(newest_develop croissant)
for t in "$client" "$adapter" "$croissant"; do
  [ -n "$t" ] || { echo "release-compose: no develop build found for one of the images" >&2; exit 1; }
done

out=$(sed -e "s#ghcr.io/catacomb5099/naviseerr:develop#ghcr.io/catacomb5099/naviseerr:$major#" \
    -e "s#ghcr.io/catacomb5099/naviseerr-setup:develop#ghcr.io/catacomb5099/naviseerr-setup:$major#" \
    -e "s#ghcr.io/catacomb5099/naviseerr-client:develop#ghcr.io/catacomb5099/naviseerr-client:$client#" \
    -e "s#ghcr.io/catacomb5099/ytmusicapi:develop#ghcr.io/catacomb5099/ytmusicapi:$adapter#" \
    -e "s#ghcr.io/catacomb5099/croissant:develop#ghcr.io/catacomb5099/croissant:$croissant#" \
    -e "s#^\# DEVELOP CHANNEL: every image below is a :develop build.*#\# naviseerr $version (stable). naviseerr follows $major.x updates; the other images are the exact builds#" \
    -e "s#^\# It can break. The first stable release will replace these tags.*#\# this release was tested with. A newer release moves them.#" \
    "$here/deploy/compose.yaml")
# A new image added to deploy/compose.yaml must get a line above, or stable users would get :develop.
if printf '%s\n' "$out" | grep -q 'image: .*:develop$'; then
  echo "release-compose: an image is still on :develop; add it to scripts/release-compose.sh" >&2; exit 1
fi
printf '%s\n' "$out"
echo "Tested with naviseerr-client $client, ytmusicapi $adapter, croissant $croissant." >&2

#!/usr/bin/env bash
# Sealed end-to-end check of the all-in-one install: the stack comes up with slskd cut off from the
# internet (compose.e2e.yaml), a finished 3-song playlist is staged the way the download loop leaves
# one, the organiser files the songs and writes the .m3u8, and a throwaway Navidrome imports it with
# the 3 songs in order. Never touches the real install (project naviseerr-e2e, its own volumes, port
# 5096, library under build/e2e/). `playlist-file.sh down` removes everything it made; KEEP=1 leaves
# the stack up to look at. See README.md next to this file.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
E2E=$ROOT/scripts/e2e
P=${E2E_PROJECT:-naviseerr-e2e}
PORT=$(sed -n 's/^NAVISEERR_PORT=//p' "$E2E/e2e.env")
WORK=$ROOT/build/e2e                        # build/ is gitignored
LIB=$WORK/library
export LIBRARY_DIR=$LIB PUID=$(id -u) PGID=$(id -g)
NAVIDROME_IMAGE=${NAVIDROME_IMAGE:-deluan/navidrome:0.64.2}
ND=$P-navidrome; ND_PORT=${ND_PORT:-4533}; ND_PASS=e2e
TITLE='E2E Road Trip: Été'; M3U="$LIB/Playlists/E2E Road Trip - Été.m3u8"

compose() { docker compose --env-file "$E2E/e2e.env" -f "$ROOT/compose.yaml" -f "$E2E/compose.e2e.yaml" -p "$P" "$@"; }
psql_() { compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -tA -U naviseerr -d naviseerr "$@"; }
sub() { curl -fsS "http://localhost:$ND_PORT/rest/$1?u=admin&p=$ND_PASS&v=1.16.1&c=e2e&f=json${2:-}"; }
scan_done() { sub getScanStatus | jq -e '.["subsonic-response"].scanStatus.scanning == false'; }
say() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; compose logs --tail 40 naviseerr slskd >&2 || true; exit 1; }
wait_for() { local n=$1; shift; for _ in $(seq 1 "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
down() { compose down -v >/dev/null 2>&1 || true; docker rm -f "$ND" >/dev/null 2>&1 || true; }
[ "${1:-}" = down ] && { down; exit 0; }

say "0. fresh work folder $WORK"
down; mkdir -p "$WORK"; find "$WORK" -mindepth 1 -delete; mkdir -p "$LIB" "$WORK/stage/E2E Folder"

say "1. build the images and start the stack, slskd sealed"
compose up -d --build
wait_for 60 curl -fsS "http://localhost:$PORT/api/downloads/active" || fail "naviseerr not up"

say "2. slskd has no Soulseek connection (checked first: nothing else runs against an unsealed slskd)"
key=$(compose exec -T naviseerr sed -n 's/^SLSKD_API_KEY=//p' /config/naviseerr.properties)
state=$(compose exec -T naviseerr curl -fsS -H "X-API-Key: $key" http://slskd:5030/api/v0/server)
grep -q '"isConnected":false' <<<"$state" || fail "slskd reached Soulseek: $state"
status=$(curl -fsS "http://localhost:$PORT/api/status")
grep -q '"connected":false' <<<"$status" || fail "GET /status does not say disconnected: $status"
echo "slskd: $(jq -r .state <<<"$state"); GET /status: $(jq -c .soulseek <<<"$status")"

say "3. stage a finished 3-song playlist (files into slskd's downloads volume, rows into the database)"
song() { ffmpeg -v error -y -f lavfi -i "sine=frequency=$((400 + $1 * 100)):duration=2" \
  -metadata title="$3" -metadata artist="$2" -metadata track="$1" -b:a 128k \
  "$WORK/stage/E2E Folder/0$1 - $2 - $3.mp3"; }
song 1 'Alpha Artist' 'Alpha Song'; song 2 'Beta Band' 'Beta Tune'; song 3 'Gamma Group' 'Gamma Track'
# As PUID:PGID: `docker cp` would leave them root's and the tagger could not write them.
docker run --rm -v "${P}_downloads:/downloads" -v "$WORK/stage:/stage:ro" alpine:3.20 \
  sh -c "cp -r /stage/. /downloads/ && chown -R $PUID:$PGID /downloads"
psql_ < "$E2E/stage-playlist.sql"

say "4. the organiser files the songs and writes the playlist file"
wait_for 45 test -f "$M3U" || fail "no playlist file after 90 s"
cmp "$M3U" "$E2E/expected.m3u8" || fail "playlist content differs from expected.m3u8"
for f in 'Alpha Artist/Alpha Song/01 - Alpha Artist - Alpha Song.mp3' \
         'Beta Band/Beta Tune/02 - Beta Band - Beta Tune.mp3' \
         'Gamma Group/Gamma Track/03 - Gamma Group - Gamma Track.mp3'; do
  [ -f "$LIB/$f" ] || fail "missing $f"; done
[ "$(psql_ -c "SELECT count(*) FROM download_tasks WHERE library_path IS NOT NULL")" = 3 ] || fail "library_path not set on 3 songs"
[ "$(psql_ -c "SELECT count(*) FROM downloads WHERE organised_at IS NOT NULL")" = 1 ] || fail "organised_at not set"
docker run --rm -v "${P}_downloads:/downloads" alpine:3.20 test ! -e '/downloads/E2E Folder' || fail "downloads folder not cleaned up"
psql_ -c "UPDATE downloads SET organised_at = NULL"            # a re-run regenerates the same file
sleep 6; cmp "$M3U" "$E2E/expected.m3u8" || fail "re-run changed the file"
cat "$M3U"

say "5. Navidrome imports it"
docker image inspect "$NAVIDROME_IMAGE" >/dev/null 2>&1 || timeout 180 docker pull "$NAVIDROME_IMAGE" \
  || "$E2E/fetch-image.sh" "$NAVIDROME_IMAGE"
docker run -d --name "$ND" -p "127.0.0.1:$ND_PORT:4533" -v "$LIB:/music:ro" -e ND_MUSICFOLDER=/music \
  -e ND_DEVAUTOCREATEADMINPASSWORD=$ND_PASS -e ND_ENABLEEXTERNALSERVICES=false \
  -e ND_ENABLEINSIGHTSCOLLECTOR=false -e ND_LOGLEVEL=warn "$NAVIDROME_IMAGE" >/dev/null
wait_for 30 sub ping || fail "navidrome not up"
sub startScan '&fullScan=true' >/dev/null      # the startup scan may run before the admin exists
sleep 2; wait_for 30 scan_done || fail "navidrome scan did not finish"
pl=$(sub getPlaylists | jq -c --arg t "$TITLE" '.["subsonic-response"].playlists.playlist[]? | select(.name == $t)')
[ -n "$pl" ] || fail "playlist not in Navidrome: $(sub getPlaylists)"
[ "$(jq -r .songCount <<<"$pl")" = 3 ] || fail "songCount: $pl"
titles=$(sub getPlaylist "&id=$(jq -r .id <<<"$pl")" | jq -r '.["subsonic-response"].playlist.entry[].title' | paste -sd, -)
[ "$titles" = 'Alpha Song,Beta Tune,Gamma Track' ] || fail "titles/order: $titles"

say "PASS: slskd sealed, 3 songs filed, .m3u8 byte-exact, Navidrome shows '$TITLE' with 3 songs in order"
[ "${KEEP:-}" = 1 ] && echo "KEEP=1: stack left up (web app http://localhost:$PORT, Navidrome http://localhost:$ND_PORT admin/$ND_PASS); '$0 down' removes it" || down

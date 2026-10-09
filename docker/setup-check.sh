#!/bin/sh
# Runs setup.sh in alpine:3.20 against throwaway folders and checks the username rule (a name is made
# up once, kept across runs, a name from .env wins and the made-up one stays stored, a bad .env name
# still stops setup) and the library checks (marker file, "empty" note, a folder PUID:PGID cannot
# write, the marker gone from an empty folder, a moved library, a changed PUID/PGID after a first start,
# a folder made read-only after a first start, PUID not a number). Needs only Docker.
# Usage: docker/setup-check.sh  (prints OK, or stops at the first failing check).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
S=$(mktemp -d)
mkdir -p "$S/config" "$S/slskd" "$S/downloads" "$S/incomplete" "$S/library"
run() {
  docker run --rm -e PUID=1000 -e PGID=1000 "$@" -v "$here/setup.sh:/setup.sh:ro" -v "$S/config:/config" \
    -v "$S/slskd:/slskd" -v "$S/downloads:/downloads" -v "$S/incomplete:/incomplete" -v "$S/library:/library" \
    alpine:3.20 sh /setup.sh 2>&1
}
# setup writes its files as root, mode 600, owned by PUID: read them through a container, not the host.
peek() { docker run --rm -v "$S/config:/config:ro" -v "$S/slskd:/slskd:ro" alpine:3.20 sed -n "$@"; }
check() { if [ "$1" = "$2" ]; then echo "ok   $3"; else echo "FAIL $3: got '$1', wanted '$2'" >&2; exit 1; fi; }
username() { printf '%s\n' "$1" | sed -n 's/^naviseerr setup: done. Soulseek username: //p'; }

out=$(run)
name=$(username "$out")
check "$(printf '%s' "$name" | grep -cE '^naviseerr-[A-Za-z0-9]{6}$')" 1 "first run makes up naviseerr-xxxxxx ($name)"
check "$(printf '%s\n' "$out" | grep -c 'The username was made up for you')" 1 "first run prints the account box"
out=$(run)
check "$(username "$out")" "$name" "second run keeps the same name"
check "$(printf '%s\n' "$out" | grep -c 'Your Soulseek account')" 0 "second run prints no box"
check "$(peek 's/^SOULSEEK_USERNAME=//p' /config/secrets.env)" "$name" "secrets.env stores the name"
out=$(run -e SOULSEEK_USERNAME=my-own-name)
check "$(username "$out")" my-own-name ".env name wins"
check "$(peek 's/^  username: //p' /slskd/slskd.yml)" "'my-own-name'" ".env name reaches slskd.yml"
check "$(peek 's/^SOULSEEK_USERNAME=//p' /config/secrets.env)" "$name" "the made-up name stays stored"
rc=0; out=$(run -e SOULSEEK_USERNAME=abcdefghijklmnopqrstuvwxyz12345) || rc=$?
check "$rc" 1 "a 31-character .env name stops setup"
check "$(printf '%s\n' "$out" | grep -c 'longer than 30')" 1 "and says why"
check "$(printf '%s\n' "$(run)" | grep -c 'your library at /library is empty')" 1 "an empty library is a note, not an error"
check "$(test -f "$S/library/.naviseerr-library" && echo yes)" yes "the marker file is left in the library"
check "$(peek p /config/library.path)" /library "the library path is remembered in the config volume"
check "$(peek '/naviseerr-library/p' /slskd/slskd.yml | grep -c .)" 1 "slskd is told not to share the marker"
docker run --rm -v "$S:/s" alpine:3.20 find /s -mindepth 1 -delete && rmdir "$S"

# Ownership checks need real Linux semantics, which a folder bind-mounted from a Mac does not have
# (Docker Desktop ignores the owner): these run with every folder inside the container. $1 = shell
# lines run as root in the container, around `sh /setup.sh`; the rest = extra docker run options.
inside() {
  script=$1; shift
  docker run --rm -e PUID=1000 -e PGID=1000 -e LIBRARY_DIR=/srv/music "$@" -v "$here/setup.sh:/setup.sh:ro" \
    alpine:3.20 sh -c "mkdir -p /config /slskd /downloads /incomplete /library; $script" 2>&1
}
rc=0; out=$(inside 'chown 1001:1001 /library; chmod 755 /library; sh /setup.sh') || rc=$?
check "$rc" 1 "a library folder user 1000:1000 cannot write stops setup"
check "$(printf '%s\n' "$out" | grep -c 'LIBRARY_DIR=/srv/music cannot be written to by user 1000:1000')" 1 "and names the setting and the user"
check "$(printf '%s\n' "$out" | grep -c 'sudo chown 1000:1000')" 1 "and gives the chown to run"
rc=0; out=$(inside 'sh /setup.sh >/dev/null; find /library -mindepth 1 -delete; sh /setup.sh') || rc=$?
check "$rc" 1 "same path, folder empty, marker gone: setup stops (disk not mounted?)"
check "$(printf '%s\n' "$out" | grep -c 'marker file naviseerr left there last time is gone')" 1 "and says so"
rc=0; out=$(inside 'sh /setup.sh >/dev/null; find /library -mindepth 1 -delete; LIBRARY_DIR=/srv/other sh /setup.sh') || rc=$?
check "$rc" 0 "a new empty folder at another LIBRARY_DIR is accepted (moved on purpose)"
rc=0; out=$(inside 'sh /setup.sh >/dev/null; sh /setup.sh') || rc=$?
check "$rc" 0 "a second start on a library holding only the marker is fine"
check "$(printf '%s\n' "$out" | grep -c 'is empty')" 1 "and still says the library is empty"
rc=0; out=$(inside 'sh /setup.sh >/dev/null; chown 1001:1001 /library; PUID=1001 PGID=1001 sh /setup.sh') || rc=$?
check "$rc" 0 "a changed PUID/PGID is accepted once the folder is theirs (the old owner's marker does not count)"
rc=0; out=$(inside 'sh /setup.sh >/dev/null; chmod 555 /library; sh /setup.sh') || rc=$?
check "$rc" 1 "a folder made read-only after a first start still stops setup (the marker you own is not the test)"
rc=0; out=$(inside 'sh /setup.sh' -e PUID=abc) || rc=$?
check "$rc" 1 "PUID=abc stops setup"
check "$(printf '%s\n' "$out" | grep -c 'not a number')" 1 "and says so"
echo "OK"

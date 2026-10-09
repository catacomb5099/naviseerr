#!/bin/sh
# Runs setup.sh four times in alpine:3.20 against throwaway folders and checks the username rule: a
# name is made up once, kept across runs, a name from .env wins and the made-up one stays stored, and a
# bad .env name still stops setup. Needs only Docker. Usage: docker/setup-check.sh  (prints OK, or
# stops at the first failing check).
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
docker run --rm -v "$S:/s" alpine:3.20 find /s -mindepth 1 -delete && rmdir "$S"
echo "OK"

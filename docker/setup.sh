#!/bin/sh
# naviseerr's setup step. compose runs it (the `setup` service, as root) on every `docker compose up`,
# before slskd and naviseerr, which only start once it exits 0. It:
#   1. gives the shared folders to PUID:PGID, the one user slskd and naviseerr both run as;
#   2. takes the Soulseek username from .env, or makes one up on the first start and keeps it;
#   3. generates the other secrets once and keeps them in /config/secrets.env;
#   4. writes slskd's config (/slskd/slskd.yml) from .env and those secrets, sharing the library
#      unless SHARE_LIBRARY=false;
#   5. hands naviseerr slskd's API key (/config/naviseerr.properties).
set -eu

PUID=${PUID:-1000}
PGID=${PGID:-1000}
# Every file written below holds a secret: readable by its owner only.
umask 077

# 1. A fresh volume, or a library folder Docker had to create, belongs to root, and slskd and naviseerr
# (not root) could not write into it.
# naviseerr's own volumes: re-owned with everything in them whenever they are not PUID:PGID's, so a
# changed PUID/PGID in .env takes effect (slskd will not start in a folder it cannot write).
for d in /config /slskd /downloads /incomplete; do
  if [ "$(stat -c %u:%g "$d")" != "$PUID:$PGID" ]; then chown -R "$PUID:$PGID" "$d"; fi
done
# Your library: only the folder itself and only while root still owns it: an existing library keeps
# its owner, and nothing inside it is ever re-owned.
if [ "$(stat -c %u /library)" = 0 ]; then chown "$PUID:$PGID" /library; fi

# Generated values live in secrets.env: made once, reused on every later start.
secrets=/config/secrets.env
random() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }
stored() { if [ -f "$secrets" ]; then sed -n "s/^$1=//p" "$secrets"; fi; }

# 2. The Soulseek username: SOULSEEK_USERNAME from .env, else the one made up on an earlier start, else
# a new one, kept in secrets.env so the account stays the same across restarts (.env wins, as for the
# password). The server's own rules on the name: 1-30 printable ASCII characters, no space at either end.
generated_name=$(stored SOULSEEK_USERNAME)
new_name=false
if [ -z "${SOULSEEK_USERNAME:-}" ] && [ -z "$generated_name" ]; then
  generated_name="naviseerr-$(random 6)"
  new_name=true
fi
name=${SOULSEEK_USERNAME:-$generated_name}
problem=
if [ "$(printf '%s' "$name" | tr -d ' -~' | wc -c)" -ne 0 ]; then
  problem="SOULSEEK_USERNAME has a character Soulseek does not accept (accents, emoji, tabs...)."
elif [ "${#name}" -gt 30 ]; then
  problem="SOULSEEK_USERNAME is longer than 30 characters."
else
  case $name in
    " "* | *" ") problem="SOULSEEK_USERNAME starts or ends with a space." ;;
  esac
fi
if [ -n "$problem" ]; then
  cat >&2 <<EOF

naviseerr setup: $problem

Fix SOULSEEK_USERNAME in the .env file next to compose.yaml, or leave it empty and a name is made up
for you, then run \`docker compose up -d\` again.

- 1 to 30 characters: plain letters, digits and punctuation, no space at the start or end.
- Pick something unique. The account is created the first time slskd logs in with it; if someone
  else already has the name, slskd's log says "invalid username or password": choose another.

EOF
  exit 1
fi

# Checked, not guessed: sharing (or not) is your call, so a typo must not quietly decide it.
case ${SHARE_LIBRARY:-true} in
  true | True | TRUE) share=true ;;
  false | False | FALSE) share=false ;;
  *)
    echo "naviseerr setup: SHARE_LIBRARY in .env must be true or false, not '$SHARE_LIBRARY'." >&2
    exit 1
    ;;
esac

# 3. Letters and digits only: slskd has been seen to reject passwords with some punctuation.
soulseek_password=$(stored SOULSEEK_PASSWORD)
api_key=$(stored SLSKD_API_KEY)
web_password=$(stored SLSKD_WEB_PASSWORD)
new_password=false
if [ -z "$soulseek_password" ]; then soulseek_password=$(random 24); new_password=true; fi
if [ -z "$api_key" ]; then api_key=$(random 32); fi
if [ -z "$web_password" ]; then web_password=$(random 24); fi

# Written to a temporary name first, so an interrupted run never leaves half a file behind. slskd can
# write into /slskd, so nothing already there is trusted: a leftover .tmp is deleted, the new one is
# only ever created, never opened through a link (set -C), and a link is never followed (-h, -T).
write() {
  rm -f "$1.tmp"
  (set -C; cat >"$1.tmp")
  chown -h "$PUID:$PGID" "$1.tmp"
  mv -T "$1.tmp" "$1"
}

write "$secrets" <<EOF
# Generated once by naviseerr's setup. Deleting this file makes new ones on the next start, and the
# Soulseek account then no longer logs in (a Soulseek password cannot be reset). SOULSEEK_USERNAME is
# the name made up for you; empty when you set your own in .env from the start.
SOULSEEK_USERNAME=$generated_name
SOULSEEK_PASSWORD=$soulseek_password
SLSKD_API_KEY=$api_key
SLSKD_WEB_PASSWORD=$web_password
EOF

# Bringing an existing account: SOULSEEK_PASSWORD in .env wins; the generated one stays stored.
password=${SOULSEEK_PASSWORD:-$soulseek_password}

# 4. YAML single quotes take everything literally; a quote inside is written twice.
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/''/g")"; }

{
  cat <<EOF
# Written by naviseerr's setup on every start from .env; edits are overwritten.
soulseek:
  username: $(q "$name")
  password: $(q "$password")
  listen_port: 50300
directories:
  downloads: /downloads
  incomplete: /incomplete
web:
  https:
    disabled: true
  authentication:
    password: $(q "$web_password")
    api_keys:
      naviseerr:
        key: $(q "$api_key")
        role: readwrite
EOF
  # /music is the library, mounted read-only into slskd.
  if [ "$share" = true ]; then
    cat <<'EOF'
shares:
  directories:
    - '[Music]/music'
  filters:
    - '\.m3u8?$'   # playlists
    - '\.partial$' # naviseerr's half-copied staging files
  cache:
    retention: 60   # minutes: rescan hourly so new songs are shared; slskd has no file watching
EOF
  fi
} | write /slskd/slskd.yml

# 5. Read by naviseerr through SPRING_CONFIG_IMPORT.
write /config/naviseerr.properties <<EOF
SLSKD_API_KEY=$api_key
EOF

# The account box: whenever something was made up this run that you must know. The password line
# only when it was generated (one you set in .env is yours already and stays out of the log).
if [ "$new_name" = true ] || { [ "$new_password" = true ] && [ -z "${SOULSEEK_PASSWORD:-}" ]; }; then
  echo
  echo "Your Soulseek account:"
  echo "    username: $name"
  if [ -z "${SOULSEEK_PASSWORD:-}" ]; then echo "    password: $soulseek_password"; fi
  cat <<EOF
Keep this somewhere: Soulseek passwords cannot be reset. It is also stored in naviseerr's config
volume (secrets.env) and in slskd's config: docker compose exec slskd cat /app/slskd.yml
EOF
  if [ "$new_name" = true ]; then
    cat <<EOF
The username was made up for you. To use a name of your own, set SOULSEEK_USERNAME in .env, run
\`docker compose up -d\` and then \`docker compose restart slskd\`.
EOF
  fi
  echo
fi
# A username is not a secret: every run says which account this install uses.
echo "naviseerr setup: done. Soulseek username: $name"

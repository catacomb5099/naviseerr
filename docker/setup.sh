#!/bin/sh
# naviseerr's setup step. compose runs it (the `setup` service, as root) on every `docker compose up`,
# before slskd and naviseerr, which only start once it exits 0. It:
#   1. gives the shared folders to PUID:PGID, the one user slskd and naviseerr both run as, and checks
#      that user can write into your library and that the library is still the one it saw last time;
#   2. takes the Soulseek username from .env, or makes one up on the first start and keeps it;
#   3. generates the other secrets once (slskd's API key, the playlist maker's token...) and keeps
#      them in /config/secrets.env;
#   4. writes slskd's config (/slskd/slskd.yml) from .env and those secrets, sharing the library
#      unless SHARE_LIBRARY=false;
#   5. hands naviseerr slskd's API key and the playlist maker's token (/config/naviseerr.properties),
#      and the playlist maker (croissant) its token (/config/curator.token).
set -eu

PUID=${PUID:-1000}
PGID=${PGID:-1000}
# The library's path on the host (compose passes LIBRARY_DIR through), for the messages below.
LIBRARY_DIR=${LIBRARY_DIR:-/library}
# Every file written below holds a secret: readable by its owner only.
umask 077

# One plain-words block per problem, then stop: compose then says "dependency failed to start ...
# setup" and `docker compose logs setup` shows this. $1 = what is wrong, $2 = how to fix it.
problem() {
  printf '\nnaviseerr setup: %s\n\n%s\n\nThen run `docker compose up -d` again.\n\n' "$1" "$2" >&2
  exit 1
}

# Checked before anything is re-owned: `chown abc:1000` would stop with a bare busybox error.
case $PUID$PGID in
  *[!0-9]*) problem "PUID or PGID in .env is not a number (PUID='$PUID', PGID='$PGID')." \
    "Set them to the numbers \`id -u\` and \`id -g\` print for the user who owns your music folder
(Linux only), or leave both empty for the default 1000." ;;
esac

# 1. A fresh volume, or a library folder Docker had to create, belongs to root, and slskd and naviseerr
# (not root) could not write into it.
# naviseerr's own volumes: re-owned with everything in them whenever they are not PUID:PGID's, so a
# changed PUID/PGID in .env takes effect (slskd will not start in a folder it cannot write).
for d in /config /slskd /downloads /incomplete /curator/output /curator/history /curator/runs; do
  if [ "$(stat -c %u:%g "$d")" != "$PUID:$PGID" ]; then chown -R "$PUID:$PGID" "$d"; fi
done
# Your library: only the folder itself and only while root still owns it: an existing library keeps
# its owner, and nothing inside it is ever re-owned. A network share may refuse root (root_squash):
# then the write test below speaks instead of a bare chown error.
if [ "$(stat -c %u /library)" = 0 ]; then chown "$PUID:$PGID" /library 2>/dev/null || true; fi

# Can PUID:PGID write into your library? Tested AS that user: root can write where naviseerr and
# slskd cannot, so a plain test here would lie. busybox has su; the user may not exist in this
# container yet (named after the id, so a new PUID/PGID in a reused container gets its own).
if [ "$PUID" = 0 ]; then usr=root; else
  grp=$(awk -F: -v g="$PGID" '$3==g{print $1;exit}' /etc/group)
  [ -n "$grp" ] || { addgroup -g "$PGID" "nvs$PGID"; grp=nvs$PGID; }
  usr=$(awk -F: -v u="$PUID" '$3==u{print $1;exit}' /etc/passwd)
  [ -n "$usr" ] || { adduser -D -H -u "$PUID" -G "$grp" -s /bin/sh "nvs$PUID"; usr=nvs$PUID; }
fi
as_user() { su "$usr" -s /bin/sh -c "$*"; }
# A hidden marker file in the library, plus the path it had on the last good start (/config), tells
# "the disk is not mounted" (same path, folder empty, marker gone) apart from a new empty library.
marker=/library/.naviseerr-library
record=/config/library.path
# Judged before the marker is written, so a brand-new folder counts as empty (the note at the end).
if [ -z "$(ls -A /library | grep -vxF .naviseerr-library)" ]; then library_empty=true; else library_empty=false; fi
if [ -f "$record" ] && [ "$(cat "$record")" = "$LIBRARY_DIR" ] && [ ! -e "$marker" ] && [ -z "$(ls -A /library)" ]; then
  problem "LIBRARY_DIR=$LIBRARY_DIR is empty, and the small marker file naviseerr left there last time is gone." \
"Is the disk or network share mounted? Mount it, or fix LIBRARY_DIR in .env.
If you emptied the folder on purpose:    touch '$LIBRARY_DIR/.naviseerr-library'"
fi
# A fresh file, never the marker: touching a file you already own says nothing about the folder, and
# a marker left by an earlier PUID (owner-only) would refuse a folder the new user can write to.
as_user "touch $marker.$$ && rm $marker.$$" 2>/dev/null || problem \
  "LIBRARY_DIR=$LIBRARY_DIR cannot be written to by user $PUID:$PGID (PUID:PGID in .env), the user naviseerr and slskd run as. It belongs to $(stat -c %u:%g /library), mode $(stat -c %a /library)." \
"Linux: give the folder to that user:    sudo chown $PUID:$PGID '$LIBRARY_DIR'
or set PUID and PGID in .env to the folder's owner (ls -ln '$LIBRARY_DIR' shows the numbers).
Docker Desktop (macOS, Windows): make the folder writable by your own account; PUID and PGID play no part there."
[ -e "$marker" ] || as_user "touch $marker"

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
# The secret naviseerr and the playlist maker (croissant) share; croissant wants 16 characters or more.
curator_token=$(stored CURATOR_TOKEN)
new_password=false
if [ -z "$soulseek_password" ]; then soulseek_password=$(random 24); new_password=true; fi
if [ -z "$api_key" ]; then api_key=$(random 32); fi
if [ -z "$web_password" ]; then web_password=$(random 24); fi
if [ -z "$curator_token" ]; then curator_token=$(random 32); fi

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
CURATOR_TOKEN=$curator_token
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
    - '\.naviseerr-library$' # the marker file setup leaves in the library (see the library check)
  cache:
    retention: 60   # minutes: rescan hourly so new songs are shared; slskd has no file watching
EOF
  fi
} | write /slskd/slskd.yml

# 5. Read by naviseerr through SPRING_CONFIG_IMPORT.
write /config/naviseerr.properties <<EOF
SLSKD_API_KEY=$api_key
CURATOR_TOKEN=$curator_token
EOF
# The same token for croissant (CURATOR_TOKEN_FILE in compose.yaml), which reads the one line.
printf '%s\n' "$curator_token" | write /config/curator.token
# Where the library was on this good start (see the marker check above).
printf '%s\n' "$LIBRARY_DIR" | write "$record"

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
# Every fresh install starts empty, so this is a note, never an error.
if [ "$library_empty" = true ]; then
  if [ "$share" = true ]; then shared='; until then nothing is shared on Soulseek'; else shared=''; fi
  echo "naviseerr setup: your library at $LIBRARY_DIR is empty. It fills as songs download$shared."
fi
# A username is not a secret: every run says which account this install uses.
echo "naviseerr setup: done. Soulseek username: $name"

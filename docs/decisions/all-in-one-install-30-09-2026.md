# All-in-one install: naviseerr, its own slskd and the web app in one Docker Compose file

**Date:** 30-09-2026
**Status:** Accepted, implemented in five stacked PRs (the server in Docker; the bundled slskd; sharing
the library; the web app; this guide). The whole stack has not been run end to end yet, see "What was
tested".
**Builds on:** [library-organiser-27-09-2026.md](library-organiser-27-09-2026.md) (the organiser that
files songs and writes the playlist files, off unless naviseerr can see slskd's downloads folder).

## Context

The owner's playlists never appeared in Navidrome or Jellyfin. naviseerr only makes a playlist by
writing `LIBRARY_ROOT/Playlists/<title>.m3u8` (`LibraryOrganiser`), and only when `SLSKD_DOWNLOADS_DIR`
and `LIBRARY_ROOT` are both set, that is when naviseerr shares a disk with slskd. The owner runs
naviseerr on a laptop against an slskd on another machine, so the organiser was off and no playlist
file was ever written. Albums showed up only because the music servers read slskd's download folder
directly.

Separately, a newcomer had to clone four repositories side by side, install Java 21 and run their own
slskd before anything worked.

## Decisions

### Bundle slskd, so the organiser is on by default

`compose.yaml` runs `slskd/slskd:0.26.0` (pinned) next to naviseerr. Both mount the same `downloads`
and `incomplete` volumes at the same paths, and compose sets `SLSKD_DOWNLOADS_DIR`,
`SLSKD_INCOMPLETE_DIR` and `LIBRARY_ROOT` for naviseerr. The organiser is therefore on in every
install, and every finished playlist becomes a `.m3u8` in `LIBRARY_DIR`, where the music servers
import it.

### The user picks the Soulseek username; everything else is generated

(Reversed 09-10-2026: the username is made up unless you set one. See the addendum below.)

Soulseek's rules forbid randomly generated usernames (automated scripting). So `SOULSEEK_USERNAME` is
the only required setting. It is checked against Soulseek's rules (1 to 30 printable ASCII
characters, no space at either end) before anything starts, and a friendly message explains the rule
when it is missing. Logging in with an unused name creates the account; there is no sign-up step.

Generated once: the Soulseek password (24 letters and digits: slskd has rejected passwords with some
punctuation), slskd's API key (32) and slskd's web password (24, replacing the default slskd/slskd).
`SOULSEEK_PASSWORD` in `.env` brings an existing account and wins over the generated one.

### Revisited 09-10-2026: the username is made up unless you set one

The owner asked for the username to be randomised at setup. The install now needs no `.env` at all:
when `SOULSEEK_USERNAME` is empty, `setup.sh` makes up `naviseerr-` plus six letters and digits on
the first start, keeps it in `secrets.env` next to the password, and reuses it on every later start.
A name set in `.env` wins and the made-up one stays stored, the same rule as the password. Every
setup run prints the username in use (a username is not a secret); the password is still printed
once. The `:?` guard in `compose.yaml` went with it, and so did the README's PowerShell `>` caveat.

What the rules actually say, checked on 09-10-2026. The official Soulseek rules page
(http://www.slsknet.org/news/node/681) says nothing about usernames. Its one sentence on automation
is: "Spammers, automated clients (robot/bot), combinations of such, or scripts otherwise failing to
implement the full range of Soulseek® features are not allowed to connect to the Soulseek®
service." It also says access "may be revoked at any time, for any reason" and that administrators
"can kill any connections without prior notice for any reason". The sentence the 30-09 decision
rested on is Nicotine+'s protocol document, not the rules: "It is unacceptable to use randomly
generated usernames, as such automated scripting is disallowed by the official server rules." That
is the Nicotine+ authors' reading of the automation rule.

The risk, plainly: the rule is about automated clients, and naviseerr's install is already a headless
client (slskd, driven by naviseerr, no chat or wishlist use), so its standing under that rule does not
change with the name. A generated name does remove the "a human chose this" signal, and a shared
`naviseerr-` prefix across installs is a one-grep target if an admin ever decides the project is
unwelcome; admins may cut any connection for any reason, and nothing makes an account unbannable.
Mitigations: the README and `.env.example` still nudge people to choose their own name, and the prefix
is one line in `setup.sh` (`generated_name="naviseerr-$(random 6)"`; a neutral `$(random 10)` hides
that it is an automated client, which some will read as safer and others as less honest). A taken
generated name (62^6 names, so about one chance in 57 billion) looks exactly like a taken hand-picked
one: slskd logs "invalid username or password" and the fix is a name of your own in `.env`.
`docker compose down -v` now deletes the generated username as well as the password; the README's
backup section covers both. Installs made before this change have `SOULSEEK_USERNAME` in `.env` and
behave exactly as before.

Checked: `docker/setup-check.sh` runs `setup.sh` in `alpine:3.20` four times (a name is made up and
printed; the same name on the second run; `.env` wins while the made-up name stays stored; a 31
character `.env` name still stops setup), and the sealed e2e stack came up with a made-up name, slskd
reading it from `slskd.yml`. The first real login that registers such a name on the network, and
whether Soulseek's administrators react to the prefix, cannot be checked from the machine this was
built on (its network blocks Soulseek, and a login would create a real account).

### Revisited 09-10-2026: the library folder is required, must exist, and is checked

The owner asked that every folder a person must provide be required and checked, the library first.
Only one mount comes from the person, `LIBRARY_DIR`; the others are named volumes setup re-owns. Until
now `LIBRARY_DIR` defaulted to `./library`, Docker created a missing path as root, and a folder
naviseerr could not write into was found out only by `WARN Could not file song` lines for ten minutes
per song, while the web app said "Downloaded". So:

- `LIBRARY_DIR` has no default (`${LIBRARY_DIR:?...}` on one YAML anchor shared by the three mounts).
  Unset or empty stops every `docker compose` command before anything builds, with a sentence naming
  the setting and the old default. Measured on Compose 5.0.1: `ps`, `logs` and `down` refuse too
  while it is unset, so the README's Updating note tells existing installs to add
  `LIBRARY_DIR=./library` first. This is a breaking change for installs that relied on the default.
- The folder must pre-exist: the anchor is long syntax with `bind: {create_host_path: false}`, so a
  mistyped path is Docker's "bind source path does not exist" at container creation (setup is the
  first container, nothing else starts) instead of a new empty folder on the wrong disk. A relative
  path without `./` now resolves against the compose folder instead of being read as a named volume.
- `setup.sh` tests the folder as `PUID:PGID` (busybox `su` after creating the user if needed): root
  can write where naviseerr cannot, and stat arithmetic lies on ACLs, NFS and Docker Desktop. The
  test file doubles as a marker, `.naviseerr-library`, and the host path of the last good start is
  kept in the config volume (`/config/library.path`). Same path, folder empty, marker gone means
  "the disk is not mounted", and setup stops rather than fill the mount point; a new path with a new
  empty folder is a library moved on purpose and is accepted. slskd gets a share filter for the
  marker. An empty library is a note on stdout, never an error: every install starts empty.
- `PUID`/`PGID` must be numbers, checked before the first `chown`; the library `chown` tolerates a
  share that refuses root (`root_squash`) and lets the write test speak.
- Every problem is one plain-words block to stderr (`problem()`), then `exit 1`.

Docker Desktop caveat: a bind mount from macOS or Windows shows as `0:0` with the host's permission
bits and ignores ownership, so the "belongs to 0:0" numbers in the message are Docker's, and the fix
there is the host folder's mode, which the message says.

Checked: `docker/setup-check.sh` (24 checks: the username rules plus the marker, the note, the
remembered path, the slskd filter, a 1001-owned folder refusing 1000, marker gone from an empty
folder, a moved library, `PUID=abc`), the sealed e2e stack (`scripts/e2e/playlist-file.sh`) after the
compose change, and on this Mac: unset and empty `LIBRARY_DIR`, a missing path, a `chmod 555`
folder, the first start's marker and note, the swapped-for-empty folder. Not checked here: Linux
ownership on a real bind mount, a real NAS unmount with `root_squash`, Windows paths, rootless Docker.

### A secrets file, and slskd's config rewritten on every start

A one-shot `setup` container (`alpine:3.20` running `docker/setup.sh` as root) runs before slskd and
naviseerr on every `docker compose up`; both wait for it to succeed.

- Secrets are generated once into the `config` volume (`/config/secrets.env`, mode 600) and reused.
- `slskd.yml` is rewritten from `.env` and those secrets on every start, so `.env` is the one place to
  change anything. slskd reloads the file by itself. Hand edits are overwritten.
- naviseerr reads the API key from `/config/naviseerr.properties` through `SPRING_CONFIG_IMPORT`.
  `SLSKD_API_KEY` is deliberately NOT in naviseerr's environment: an environment variable, even an
  empty one, beats an imported file.
- The password is printed once on the first start, because it cannot be reset.
- slskd can write into its own volume, so `setup`, running as root, never follows a link it finds
  there when it writes `slskd.yml`.
- No `global:` or `groups:` keys in `slskd.yml`: slskd 0.25+ refuses to start with them.

### Folders, ownership and one user

Every container that touches music files runs as one user, `PUID:PGID` (default 1000:1000), so
naviseerr can move and delete what slskd downloaded. slskd gets it through `user:`: it refuses
`PUID`/`PGID` variables together with `user:`, and with `user:` it never changes any owner itself.

Docker creates a missing bind-mount folder, and a fresh volume, owned by root. `setup` treats
naviseerr's own volumes and the library differently:

- its own volumes (`config`, `slskd`, `downloads`, `incomplete`) are re-owned, contents included,
  whenever they do not belong to `PUID:PGID`, so changing `PUID`/`PGID` later works (slskd exits
  when it cannot write its folder);
- the library folder is re-owned only while root owns it, and never recursively, so an existing
  library keeps its owner and nothing inside it is touched.

It also guarantees slskd's download folders exist and are writable before slskd starts, which slskd
requires for custom folders.

### Share the library

slskd shares `LIBRARY_DIR`, mounted read-only, as `[Music]`. Playlist files and naviseerr's
`.partial` staging files are left out. slskd has no file watching, so it rescans hourly
(`shares.cache.retention: 60`, the minimum). Soulseek expects users to share; people who share nothing
land in "leecher" groups that others may slow down or refuse. `SHARE_LIBRARY=false` turns it off; any
value other than true or false stops `setup` with a message, so a typo never decides it silently.
Sharing does not make an account unbannable: nothing does.

### Split the compose files

`compose.yaml` used to be the developer's file, started by `spring-boot-docker-compose` whenever
IntelliJ or `./gradlew bootRun` starts the server. With the all-in-one there, every IDE run would
start a second naviseerr and an slskd. So:

- the old file moved to `compose.dev.yaml` (`spring.docker.compose.file`). Apart from a header and
  an explicit project name, it is unchanged: same container names, same explicitly named volume
  `naviseerr-postgres-data`, which holds the owner's data. The project name is `naviseerr`, the one
  it always had (from the checkout folder), so the owner's existing containers, adapter image and
  volume carry over with nothing to clean up;
- `compose.yaml` is the all-in-one: project name `naviseerr-app`, no `container_name`, volumes scoped
  to the project. A second clone or a reinstall never picks up the developer's database, and the two
  stacks never replace each other's containers.

### Build from GitHub instead of sibling checkouts

Compose builds the adapter (`catacomb5099/ytmusicapi#move-fast-break-things`, the adapter's repository
is named ytmusicapi), the curator (`croissant#main`) and the web app
(`naviseerr-client#move-fast-break-things`) straight from their git URLs, so a newcomer clones one
repository. The server is built from the checkout itself (`build: .`): a two-stage `Dockerfile` that
runs `bootJar` only (the tests need Docker), with optional `certs/*.pem` for building behind a
TLS-intercepting proxy. `compose.dev.yaml` keeps its local sibling builds: on the owner's laptop the
adapter image needs the corporate CA, which a GitHub build does not have.

### Ports

- Published: the web app on `NAVISEERR_PORT` (5056; 5055 is taken by Jellyseerr and Overseerr), and
  Soulseek's listen port as `50300:50300`. The host port must equal the port slskd announces to the
  network, and slskd has no UPnP, so forwarding it on the router is a manual, optional step.
- Not published: Postgres (it would clash with any Postgres already on the machine), the adapter,
  naviseerr's API (the web app forwards `/api` to it), slskd's web page and the curator.

## Trade-offs

- The first start builds four images from source and takes several minutes. Builds follow moving
  branches, so a rebuild picks up whatever is on them.
- A finished file moves from the `downloads` volume to the library bind mount by copy then delete, not
  a rename: they are different filesystems. The organiser already stages under a temporary name.
- A taken username costs the newcomer a trip to `docker compose logs slskd`: slskd tries once, logs
  "invalid username or password" and stays offline until restarted.
- Deleting the `config` volume generates a new Soulseek password, and the account no longer logs in
  (unless `SOULSEEK_PASSWORD` is set).
- The library is shared by default. The user is responsible for what they share; `.env.example` says
  so.
- slskd's web page is only reachable after adding a `compose.override.yaml` (gitignored, so
  `git pull` keeps working). compose.yaml's comment publishes it on `127.0.0.1` only: `setup` turns
  slskd's HTTPS off, so its password and API key must not cross the network.

## What was tested

The server image builds and the full test suite passes. Both compose files validate
(`docker compose config`). `docker/setup.sh` was run for real in `alpine:3.20` against throwaway folders:
first and second runs, the password override, invalid usernames, a username with quotes, ownership
rules (including a later `PUID` change), links planted in slskd's volume, and both `SHARE_LIBRARY`
values.

End to end on 30-09-2026, the whole stack ran on the author's laptop with every container on a Docker
network that has no route to the internet, so the bundled slskd could not reach the Soulseek network
(its log shows it failing to resolve the server) and no account was registered. Checked: `setup`
generated the secrets and printed the account once; naviseerr started with the organiser ON and
reached slskd with the generated API key (a wrong or missing key gets 401); the web app served the
client and forwarded `/api` to naviseerr. A finished three-song playlist download was then simulated
(files placed where slskd puts them, rows inserted as the download loop writes them): naviseerr filed
all three songs into the library and wrote `Playlists/<title>.m3u8`. Navidrome 0.64.2 and Jellyfin
12.1.0, each pointed at that folder, both showed the playlist with its three songs in order (an
accented title included). slskd shared the three songs and not the `.m3u8`. A second
`docker compose up` kept every secret. Real Soulseek search and download were NOT exercised: the
network this was built on forbids it, and it would have registered an account.

## Not decided / follow-ups

- In-app Soulseek connection status and a username picker (slskd's `GET /api/v0/server` reports
  `isLoggedIn`; `PUT /api/v0/server` retries the login).
- Published images on GHCR, so the first start downloads instead of building.
- Bundling Navidrome.
- The search pacing that gets an account banned is not published; naviseerr must keep pacing its own
  searches.
- Backfilling playlists downloaded before this change.
- An upload speed limit (`transfers.upload.speed_limit`) so sharing does not fill a home connection.
- Asking slskd to rescan (`PUT /api/v0/shares`) right after filing, instead of waiting up to an hour.

## Evidence

- Nicotine+'s reading that generated usernames are unacceptable; the server's username checks;
  passwords cannot be reset:
  https://github.com/nicotine-plus/nicotine-plus/blob/c33032b8f18f80e0d9f5e93d01d452f47726a71f/doc/SLSKPROTOCOL.md
- The official Soulseek rules (automation sentence; nothing about usernames; access revocable at
  any time): http://www.slsknet.org/news/node/681 (the https address answers 403 to scripts)
- slskd `user:` vs `PUID`/`PGID`, default health check (60 minute start period):
  https://github.com/slskd/slskd/blob/0.26.0/Dockerfile, https://github.com/slskd/slskd/blob/0.26.0/docs/docker.md
- slskd config precedence, hot reload, custom download folders must exist, shares and cache retention:
  https://github.com/slskd/slskd/blob/0.26.0/docs/config.md
- slskd refuses `global:`/`groups:`; API key length; retention minimum:
  https://github.com/slskd/slskd/blob/0.26.0/src/slskd/Core/Options.cs
- slskd logs in once after a wrong password, then stays offline:
  https://github.com/slskd/slskd/blob/0.26.0/src/slskd/Application.cs, https://github.com/slskd/slskd/issues/1040
- slskd: no UPnP https://github.com/slskd/slskd/issues/908; no file watching https://github.com/slskd/slskd/issues/1772
- Navidrome imports `.m3u8` files; owner is the first admin; private by default:
  https://github.com/navidrome/navidrome/blob/v0.64.2/scanner/phase_4_playlists.go,
  https://github.com/navidrome/navidrome/blob/v0.64.2/conf/configuration.go
- Jellyfin turns a `.m3u8` in a Music library into a playlist:
  https://github.com/jellyfin/jellyfin/blob/v10.11.11/Emby.Server.Implementations/Library/Resolvers/PlaylistResolver.cs.
  An open report says file playlists come up empty on 12.1 (https://github.com/jellyfin/jellyfin/issues/18169);
  it did not reproduce with 12.1.0 in the test above.
- Spring Boot reads `spring.config.import` from the environment as an initial import; environment
  variables beat imported files:
  https://raw.githubusercontent.com/spring-projects/spring-boot/v4.0.2/core/spring-boot/src/main/java/org/springframework/boot/context/config/ConfigDataEnvironment.java,
  https://raw.githubusercontent.com/spring-projects/spring-boot/v4.0.2/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/features/external-config.adoc
- `spring.docker.compose.file`:
  https://raw.githubusercontent.com/spring-projects/spring-boot/v4.0.2/core/spring-boot-docker-compose/src/main/java/org/springframework/boot/docker/compose/lifecycle/DockerComposeProperties.java
- Compose builds from a git URL: https://raw.githubusercontent.com/compose-spec/compose-spec/main/build.md;
  an explicit volume `name:` is shared by every project: https://raw.githubusercontent.com/compose-spec/compose-spec/main/07-volumes.md;
  `service_completed_successfully`: https://raw.githubusercontent.com/compose-spec/compose-spec/main/05-services.md
- Docker creates a missing bind-mount folder as root:
  https://raw.githubusercontent.com/moby/moby/master/daemon/volume/mounts/mounts.go
- Building on the builder's own CPU (`--platform=$BUILDPLATFORM`):
  https://raw.githubusercontent.com/docker/docs/main/content/manuals/build/building/multi-platform.md

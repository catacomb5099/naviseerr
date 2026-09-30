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

Soulseek's rules forbid randomly generated usernames (automated scripting). So `SOULSEEK_USERNAME` is
the only required setting. It is checked against Soulseek's rules (1 to 30 printable ASCII
characters, no space at either end) before anything starts, and a friendly message explains the rule
when it is missing. Logging in with an unused name creates the account; there is no sign-up step.

Generated once: the Soulseek password (24 letters and digits: slskd has rejected passwords with some
punctuation), slskd's API key (32) and slskd's web password (24, replacing the default slskd/slskd).
`SOULSEEK_PASSWORD` in `.env` brings an existing account and wins over the generated one.

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

- Soulseek rules against generated usernames; username rules; passwords cannot be reset:
  https://github.com/nicotine-plus/nicotine-plus/blob/c33032b8f18f80e0d9f5e93d01d452f47726a71f/doc/SLSKPROTOCOL.md
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

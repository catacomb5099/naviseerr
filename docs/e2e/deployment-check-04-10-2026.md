# What we found installing naviseerr from scratch (04-10-2026)

**Date:** 04-10-2026
**What was installed:** naviseerr `move-fast-break-things` at `53ee36c` (the install guide, #86), the web app
at `6b2650c`, the YouTube Music helper at `c114c72`.
**How:** README "Install", step by step, as someone new to it would. Only changes that the laptop needed
(listed under "Laptop-only" below). No code was changed.

## Verdict

**The one-command install works.** After filling in one setting, the build took 49 seconds. 23 seconds
after `docker compose up -d` every part was running, and the four that have a health check said healthy.
The web app opened and search worked end to end. Finished downloads were filed into the music folder
and the playlist file was written within 2 seconds. Data survived a stop and start, a second `up -d`
changed nothing, and an update took 15 to 25 seconds. Against the owner's real slskd, one song went from
request to downloaded in 19 seconds.

**What a newcomer will trip over:**

1. **Soulseek not connected = no explanation anywhere.** When slskd cannot log in to Soulseek (name already
   taken, a network that blocks it, a wrong password), every download shows "Starting" for two minutes, then
   "Search failed". `docker compose ps` still says everything is healthy. slskd gives the reason in plain
   words, but naviseerr throws it away.
2. **Albums with untagged files look broken in Navidrome.** naviseerr files songs into the right folders but
   never writes tags. A downloaded album whose files have no tags shows up as "[Unknown Album]" by
   "[Unknown Artist]", with file names as song titles.
3. **Turning sharing off does not stop sharing** until slskd is restarted, even though the settings file
   says it does.

None of these stops the install from working. The first one decides whether a newcomer whose Soulseek login
fails can ever work out why.

## Timings and sizes

| What | Result |
|---|---|
| `git clone` | 2.2 s, 12 MB |
| Base images a newcomer downloads before the first build | about 710 MB (compressed, amd64); the laptop already had them, so no download was timed |
| First build, without reusing earlier build steps | 49 s on an 11-core laptop (server 41 s, web app packages 16 s, helper packages 7 s). A NAS or Raspberry Pi will be several times slower; not measured |
| First `docker compose up -d` until every part is healthy | 23 s (the command itself returns after 12 s) |
| Second `docker compose up -d`, nothing changed | 1 s, nothing restarted |
| `docker compose down`, then `up -d` until healthy | 3 s + 29 s |
| Update, nothing changed (`docker compose up -d --build`) | 15-16 s; it still restarts three parts (issue 9) |
| Update with a change in the server's code | 25 s |
| Restart of the server | web app answers again after about 4 s |
| Search "daft punk" through the web app | 1.8 s first time, 0.6 s after; 25 songs, 20 albums, 12 artists, 28 playlists |
| One real song via the owner's slskd | 19 s from request to downloaded |

Disk: what runs is about 1.8 GB (server 561 MB, slskd 417 MB, database 407 MB, YouTube helper 280 MB, web
app 123 MB, setup 14 MB). The build also leaves about 1.3 GB of build-only images (Java build kit 947 MB,
Node 344 MB) plus a build cache. Plan for about 4 GB free.

Memory when idle: about 590 MiB in total (server 348, slskd 92, helper 90, database 44, web app 13); about
650 MiB after some searching and downloading.

## Issues in the product

Ranked by how much they will hurt. "Who" says who would hit it: every newcomer, only some setups, or only
the laptop this was tested on.

### 1. Nothing tells you Soulseek is not connected

- **What happened:** slskd was cut off from the internet on purpose (see "Laptop-only"), exactly like a
  user whose name is taken or whose network blocks Soulseek. A requested song ("Da Funk") showed
  "Starting" for 2 minutes, then "Search failed". naviseerr asked slskd to search 31 times in those 2
  minutes; slskd refused each time with a clear reason that naviseerr discarded.
- **Who:** every newcomer whose Soulseek login does not work. The README's own troubleshooting case, a
  taken name, would end up here.
- **How bad:** high. The app gives no clue, and the health status says all is well (issue 2).
- **Evidence:**
  ```
  $ docker compose logs slskd
  [12:17:01 ERR] Failed to reconnect: Failed to resolve address 'vps.slsknet.org': Resource temporarily unavailable
  # slskd's own status, asked with its API key from inside the server container:
  GET /api/v0/server -> {"state":"None","isConnected":false,"isLoggedIn":false,...}
  POST /api/v0/searches -> 409 "The server connection must be connected and logged in to perform a search (currently: Disconnected)"
  # what naviseerr kept:
  phase=FAILED  retry_index=30  last_error="409 Conflict from POST http://slskd:5030/api/v0/searches"  took 00:02:01
  # what the web app shows: "Da Funk - Search failed"
  ```
- **Suggested fix:** check slskd's `GET /api/v0/server` and show "Not connected to Soulseek" in the app,
  with slskd's reason (this is already the top follow-up in the all-in-one decision record). Smaller first
  step: treat a 409 from `POST /searches` as its own failure code, "Not connected to Soulseek", and keep
  slskd's message on the row.

### 2. The health check says slskd is fine while it is offline

- **What happened:** `docker compose ps` showed slskd `healthy` for the whole test, while it never
  connected to Soulseek.
- **Who:** every newcomer with a login problem, and anyone following "`docker compose ps` shows what is
  running".
- **How bad:** medium. It points people away from the real problem.
- **Evidence:** `docker compose ps` -> `slskd running healthy`; slskd's `/health` page answers `Healthy`;
  `GET /api/v0/server` answers `"isLoggedIn":false`.
- **Suggested fix:** after issue 1, let the server's status page report Soulseek's state. A health check
  can only say "running" or "not running"; the useful place for "not logged in" is the app.

### 3. Downloaded albums without tags show up as "Unknown" in Navidrome

- **What happened:** a simulated finished album "Discovery" (two files without tags) and a two-song playlist
  (tagged files) were filed. Folders and the playlist file came out exactly as documented. Navidrome
  0.64.2, pointed at the folder, ignored the folder names and went by the tags alone. naviseerr does not
  write tags today (by design for now; see the library organiser decision record).
- **Who:** anyone using Navidrome, for every download whose files are untagged or wrongly tagged. How
  common that is on Soulseek has not been counted.
- **How bad:** medium. The album the user asked for is not there under its name; one song is not findable
  by its title.
- **Evidence:** what Navidrome stored after its scan:
  ```
  title                    artist                             album                   path
  02 - Aerodynamic         [Unknown Artist]                   [Unknown Album]         Daft Punk/Discovery/02 - Aerodynamic.mp3
  track03                  [Unknown Artist]                   [Unknown Album]         Daft Punk/Discovery/track03.mp3      <- naviseerr knows this is "Digital Love"
  One More Time            Daft Punk                          Discovery               Daft Punk/One More Time/01 - One More Time.mp3
  Get Lucky (Radio Edit)   Daft Punk feat. Pharrell Williams  Random Access Memories  Daft Punk/Get Lucky/08 Get Lucky.mp3
  ```
  So Navidrome shows an album "Discovery" holding one playlist song, and the downloaded album as
  "[Unknown Album]".
- **Suggested fix:** before filing, fill only the empty title, artist, album, album artist and track number
  tags from what naviseerr already knows, never overwriting (the step the organiser decision record
  already describes). Count untagged downloads first if you want proof it is worth it.

### 4. Turning sharing off does not stop sharing

- **What happened:** with four songs shared, `SHARE_LIBRARY=false` and `docker compose up -d` (what
  `.env.example` implies) removed the folder from slskd's settings, but slskd kept offering the same four
  files. Only `docker compose restart slskd` made it rescan and share nothing.
- **Who:** anyone who decides after installing that they do not want to share.
- **How bad:** medium. It is a privacy choice the user made, and it is quietly not applied.
- **Evidence:**
  ```
  [12:26:09 INF] Shared directory configuration changed.  Shares must be re-scanned for changes to take effect.
  GET /api/v0/shares          -> {"local":[]}
  GET /api/v0/shares/contents -> still lists "Music\Daft Punk\Discovery", 2 files, ... (4 files in total)
  $ docker compose restart slskd
  [12:26:33 WRN] Performing a forced re-scan of shares ... Sharing 0 directories and 0 files
  ```
- **Suggested fix:** add "then `docker compose restart slskd`" next to `SHARE_LIBRARY` in `.env.example` and
  the README. Better: have naviseerr ask slskd to rescan (`PUT /api/v0/shares`) when it starts, which also
  helps issue 12.

### 5. The logs flood when anything fails

- **What happened:** each failed search attempt in issue 1 printed a full Java error trace, about 50
  lines. One failed song wrote about 1,700 log lines in 2 minutes. A finished song whose file never shows
  up prints "not in /downloads yet" every 2 seconds for 10 minutes (about 300 lines) before one useful
  warning. Detailed "debug" logging for downloads is on by default, so this is what `docker compose logs`
  shows a user.
- **Who:** everyone who opens the logs, which the README asks them to do for every problem.
- **How bad:** low to medium. The one line that matters is hard to find.
- **Evidence:**
  ```
  WARN  DownloadStepExecutor : Step SEARCH_INIT for download a0900d2e-... failed
  org.springframework.web.reactive.function.client.WebClientResponseException$Conflict: 409 Conflict from POST http://slskd:5030/api/v0/searches
      at ... (about 50 more lines, 31 times)
  DEBUG LibraryOrganiser : Song 2e706609-... not in /downloads yet ('@@q\Mezzanine\01 Angel.flac'); will look again next pass   (every 2 s)
  WARN  LibraryOrganiser : Giving up on filing song 2e706609-...: '...01 Angel.flac' never appeared under /downloads within PT10M. Left wherever slskd put it.
  ```
  When idle, the logs are quiet (0 lines in 40 s).
- **Suggested fix:** log known slskd refusals as one line without the trace; turn download logging down to
  normal in the Docker image (keep the detail for developers); say "not there yet" once, not every pass.

### 6. A song can say "Downloaded" but never reach the music folder

- **What happened:** a finished song whose file never appeared was given up on after 10 minutes (warning
  above), but the web app still lists it as downloaded.
- **Who:** rare in the all-in-one install, because slskd and naviseerr share the same folders. It happens
  when slskd names a file differently than expected.
- **How bad:** low, but confusing: the app says done and the music app has nothing.
- **Evidence:** `GET /api/downloads/all` -> `"title":"Teardrop","stage":"SUCCEEDED"` while
  `download_tasks.library_path` is empty and the library has no such file.
- **Suggested fix:** show "Downloaded, but not added to your library" when filing was given up on.

### 7. Search error says "Bad Gateway"

- **What happened:** with the YouTube Music helper stopped, a search waited 6 to 9 seconds, then the web
  app showed "API request failed: Bad Gateway". Search came back 2 seconds after the helper restarted.
- **Who:** anyone, when the helper crashes or YouTube refuses it.
- **How bad:** low to medium. The words mean nothing to the audience the README is written for.
- **Evidence:** `curl localhost:5096/api/search/daft%20punk` -> `HTTP 502 in 9.1s`; server log
  `ytmusic-adapter unavailable: ytmusic-adapter search took over 3000 ms, 3 times`.
- **Suggested fix:** the web app turns a 502 or 503 into "Search is not available right now. Try again in a
  minute."

### 8. A forgotten username is only reported after the whole build

- **What happened:** a fresh install without `SOULSEEK_USERNAME` first built all three images, then
  stopped with a message that does not say where to look. The web page did not open.
- **Who:** newcomers who skip step 2.
- **How bad:** low. The README's troubleshooting covers it, and the second try is quick.
- **Evidence:**
  ```
  $ docker compose up -d
   Image ...-naviseerr Building ...
   Container ...-setup-1 Error service "setup" didn't complete successfully: exit 1
  $ curl localhost:<port>  ->  connection refused
  $ docker compose logs setup  ->  "naviseerr setup: SOULSEEK_USERNAME is not set." (clear, friendly)
  ```
- **Suggested fix:** in `compose.yaml`, write `${SOULSEEK_USERNAME:?Set SOULSEEK_USERNAME in .env, see README step 2}`
  for the setup service, so compose stops before building, with that sentence. Side effect: every compose
  command, `down` included, then refuses to run without the name.

### 9. Every update restarts the app, even when nothing changed

- **What happened:** `docker compose up -d --build` with no changes, run twice, restarted the server, the
  web app and the helper both times, although the images were identical. `docker compose build` followed by
  `up -d` did the same. The web app answered "Bad Gateway" for about 4 seconds each time.
- **Who:** everyone who updates.
- **How bad:** cosmetic. Downloads carry on after the restart (checked: a song in progress resumed and
  finished normally).
- **Evidence:** `Container naviseerr-e2e-1004-naviseerr-1 Recreate` with the same image id
  (`sha256:2884b21b...`) before and after. Seen with Docker Desktop 29.1.5 and Compose 5.0.1; not checked on
  other versions.
- **Suggested fix:** none needed now. Publishing ready-made images (an open follow-up) would make updates a
  plain download.

### 10. Updates take whatever is newest in three other repositories

- **What happened:** `--build` rebuilds the web app and the helper from the tip of their
  `move-fast-break-things` branches, whatever `git pull` brought for the server. Nothing ties the three
  versions together.
- **Who:** everyone, over time.
- **How bad:** low today; a risk when a server change and a web app change must ship together.
- **Evidence:** `compose.yaml`: `build: https://github.com/catacomb5099/naviseerr-client.git#move-fast-break-things`
  (and the same for the helper).
- **Suggested fix:** point the git addresses at a tag or commit, updated together with the server, or publish
  versioned images.

### 11. No login on the web app

- **What happened:** the web app on port 5056 has no login. Anyone who can reach that port can search and
  start downloads.
- **Who:** fine on a home network; a risk for anyone who forwards 5056 on their router to use it from
  outside.
- **How bad:** low now, higher once people use it away from home.
- **Evidence:** every page and `/api/...` call answered without any sign-in.
- **Suggested fix:** one README line: "Do not forward 5056 on your router: the web app has no login."

### 12. New songs are shared up to an hour late, and a restart does not rescan

- **What happened:** slskd scanned the library when it started (0 files at the time). Four songs filed
  later were not shared until an explicit rescan. A plain restart reloaded the old list instead of
  scanning.
- **Who:** everyone who shares (the default).
- **How bad:** cosmetic. This is the known "rescan right after filing" follow-up.
- **Evidence:** `Share cache loaded from disk successfully. Sharing 0 directories and 0 files` after restart;
  after `PUT /api/v0/shares`: `Sharing 5 directories and 4 files`.
- **Suggested fix:** naviseerr asks slskd to rescan after filing a batch (as already planned).

### 13. Small things

- **Playlist names lose punctuation in the file name.** "E2E Road Trip: Été" became
  `Playlists/E2E Road Trip_ Été.m3u8`. Navidrome uses the name inside the file (correct); Jellyfin uses the
  file name, so it would show "E2E Road Trip_ Été". Replacing `:` with ` -` instead of `_` would read
  better.
- **The web app has no health check**, so `docker compose ps` shows an empty health column for it. A
  one-line `HEALTHCHECK` in the web app's Dockerfile fixes it.
- **A log line does bad arithmetic:** `45 relevant candidates (43 in the requested version, -2 other versions, 4 unverified artist)`.
- **The web app build reports** `16 vulnerabilities (2 low, 3 moderate, 11 high)` in its build tools, and
  `esbuild@0.21.5 (postinstall) ... not yet covered by allowScripts`. Nothing reaches the running app, but a
  future npm may refuse the install script.
- **The real download was a FLAC file** although the minimum bit rate is 320 kbps (the FLAC filter issue
  already known from 30-09).

## README and settings file

- **`.env.example` says "Required" twice for things a newcomer must leave empty.** Its developer section
  marks `SLSKD_API_KEY` and `SLSKD_URL` as "Required". The header above says the install ignores them, but a
  newcomer scanning for "Required" finds three, not one. Suggested fix: remove "Required" in that section,
  or move it to a separate file for developers.
- **No "stop, remove, back up" section, and no warning about `docker compose down -v`.** That command, common
  in online advice, deletes the `config` volume, the only place the generated Soulseek password is kept,
  and Soulseek passwords cannot be reset. Suggested fix: a short section with `docker compose down` to
  stop, a warning about `-v`, and one command to copy the password somewhere safe.
- **"How do I know it is working?" is missing.** After step 5 there is nothing to check that Soulseek is
  logged in. Until issue 1 is fixed, a line like "`docker compose logs slskd` should not keep saying
  'Failed to reconnect'" would help.
- **Disk and download size are not mentioned.** "What you need" could say: about 700 MB to download and
  4 GB of disk.
- **Permission advice is for Linux only.** On macOS with Docker Desktop, the default `PUID`/`PGID`
  (1000:1000) worked as is: files in the library showed up as the Mac user's own (502:20 here). The
  troubleshooting text could say "Linux" in front.
- **Changing the username:** the README says to restart slskd afterwards. Here slskd picked up the new name
  by itself (`Options changed, restarting (re)connection process...`). Whether it then logs in without a
  restart could not be checked (no Soulseek here); the restart does no harm.
- **What worked as written:** the password is printed once (`docker compose logs setup`), the fallback
  `docker compose exec slskd cat /app/slskd.yml` shows it, a second start keeps every secret, the setup
  error for a missing name is clear, deep links like `/downloads` survive a reload, and the library folder
  is created and writable without any action.

## Laptop-only deviations (not product problems)

This laptop sits behind a corporate network that inspects encrypted traffic and forbids file sharing. To get
past that, and only that:

- **The web app and the helper were built from local copies, not from GitHub.** Built from the GitHub
  address as the README does, the web app failed after 74 s:
  `npm error code UNABLE_TO_GET_ISSUER_CERT_LOCALLY ... request to https://registry.npmjs.org/zod-validation-error/-/zod-validation-error-4.0.2.tgz failed`.
  The `certs/` folder trick for such networks only works for local builds. A home network does not have
  this problem. Anyone behind a similar office or school network, or some antivirus products, would hit it
  too; ready-made images would remove it. The helper's GitHub build "passed", but only because every step
  was reused from an earlier build, so it proves nothing.
- **slskd was sealed off the internet** (a Docker network with no way out), so no Soulseek account was
  created and port 50300 was not published. This is also what made issue 1 visible.
- **The real download used a second, separate server container** pointed at the owner's own slskd, with its
  own empty database and the corporate certificate added. That song stayed on the owner's server, so
  filing a real downloaded file into the local library was not tested.
- **Navidrome ran with no network and no user account.** Its scan of the library was read straight from its
  database. Importing the playlist needs an admin account, so that was not repeated; it was checked on
  30-09 (Navidrome 0.64.2 imported all songs).
- Docker Hub responded normally today (0.3 s per image lookup), and every base image was already on the
  laptop, so the download hang seen on 30-09 did not come up.

## Could not test

- Real Soulseek with the bundled slskd: account creation, a taken name, a wrong password, port 50300
  forwarding, and how many results a fresh account gets.
- Linux (permissions with `sudo`, a library folder Docker creates as root) and Windows.
- Build time on slow hardware (NAS, Raspberry Pi) and a first download of the base images.
- Filing a real downloaded file, as opposed to simulated ones.
- Playlist import into Navidrome or Jellyfin (needs an account; done on 30-09).
- The optional curator (`--profile curator`).
- Updating an install that already holds data across a database change (only a fresh database was set up).

## How this was run

```sh
git worktree add -b docs/ai-e2e-deployment-check ~/IdeaProjects/.mfbt-wt/nav-e2e-1004 origin/move-fast-break-things
cp .env.example .env    # SOULSEEK_USERNAME=e2e-sealed-1004, NAVISEERR_PORT=5096
# compose.override.yaml (gitignored): build the web app and helper from local copies with the corporate
# certificate; slskd only on an `internal: true` network; naviseerr on both networks.
docker compose -p naviseerr-e2e-1004 build --no-cache
docker compose -p naviseerr-e2e-1004 up -d
```

Finished downloads were simulated by copying small test mp3 files (made with ffmpeg, some tagged, some
not) into slskd's downloads volume at `<last folder of the sharer's path>/<file>`, then adding the matching
`media_items`, `downloads` (one playlist, one album, two songs, all SUCCEEDED) and `download_tasks` rows.
Everything was removed afterwards with `docker compose -p naviseerr-e2e-1004 down -v`; the built images were
kept.

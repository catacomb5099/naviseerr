# Tell Navidrome and Jellyfin to rescan when a download is filed (19-10-2026)

> Status: accepted. Owner's ask of 19-10-2026: "navidrome + jellyfin integration so a finished collection
> or individual song download triggers a library rescan". Written from the code at `move-fast-break-things`
> `2a27bb0`, Navidrome 0.64.2 and Jellyfin 12.2.0 run live on the laptop. The companion ask (the
> playlist's picture, same day) is in the addendum of `library-organiser-27-09-2026.md`.

## The short answer

- **New package `mediaserver/`**: one client per server (`NavidromeClient`, `JellyfinClient`) and a
  `MediaServerNotifier` the download loop pokes. When a download is stamped `organised_at` (its songs
  are filed, its playlist file written: the moment the owner describes), the notifier sends **one rescan
  request per configured server**, at most once every 30 s (`media-servers.rescan-gap-ms`, trailing: a
  change inside the gap goes out when it ends). The first flush after start sends one unconditionally,
  as the credentials check and to catch anything filed right before a restart.
- **Settings are five optional env lines** in the install's `.env`, like `DISCOGS_TOKEN`:
  `NAVIDROME_URL`, `NAVIDROME_USER`, `NAVIDROME_PASSWORD`, `JELLYFIN_URL`, `JELLYFIN_API_KEY`
  (`application.yaml` `media-servers:`). Setting a URL is the switch. No screen this sweep: a settings page
  would show a Navidrome admin password to anyone on the LAN until the login (auth ask) lands.
- **`GET /status` says how each server answered**: `library.mediaServers[] = {name, url, problem, lastRescanAt}`,
  `problem` one plain sentence (wrong password, non-admin user, rejected API key, host not answering) or
  null. The log has one WARN when a server first fails and one INFO when it works again, DEBUG in between.
- **Secrets never travel in a URL or a log line**: Navidrome gets the Subsonic token form
  (`t` = md5(password + salt), fresh salt per call); Jellyfin gets `Authorization: MediaBrowser Token="<key>"`.
  Error sentences carry the status code, never the request URL.

## Why a call at all: the servers' own watchers do not cover the common layouts

Navidrome 0.64.2 watches its music folder (5 s debounce, periodic scan disabled by default) and Jellyfin
has an optional per-library "real time monitoring" (60 s delay). Both are inotify under the hood, and
inotify delivers nothing over an NFS/SMB share, from another machine, or through Docker Desktop's file
sharing. Measured 19-10-2026 on this laptop: Navidrome did not see a tagged mp3 written the way the
organiser writes it (`.partial` then rename) for 3+ minutes; an explicit `startScan` found it in 0.9 s.
This is exactly the owner's complaint. Lidarr, Sonarr and Radarr ship "Update Library" connections for
the same reason; their shape (host, login, one call on import) is what this follows.

## What each server is asked

| | Navidrome | Jellyfin |
|---|---|---|
| call | `GET /rest/startScan` (Subsonic, `v=1.16.1`, `c=naviseerr`, `f=json`), incremental (`fullScan` absent) | `GET /Library/MediaFolders`, then `POST /Items/{id}/Refresh?Recursive=true&MetadataRefreshMode=Default&ImageRefreshMode=Default` per library of type music or of no type (a "Mixed" library; what the UI's "Scan Library" button sends); `POST /Library/Refresh` when there is no such library |
| auth | `u` + `t`/`s` token form; **the account must be an admin** (`Scanner.AdminOnly` is true by default; a non-admin gets Subsonic error 50) | API key, `Authorization: MediaBrowser Token="<key>"` (Dashboard -> API Keys); keys carry the Administrator role |
| wrong login | error 40 -> "Navidrome rejected the username or password" | 401/403 -> "Jellyfin rejected the API key" |
| while already scanning | the request is accepted and dropped inside Navidrome (not queued); the trailing resend after the gap covers scans shorter than 30 s | queued by Jellyfin |
| new music visible | 0.9 s measured (incremental scan of a small library) | 6 s measured (12.2.0, a new artist folder) |

Navidrome's native API (`/api/...`) has no scan route in 0.64.2; the Subsonic one is the only way, and it
is what Navidrome's own Rescan button uses.

**Why not Lidarr's `POST /Library/Media/Updated` for Jellyfin** (the plan before the live run). Measured
19-10-2026 on 12.2.0, one fresh mp3 in a new artist folder each time: with the library's own path from
`MediaFolders` it imported nothing while the library was still empty (there is no physical folder item to
refresh yet) and took 84 s once the library had content; with the artist folder's path (Lidarr's shape,
which needs naviseerr to know Jellyfin's view of the path) 70 s, Jellyfin's 60 s monitor delay included;
`POST /Library/Refresh` 21 s but every library and it cancels a running scan. The per-library refresh:
6 s, the music libraries only, queued behind a running scan, works on an empty library. Flip: swap the
call in `JellyfinClient.rescan` for `Media/Updated` with the `MediaFolders` paths if a Jellyfin version
ever refuses the per-library refresh with an API key.

## Flippable choices (defaults first)

- **Trigger = a download finishing** (the `organised_at` stamp), not every filed song. A 100-song
  playlist's songs reach the servers when the playlist is done. Flip: also count `setLibraryPath` writes in
  `DownloadTaskRunner.organise` (same hook, more calls, still bounded by the gap).
- **Incremental Navidrome scan.** Flip: `fullScan=true` (minutes on a big library; not recommended).
- **Gap 30 s** (`media-servers.rescan-gap-ms`): a burst of single-song downloads costs two calls a minute.
- **Boot check on**: one rescan within a minute of start. Flip: start with `pending = false` in the
  notifier; a wrong setting is then only found at the first finished download.
- **No HTTPS trust-store work**: a self-signed https Jellyfin/Navidrome fails with a plain WARN; use the http
  LAN address or a real certificate. Flip: the `JAVA_TOOL_OPTIONS` truststore the laptop override already uses.
- **Not built**: slskd's own share rescan (`PUT /api/v0/shares`, open since the all-in-one ADR). The
  notifier's flush is the place for it (one more target), left out because nobody asked and it would make
  `SlskdService` collide with the install work of the same sweep.

## Addendum, same day: the playlist's picture reaches Jellyfin after the rescan

Jellyfin reads no picture file for playlists (`library-organiser-27-09-2026.md`, addendum 19-10-2026), so
the notifier does one more thing for it: FINALISE tells it which playlist file was just written
(`playlistWritten`); once Jellyfin's rescan has been accepted, the notifier reads `<title>.jpg` beside
the file (none: nothing to do), polls `GET /Items?includeItemTypes=Playlist&searchTerm=<title>` every
`media-servers.cover-poll-ms` (5 s, 36 tries) until Jellyfin lists the playlist (measured: 6 s after the
per-library refresh), then `POST /Items/{id}/Images/Primary` with the JPEG as base64 (204; served back
byte-identical). Only the item whose `Path` is our file: never by name, since the search also lists
playlists made inside Jellyfin and one sharing the title must keep its own picture. Fire and forget, off
the download pass. Flippable: the poll interval and count (`cover-poll-ms`, `MediaServerNotifier.COVER_POLLS`);
a playlist Jellyfin never lists keeps its collage until the next finalise, one WARN; a push that fails
because Jellyfin went away is kept for its next rescan; no retry column (deliberately in memory). Side effect to know about:
Jellyfin saves its own `<title>-poster.jpg` and `<title>.xml` beside the playlist file.

## Consequences

- No schema change. The notifier's state (pending flag, last outcome per server) is in memory and lost on
  restart; the boot mark covers it.
- The compose files are not touched here (the owner tries install files by hand first): the five lines go
  under the `naviseerr` service as `NAVIDROME_URL: ${NAVIDROME_URL:-}` and so on, written up for the
  install work of the same day.
- The web app does not show `library.mediaServers[].problem` yet; a third amber strip in
  `SoulseekStatusBar` is a ~6-line follow-up.

# Sealed end-to-end check: does a downloaded playlist reach Navidrome?

One command proves, on any machine with Docker, that the all-in-one install files finished songs into the
library, writes the playlist file, rewrites it when a failed song is retried and lands, and that Navidrome
imports it with the songs in order:

```sh
scripts/e2e/playlist-file.sh
```

About 2-3 minutes on a laptop (most of it the first image build; later runs reuse Docker's cache). It
prints `PASS: ...` at the end, or `FAIL: <what>` plus the last 40 log lines of naviseerr and slskd.

## What it does

1. Starts the install's own `compose.yaml` under the project name `naviseerr-e2e`, with
   `compose.e2e.yaml` on top: slskd sits alone on an `internal: true` network with no published port,
   so it **cannot reach the Soulseek network** and never registers the throwaway account in `e2e.env`.
   The web app answers on port 5096, the library lands under `build/e2e/library`.
2. Checks the seal first: slskd's own `GET /server` (through the API key setup gave naviseerr) says
   `isConnected: false`, and the product's `GET /api/status` says `connected: false` (the web app shows
   its "Not connected to Soulseek" strip for exactly this). Nothing else runs before that check passes.
3. Stages a partly downloaded 4-song playlist the way the download loop leaves one: three 2-second mp3s
   made with `ffmpeg` are put in slskd's downloads volume, and `stage-playlist.sql` adds the matching
   `media_items`, `downloads` (`PARTIAL_SUCCESS`), `download_tasks` (three `SUCCEEDED`, one `FAILED`) and
   `song_albums` rows.
4. Waits for the organiser (runs every 2 s): each song tagged and moved to
   `library/<artist>/<title>/`, slskd's folder cleaned up, `library_path` and `organised_at` stamped,
   and `library/Playlists/E2E Road Trip - Été.m3u8` byte-identical to `expected.m3u8` (three songs; the
   failed one is not listed). Clears `organised_at` and checks that a re-run writes the same file again.
4b. Retries the failed song through the real API (`POST /api/downloads/{id}/retry?taskId=`, 202) and
   checks the download is `IN_PROGRESS` again with `organised_at` cleared and the song back at
   `SEARCH_INIT`. Soulseek is sealed off, so the loop holds the song's clocks and never claims it; the
   stage then simulates the one thing a sealed network cannot do, the landing: a fourth mp3 goes into
   slskd's downloads volume where slskd would put it and the row is finished the way the loop finishes
   one. Within a few passes the song is filed, the download is `SUCCEEDED` and stamped again, the log says
   `Wrote playlist ... with 4 track(s)`, and the `.m3u8` is byte-identical to `expected-after-retry.m3u8`.
5. Starts a throwaway Navidrome (`deluan/navidrome:0.64.2`, port 127.0.0.1:4533, admin `admin`/`e2e`
   created by `ND_DEVAUTOCREATEADMINPASSWORD`) on that library, scans, and asserts over the Subsonic
   API that the playlist "E2E Road Trip: Été" is there with 4 songs, Alpha, Beta, Gamma then Delta.
6. Removes everything it made (`docker compose down -v`, the Navidrome container). `KEEP=1` leaves the
   stack up to look at (web app http://localhost:5096, Navidrome http://localhost:4533);
   `scripts/e2e/playlist-file.sh down` removes it later.

Needs on the machine: Docker with Compose 2.24 or newer (`!override`), `ffmpeg`, `jq`, `curl`. Another
web-app port: `NAVISEERR_PORT=5097 scripts/e2e/playlist-file.sh` (an exported `NAVISEERR_PORT` is also
honoured, so a shell that sourced its own `.env` still curls the port it published).

## What it proves, and what it cannot

Proves: the compose file, setup's generated secrets and slskd config, the shared volumes and user, the
organiser's tagging, filing and playlist writing, the playlist file's format, that a retry through the
API reopens a finished playlist and the file is rewritten whole once the song is filed, and Navidrome's
import of it, all from a clean start. The colon-to-" -" file name rule is pinned by `expected.m3u8`.

Cannot prove, because slskd is sealed on purpose: the Soulseek login itself (account creation, a taken
name), a real search and transfer (the retried song's search never starts; its landing is simulated), and
slskd's real landing path for a downloaded file (the staged path follows slskd 0.26's rule and the
04-10-2026 check against a real slskd). Those need an install at home,
off any corporate network: request a short playlist in the web app, and when it says "Downloaded" look
in `library/Playlists` and in Navidrome.

## Behind a TLS-intercepting proxy

The web app and the YouTube Music helper are built from GitHub, which such a proxy breaks
(`UNABLE_TO_GET_ISSUER_CERT_LOCALLY`). Build them from sibling checkouts instead; their gitignored
`certs/*.pem` are picked up:

```sh
E2E_CLIENT_CONTEXT=../naviseerr-client E2E_ADAPTER_CONTEXT=../ytmusic-adapter scripts/e2e/playlist-file.sh
```

If `docker pull` hangs there too, `fetch-image.sh deluan/navidrome:0.64.2` copies the image with skopeo
(the script tries it by itself after `docker pull` fails), or tag an image you already have:
`docker tag deluan/navidrome:latest deluan/navidrome:0.64.2`.

## Files

| file | what |
|---|---|
| `playlist-file.sh` | the check; `down` as its only argument removes the stack |
| `compose.e2e.yaml` | the sealing override and the optional local build contexts |
| `e2e.env` | the install settings for the run (no secrets: the account never logs in and its password is generated inside the stack) |
| `stage-playlist.sql` | the partly-downloaded-playlist rows (3 done, 1 failed) |
| `expected.m3u8` | the playlist file before the retry, byte for byte (UTF-8, no BOM, LF, NFC) |
| `expected-after-retry.m3u8` | the same file after the retried song landed (four songs) |
| `fetch-image.sh` | skopeo fallback for laptops where `docker pull` hangs |

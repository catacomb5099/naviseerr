# Naviseerr Agent Guide

This file is the primary project guide for AI agents working in this repository. Read it before making changes.

For detailed, agent-oriented subsystem context (how the slskd pipeline, download manager, persistence, YouTube Music search, and reactive patterns actually work today, plus testing and known gotchas), see the deep-dive guides in [docs/architecture/](docs/architecture/README.md).

## Project Identity

Naviseerr is the backend/server for "Jellyseerr, but for music." The goal is a FOSS service that helps users search for music and download individual songs or collections through free sources such as slskd/Soulseek, torrent indexers, and other future providers.

This repository is not the visual client. Do not assume frontend code lives here; `naviseerr-client` is a separate project.

The product direction is intentionally track-first. Existing tools such as Lidarr focus on artists, albums, and discographies; Naviseerr should support single-track discovery, playlist/collection workflows, and clear download progress.

## Current Stack

- Java 21
- Spring Boot 4
- Spring WebFlux and Reactor (`Mono`, reactive polling)
- Spring Data R2DBC over Postgres (reactive, no blocking JDBC in the runtime path)
- Flyway for schema creation and migration, with versioned files under `src/main/resources/db/migration/` — see schema management note below
- Gradle
- Lombok
- YouTube Music (via the sidecar `ytmusic-adapter` service, see below) for search metadata. LastFM's
  client code is retained on disk but unused as of 10-08-2026 — see
  [docs/architecture/ytmusic-integration.md](docs/architecture/ytmusic-integration.md) and the
  [ADR](docs/decisions/ytmusic-search-provider-10-08-2026.md).
- slskd for Soulseek search/download orchestration
- JUnit Platform with MockK present for tests

## Schema Management Approach

**Flyway owns the schema**, with versioned files under `src/main/resources/db/migration/`. `schema.sql` and `spring.sql.init` are retired.

Naviseerr is continuously updated software installed by other people. A user on an old version pulls a new image with a year of download history already in their database, so the schema has to *evolve* rather than be *declared*. That is what Flyway does: it keeps a table recording which migration files it has already run, then runs only the new ones, once each, in order.

Two things to know:

- **Existing installs are baselined.** They already have tables but no Flyway history table, so `baseline-on-migrate: true` tells Flyway to treat what is already there as `V1` instead of trying to recreate it. Do not remove that setting.
- **Flyway needs a blocking JDBC driver**, used only to run migrations at startup. The runtime path stays entirely on R2DBC. Do not use the JDBC connection for anything else.

For context on why the previous approach was dropped: `CREATE TABLE IF NOT EXISTS` never destroys data and handled adding tables fine, and Postgres's `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` covers additive column changes too. What it cannot express idempotently is renames, type changes, data backfills, and altering an existing `CHECK` constraint — and widening `downloads.status` for `PARTIAL_SUCCESS` and `CANCELLED` is exactly that. Baselining while the schema is two tables is far cheaper than doing it under pressure later.

Prefer putting genuinely new state in a new table over altering an existing one. See `docs/decisions/minimal-postgres-downloads-26-06-2026.md` and `docs/decisions/durable-download-state-machine-13-08-2026.md`.

## Current Implementation State

The durable state machine described in "Download Manager Architecture" below is now built, not just targeted. Collection downloads landed on 14-09-2026 — see [the ADR](docs/decisions/collection-downloads-14-09-2026.md). Download metadata (a `media_items` table, artwork, lifecycle timestamps, and the per-song view `GET /downloads/{id}`) landed on 25-09-2026 — see [that ADR](docs/decisions/download-metadata-25-09-2026.md). Cancel and retry landed on 28-09-2026 — see [that ADR](docs/decisions/retry-and-cancel-28-09-2026.md). The all-in-one install (one `compose.yaml` with naviseerr's own slskd, a generated Soulseek account and the web app; developers use `compose.dev.yaml`) landed on 30-09-2026 — see [that ADR](docs/decisions/all-in-one-install-30-09-2026.md). Remaining gaps (SSE) are called out explicitly below.

The current application is a small Java REST/WebFlux service that:

- Calls a sidecar service, `ytmusic-adapter` (a standalone Python/FastAPI process wrapping
  `ytmusicapi`, in the sibling repo `~/IdeaProjects/ytmusic-adapter`, wired into
  [compose.dev.yaml](compose.dev.yaml)), for track, album, artist, and playlist search, and to resolve an album or playlist id to its track list. LastFM previously filled this
  role; its client code still compiles but is no longer called — see
  [docs/architecture/ytmusic-integration.md](docs/architecture/ytmusic-integration.md).
- Accepts `POST /download/song/{videoId}` and `POST /download/collection/{id}?type=ALBUM|PLAYLIST|CURATED`, inserts a `PENDING` row into the `downloads` table, and returns `202 Accepted` immediately (fast ack; no work on the request thread). The request carries a **YouTube id and a type**, not a name — the server resolves what the id is, from `ytmusic-adapter`, when the loop admits it.
- Runs a durable, Postgres-backed download state machine that turns those rows into real slskd downloads (see "Download Execution Flow" below) and survives a restart mid-download — nothing about a download's position is held in the JVM heap.
- Calls slskd to search Soulseek, select candidates, enqueue downloads, poll to completion, and retry/fail over across candidates, driven by `DownloadStateMachine` — a pure function (no fields, no I/O, no clock of its own) that maps `(task, slskd response, now)` to one of three decisions.
- Persists a terminal `SUCCEEDED`/`FAILED` status per download once the state machine reaches a success/fail state.

Three tables live in `com.catacomb5099.naviseerr.download` (plus two from V12 for the album work: `song_albums`, which trusted YouTube Music album a song or playlist track belongs to and its track number there, one row per YouTube id written by `SongAlbumResolver` (`album_id` NULL = looked, nothing trusted); and `album_searches`, one album download's search for a sharer with the whole album, created empty and unused yet). As of V5 the relationship between the first two is **one download to N tasks** — one task per song; as of V6 neither of them holds a title or a picture, `media_items` does:

- `downloads` (`download_id UUID`, `youtube_id TEXT NOT NULL`, `download_type TEXT CHECK (SONG|ALBUM|PLAYLIST|CURATED)`, `status TEXT CHECK (...)`, `failure_reason TEXT`, `created_at`/`admitted_at`/`finished_at TIMESTAMPTZ`) — one user REQUEST and its lifecycle, nothing about what was requested beyond the id. The low-churn, user-facing record that history queries read, written at request, at admission, and at conclusion. Status values are enforced at the DB level via a `CHECK` constraint rather than a native Postgres enum (see decisions doc for rationale); `PARTIAL_SUCCESS` is in that set and is reachable only for a collection. `failure_reason` is written only by `failUnadmitted`, for what can happen before any task row exists to carry it (ytmusic-adapter cannot resolve the id, or the queued download was cancelled), and cleared by retry/readmit (`RETRY_SQL`, `READMIT_SQL`) so a new attempt starts with no stale reason.
- `media_items` (`youtube_id TEXT PRIMARY KEY`, `title`, `artists TEXT[]`, `artist_ids TEXT[]` (V11: one YouTube Music channel id per `artists` entry, `''` where YouTube gave none, empty for rows written before V11), `image_url`, `duration_seconds` (songs), `track_count` (collections: YouTube's own count when the adapter gives one, which can exceed the tracks downloaded; else the number of tracks), `year` and `album_type` (V12, album rows only: release year and YouTube's `Album`/`EP`/`Single`), `fetched_at`) — what a YouTube id IS: the name and picture every read endpoint shows. One row per id, songs and collections alike, written by admission from the adapter response (the download's own id plus every track's) and upserted on every later answer, never blanked. Every feed query `LEFT JOIN`s it through `downloads.youtube_id`; a QUEUED download has no row yet and reports a null title, which is the honest state. `download_tasks.youtube_id` joins it for the per-song view.
- `download_tasks` (`task_id UUID PRIMARY KEY`, `download_id UUID REFERENCES downloads`, `youtube_id TEXT`, `song_name TEXT`, `position INT`, `track_title`/`track_number`/`duration_seconds` (V12), `phase`, `phase_entered_at`, `next_attempt_at`, `lease_owner`/`lease_expires_at`, `search_id`, `candidates` as JSON, `candidate_index`, `retry_index`, `slskd_username`/`slskd_filename`/`slskd_transfer_id`, `finished_at`, `failure_reason`, `progress_percent NUMERIC(5,2)`) — the working state of ONE SONG's pipeline, written every few seconds. A song request has one row; a ten-track album has ten, with `position` recording list order (the index after unavailable tracks are dropped). `track_title`, `track_number` and `duration_seconds` are the album or playlist ROW's own title, YouTube track number (albums only; null for playlists and songs) and length, written at admission and never changed; they live on the task, not `media_items`, because one video id can sit on two rows of one album with different titles (the Definitely Maybe 30th Anniversary edition lists `h7-BHdjeEY0` as tracks 21 and 24). Null on rows created before V12. Nothing reads them yet; they are for album tagging and whole-album downloads. **`download_tasks.song_name` is the Soulseek query wording, `"Title - Primary Artist"`, not a display title** — the string the client used to send before requests became ids. `SearchQueryTiers` derives both the search wordings and the cleaned `pickerName` that `TrackMatchingService` judges files against (never the raw name: see [the 28-09-2026 post-mortem](docs/decisions/playlist-post-mortem-28-09-2026.md)). The per-song display title is `media_items.title`. Rows are **retained** once terminal (`SUCCEEDED`/`FAILED`), never deleted — so a self-hoster can see which peers were tried and how each failed. A partial index on `next_attempt_at` (covering only non-terminal rows) keeps the due-work query fast regardless of how much history accumulates.

`download_id` was `download_tasks`'s primary key before V5; that was the 1:1 assumption collections removed. Every statement that acts on one song's state (`CLAIM_DUE_SQL`, `SAVE_SQL`, `FINISH_TASK_SQL`) now keys on `task_id`. Keying any of them on `download_id` would step every song of an album on one song's slskd response.

`progress_percent` (0-100, everywhere, no exceptions) is written by `DownloadStateMachine.afterDownloadPoll` from slskd's `percentComplete` on the same `Continue`/`Advance` write every other field rides on — no separate statement, no new write volume. It is reset to zero on retry or candidate failover (a resumed transfer is a new transfer, not a continuation), left untouched when a transfer is briefly absent from the batched response (an absent source value must never overwrite a real one), and normalised to exactly `100` on `SUCCEEDED` by `DownloadService.finishDownload`'s CTE. `FAILED` deliberately keeps its last observed value rather than being forced to either end — see `docs/decisions/download-progress-reporting-17-08-2026.md`. `DownloadTaskRepository.save` now takes `(task, owner)` and only writes when the row is still non-terminal **and** still held by that owner's lease — the guard that was missing before this landed.

The client-facing feed is `GET /downloads/active` plus `GET /downloads?ids=` and the paged `GET /downloads/all` (`DownloadController` + `ActiveDownloadRepository`). It reports **one row per download, never one per song** — its three queries aggregate over the task rows (`ActiveDownloadRepository.TASK_AGGREGATE`), because a plain join emits a ten-track album as ten cards sharing one `downloadId`. For a single-song download every aggregate is over one row and returns that row's own value. A collection reports the **least advanced** song's stage (it is still "searching" while any track is), the **mean** progress across its songs, and `songCount`/`songsSucceeded`/`songsFailed` so a card can say "7 of 12". The per-song breakdown is `GET /downloads/{id}` (`ActiveDownloadRepository.findSongs`), which shows the task rows as themselves, in `position` order, with the pipeline's bookkeeping (candidate count/index, retry index, peer, filename, last slskd error) on the wire for the self-hoster. Four things about the feed are load-bearing and easy to undo by accident:

- It reports a computed **`DownloadStage`**, never `downloads.status` or `download_tasks.phase`. `ActiveDownloadRepository.toStage` is the single place they are combined. Do not add `phase` to the wire — the `phase` CHECK constraint admits `'SUCCEEDED'`/`'FAILED'`, which the four-value `DownloadPhase` enum cannot parse, and `toStage` is what keeps that off the read path.
- The live branch **LEFT JOINs** `download_tasks`, so a download the runner has not admitted yet is reported as `QUEUED` rather than being invisible. Both of its timestamps `COALESCE` to `downloads.created_at` for the same reason.
- The terminal branch's status filter must include `PARTIAL_SUCCESS`, or a collection that half-succeeded finishes and is then never reported at all.
- Finished downloads keep appearing for `download-task.terminal-retention-ms`. **Without that window the client never learns any outcome at all** — the row vanishes the instant it finishes. `GET /downloads?ids=` is the escape hatch past the window and ignores every filter; an id absent from *that* response is the only signal that a download does not exist.

`failure_reason` stores a `DownloadFailureCode` **name**, not prose — the client owns the wording. `DownloadService.finishTask` is idempotent: it applies only while the task is still non-terminal and only for the caller holding the lease, so a duplicate finish cannot re-stamp `finished_at` (which would drag a long-finished row back into the retention window). `updated_at` is written with SQL `now()` on purpose; see the ADR addendum.

**A download's status is derived from its task rows, not written beside them.** `finishTask` settles one song; `DownloadTaskRepository.concludeDownloads` is a separate idempotent statement, run at the end of every pass, that gives a download its terminal status once every one of its tasks is terminal (`PARTIAL_SUCCESS` when there is at least one success and at least one failure). Do not try to fold it back into the per-task write. Two songs of one download finishing concurrently would each read a snapshot in which the other is still running, so neither would conclude, and the download would sit `IN_PROGRESS` forever with every song finished — and a data-modifying CTE cannot see the effect of its own write, so a bigger statement does not fix it. Deriving it in the loop is the level-triggered rule this design already rests on: a missed conclusion costs one interval, not a download.

### Download Execution Flow

One level-triggered loop, `DownloadTaskRunner`, ticking every `download-task.loop-interval-ms`. Each pass runs admit, then claim, then step:

1. **Admit** — two halves, with an HTTP call between them, because what task rows a download needs is a question only `ytmusic-adapter` can answer (or, for a `CURATED` download, the playlist curator: `CuratorClient.getEdition`, mapped by `DownloadTaskRunner.curatedCollection` into the same `YoutubeCollectionInfo` shape). `DownloadTaskRepository.admitDownloads` selects `PENDING` rows with no task row and **writes nothing**; `DownloadTaskRunner.gatherMetadata` then calls the adapter (`/v1/songs/{id}`, `/v1/albums/{id}` or `/v1/playlists/{id}` per `download_type`) and `createTasks` inserts one task row per song and flips `downloads.status` to `IN_PROGRESS` in one statement. A crash in between leaves the row exactly as it was and the next pass retries it — which is the only reason admission can safely stop being a single statement. Bounded by `max-concurrent-downloads`, which counts `downloads` rows, not tasks — a 500-song collection is one in-flight download, so it cannot starve admission for everything else.
   - A metadata failure is classified, not retried blindly: a `YtMusicBadRequestException` (400/422, or a **404 carrying the adapter's `{"error":…}` envelope** — it looked, and the id does not exist) fails the download with `METADATA_UNAVAILABLE`, because retrying cannot make an id exist and a row left `PENDING` would be re-requested every loop interval forever. Anything else (a `YtMusicUnavailableException` — timeout, 502/503, connection refused, or a 404 **without** that envelope, which is FastAPI's route-not-found from a stale sidecar image) leaves it `PENDING` for the next pass, so a sidecar restart or an outdated image does not fail every download requested while it was down.
2. **Claim** — `claimDueTasks` claims due, unleased, non-terminal task rows (`FOR UPDATE SKIP LOCKED`, stamping a lease) up to `batch-size`. `DOWNLOAD_INIT` rows are excluded from the claim entirely once `max-concurrent-transfers` has no free slot, so a large collection sitting at `DOWNLOAD_INIT` cannot spend every pass being claimed and re-deferred. `SEARCH_INIT` rows are claimed only up to the number of free `max-concurrent-searches` slots (slskd runs two searches at a time and queues the rest inside itself). Polling an already-running search or transfer is never gated.
3. **Conclude** — `concludeDownloads` runs after stepping and unconditionally; see the note above.
4. **Step** — `DownloadStepExecutor` makes the one slskd call each phase needs (`SEARCH_INIT`/`DOWNLOAD_INIT` call slskd directly; `SEARCH_POLL`/`DOWNLOAD_POLL` read from two lists — `GET /searches` and `GET /transfers/downloads` — fetched once per pass and only when a claimed row actually needs one) and hands the response to `DownloadStateMachine`, which returns `Advance` (phase transition, re-run next pass immediately), `Continue` (re-poll/retry after the phase's poll interval), or `Terminal` (finish the download and mark the task row terminal in one atomic CTE — `DownloadService.finishDownload`). Rows claimed within a pass are stepped concurrently (`flatMap`); passes themselves stay serialised (`concatMap`).
5. **Organise** (V8, optional) — `LibraryOrganiser` moves each `SUCCEEDED` task's file out of slskd's downloads folder into `library.root` (`<artist>/<album or title>/<file>`; a song or playlist track with a trusted YouTube Music album goes in `<album artist>/<album title>/`, the album's own folder, and waits up to two minutes after finishing for the album lookup below, inside the filing query's WHERE), writes `download_tasks.library_path`, and on a task's first real `FAILED` deletes the partial files slskd left in its incomplete folder. Then, for each finished download whose songs are all filed (or given up on), it writes a `PLAYLIST`'s `<root>/Playlists/<title>.m3u8` (entries relative to that folder, track order) and stamps `downloads.organised_at`; an `ALBUM` or `SONG` needs no extra file and is just stamped. Level-triggered like everything else: the query is "succeeded, no `library_path`, finished within the last ten minutes", so a song not yet moved is picked up again next pass and history is never trawled. **Off unless both `library.slskd-downloads-dir` and `library.root` are set** (env `SLSKD_DOWNLOADS_DIR`, `LIBRARY_ROOT`; the slskd folder must be visible to naviseerr) — see [the ADR](docs/decisions/library-organiser-27-09-2026.md) for the folder scheme and why playlist tracks are filed like songs rather than kept in one folder.

**Album lookup** (separate loop, on only with the organiser) — `SongAlbumResolver` runs on its own `Flux.interval` (same interval and batch size), never inside `pass()`: one song costs two to six adapter calls of 1-15 s, one at a time. It picks songs and playlist tracks (never album downloads' tracks) still downloading or finished and not filed within the organiser's window, never the history, and finds the album: the curator's own album id for a `CURATED` song, else `/v1/songs/{id}/details`, else (an official video, or an untrusted album) the song search's first five rows with the same artist, title and length within 3 s. **Trusted** = the song artist's own `Album` or `EP` — never a Single, never Various Artists — that has the song (row with its id and title, else title and length). Then the plainest edition: shortest title among the album and its same-artist "other versions", smaller id on a tie, so Supersonic and Live Forever both land on plain "Definitely Maybe". Writes the album's `media_items` row, then `song_albums`. See [the ADR](docs/decisions/youtube-album-tags-04-10-2026.md).

(Read the numbering as admit, claim, step, conclude, organise — step 3 above runs after step 4's claim/step pair within `DownloadTaskRunner.pass()`, so a download whose last song finishes in a pass reports its outcome on the same tick; step 5 runs last.)

A lease (`lease_owner` + `lease_expires_at`) does two jobs: it stops a second pass double-stepping a row whose slskd call is still outstanding, and its expiry is what lets any process pick up a dead process's row — no reaper needed. Full detail (DDL, per-phase intervals/budgets, the recovery walkthrough) lives in [download-manager.md](docs/architecture/download-manager.md).

The current application does not have:

- SSE/WebSocket progress streaming.
- User accounts, JWT handling, or authorization.
- A `CANCELLED` status or `SKIPPED`: a cancelled song is a `FAILED` row with reason `CANCELLED` (see `docs/decisions/retry-and-cancel-28-09-2026.md`).
- Redis or RabbitMQ — **rejected**, not merely absent. Postgres is the workflow engine, indefinitely; see `docs/decisions/durable-download-state-machine-13-08-2026.md`.

Current endpoints:

- `GET /search/{query}` — general search ("All", YouTube Music via `ytmusic-adapter`): each list is YouTube Music's own mixed page for that kind first (its best guess at what the query means, about 6 of each), then the category search below it (20) for depth, matched by YouTube id (the same recording under a second id can still appear twice; the client folds songs and albums by title and artists); six adapter calls at once, each capped at 3 s per try (`SearchService.ALL_TRY`) because YouTube sometimes stalls a call for 5-10 s: songs get three tries, the other parts two (the featured-playlists half one, then fan-made only). A failing songs search fails the whole answer (so a down adapter never reads as "no results"); the mixed page or any other category that fails drops out and is named in the answer's `unavailable` list (`mixed`, `albums`, `artists`, `playlists`; empty when all answered, null on the category routes), so the client can say "couldn't load albums" instead of showing none. See `docs/decisions/search-all-per-category-30-09-2026.md`. Each song in `tracks[]` (here and on `/tracks`) carries `plays`, YouTube's play count in the album-track wording ("7.2M plays": the adapter's bare "7.2M" gains the noun, never parsed); null on the Top result card's song, where YouTube shows none
- `GET /search/{query}/tracks?limit=` — track search
- `GET /search/{query}/albums?limit=` — album search
- `GET /search/{query}/artists?limit=` — artist search. On these three and on playlists, `limit` defaults to 20 and is pulled into 1..100 (the adapter's ceiling). Each try gets 2 s plus 1 s per 20 asked for (`SearchService.tryFor`) and is asked once more if it runs out, since YouTube sometimes stalls a call for 5-10 s; two stalls in a row are a 502. A stalled featured-playlists half falls back to fan-made only after one try. The client's "Show more" asks again with a bigger `limit` and keeps the ones it has not shown: YouTube Music offers no "next page", its order shifts a little between calls, and the same song can come back twice, so the client merges rather than trusting positions (artists and playlists by id, songs and albums by title and artists, since one recording often has several ids)
- `GET /search/{query}/playlists?limit=` — playlist search (`limit` grows the fan-made half only; the featured half stays at `search-result-limit`, because past its first relevant rows it is filler): YouTube Music's featured playlists and fan-made ones together (two adapter calls). The top two of each keep YouTube's order (featured first); the rest of both lists is shuffled, so the same search can come back in a different order. A failing featured search degrades to fan-made only. `Playlist.id` is the bare `PL...` id (`RDCLAK5uy_...` for featured); the client must use it unchanged for both `/collections/{id}` and `/download/collection/{id}`
- `GET /artists/{channelId}` — one artist page as `{id, name, iconURL, description, subscribers, topSongs[Track], albums[Album], singles[Album], playlists[Playlist], similarArtists[Artist]}`, the shelves in the search DTOs so the client reuses its cards. Every list is empty-not-null and capped at 10. `playlists` is a playlist search for the artist's name (YouTube Music exposes no "featuring" list), run after the artist call because it needs the name, and best-effort: if it fails the page still loads with an empty shelf. `Track.albumId` is `""` (the adapter names a top song's album but gives no id), `Track.plays` is YouTube's wording ("1.7B plays", never parsed; null when the adapter sends none) and `Artist.iconUrl` in `similarArtists` is the adapter's `related[].thumbnailUrl`, `""` when it sends none (older adapter image). Unknown id is 404 (adapter 400/422/500 fold into the same exception and also surface as 404, as for `/collections`); adapter down is 502
- `GET /collections/{id}?type=ALBUM|PLAYLIST` — (`CURATED` is 400: suggested playlists are read on `GET /suggested-playlists/{category}`) one album or playlist as `{id, type, name, artists, iconURL, year, trackCount, tracks[{id, name, artists, iconURL, durationSeconds, position, plays}]}`, resolved live from `ytmusic-adapter` via the same `getAlbumInfo`/`getPlaylistInfo` admission uses, so the track list is exactly what a download of that id would create. `plays` is YouTube's own wording ("28M plays", never parsed) on an album's tracks and null on a playlist's: YouTube gives playlist rows no play count (search results and artist top songs have one, see those routes), so the client asks `GET /songs/views` for them, one adapter call per song. `type=SONG` is 400; an id the adapter does not know is 404 (adapter 400/422/500 are folded into the same exception and also surface as 404); adapter down is 502
- `GET /songs/{videoId}` — one song as `{id, name, artists[{id, name}], album{id, name}|null, durationSeconds, year, viewCount, plays, iconURL, explicit, credits[{role, names[]}]}`, resolved live from `ytmusic-adapter`'s `/v1/songs/{id}/details` (up to four YouTube Music calls behind it, 1–2.5 s). `viewCount` is the exact plays of this one video or upload; `plays` is YouTube Music's larger combined count in its own wording ("1.7B plays", never parsed, as album and search rows carry it), null for an official video or when the adapter cannot find the album track. `album`, `year` and `explicit` are null and `credits` is `[]` for an official-video id — YouTube Music has none of them for those, and the adapter does not guess. `iconURL` is `""` when none. Unknown id is 404 (same folding of adapter 400/422/500 as `/collections`); adapter down is 502
- `GET /songs/views?ids=a,b,c` — how many times each song was played, as `{"a": 19334421, "c": 7004756}`: the adapter's `/v1/songs/{id}` `viewCount`, the exact plays of that one video or upload. A different and smaller number than the `plays` wording album, search and top-song rows carry, which is YouTube Music's combined count; used for playlist songs, which have no `plays`. One adapter call per distinct id, 8 at a time (the adapter's own YouTube concurrency). Ids are comma-separated, trimmed, blanks dropped, duplicates looked up once; 400 when there is no id or more than 50. An id whose lookup fails (unknown, adapter down, timeout) or has no count is absent from the object, never an error for the rest, so the answer is always an object. The literal path wins over `/songs/{videoId}`
- `POST /download/song/{videoId}` — inserts a `PENDING` download row of type `SONG`, returns `202 Accepted`; processed asynchronously by the download execution flow
- `POST /download/collection/{id}?type=ALBUM|PLAYLIST|CURATED` — the same, for every track of an album or playlist as ONE download. `CURATED` is one edition of a suggested playlist: the id is the curator's category key (`80s-indie-pop`) and the track list is fetched from the curator at admission instead of from ytmusic-adapter; it is filed and given a playlist file like a `PLAYLIST`. See `docs/decisions/curated-download-28-09-2026.md`. `type` is required rather than inferred from the id: albums and playlists are two different adapter endpoints, and guessing from an id prefix is a heuristic that silently breaks the first time YouTube changes one. `type=SONG` is rejected with 400 — a single track has its own route
- `GET /downloads/active` — every non-terminal download plus every one finished within `terminal-retention-ms`, most-recently-updated first, as `{downloadId, youtubeId, downloadType, title, artists, artistIds, imageUrl, stage, progressPercent, songCount, songsSucceeded, songsFailed, requestedAt, stageEnteredAt, updatedAt, finishedAt, failureCode}` plus `pollIntervalMs` and `terminalRetentionMs`; the client polls this, no SSE. `artistIds` is index-aligned with `artists` (the id to open `/artists/{id}` with; null where YouTube gave none, shorter or empty for rows written before V11), so a missing entry means "no link"
- `GET /downloads?ids=a,b,c` — the same shape for specific ids, ignoring both the terminal filter and the retention window (max 100 ids). Lets a client reconcile cards it held across a restart; absent ids are omitted, not 404'd
- `GET /downloads/all?pageSize=&pageNumber=&type=SONG|ALBUM|PLAYLIST|CURATED` — the same shape, paginated over every download ever, newest request first (by `requestedAt`, so a retry or progress never reorders it); the history table. `type` is optional and narrows the list on the server so `totalPages` counts within the filter (filtering a page client-side left the pager describing the unfiltered list); `type=PLAYLIST` also matches `CURATED`, since to the user a suggested playlist is a playlist. Unknown value is 400
- `GET /downloads/{id}` — one download as `{download, songs[]}`: the feed card plus every song in track order as `{taskId, youtubeId, position, title, artists, artistIds, imageUrl, durationSeconds, stage, progressPercent, failureCode, stageEnteredAt, updatedAt, finishedAt, candidateCount, candidateIndex, retryIndex, slskdUsername, slskdFilename, lastError}`. 404 for an unknown id; `songs` is empty, not absent, for a download not yet admitted
- `POST /downloads/{id}/cancel?taskId=` — cancels every unfinished song of a download, or one song. A cancelled song is a `FAILED` task row with `failure_reason` `CANCELLED` (no status of its own); the download's status is then derived as usual (`FAILED` when nothing downloaded, `PARTIAL_SUCCESS` otherwise). Best-effort `DELETE` of the live slskd transfers. 200 with the fresh card, 409 with the current card when nothing was left to cancel, 404 unknown. See `docs/decisions/retry-and-cancel-28-09-2026.md`
- `POST /downloads/{id}/retry` — retries a finished download: every `FAILED` song (cancelled included) is reset in place to `SEARCH_INIT` and the download reopened, in one statement; a download that failed before it had songs goes back to `PENDING`. 202 with the fresh card, 409 with the current card when there is nothing to retry (running, fully downloaded, or a second click), 404 unknown
- `GET /suggested-playlists` — the playlist curator's latest edition per category as `{enabled, refreshDay, playlists[{category, title, year, editionDate, trackCount}]}`; `year` is the category's Discogs range ("1980-1989", "1950-2026" for all-time; null from an older curator) so the client can group by decade; `refreshDay` ("MONDAY", from `curator.cron`; null when the cron is not one plain weekday) lets the client say "New edition every Monday"; `enabled` is false (and the list empty) on an install with no curator configured, so the client can hide the section. Read through to the curator's `GET /v1/editions` on every call; curator down is 502
- `GET /suggested-playlists/{category}` — one edition as `{category, title, filters, editionDate, trackCount, tracks[{id, name, artists, album, albumYear, popularity, tier, reason, iconURL, position}]}`, field names as on the search contract so the client's song rows work unchanged. `tier`/`reason` are the curator's explanation of each pick; `iconURL` is YouTube's predictable thumbnail (the curator stores no artwork). 404 when the curator has no edition for the category; 503 when no curator is configured; 502 when it is unreachable. See `docs/decisions/suggested-playlists-api-28-09-2026.md`
- `POST /suggested-playlists/refresh` — "make this week's playlists now": triggers the curator (no retry, unlike the cron tick) and answers 202 at once with the curator's run record `{runId, status, requestedAt, startedAt, finishedAt, categories[{key, status, editionDate, trackCount, message}], final}`; pressing during a run returns that run. naviseerr then follows the run in the background as after a cron tick (`CuratorScheduler.refreshNow()`). 503 with no curator
- `GET /suggested-playlists/refresh` — the most recent curator run in the same shape, for the client to follow (`queued`/`running`, then `succeeded`/`partial`/`failed`). 404 when the curator never ran; 503 with no curator. The literal path wins over `/{category}`

## Deeper Context (docs/architecture)

Deep-dive guides for agents and developers live in [docs/architecture/](docs/architecture/README.md). Read the relevant one before working on a subsystem; the cited source files remain the source of truth.

- [codebase-map.md](docs/architecture/codebase-map.md) — repo layout, package map, entry points, branch topology, build/run.
- [slskd-integration.md](docs/architecture/slskd-integration.md) — the Soulseek search -> select -> download -> poll pipeline and retry/failover.
- [download-manager.md](docs/architecture/download-manager.md) — the durable download task loop (admit, claim, step, apply; leases; the four capacity bounds).
- [persistence.md](docs/architecture/persistence.md) — R2DBC + Postgres, the `downloads`/`download_tasks` tables, claim/status SQL, Flyway.
- [ytmusic-integration.md](docs/architecture/ytmusic-integration.md) — YouTube Music metadata search (via the sidecar `ytmusic-adapter`) and response mapping. The active search provider.
- [lastfm-integration.md](docs/architecture/lastfm-integration.md) — LastFM metadata search and response mapping. Superseded, unused, retained on disk.
- [reactive-patterns.md](docs/architecture/reactive-patterns.md) — Reactor cookbook: the level-triggered interval loop, `flatMap` vs `concatMap`.
- [testing.md](docs/architecture/testing.md) — unit (Mockito + StepVerifier) and integration (Testcontainers) testing, and how to run them.
- [gotchas.md](docs/architecture/gotchas.md) — known bugs and hygiene issues (e.g. committed secrets, unverified `SlskdSearchState` values).

## Product Context

MVP:

- Search songs, artists, albums, and playlists.
- Download songs.

Important future milestones:

- Download manager for songs and collections.
- Download history and cancellation.
- Cache and database-backed state.
- Artist/song/album pages.
- Optional "peek" streaming for short playback sections.

Success is mostly about UX quality and hit rate: fluid navigation, transparent loading/error states, modern behavior, and maximizing successful downloads from imperfect external sources.

## Download Manager Architecture

> [!IMPORTANT]
> This section was rewritten on 2026-08-13. It previously described a RabbitMQ + Redis pipeline. **That architecture is rejected, not pending** — do not reintroduce it, and treat any older doc or branch that still describes it as stale. See `docs/decisions/durable-download-state-machine-13-08-2026.md`.

**Postgres is the workflow engine. There is no broker and no cache tier.** naviseerr self-hosts as a single service against a single Postgres, indefinitely. Requiring users to also run RabbitMQ and Redis is an adoption tax the project will not pay: the products naviseerr competes with (Lidarr, Jellyseerr) ship as one container.

The execution model is a **level-triggered reconciliation loop** over durable state, not an event/queue pipeline. The distinction is load-bearing: never act on a notification that cannot be regenerated. Repeatedly ask the database what is due and act on the answer, so a lost wakeup costs one interval instead of a download.

- HTTP requests acknowledge immediately and insert a row. They never do work.
- `downloads` owns the user-facing lifecycle and permanent history: pending, in progress, success, failed, and later cancelled / skipped / partial-success for collections.
- `download_tasks` owns the working state of one download — which step, the slskd search id, the candidate list, retry counters, the correlation ids, `next_attempt_at`, and a lease. Rows are **kept after completion** in a terminal phase with a failure reason, because self-hosters need to be able to answer "which peers were tried, and how did each fail?" from their own instance. A partial index on `next_attempt_at` covering only non-terminal rows keeps the due-work query fast regardless of how much history accumulates.
- Each step is exactly **one** external call, so a crash costs at most one call's work and resumes from the same step.
- Waits are persisted as `next_attempt_at`, never held in memory. A wait held in memory is a wait that dies with the process — and it also pins a worker for its duration.
- Retries and backoff are timestamps in a column, not retry operators wrapped around a subscription.
- Crash detection is a **lease with an expiry**, not a time-since-`updated_at` reaper. One mechanism covers both "another pass must not double-step this row" and "the process that held this row died".
- Terminal writes are a single atomic statement (a data-modifying CTE) that sets the download's status and marks the task row terminal (retained, not deleted — self-hosters need the history).
- Cancellation is a terminal write, not a flag: a cancelled song is a `FAILED` task row with reason `CANCELLED`, written by `DownloadService.cancel` with the lease cleared, so the loop never claims it again. Live slskd transfers are cancelled best-effort afterwards; queued work is never retracted in slskd.
- SSE, when it lands, reads from Postgres. If a resume cursor is needed, add an append-only `download_events` table and use its sequence number. That table is also the dataset for tuning candidate ranking and match thresholds, which is its stronger justification.

Four independent bounds, and they must not be conflated — the deleted `flatMap(this::process, 3)` collapsed all of them into one number:

- `batch-size / loop-interval` is a hard ceiling on the request rate to external providers.
- `max-concurrent-downloads` caps how many user requests are worked on at once. It counts `downloads` rows, so a collection of 500 songs is **one** in-flight download and cannot lock every other request out of admission.
- `max-concurrent-transfers` caps how many slskd transfers exist at once. This protects bandwidth and peers' upload queues. It gates only the step that *starts* a transfer — never polling, because polling is one cheap GET and starving it stalls a download slskd is happily finishing.

- `max-concurrent-searches` caps how many Soulseek searches run at once. slskd only ever runs two and queues the rest internally while still answering `POST /searches` with 200, so anything above its slot count only makes naviseerr's search budget lie (measured 27-09-2026: 26 of 50 searches "timed out" by us, then completed fine in slskd). It gates only `SEARCH_INIT`, never `SEARCH_POLL`, and by count rather than yes/no.

Work is taken oldest-first with no per-collection cap, so one collection may legitimately hold every transfer slot until it is done. That is the intended default: if a user asked for something, they usually want it finished. Making it a user-facing option is planned, not hardcoded.

Downloads parked waiting on a remote poll cost one table row each and need no bound at all.

The current authoritative design lives in `docs/superpowers/specs/2026-08-13-durable-download-state-machine-design.md`, with the implementation plan in `docs/superpowers/plans/2026-08-13-durable-download-state-machine.md`.

## Playlist Curator (weekly refresh)

`com.catacomb5099.naviseerr.curator` is the only cron job in the project. Once a week it triggers the
separate croissant service (`POST /v1/runs`; repo catacomb5099/croissant), polls the run until it finishes or a 30-minute budget
runs out, and logs the outcome per category. It is off unless `CURATOR_URL` and `CURATOR_TOKEN` are both
set and touches no table. The outbound client (`CuratorClient`) follows the `YtMusicService` pattern
(timeout, typed error, retry of transient failures only) and also reads the curator's editions for
`SuggestedPlaylistController` (`GET /suggested-playlists[/{category}]`, and `POST|GET /suggested-playlists/refresh`
for a manual "make this week's playlists now"), which is how the client shows and requests them.
See `docs/decisions/curator-weekly-trigger-27-09-2026.md` and
`docs/decisions/suggested-playlists-api-28-09-2026.md`.

## Domain Model Direction

Expected core entities:

- `Song`: metadata plus discovered download links and validity windows.
- `Download`: **exists**. One user request — a song, an album, or a playlist — identified by a YouTube id and a `DownloadType`, plus its lifecycle timestamps.
- `MediaItem`: **exists**, as the `media_items` row. What a YouTube id is: title, artists, artwork. Shared by songs and collections and by every download that points at the same id.
- `DownloadTask`: **exists**. One song's pipeline position. N per `Download`; this is what `CollectionDownload` turned out to be, rather than a third table.

Likely statuses:

- `Pending`
- `InProgress`
- `Success`
- `Failed`
- `Cancelled`
- `Skipped`
- `PartialSuccess` for collections — **exists**, as `PARTIAL_SUCCESS`

Cancellation should be treated as a first-class action. Prefer correctness and eventual consistency over directly mutating/removing queued work in ways that can race with a completed download.

## Engineering Guidelines

- Keep the backend reactive unless there is a strong reason not to. Do not introduce blocking calls into reactive paths without isolating them.
- Preserve clear boundaries between provider clients (`ytmusic`, `slskd`; `lastfm` is unused, retained on disk), orchestration services, domain models, and API controllers.
- Model external-provider failures explicitly. slskd and ytmusic-adapter can be slow, incomplete, or inconsistent. `YtMusicService` is the first provider client with real timeout/retry/typed-error handling (`YtMusicBadRequestException` vs `YtMusicUnavailableException`) — follow that pattern for new provider clients rather than the untimed, untyped LastFM/slskd ones it replaces.
- Use fuzzy matching and metadata checks carefully; prioritize high-confidence track matches over downloading the first result.
- Keep API behavior user-centered: report "no good match" distinctly from provider errors, timeouts, and cancellations.
- Do not write byte-level progress changes (transferred bytes, percent complete) to the primary database. This rule is about churn measured in hundreds of writes per download — it does **not** prohibit persisting step transitions and poll timestamps, which are roughly one row-write per poll per download (tens of writes per second at the busiest, which Postgres does not notice) and which are what makes crash recovery possible at all.
- Batch/throttle SSE updates. Progress every 5% or meaningful status transitions is usually better than emitting every tiny byte change.
- For low-bandwidth assumptions, favor compact payloads, resumable/delta progress streams, and avoiding refetching full history after reconnects.
- Do not add legal/security conclusions beyond normal engineering hygiene unless explicitly asked.

## Testing And Verification

- Add or update tests when changing matching, polling, download orchestration, cancellation, or state transitions.
- Run `./gradlew test` when code changes are made.
- For documentation-only changes, no Gradle verification is required unless the docs include generated code or examples that should compile.

## Configuration Hygiene

- Treat API keys, hostnames, and tokens as local configuration, not design assumptions.
- Do not add new secrets to tracked files.
- If touching configuration, prefer environment-variable-backed values or local override files where practical.

## Agent Workflow

- Before broad changes, inspect the current package structure and tests.
- Keep edits scoped to `naviseerr`; do not modify `naviseerr-client` unless the user explicitly asks.
- Do not overwrite unrelated local changes.
- Prefer small, reviewable increments and update this guide when durable project decisions change.

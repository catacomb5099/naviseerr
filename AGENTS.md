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

The durable state machine described in "Download Manager Architecture" below is now built, not just targeted. Collection downloads landed on 14-09-2026 — see [the ADR](docs/decisions/collection-downloads-14-09-2026.md). Download metadata (a `media_items` table, artwork, lifecycle timestamps, and the per-song view `GET /downloads/{id}`) landed on 25-09-2026 — see [that ADR](docs/decisions/download-metadata-25-09-2026.md). Remaining gaps (cancellation, SSE) are called out explicitly below.

The current application is a small Java REST/WebFlux service that:

- Calls a sidecar service, `ytmusic-adapter` (a standalone Python/FastAPI process wrapping
  `ytmusicapi`, in the sibling repo `~/IdeaProjects/ytmusic-adapter`, wired into
  [compose.yaml](compose.yaml)), for track, album, artist, and playlist search, and to resolve an album or playlist id to its track list. LastFM previously filled this
  role; its client code still compiles but is no longer called — see
  [docs/architecture/ytmusic-integration.md](docs/architecture/ytmusic-integration.md).
- Accepts `POST /download/song/{videoId}` and `POST /download/collection/{id}?type=ALBUM|PLAYLIST`, inserts a `PENDING` row into the `downloads` table, and returns `202 Accepted` immediately (fast ack; no work on the request thread). The request carries a **YouTube id and a type**, not a name — the server resolves what the id is, from `ytmusic-adapter`, when the loop admits it.
- Runs a durable, Postgres-backed download state machine that turns those rows into real slskd downloads (see "Download Execution Flow" below) and survives a restart mid-download — nothing about a download's position is held in the JVM heap.
- Calls slskd to search Soulseek, select candidates, enqueue downloads, poll to completion, and retry/fail over across candidates, driven by `DownloadStateMachine` — a pure function (no fields, no I/O, no clock of its own) that maps `(task, slskd response, now)` to one of three decisions.
- Persists a terminal `SUCCEEDED`/`FAILED` status per download once the state machine reaches a success/fail state.

Three tables live in `com.catacomb5099.naviseerr.download`. As of V5 the relationship between the first two is **one download to N tasks** — one task per song; as of V6 neither of them holds a title or a picture, `media_items` does:

- `downloads` (`download_id UUID`, `youtube_id TEXT NOT NULL`, `download_type TEXT CHECK (SONG|ALBUM|PLAYLIST)`, `status TEXT CHECK (...)`, `failure_reason TEXT`, `created_at`/`admitted_at`/`finished_at TIMESTAMPTZ`) — one user REQUEST and its lifecycle, nothing about what was requested beyond the id. The low-churn, user-facing record that history queries read, written at request, at admission, and at conclusion. Status values are enforced at the DB level via a `CHECK` constraint rather than a native Postgres enum (see decisions doc for rationale); `PARTIAL_SUCCESS` is in that set and is reachable only for a collection. `failure_reason` is written only by `failUnadmitted`, for the one failure (ytmusic-adapter cannot resolve the id) that happens before any task row exists to carry it.
- `media_items` (`youtube_id TEXT PRIMARY KEY`, `title`, `artists TEXT[]`, `image_url`, `duration_seconds` (songs), `track_count` (collections), `fetched_at`) — what a YouTube id IS: the name and picture every read endpoint shows. One row per id, songs and collections alike, written by admission from the adapter response (the download's own id plus every track's) and upserted on every later answer, never blanked. Every feed query `LEFT JOIN`s it through `downloads.youtube_id`; a QUEUED download has no row yet and reports a null title, which is the honest state. `download_tasks.youtube_id` joins it for the per-song view.
- `download_tasks` (`task_id UUID PRIMARY KEY`, `download_id UUID REFERENCES downloads`, `youtube_id TEXT`, `song_name TEXT`, `position INT`, `phase`, `phase_entered_at`, `next_attempt_at`, `lease_owner`/`lease_expires_at`, `search_id`, `candidates` as JSON, `candidate_index`, `retry_index`, `slskd_username`/`slskd_filename`/`slskd_transfer_id`, `finished_at`, `failure_reason`, `progress_percent NUMERIC(5,2)`) — the working state of ONE SONG's pipeline, written every few seconds. A song request has one row; a ten-track album has ten, with `position` recording track order. **`download_tasks.song_name` is the Soulseek query wording, `"Title - Primary Artist"`, not a display title** — the string the client used to send before requests became ids, and the shape `TrackMatchingService` still splits on. The per-song display title is `media_items.title`. Rows are **retained** once terminal (`SUCCEEDED`/`FAILED`), never deleted — so a self-hoster can see which peers were tried and how each failed. A partial index on `next_attempt_at` (covering only non-terminal rows) keeps the due-work query fast regardless of how much history accumulates.

`download_id` was `download_tasks`'s primary key before V5; that was the 1:1 assumption collections removed. Every statement that acts on one song's state (`CLAIM_DUE_SQL`, `SAVE_SQL`, `FINISH_TASK_SQL`) now keys on `task_id`. Keying any of them on `download_id` would step every song of an album on one song's slskd response.

`progress_percent` (0-100, everywhere, no exceptions) is written by `DownloadStateMachine.afterDownloadPoll` from slskd's `percentComplete` on the same `Continue`/`Advance` write every other field rides on — no separate statement, no new write volume. It is reset to zero on retry or candidate failover (a resumed transfer is a new transfer, not a continuation), left untouched when a transfer is briefly absent from the batched response (an absent source value must never overwrite a real one), and normalised to exactly `100` on `SUCCEEDED` by `DownloadService.finishDownload`'s CTE. `FAILED` deliberately keeps its last observed value rather than being forced to either end — see `docs/decisions/download-progress-reporting-17-08-2026.md`. `DownloadTaskRepository.save` now takes `(task, owner)` and only writes when the row is still non-terminal **and** still held by that owner's lease — the guard that was missing before this landed.

The client-facing feed is `GET /downloads/active` plus `GET /downloads?ids=` and the paged `GET /downloads/all` (`DownloadController` + `ActiveDownloadRepository`). It reports **one row per download, never one per song** — its three queries aggregate over the task rows (`ActiveDownloadRepository.TASK_AGGREGATE`), because a plain join emits a ten-track album as ten cards sharing one `downloadId`. For a single-song download every aggregate is over one row and returns that row's own value. A collection reports the **least advanced** song's stage (it is still "searching" while any track is), the **mean** progress across its songs, and `songCount`/`songsSucceeded`/`songsFailed` so a card can say "7 of 12". The per-song breakdown is `GET /downloads/{id}` (`ActiveDownloadRepository.findSongs`), which shows the task rows as themselves, in `position` order, with the pipeline's bookkeeping (candidate count/index, retry index, peer, filename, last slskd error) on the wire for the self-hoster. Four things about the feed are load-bearing and easy to undo by accident:

- It reports a computed **`DownloadStage`**, never `downloads.status` or `download_tasks.phase`. `ActiveDownloadRepository.toStage` is the single place they are combined. Do not add `phase` to the wire — the `phase` CHECK constraint admits `'SUCCEEDED'`/`'FAILED'`, which the four-value `DownloadPhase` enum cannot parse, and `toStage` is what keeps that off the read path.
- The live branch **LEFT JOINs** `download_tasks`, so a download the runner has not admitted yet is reported as `QUEUED` rather than being invisible. Both of its timestamps `COALESCE` to `downloads.created_at` for the same reason.
- The terminal branch's status filter must include `PARTIAL_SUCCESS`, or a collection that half-succeeded finishes and is then never reported at all.
- Finished downloads keep appearing for `download-task.terminal-retention-ms`. **Without that window the client never learns any outcome at all** — the row vanishes the instant it finishes. `GET /downloads?ids=` is the escape hatch past the window and ignores every filter; an id absent from *that* response is the only signal that a download does not exist.

`failure_reason` stores a `DownloadFailureCode` **name**, not prose — the client owns the wording. `DownloadService.finishTask` is idempotent: it applies only while the task is still non-terminal, so a duplicate finish cannot re-stamp `finished_at` (which would drag a long-finished row back into the retention window). `updated_at` is written with SQL `now()` on purpose; see the ADR addendum.

**A download's status is derived from its task rows, not written beside them.** `finishTask` settles one song; `DownloadTaskRepository.concludeDownloads` is a separate idempotent statement, run at the end of every pass, that gives a download its terminal status once every one of its tasks is terminal (`PARTIAL_SUCCESS` when there is at least one success and at least one failure). Do not try to fold it back into the per-task write. Two songs of one download finishing concurrently would each read a snapshot in which the other is still running, so neither would conclude, and the download would sit `IN_PROGRESS` forever with every song finished — and a data-modifying CTE cannot see the effect of its own write, so a bigger statement does not fix it. Deriving it in the loop is the level-triggered rule this design already rests on: a missed conclusion costs one interval, not a download.

### Download Execution Flow

One level-triggered loop, `DownloadTaskRunner`, ticking every `download-task.loop-interval-ms`. Each pass runs admit, then claim, then step:

1. **Admit** — two halves, with an HTTP call between them, because what task rows a download needs is a question only `ytmusic-adapter` can answer. `DownloadTaskRepository.admitDownloads` selects `PENDING` rows with no task row and **writes nothing**; `DownloadTaskRunner.gatherMetadata` then calls the adapter (`/v1/songs/{id}`, `/v1/albums/{id}` or `/v1/playlists/{id}` per `download_type`) and `createTasks` inserts one task row per song and flips `downloads.status` to `IN_PROGRESS` in one statement. A crash in between leaves the row exactly as it was and the next pass retries it — which is the only reason admission can safely stop being a single statement. Bounded by `max-concurrent-downloads`, which counts `downloads` rows, not tasks — a 500-song collection is one in-flight download, so it cannot starve admission for everything else.
   - A metadata failure is classified, not retried blindly: a `YtMusicBadRequestException` (400/422/**404**) fails the download with `METADATA_UNAVAILABLE`, because retrying cannot make an id exist and a row left `PENDING` would be re-requested every loop interval forever. Anything else (a `YtMusicUnavailableException` — timeout, 502/503, connection refused) leaves it `PENDING` for the next pass, so a sidecar restart does not fail every download requested while it was down.
2. **Claim** — `claimDueTasks` claims due, unleased, non-terminal task rows (`FOR UPDATE SKIP LOCKED`, stamping a lease) up to `batch-size`. `DOWNLOAD_INIT` rows are excluded from the claim entirely once `max-concurrent-transfers` has no free slot, so a large collection sitting at `DOWNLOAD_INIT` cannot spend every pass being claimed and re-deferred. Polling an already-running search or transfer is never gated.
3. **Conclude** — `concludeDownloads` runs last and unconditionally; see the note above.
4. **Step** — `DownloadStepExecutor` makes the one slskd call each phase needs (`SEARCH_INIT`/`DOWNLOAD_INIT` call slskd directly; `SEARCH_POLL`/`DOWNLOAD_POLL` read from two lists — `GET /searches` and `GET /transfers/downloads` — fetched once per pass and only when a claimed row actually needs one) and hands the response to `DownloadStateMachine`, which returns `Advance` (phase transition, re-run next pass immediately), `Continue` (re-poll/retry after the phase's poll interval), or `Terminal` (finish the download and mark the task row terminal in one atomic CTE — `DownloadService.finishDownload`). Rows claimed within a pass are stepped concurrently (`flatMap`); passes themselves stay serialised (`concatMap`).

(Read the numbering as admit, claim, step, conclude — step 3 above runs after step 4's claim/step pair within `DownloadTaskRunner.pass()`, so a download whose last song finishes in a pass reports its outcome on the same tick.)

A lease (`lease_owner` + `lease_expires_at`) does two jobs: it stops a second pass double-stepping a row whose slskd call is still outstanding, and its expiry is what lets any process pick up a dead process's row — no reaper needed. Full detail (DDL, per-phase intervals/budgets, the recovery walkthrough) lives in [download-manager.md](docs/architecture/download-manager.md).

The current application does not have:

- SSE/WebSocket progress streaming.
- User accounts, JWT handling, or authorization.
- Cancellation, `CANCELLED`, or `SKIPPED`.
- Redis or RabbitMQ — **rejected**, not merely absent. Postgres is the workflow engine, indefinitely; see `docs/decisions/durable-download-state-machine-13-08-2026.md`.

Current endpoints:

- `GET /search/{query}` — general search (YouTube Music, via `ytmusic-adapter`)
- `GET /search/{query}/tracks` — track search
- `GET /search/{query}/albums` — album search
- `GET /search/{query}/artists` — artist search
- `GET /search/{query}/playlists` — playlist search. `Playlist.id` is the bare `PL...` id; the client must use it unchanged for both `/collections/{id}` and `/download/collection/{id}`
- `GET /collections/{id}?type=ALBUM|PLAYLIST` — one album or playlist as `{id, type, name, artists, iconURL, year, trackCount, tracks[{id, name, artists, iconURL, durationSeconds, position}]}`, resolved live from `ytmusic-adapter` via the same `getAlbumInfo`/`getPlaylistInfo` admission uses, so the track list is exactly what a download of that id would create. `type=SONG` is 400; an id the adapter does not know is 404; adapter down is 502
- `POST /download/song/{videoId}` — inserts a `PENDING` download row of type `SONG`, returns `202 Accepted`; processed asynchronously by the download execution flow
- `POST /download/collection/{id}?type=ALBUM|PLAYLIST` — the same, for every track of an album or playlist as ONE download. `type` is required rather than inferred from the id: albums and playlists are two different adapter endpoints, and guessing from an id prefix is a heuristic that silently breaks the first time YouTube changes one. `type=SONG` is rejected with 400 — a single track has its own route
- `GET /downloads/active` — every non-terminal download plus every one finished within `terminal-retention-ms`, most-recently-updated first, as `{downloadId, youtubeId, downloadType, title, artists, imageUrl, stage, progressPercent, songCount, songsSucceeded, songsFailed, requestedAt, stageEnteredAt, updatedAt, finishedAt, failureCode}` plus `pollIntervalMs` and `terminalRetentionMs`; the client polls this, no SSE
- `GET /downloads?ids=a,b,c` — the same shape for specific ids, ignoring both the terminal filter and the retention window (max 100 ids). Lets a client reconcile cards it held across a restart; absent ids are omitted, not 404'd
- `GET /downloads/all?pageSize=&pageNumber=` — the same shape, paginated over every download ever, newest first; the history table
- `GET /downloads/{id}` — one download as `{download, songs[]}`: the feed card plus every song in track order as `{taskId, youtubeId, position, title, artists, imageUrl, durationSeconds, stage, progressPercent, failureCode, stageEnteredAt, updatedAt, finishedAt, candidateCount, candidateIndex, retryIndex, slskdUsername, slskdFilename, lastError}`. 404 for an unknown id; `songs` is empty, not absent, for a download not yet admitted

## Deeper Context (docs/architecture)

Deep-dive guides for agents and developers live in [docs/architecture/](docs/architecture/README.md). Read the relevant one before working on a subsystem; the cited source files remain the source of truth.

- [codebase-map.md](docs/architecture/codebase-map.md) — repo layout, package map, entry points, branch topology, build/run.
- [slskd-integration.md](docs/architecture/slskd-integration.md) — the Soulseek search -> select -> download -> poll pipeline and retry/failover.
- [download-manager.md](docs/architecture/download-manager.md) — the durable download task loop (admit, claim, step, apply; leases; the three capacity bounds).
- [persistence.md](docs/architecture/persistence.md) — R2DBC + Postgres, the `downloads`/`download_tasks` tables, claim/status SQL, Flyway.
- [ytmusic-integration.md](docs/architecture/ytmusic-integration.md) — YouTube Music metadata search (via the sidecar `ytmusic-adapter`) and response mapping. The active search provider.
- [lastfm-integration.md](docs/architecture/lastfm-integration.md) — LastFM metadata search and response mapping. Superseded, unused, retained on disk.
- [reactive-patterns.md](docs/architecture/reactive-patterns.md) — Reactor cookbook: the level-triggered interval loop, `flatMap` vs `concatMap`.
- [testing.md](docs/architecture/testing.md) — unit (Mockito + StepVerifier) and integration (Testcontainers) testing, and how to run them.
- [gotchas.md](docs/architecture/gotchas.md) — known bugs and hygiene issues (e.g. committed secrets, unverified `SlskdSearchState` values).

## Product Context

MVP:

- Search songs, artists, and albums.
- Download songs.

Important future milestones:

- Download manager for songs and collections.
- Download history and cancellation.
- Cache and database-backed state.
- Artist/song/album pages.
- Playlist search and playlist downloads.
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
- Cancellation, when it lands, is a flag checked by the loop — not an attempt to retract queued work.
- SSE, when it lands, reads from Postgres. If a resume cursor is needed, add an append-only `download_events` table and use its sequence number. That table is also the dataset for tuning candidate ranking and match thresholds, which is its stronger justification.

Three independent bounds, and they must not be conflated — the deleted `flatMap(this::process, 3)` collapsed all of them into one number:

- `batch-size / loop-interval` is a hard ceiling on the request rate to external providers.
- `max-concurrent-downloads` caps how many user requests are worked on at once. It counts `downloads` rows, so a collection of 500 songs is **one** in-flight download and cannot lock every other request out of admission.
- `max-concurrent-transfers` caps how many slskd transfers exist at once. This protects bandwidth and peers' upload queues. It gates only the step that *starts* a transfer — never polling, because polling is one cheap GET and starving it stalls a download slskd is happily finishing.

Work is taken oldest-first with no per-collection cap, so one collection may legitimately hold every transfer slot until it is done. That is the intended default: if a user asked for something, they usually want it finished. Making it a user-facing option is planned, not hardcoded.

Downloads parked waiting on a remote poll cost one table row each and need no bound at all.

The current authoritative design lives in `docs/superpowers/specs/2026-08-13-durable-download-state-machine-design.md`, with the implementation plan in `docs/superpowers/plans/2026-08-13-durable-download-state-machine.md`.

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

# Naviseerr Agent Guide

This file is the primary project guide for AI agents working in this repository. Read it before making changes.

For detailed, agent-oriented subsystem context (how the slskd pipeline, download manager, persistence, LastFM, and reactive patterns actually work today, plus testing and known gotchas), see the deep-dive guides in [docs/architecture/](docs/architecture/README.md).

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
- LastFM for search metadata
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

The durable state machine described in "Download Manager Architecture" below is now built, not just targeted. Remaining gaps (collections, cancellation, SSE) are called out explicitly below.

The current application is a small Java REST/WebFlux service that:

- Calls LastFM for track, album, and artist search.
- Accepts `POST /download/{songName}`, inserts a `PENDING` row into the `downloads` table, and returns `202 Accepted` immediately (fast ack; no work on the request thread).
- Runs a durable, Postgres-backed download state machine that turns those rows into real slskd downloads (see "Download Execution Flow" below) and survives a restart mid-download — nothing about a download's position is held in the JVM heap.
- Calls slskd to search Soulseek, select candidates, enqueue downloads, poll to completion, and retry/fail over across candidates, driven by `DownloadStateMachine` — a pure function (no fields, no I/O, no clock of its own) that maps `(task, slskd response, now)` to one of three decisions.
- Persists a terminal `SUCCEEDED`/`FAILED` status per download once the state machine reaches a success/fail state.

Two tables live in `com.catacomb5099.naviseerr.download`:

- `downloads` (`download_id UUID`, `song_name TEXT`, `status TEXT CHECK (...)`, `created_at TIMESTAMPTZ`) — the low-churn, user-facing record that history queries read. Status values are enforced at the DB level via a `CHECK` constraint rather than a native Postgres enum (see decisions doc for rationale).
- `download_tasks` (`download_id UUID PRIMARY KEY REFERENCES downloads`, `phase`, `phase_entered_at`, `next_attempt_at`, `lease_owner`/`lease_expires_at`, `search_id`, `candidates` as JSON, `candidate_index`, `retry_index`, `slskd_username`/`slskd_filename`/`slskd_transfer_id`, `finished_at`, `failure_reason`) — the working state of one download's pipeline, written every few seconds. Rows are **retained** once terminal (`SUCCEEDED`/`FAILED`), never deleted — so a self-hoster can see which peers were tried and how each failed. A partial index on `next_attempt_at` (covering only non-terminal rows) keeps the due-work query fast regardless of how much history accumulates.

### Download Execution Flow

One level-triggered loop, `DownloadTaskRunner`, ticking every `download-task.loop-interval-ms`. Each pass runs admit, then claim, then step:

1. **Admit** — `DownloadTaskRepository.admitNewDownloads` is one atomic statement: it finds non-terminal `downloads` rows with no `download_tasks` row, inserts one at `SEARCH_INIT`, and flips `downloads.status` to `IN_PROGRESS`. Bounded by `max-concurrent-downloads`, which counts `downloads` rows, not tasks — a 500-song collection is one in-flight download, so it cannot starve admission for everything else.
2. **Claim** — `claimDueTasks` claims due, unleased, non-terminal task rows (`FOR UPDATE SKIP LOCKED`, stamping a lease) up to `batch-size`. `DOWNLOAD_INIT` rows are excluded from the claim entirely once `max-concurrent-transfers` has no free slot, so a large collection sitting at `DOWNLOAD_INIT` cannot spend every pass being claimed and re-deferred. Polling an already-running search or transfer is never gated.
3. **Step** — `DownloadStepExecutor` makes the one slskd call each phase needs (`SEARCH_INIT`/`DOWNLOAD_INIT` call slskd directly; `SEARCH_POLL`/`DOWNLOAD_POLL` read from two lists — `GET /searches` and `GET /transfers/downloads` — fetched once per pass and only when a claimed row actually needs one) and hands the response to `DownloadStateMachine`, which returns `Advance` (phase transition, re-run next pass immediately), `Continue` (re-poll/retry after the phase's poll interval), or `Terminal` (finish the download and mark the task row terminal in one atomic CTE — `DownloadService.finishDownload`). Rows claimed within a pass are stepped concurrently (`flatMap`); passes themselves stay serialised (`concatMap`).

A lease (`lease_owner` + `lease_expires_at`) does two jobs: it stops a second pass double-stepping a row whose slskd call is still outstanding, and its expiry is what lets any process pick up a dead process's row — no reaper needed. Full detail (DDL, per-phase intervals/budgets, the recovery walkthrough) lives in [download-manager.md](docs/architecture/download-manager.md).

The current application does not have:

- SSE/WebSocket progress streaming.
- User accounts, JWT handling, or authorization.
- Collection/playlist download orchestration.
- Redis or RabbitMQ — **rejected**, not merely absent. Postgres is the workflow engine, indefinitely; see `docs/decisions/durable-download-state-machine-13-08-2026.md`.

Current endpoints:

- `GET /search/{query}` — LastFM general search
- `GET /search/{query}/tracks` — LastFM track search
- `GET /search/{query}/albums` — LastFM album search
- `GET /search/{query}/artists` — LastFM artist search
- `POST /download/{songName}` — inserts a `PENDING` download row, returns `202 Accepted`; processed asynchronously by the download execution flow

## Deeper Context (docs/architecture)

Deep-dive guides for agents and developers live in [docs/architecture/](docs/architecture/README.md). Read the relevant one before working on a subsystem; the cited source files remain the source of truth.

- [codebase-map.md](docs/architecture/codebase-map.md) — repo layout, package map, entry points, branch topology, build/run.
- [slskd-integration.md](docs/architecture/slskd-integration.md) — the Soulseek search -> select -> download -> poll pipeline and retry/failover.
- [download-manager.md](docs/architecture/download-manager.md) — the durable download task loop (admit, claim, step, apply; leases; the three capacity bounds).
- [persistence.md](docs/architecture/persistence.md) — R2DBC + Postgres, the `downloads`/`download_tasks` tables, claim/status SQL, Flyway.
- [lastfm-integration.md](docs/architecture/lastfm-integration.md) — LastFM metadata search and response mapping.
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
- `Download`: one song download for one user, optionally attached to a collection.
- `CollectionDownload`: a batch/playlist-style download with per-song counts and aggregate status.

Likely statuses:

- `Pending`
- `InProgress`
- `Success`
- `Failed`
- `Cancelled`
- `Skipped`
- `PartialSuccess` for collections

Cancellation should be treated as a first-class action. Prefer correctness and eventual consistency over directly mutating/removing queued work in ways that can race with a completed download.

## Engineering Guidelines

- Keep the backend reactive unless there is a strong reason not to. Do not introduce blocking calls into reactive paths without isolating them.
- Preserve clear boundaries between provider clients (`lastfm`, `slskd`), orchestration services, domain models, and API controllers.
- Model external-provider failures explicitly. slskd and LastFM can be slow, incomplete, or inconsistent.
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

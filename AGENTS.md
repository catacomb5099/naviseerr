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
- `spring.sql.init` with `schema.sql` for schema creation (no Flyway yet — see schema management note below)
- Gradle
- Lombok
- LastFM for search metadata
- slskd for Soulseek search/download orchestration
- JUnit Platform with MockK present for tests

## Schema Management Approach

Schema is currently managed via `src/main/resources/schema.sql`, applied on startup by `spring.sql.init`. All table definitions use `CREATE TABLE IF NOT EXISTS` to make the script idempotent across restarts.

This is intentionally simpler than Flyway. The trade-off: `schema.sql` can safely add tables but cannot express column-level migrations (e.g. `ALTER TABLE ADD COLUMN`). When the schema needs to evolve across deployments (adding a column to an existing table, renaming a column, etc.), that is the right moment to introduce Flyway with versioned migration files.

Do not add Flyway or a blocking JDBC driver until that need arises. See `docs/decisions/minimal-postgres-downloads-26-06-2026.md` for the full rationale.

## Current Implementation State

Be explicit about this distinction: much of the architecture described below is the targeted end state, not what exists today.

The current application is a small Java REST/WebFlux service that:

- Calls LastFM for track, album, and artist search.
- Accepts `POST /download/{songName}`, inserts a `PENDING` row into the `downloads` table, and returns `202 Accepted` immediately (fast ack; no work on the request thread).
- Runs an event-driven download pipeline that turns those `PENDING` rows into real slskd downloads (see "Download Execution Flow" below).
- Calls slskd to search Soulseek, select candidates, enqueue downloads, poll to completion, and retry/fail over across candidates via the existing reactive polling/retry logic.
- Persists a terminal `SUCCEEDED`/`FAILED` status per download once the pipeline reaches a success/fail state.

The `downloads` table (`download_id UUID`, `song_name TEXT`, `status TEXT CHECK (...)`, `created_at TIMESTAMPTZ`) lives in `com.catacomb5099.naviseerr.download`. Status values are enforced at the DB level via a `CHECK` constraint rather than a native Postgres enum (see decisions doc for rationale).

### Download Execution Flow

Three decoupled concerns in `com.catacomb5099.naviseerr.download`:

1. Ingress — `DownloadController` + `DownloadService.requestDownload` insert a `PENDING` row and return `202`. Nothing else happens on the request.
2. Claim + emit (interval) — `PendingDownloadRunner` polls the DB on an interval (`download-runner.interval-ms`), claiming the oldest `PENDING` rows with `claimPendingDownloads` (`UPDATE ... FOR UPDATE SKIP LOCKED ... RETURNING`, flipping them to `IN_PROGRESS`) and emitting each claimed row into `DownloadQueue`. The `PENDING -> IN_PROGRESS` transition is what triggers enqueueing.
3. Process (event-driven within the process) — `DownloadQueue` wraps an in-memory Reactor `Sinks.many().unicast().onBackpressureBuffer()`. `DownloadWorker` subscribes once and processes claimed downloads with bounded concurrency (`download-worker.concurrency`, default 3) via `flatMap`. For each item it runs `DownloadFulfillment.fulfill` (slskd search -> select best files -> enqueue/poll download) and then writes the terminal status via the transactional `DownloadService.markStatusIfInProgress`. Both an error and an empty result map to `FAILED`; every item is isolated so one failure never tears down the worker. The queue sleeps when empty and wakes immediately on emit — no polling on the queue itself (the only interval is the DB claim). Push-based, but not durable messaging: the buffer is heap-only, so anything queued or in flight is lost on restart and its row is stranded at `IN_PROGRESS`.

The current application does not have:

- Redis.
- RabbitMQ or any other durable/cross-process queue (the work queue is in-memory).
- SSE/WebSocket progress streaming.
- User accounts, JWT handling, or authorization.
- Collection/playlist download orchestration.
- Resilience/recovery for in-flight work — see the must-do below.

> [!IMPORTANT]
> No resilience / crash-recovery for in-flight downloads yet — THIS MUST BE ADDED.
>
> The work queue is in-memory only. Because the DB is the durable ingress, rows still in `PENDING` are picked up automatically by the next claim interval after a restart, so they are effectively recovered. The gap is rows already flipped to `IN_PROGRESS` when the process dies: the claimer only claims `PENDING`, and the in-memory queue contents are lost, so those downloads are stranded in `IN_PROGRESS` forever.
>
> This was omitted only to keep the first cut simple and MUST be implemented next:
> - A reaper/timeout that reclaims stale `IN_PROGRESS` rows (reset to `PENDING` or re-enqueue), with idempotent reprocessing.
> - Queue overflow/backpressure from the worker back to the claimer (the buffer is currently unbounded).
> - Eventually a durable broker (RabbitMQ/Redis) per the target architecture, which removes the in-memory-loss problem entirely.

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
- [download-manager.md](docs/architecture/download-manager.md) — the event-driven download queue (ingress, claim, queue, worker, terminal status).
- [persistence.md](docs/architecture/persistence.md) — R2DBC + Postgres, the `downloads` table, claim/status SQL, patterns.
- [lastfm-integration.md](docs/architecture/lastfm-integration.md) — LastFM metadata search and response mapping.
- [reactive-patterns.md](docs/architecture/reactive-patterns.md) — Reactor cookbook: polling-via-retry and the Sinks work queue.
- [testing.md](docs/architecture/testing.md) — unit (Mockito + StepVerifier) and integration (Testcontainers) testing, and how to run them.
- [gotchas.md](docs/architecture/gotchas.md) — known bugs and hygiene issues (e.g. the `TransferedFileUtil` state-parsing bug, committed secrets).

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

## Target Download Manager Architecture

This section describes the intended future architecture. Do not assume these components already exist. When implementing toward this direction, introduce them deliberately and in small, tested steps.

The intended download manager is asynchronous and stateful:

- HTTP/API requests should acknowledge quickly and enqueue work instead of blocking while downloads run.
- RabbitMQ should own execution state and pipeline steps.
- Redis should own ephemeral/high-frequency state such as progress percentages, active quotas, rate limits, and SSE resume cursors.
- The database should own canonical milestones and history: pending, in progress, success, failed, cancelled, skipped, partial success.
- SSE should push progress/status updates to clients. Do not build aggressive client polling for progress.
- Use delayed/requeued messages for retries, throttling, and multi-step pipelines.
- Use a DLQ for exhausted retries or unrecoverable external failures.

Prefer a pipeline like:

1. Create download or collection record.
2. Initialize progress in Redis.
3. Publish a queue step.
4. Worker checks cancellation and throttling before external calls.
5. Search/download through provider services.
6. Update Redis for frequent progress changes.
7. Emit SSE progress events at a controlled cadence.
8. Persist only meaningful milestones/final states to the database.
9. Enqueue the next step or finalize the collection.

Avoid holding a worker thread for a long poll/wait when the work can be represented as another delayed step.

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
- Do not write high-frequency progress changes directly to the primary database.
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

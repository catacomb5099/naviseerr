# Naviseerr Agent Guide

This file is the primary project guide for AI agents working in this repository. Read it before making changes.

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
- Calls slskd to search Soulseek and enqueue downloads (processor classes exist; not wired into `/download` yet).
- Polls slskd until search/download completion.
- Retries failed candidate downloads according to the existing reactive polling/retry logic.
- Accepts `POST /download/{songName}`, inserts a `PENDING` row into the `downloads` table, and returns `202 Accepted`. No actual search or download is triggered yet.

The `downloads` table (`download_id UUID`, `song_name TEXT`, `status TEXT CHECK (...)`, `created_at TIMESTAMPTZ`) lives in `com.catacomb5099.naviseerr.download`. Status values are enforced at the DB level via a `CHECK` constraint rather than a native Postgres enum (see decisions doc for rationale).

The current application does not have:

- Redis.
- RabbitMQ or any other durable queue.
- A download manager service that executes actual downloads from the queue.
- SSE/WebSocket progress streaming.
- Actual slskd/LastFM orchestration wired into the `/download` endpoint (it only records intent).
- User accounts, JWT handling, or authorization.
- Collection/playlist download orchestration.

Current endpoints:

- `GET /search/{query}` — LastFM general search
- `GET /search/{query}/tracks` — LastFM track search
- `GET /search/{query}/albums` — LastFM album search
- `GET /search/{query}/artists` — LastFM artist search
- `POST /download/{songName}` — inserts a `PENDING` download row, returns `202 Accepted`

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

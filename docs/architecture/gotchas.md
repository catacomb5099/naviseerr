# Gotchas and Known Issues

> Status: current as of 2026-06-29, branch `event-driven-download-queue`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Foot-guns, latent bugs, and hygiene issues to know before touching related code. Each entry: what, where, impact, suggested action.

## 1. `getStateList` matches enum name() not the slskd value (latent bug)

- Where: [TransferedFileUtil.java](../../src/main/java/com/catacomb5099/naviseerr/util/TransferedFileUtil.java) - `transferState.name().equalsIgnoreCase(state)`.
- What: slskd reports states like `"InProgress"` and `"TimedOut"`, but the code compares against the enum `name()` (`IN_PROGRESS`, `TIMED_OUT`) instead of the enum's `value` field (`"InProgress"`, `"TimedOut"`).
- Impact: single-word states (`Succeeded`, `Errored`, `Cancelled`, `Rejected`, `Aborted`, `Queued`, `Completed`) match, but `InProgress` and `TimedOut` never match. A timed-out download is not recognized as a failure - it falls through to "in progress" and keeps polling until `slskd-service.max-poll-attempts` is exhausted. This directly affects the download `failed` predicate in [SlskdDownloadProcessor](../../src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdDownloadProcessor.java).
- Suggested action: compare against `TransferState.getValue()` (the slskd string) instead of `name()`. Add a `TransferedFileUtilTest` case for `"Completed, TimedOut"` and `"InProgress"`.

## 2. Secrets committed in application.yaml

- Where: [application.yaml](../../src/main/resources/application.yaml) - `last-fm-service.api_key` and `slskd-service.api_key` are hardcoded.
- Impact: live keys in version control; against the Configuration Hygiene rule in `AGENTS.md`.
- Suggested action: move to environment variables / local override (as already done for `spring.r2dbc.*`), rotate the exposed keys, and avoid adding new secrets to tracked files.

## 3. Download queue is in-memory with no crash recovery for in-flight work

- Where: [DownloadQueue.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadQueue.java) + [PendingDownloadRunner.java](../../src/main/java/com/catacomb5099/naviseerr/download/PendingDownloadRunner.java).
- What/impact: `PENDING` rows are auto-recovered by the next claim interval after a restart, but rows already flipped to `IN_PROGRESS` when the process dies are stranded (the claimer only claims `PENDING`; the in-memory queue is lost).
- Suggested action (must-do): a reaper/timeout that reclaims stale `IN_PROGRESS` (reset to `PENDING` or re-enqueue) with idempotent reprocessing. See [download-manager.md](download-manager.md) and the ADR.

## 4. Sinks buffer is unbounded (no backpressure to the claimer)

- Where: `Sinks.many().unicast().onBackpressureBuffer()` in [DownloadQueue.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadQueue.java).
- Impact: if the claimer enqueues faster than the worker drains (e.g. large `download-runner.batch-size` / small `download-worker.concurrency`), the buffer can grow unbounded.
- Suggested action: bound the buffer and/or apply backpressure from the worker back to the claim cadence; revisit when adding the durable broker.

## 5. LastFM response mapping: index risk and placeholder values (dormant since 10-08-2026)

- Where: [SearchResponseMapper.java](../../src/main/java/com/catacomb5099/naviseerr/util/SearchResponseMapper.java).
- What/impact: artist/album image selection uses `images.get(2)` guarded only by `isEmpty()`, so fewer than 3 images throws `IndexOutOfBoundsException`. `mapFromLastFMTrack` returns placeholders (`"lol"` album id, `0` year); album year is `0`.
- Status: `SearchService` no longer calls this path (see [ytmusic-integration.md](ytmusic-integration.md)), so this bug is currently unreachable rather than fixed. `YtMusicSearchResponseMapper` gets `Track.albumId` and `Album.year` from real fields and never indexes into an image list without a bounds check — the replacement, not a patch.
- Suggested action: no longer worth fixing in place; delete alongside the rest of the LastFM code per the [ADR](../decisions/ytmusic-search-provider-10-08-2026.md).

## 6. `@EnableWebFlux` + wide-open CORS

- Where: [WebConfig.java](../../src/main/java/com/catacomb5099/naviseerr/config/WebConfig.java).
- What/impact: `@EnableWebFlux` switches WebFlux into "full control" mode, which can disable parts of Spring Boot's WebFlux auto-configuration if you later rely on them. CORS is `allowedOrigins("*")` for all paths - fine for local dev, not for production.
- Suggested action: be aware before adding WebFlux config; tighten CORS before any non-local deployment.

## 7. schema.sql runs on every startup; cannot express migrations

- Where: `spring.sql.init` + [schema.sql](../../src/main/resources/schema.sql).
- What/impact: the whole script runs on each startup. `CREATE TABLE IF NOT EXISTS` keeps it idempotent, but it cannot express column changes (e.g. `ALTER TABLE ADD COLUMN`) safely.
- Suggested action: introduce Flyway at the moment the schema must evolve across deployments (see the [minimal-postgres-downloads ADR](../decisions/minimal-postgres-downloads-26-06-2026.md)).

## 8. `@SpringBootTest` tests require Docker

- Where: [testing.md](testing.md), Testcontainers-backed tests.
- Impact: `./gradlew test` fails without a running Docker daemon (Testcontainers can't start Postgres); first run pulls the `postgres:16-alpine` image.
- Suggested action: run Docker for the full suite, or filter to unit tests when Docker is unavailable.

## 9. Track matching assumes "artist - title" separator

- Where: [TrackMatchingService.extractParts](../../src/main/java/com/catacomb5099/naviseerr/util/TrackMatchingService.java) (noted TODO).
- Impact: artist/title extraction relies on a single `"-"`; titles containing `-`, or other separators, are split incorrectly (the fuzzy ratio checks still apply, so matching degrades rather than breaks).
- Suggested action: drive matching from structured LastFM fields (artist/title) rather than parsing a combined string.

## 10. A rejected queue emission is dropped silently (relates to 3 and 4)

- Where: `enqueue` in [DownloadQueue.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadQueue.java).
- What/impact: a failed `tryEmitNext` is logged and the download is discarded - there is no retry and no back-channel to the claimer, so the row is left stranded at `IN_PROGRESS` exactly as in 3. Narrow in practice: the sole producer emits serially into an unbounded buffer, so this is only reachable once the sink is terminated or cancelled (shutdown, or a cancelled worker subscription).
- Suggested action: fold into the same reaper work as 3; a bounded buffer (4) would make emit rejection a routine case rather than a shutdown-only one, so handle it before bounding.

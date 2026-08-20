# Gotchas and Known Issues

> Status: current as of 2026-08-13, branch `durable-download-state-machine`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Foot-guns, latent bugs, and hygiene issues to know before touching related code. Each entry: what, where, impact, suggested action.

## 1. Secrets committed in application.yaml

- Where: [application.yaml](../../src/main/resources/application.yaml) - `last-fm-service.api_key` and `slskd-service.api_key` are hardcoded.
- Impact: live keys in version control; against the Configuration Hygiene rule in `AGENTS.md`.
- Suggested action: move to environment variables / local override (as already done for `spring.r2dbc.*`), rotate the exposed keys, and avoid adding new secrets to tracked files.

## 2. Track matching assumes "artist - title" separator

- Where: [TrackMatchingService.extractParts](../../src/main/java/com/catacomb5099/naviseerr/util/TrackMatchingService.java) (noted TODO).
- Impact: artist/title extraction relies on a single `"-"`; titles containing `-`, or other separators, are split incorrectly (the fuzzy ratio checks still apply, so matching degrades rather than breaks).
- Suggested action: drive matching from structured LastFM fields (artist/title) rather than parsing a combined string.

## 3. `SlskdSearchState`'s values are unverified guesses

- Where: [SlskdSearchState.java](../../src/main/java/com/catacomb5099/naviseerr/schema/slskd/SlskdSearchState.java).
- What/impact: the state strings (`"Requested"`, `"InProgress"`, `"TimedOut"`, `"Cancelled"`, `"Errored"`, ...) have not been confirmed against a live slskd instance. If slskd's actual strings differ, a real failure state simply won't match `isFailure`.
- Why it's low-risk in practice: the design is deliberately robust to getting this wrong. An unrecognised or misspelled state string falls through to `SlskdSearchState.isFailure`'s default of `false`, which routes to "completed with no usable candidates" (`NO_CANDIDATES`) rather than being misclassified as success.
- Suggested action: confirm the strings against a live slskd instance and add a regression test per confirmed value; until then, treat this enum as fail-safe but unverified.

## 4. The `"flac"` extension check is case-sensitive

- Where: [SlskdSearchResultProcessor.isFlacAndHighBitrate](../../src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessor.java) - `file.getExtension().equals("flac")`.
- Impact: a file reported with extension `"FLAC"` or `"Flac"` fails this check and is kept only if it also clears the bitrate filter, so some genuine FLAC files can be silently excluded from candidates.
- Suggested action: `equalsIgnoreCase("flac")`. Left as-is deliberately through the durable-download-state-machine work — `selectBestFiles` and everything under it stayed byte-for-byte unchanged so its existing tests passing unmodified could serve as the guard that the pipeline rewrite did not also touch ranking/filtering; fixing this is a small, separate, well-scoped change.

## 5. `@EnableWebFlux` + wide-open CORS

- Where: [WebConfig.java](../../src/main/java/com/catacomb5099/naviseerr/config/WebConfig.java).
- What/impact: `@EnableWebFlux` switches WebFlux into "full control" mode, which can disable parts of Spring Boot's WebFlux auto-configuration if you later rely on them. CORS is `allowedOrigins("*")` for all paths - fine for local dev, not for production.
- Suggested action: be aware before adding WebFlux config; tighten CORS before any non-local deployment.

## 6. `@SpringBootTest` tests require Docker

- Where: [testing.md](testing.md), Testcontainers-backed tests.
- Impact: `./gradlew test` fails without a running Docker daemon (Testcontainers can't start Postgres); first run pulls the `postgres:16-alpine` image.
- Suggested action: run Docker for the full suite, or filter to unit tests when Docker is unavailable.

## 7. LastFM response mapping: index risk and placeholder values

- Where: [SearchResponseMapper.java](../../src/main/java/com/catacomb5099/naviseerr/util/SearchResponseMapper.java).
- What/impact: artist/album image selection uses `images.get(2)` guarded only by `isEmpty()`, so fewer than 3 images throws `IndexOutOfBoundsException`. `mapFromLastFMTrack` returns placeholders (`"lol"` album id, `0` year); album year is `0`.
- Suggested action: select images defensively (size check / first available) and replace placeholders with real mapping when those fields matter.

## 8. The one accepted crash window: a duplicate download after a crash mid-enqueue

- Where: `DOWNLOAD_INIT` in [DownloadStepExecutor.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadStepExecutor.java) / [DownloadStateMachine.afterDownloadInit](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java).
- What/impact: a crash between `POST /transfers/downloads/{user}` returning and the transfer id being persisted leaves slskd downloading a file naviseerr has no record of; on restart, `DOWNLOAD_INIT` re-runs and asks the same (or next) peer for the same file again, which can start a second transfer.
- This is a deliberate, documented accepted risk, not a latent bug - see [docs/decisions/durable-download-state-machine-13-08-2026.md](../decisions/durable-download-state-machine-13-08-2026.md) ("Accept an occasional duplicate download after a crash"). The crash window is single-digit milliseconds; the cost is one extra duplicate file, once, per crash.
- Suggested action: none required. The recorded follow-up, if this judgement ever changes, is to adopt the orphaned slskd transfer instead of re-enqueueing (needs `GET /transfers/downloads/{username}` confirmed against a live instance first) - not currently planned.

## Related docs

- [download-manager.md](download-manager.md) - the durable state machine these entries reference.
- [slskd-integration.md](slskd-integration.md) - search/candidate/transfer details.

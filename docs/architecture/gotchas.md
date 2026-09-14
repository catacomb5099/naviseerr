# Gotchas and Known Issues

> Status: current as of 2026-09-14. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Foot-guns, latent bugs, and hygiene issues to know before touching related code. Each entry: what, where, impact, suggested action.

## 1. Secrets committed in application.yaml (fixed; keys still need rotating)

- Where: [application.yaml](../../src/main/resources/application.yaml) - `last-fm-service.api_key` and `slskd-service.api_key` used to be hardcoded.
- Status: fixed. Both now read from `./.env` via `spring.config.import` (see [.env.example](../../.env.example)). `slskd-service.api_key` has no default and fails fast; `last-fm-service.api_key` carries a placeholder default because Last.fm is dormant (see #7) and must not block startup.
- Still outstanding: the keys that were previously committed are in the git history, so they remain exposed and should be rotated. Do not add new secrets to tracked files.

## 2. Track matching assumes "artist - title" separator

- Where: [TrackMatchingService.extractParts](../../src/main/java/com/catacomb5099/naviseerr/util/TrackMatchingService.java) (noted TODO).
- Impact: artist/title extraction relies on a single `"-"`; titles containing `-`, or other separators, are split incorrectly (the fuzzy ratio checks still apply, so matching degrades rather than breaks).
- Still open as of 14-09-2026, and now *more* fixable rather than less: collection downloads made the server fetch a track's real title and artist list from ytmusic-adapter (`YoutubeSongInfo.authorNames()`), so the structured fields this entry asks for exist. What is not yet done is threading them past `download_tasks.song_name` — the task row still carries one glued string, so `SEARCH_INIT` still hands the matcher a combined name to take apart.
- Suggested action: carry the artist list onto the task row and drive matching from it. The shape worth reaching for: a `SlskdQueryBuilder` seam owning the one question of how a track is worded for Soulseek, matching on *any* artist rather than all of them (a four-way collab is rarely filed under all four names), and the hyphen split kept only as the degraded path for a row with no artists. Independent of, and still applicable after, the collections change.

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

## 7. LastFM response mapping: index risk and placeholder values (dormant since 10-08-2026)

- Where: [SearchResponseMapper.java](../../src/main/java/com/catacomb5099/naviseerr/util/SearchResponseMapper.java).
- What/impact: artist/album image selection uses `images.get(2)` guarded only by `isEmpty()`, so fewer than 3 images throws `IndexOutOfBoundsException`. `mapFromLastFMTrack` returns placeholders (`"lol"` album id, `0` year); album year is `0`.
- Status: `SearchService` no longer calls this path (see [ytmusic-integration.md](ytmusic-integration.md)), so this bug is currently unreachable rather than fixed. `YtMusicSearchResponseMapper` gets `Track.albumId` and `Album.year` from real fields and never indexes into an image list without a bounds check — the replacement, not a patch.
- Suggested action: no longer worth fixing in place; delete alongside the rest of the LastFM code per the [ADR](../decisions/ytmusic-search-provider-10-08-2026.md).

## 8. The one accepted crash window: a duplicate download after a crash mid-enqueue

- Where: `DOWNLOAD_INIT` in [DownloadStepExecutor.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadStepExecutor.java) / [DownloadStateMachine.afterDownloadInit](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java).
- What/impact: a crash between `POST /transfers/downloads/{user}` returning and the transfer id being persisted leaves slskd downloading a file naviseerr has no record of; on restart, `DOWNLOAD_INIT` re-runs and asks the same (or next) peer for the same file again, which can start a second transfer.
- This is a deliberate, documented accepted risk, not a latent bug - see [docs/decisions/durable-download-state-machine-13-08-2026.md](../decisions/durable-download-state-machine-13-08-2026.md) ("Accept an occasional duplicate download after a crash"). The crash window is single-digit milliseconds; the cost is one extra duplicate file, once, per crash.
- Suggested action: none required. The recorded follow-up, if this judgement ever changes, is to adopt the orphaned slskd transfer instead of re-enqueueing (needs `GET /transfers/downloads/{username}` confirmed against a live instance first) - not currently planned.

## 9. A download's aggregate status is derived by the loop, and only by the loop

- Where: [DownloadTaskRepository.CONCLUDE_SQL](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java) / [DownloadTaskRunner.pass](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java).
- What: since V5 a download has one task row per song, so `downloads.status` is a function of N rows. `DownloadService.finishTask` settles one song and deliberately does **not** touch `downloads`; `concludeDownloads` runs once at the end of every pass and derives the status.
- The foot-gun: this looks like an easy simplification — fold the aggregate back into the per-task terminal write, as it was pre-V5, and lose a statement per pass. It does not work. Two songs of one download finishing concurrently each read a snapshot in which the other is still non-terminal, so **neither** concludes, and the download sits `IN_PROGRESS` forever with every song finished. A data-modifying CTE cannot see the effect of its own write, so a bigger single statement does not close it either.
- Suggested action: leave it alone. If you need the download's status sooner than the end of the pass, move the `concludeDownloads` call, not the logic. See [the ADR](../decisions/collection-downloads-14-09-2026.md).

## 10. `POST /download/{songName}` is gone, with no deprecation window

- Where: [DownloadController.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java), as of 14-09-2026.
- What/impact: replaced by `POST /download/song/{videoId}` and `POST /download/collection/{id}?type=`. A `naviseerr-client` image older than this server gets a 404 on every download request. Normally a self-hosted service would keep a shim for a release; here the old route inserts a row with no YouTube id, which admission cannot resolve, so every download through it would fail — and a route that reliably produces failures is worse for the user than a 404, because they cannot tell "my server is newer than my client" from "Soulseek had nothing".
- Suggested action: ship the client change alongside the server. Nothing to fix here.

## Related docs

- [download-manager.md](download-manager.md) - the durable state machine these entries reference.
- [slskd-integration.md](slskd-integration.md) - search/candidate/transfer details.

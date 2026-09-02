# Gotchas and Known Issues

> Status: current as of 2026-08-13, branch `durable-download-state-machine`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Foot-guns, latent bugs, and hygiene issues to know before touching related code. Each entry: what, where, impact, suggested action.

## 1. Secrets committed in application.yaml (fixed; keys still need rotating)

- Where: [application.yaml](../../src/main/resources/application.yaml) - `last-fm-service.api_key` and `slskd-service.api_key` used to be hardcoded.
- Status: fixed. Both now read from `./.env` via `spring.config.import` (see [.env.example](../../.env.example)). `slskd-service.api_key` has no default and fails fast; `last-fm-service.api_key` carries a placeholder default because Last.fm is dormant (see #7) and must not block startup.
- Still outstanding: the keys that were previously committed are in the git history, so they remain exposed and should be rotated. Do not add new secrets to tracked files.

## 2. Track matching's hyphen split is now a documented degraded fallback, not the primary strategy (updated 31-08-2026)

- Where: [TrackMatchingService.extractParts](../../src/main/java/com/catacomb5099/naviseerr/util/TrackMatchingService.java), reached via `isMatchByLegacySplit`.
- What changed: this used to be the only matching strategy, guessing an artist by splitting the song string on `"-"` (noted as a TODO). The song-metadata-table plan gave `TrackMatchingService.isMatch` real artist metadata (`TrackQuery.artists()`) to work with instead, so the hyphen split now runs only when `artists` is empty - the deprecated `POST /download/{songName}` route, and any row backfilled by `V5__song_metadata.sql` with no artist to carry over. See [slskd-integration.md](slskd-integration.md#track-matching) and [docs/decisions/song-metadata-table-31-08-2026.md](../decisions/song-metadata-table-31-08-2026.md).
- Remaining impact, unchanged for that fallback path only: splitting on a single `"-"` still misparses a title that contains its own hyphen (e.g. `"Twenty-One"`); the fuzzy ratio checks still apply on top, so matching degrades rather than breaks outright.
- Suggested action: none required as a bug fix - this is now intentional, accepted behaviour for callers with no artist metadata to hand in. It goes away entirely in a future release, alongside `V7`, once the deprecated route is removed (see #9 below).

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

## 9. `POST /download/{songName}` is deprecated - delete once `naviseerr-client` no longer needs it (31-08-2026)

- Where: [DownloadController.download](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java), `@Deprecated`.
- What/impact: kept working exactly as before (empty artists, no image) only so a self-hoster who pulls a new server image can still be running an old client image for a while. It is not equivalent to `POST /download` - a song title containing `/` cannot be represented as a path variable under any encoding, so such songs are simply unrequestable through this route.
- Suggested action: delete this mapping, and the `extractParts` fallback it is the only real caller of, in the `V7` release alongside dropping `downloads.song_name`/`download_tasks.song_name` (#10 below) - once `naviseerr-client` (`src/api/endpoints.ts`, `src/components/SongCard.tsx`) no longer posts to it. See `docs/decisions/song-metadata-table-31-08-2026.md` and the plan's "Next release (V7)" section.

## 10. `downloads.song_name` and `download_tasks.song_name` are dead columns awaiting `V7` removal (31-08-2026)

- Where: `downloads.song_name`, `download_tasks.song_name` - both nullable since `V5__song_metadata.sql`.
- What/impact: no *production* code path writes or reads either column any more. The write path inserts into `songs` instead (`DownloadService.requestDownload`); the admit/claim SQL and the download feed both join `songs` for the name. The columns exist purely so a rollback to a pre-`songs`-table server image still has a column to read - see `docs/decisions/song-metadata-table-31-08-2026.md`'s "expand now, contract next release" decision.
- The one exception: `DownloadService.claimPendingDownloads` (`CLAIM_PENDING_SQL`) still selects `song_name`, and `Download.songName` (the `@Column("song_name")`-annotated field on the `Download` entity) still round-trips it whenever a `Download` reads/writes through Spring Data. Both are already dead code - `claimPendingDownloads` has no production caller (see the plan's "Not in this plan" section) - exercised today only by `DownloadServiceClaimIT`. They were deliberately left alone by this plan, out of scope.
- Suggested action: **do not reintroduce a write or a read of either column.** A future `V7` migration drops both, alongside removing the deprecated route (#9) and the `extractParts` fallback. Whoever writes that `V7` migration must **also** remove `DownloadService.claimPendingDownloads`/`CLAIM_PENDING_SQL` and `Download.songName` in the same pass - dropping the column without also removing those two breaks `claimPendingDownloads` and `Download` entity mapping at runtime (`DownloadServiceClaimIT` would fail first). If you find yourself about to write to `downloads.song_name` or `download_tasks.song_name`, that is very likely a sign the code should be writing to `songs` instead.

## 11. Rolling back past `V6` after it has run silently stops admitting new downloads (02-09-2026)

- Where: `download_tasks.song_id` (`NOT NULL` as of [V6__download_tasks_song_id_not_null.sql](../../src/main/resources/db/migration/V6__download_tasks_song_id_not_null.sql)); the admit statement in whatever pre-`V6` server image is being rolled back to.
- What/impact: a pre-`V6` (in fact pre-`V5`) server image's admit statement never wrote `download_tasks.song_id` at all - it wrote `song_name` instead, since `song_id` didn't exist in its schema. If a self-hoster pulls that old image back after their database has already run `V5` and `V6`, every insert that old image's admit step attempts now hits `download_tasks.song_id`'s `NOT NULL` constraint with nothing to put there, and fails. The `downloads` row the old code created stays `PENDING` forever - there is no error surfaced to the user, only a repeating log line from the failed admit insert.
- Suggested action: before rolling back to a pre-`V6` image against a database that has already run `V6`, either run `ALTER TABLE download_tasks ALTER COLUMN song_id DROP NOT NULL`, or roll back Flyway's migration history itself past `V6` (and ideally `V5`). See `docs/decisions/song-metadata-table-31-08-2026.md`'s "expand now, tighten within the release, contract next release" decision for the full reasoning; that decision's rollback story covers reads correctly but this write-path gap is the part it doesn't mention.

## Related docs

- [download-manager.md](download-manager.md) - the durable state machine these entries reference.
- [slskd-integration.md](slskd-integration.md) - search/candidate/transfer details.
- [docs/decisions/song-metadata-table-31-08-2026.md](../decisions/song-metadata-table-31-08-2026.md) - the `songs` table, the deprecation window, and the V5/V6/V7 migration sequence entries #9 and #10 reference.

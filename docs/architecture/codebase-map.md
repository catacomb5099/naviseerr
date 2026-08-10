# Codebase Map

> Status: current as of 2026-06-29, branch `event-driven-download-queue`, with the search-provider
> repoint (10-08-2026, branch `more-agent-docs`) layered on top — see
> [ytmusic-integration.md](ytmusic-integration.md). Agent-oriented guide - the cited source files
> are the source of truth; verify before relying.

Orientation for the Naviseerr backend: where things live, the entry points, the branch topology, and how to build and run.

## Top-level layout

- `src/main/java/com/catacomb5099/naviseerr/` - application code (base package below).
- `src/main/resources/` - [application.yaml](../../src/main/resources/application.yaml) (config) and [schema.sql](../../src/main/resources/schema.sql) (DB schema applied on startup).
- `src/test/java/com/catacomb5099/naviseerr/` - tests (see [testing.md](testing.md)).
- [build.gradle](../../build.gradle) - Gradle build (Java 21, Spring Boot 4, R2DBC, fuzzywuzzy, Lombok, Testcontainers).
- [compose.yaml](../../compose.yaml) - local Postgres 16 for development.
- `docs/` - `architecture/` (this folder), `decisions/` (ADRs), `conversations/` (session logs).

## Package map (`com.catacomb5099.naviseerr`)

- [NaviseerrApplication.java](../../src/main/java/com/catacomb5099/naviseerr/NaviseerrApplication.java) - `@SpringBootApplication` entry point. Plain (no `@EnableScheduling`/`@EnableAsync`).
- `config/`
  - [WebConfig.java](../../src/main/java/com/catacomb5099/naviseerr/config/WebConfig.java) - `@EnableWebFlux` + wide-open CORS (`*`). See the note in [gotchas.md](gotchas.md).
- `schema/response/` - API response DTOs returned to clients: `Track`, `Album`, `Artist`, `SearchResponse`.
- `schema/slskd/` - slskd API DTOs: `SearchState`, `SearchResponseItem`, `SearchFile`, `TransferedFile`, `QueueDownloadResponse`, and the `TransferState` enum. Details in [slskd-integration.md](slskd-integration.md).
- `services/`
  - [SearchService.java](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java) - `@RestController` for the search endpoints. Backed by `YtMusicService` as of 10-08-2026 (was LastFM).
  - `ytmusic/` - `YtMusicService`, `YtMusicConfig`, `YtMusicSearchType`, `YtMusicException`/`YtMusicBadRequestException`/`YtMusicUnavailableException`, `model/YtMusicSearchResponse`. The active search provider. See [ytmusic-integration.md](ytmusic-integration.md).
  - `lastfm/` - `LastFMService`, `LastFMConfig`, `model/LastFmSearchResponse`. **Deprecated, unused, retained on disk** — see [lastfm-integration.md](lastfm-integration.md) and the [ADR](../decisions/ytmusic-search-provider-10-08-2026.md).
  - `slskd/` - `SlskdService`, `SlskdConfig`, `SlskdSearchResultProcessor`, `SlskdDownloadProcessor`. See [slskd-integration.md](slskd-integration.md).
- `download/` - the download manager (this branch's focus): `Download`, `DownloadStatus`, `DownloadController`, `DownloadService`, `DownloadQueue`, `DownloadFulfillment`, `DownloadWorker`, `PendingDownloadRunner`. See [download-manager.md](download-manager.md) and [persistence.md](persistence.md).
- `util/`
  - `YtMusicSearchResponseMapper` - maps `YtMusicSearchResponse` to the `schema/response/` DTOs. Active mapping path; see [ytmusic-integration.md](ytmusic-integration.md).
  - `LastFMAPIMethod`, `LastFMAPIMethodHelper`, `SearchResponseMapper` - LastFM helpers/mapping. Unused; see above.
  - [TrackMatchingService.java](../../src/main/java/com/catacomb5099/naviseerr/util/TrackMatchingService.java) - fuzzy match (fuzzywuzzy) of a track title against a slskd filename.
  - [TransferedFileUtil.java](../../src/main/java/com/catacomb5099/naviseerr/util/TransferedFileUtil.java) - parses the slskd transfer state string into `TransferState`s (has a known bug - see [gotchas.md](gotchas.md)).
  - `networkcalls/ReactivePoller.java` - the reactive polling/retry engine. See [reactive-patterns.md](reactive-patterns.md).

## Entry points / HTTP endpoints

- [SearchService.java](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java) (`@RestController`):
  - `GET /search/{query}` - combined search (YouTube Music)
  - `GET /search/{query}/tracks` | `/albums` | `/artists` - per-type search (YouTube Music)
- [DownloadController.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java) (`@RestController`):
  - `POST /download/{songName}` - inserts a `PENDING` download row, returns `202 Accepted`; processed asynchronously (see [download-manager.md](download-manager.md)).

Spring beans inventory: `@RestController` x2 (`SearchService`, `DownloadController`); `@Service` (`YtMusicService`, `LastFMService` [unused], `SlskdService`, `TrackMatchingService`, `DownloadService`, `DownloadFulfillment`); `@Component` (`LastFMAPIMethodHelper`, `SlskdSearchResultProcessor`, `SlskdDownloadProcessor`, `DownloadQueue`, `DownloadWorker`, `PendingDownloadRunner`); `@Configuration` (`WebConfig`, `YtMusicConfig`, `LastFMConfig` [unused], `SlskdConfig`).

A separate, sibling-repo service is also part of the runtime picture now: `ytmusic-adapter`
(`~/IdeaProjects/ytmusic-adapter`), a Python/FastAPI process wired into [compose.yaml](../../compose.yaml).
See [ytmusic-integration.md](ytmusic-integration.md).

## Branch topology

The download work was built up across branches; the Cursor/IDE index can be stale across them, so verify against the checked-out tree.

- `master` - LastFM search + slskd processor classes, fully `Mono`-based. No database. (On `master`, `SearchService` also contained the slskd download orchestration; that was removed on the DB branches.)
- `background-runner-pending-downloads` - adds Spring Data R2DBC + Postgres, the `downloads` table, the `Download`/`DownloadStatus` model, `DownloadController` + `DownloadService`, and an interval `PendingDownloadRunner` that claimed pending rows (logged only).
- `event-driven-download-queue` (current) - turns the claimer into a producer feeding an in-memory queue consumed by a worker that runs the real slskd flow and writes terminal status. See [download-manager.md](download-manager.md).

## Build and run

- Build/test: `./gradlew build` / `./gradlew test` (Java 21 toolchain). Tests that boot Spring need Docker (Testcontainers) - see [testing.md](testing.md).
- Run locally: `./gradlew bootRun`. `spring-boot-docker-compose` is a `developmentOnly` dependency, so `bootRun` auto-starts the Postgres in [compose.yaml](../../compose.yaml). It is NOT on the test classpath, so tests do not get it (they use Testcontainers).
- Config: [application.yaml](../../src/main/resources/application.yaml) holds `spring.r2dbc.*`, `spring.sql.init.*`, `last-fm-service.*` (unused), `yt-music-service.*`, `slskd-service.*`, `download-runner.*`, `download-worker.*`. Note secrets are currently committed there - see [gotchas.md](gotchas.md).

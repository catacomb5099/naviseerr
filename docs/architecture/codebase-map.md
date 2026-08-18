# Codebase Map

> Status: current as of 2026-08-13, branch `durable-download-state-machine`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Orientation for the Naviseerr backend: where things live, the entry points, the branch topology, and how to build and run.

## Top-level layout

- `src/main/java/com/catacomb5099/naviseerr/` - application code (base package below).
- `src/main/resources/` - [application.yaml](../../src/main/resources/application.yaml) (config) and `db/migration/` (Flyway-versioned DB schema, applied at boot over a blocking JDBC connection used only for that; see [persistence.md](persistence.md)).
- `src/test/java/com/catacomb5099/naviseerr/` - tests (see [testing.md](testing.md)).
- [build.gradle](../../build.gradle) - Gradle build (Java 21, Spring Boot 4, R2DBC, fuzzywuzzy, Lombok, Testcontainers).
- [compose.yaml](../../compose.yaml) - local Postgres 16 for development.
- `docs/` - `architecture/` (this folder), `decisions/` (ADRs), `conversations/` (session logs).

## Package map (`com.catacomb5099.naviseerr`)

- [NaviseerrApplication.java](../../src/main/java/com/catacomb5099/naviseerr/NaviseerrApplication.java) - `@SpringBootApplication` entry point. Plain (no `@EnableScheduling`/`@EnableAsync`).
- `config/`
  - [WebConfig.java](../../src/main/java/com/catacomb5099/naviseerr/config/WebConfig.java) - `@EnableWebFlux` + wide-open CORS (`*`). See the note in [gotchas.md](gotchas.md).
  - [TimeConfig.java](../../src/main/java/com/catacomb5099/naviseerr/config/TimeConfig.java) - the `Clock` bean (`Clock.systemUTC()`), injected wherever "now" matters so budget/lease branches are unit-testable without sleeping.
- `schema/response/` - API response DTOs returned to clients: `Track`, `Album`, `Artist`, `SearchResponse`.
- `schema/slskd/` - slskd API DTOs: `SearchState`, `SearchResponseItem`, `SearchFile`, `TransferedFile`, `QueueDownloadResponse`, the `TransferState` enum, and `SlskdSearchState` (search-state classification, added with the durable-download-state-machine work). Details in [slskd-integration.md](slskd-integration.md).
- `services/`
  - [SearchService.java](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java) - `@RestController` for the LastFM search endpoints (LastFM only on this branch).
  - `lastfm/` - `LastFMService`, `LastFMConfig`, `model/LastFmSearchResponse`. See [lastfm-integration.md](lastfm-integration.md).
  - `slskd/` - `SlskdService`, `SlskdConfig`, `SlskdSearchResultProcessor`. See [slskd-integration.md](slskd-integration.md).
- `download/` - the durable download state machine: `Download`, `DownloadStatus`, `DownloadController`, `DownloadService`, `DownloadCandidate`, `DownloadPhase`, `DownloadTask`, `DownloadDecision`, `DownloadStateMachine`, `DownloadTaskRepository`, `DownloadStepExecutor`, `DownloadTaskRunner`. See [download-manager.md](download-manager.md) and [persistence.md](persistence.md).
- `util/`
  - `LastFMAPIMethod`, `LastFMAPIMethodHelper`, `SearchResponseMapper` - LastFM helpers/mapping.
  - [TrackMatchingService.java](../../src/main/java/com/catacomb5099/naviseerr/util/TrackMatchingService.java) - fuzzy match (fuzzywuzzy) of a track title against a slskd filename.
  - [TransferedFileUtil.java](../../src/main/java/com/catacomb5099/naviseerr/util/TransferedFileUtil.java) - parses the slskd transfer state string into `TransferState`s, matching on `getValue()` (the fix landed with this work - see [slskd-integration.md](slskd-integration.md)).

## Entry points / HTTP endpoints

- [SearchService.java](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java) (`@RestController`):
  - `GET /search/{query}` - combined LastFM search
  - `GET /search/{query}/tracks` | `/albums` | `/artists` - per-type LastFM search
- [DownloadController.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java) (`@RestController`):
  - `POST /download/{songName}` - inserts a `PENDING` download row, returns `202 Accepted`; processed asynchronously (see [download-manager.md](download-manager.md)).

Spring beans inventory: `@RestController` x2 (`SearchService`, `DownloadController`); `@Service` (`LastFMService`, `SlskdService`, `TrackMatchingService`, `DownloadService`); `@Component` (`LastFMAPIMethodHelper`, `SlskdSearchResultProcessor`, `DownloadTaskRunner`, `DownloadStepExecutor`, `DownloadStateMachine`); `@Repository` (`DownloadTaskRepository`); `@Configuration` (`WebConfig`, `TimeConfig`, `LastFMConfig`, `SlskdConfig`).

## Branch topology

The download work was built up across branches; the Cursor/IDE index can be stale across them, so verify against the checked-out tree.

- `master` - LastFM search + slskd processor classes, fully `Mono`-based. No database. (On `master`, `SearchService` also contained the slskd download orchestration; that was removed on the DB branches.)
- `background-runner-pending-downloads` - adds Spring Data R2DBC + Postgres, the `downloads` table, the `Download`/`DownloadStatus` model, `DownloadController` + `DownloadService`, and an interval `PendingDownloadRunner` that claimed pending rows (logged only).
- `event-driven-download-queue` - turned the claimer into a producer feeding an in-memory queue consumed by a worker that ran the real slskd flow and wrote terminal status.
- `durable-download-state-machine` (current) - deletes the in-heap queue, the interval claimer, and the `ReactivePoller`-based poll-until-done chains entirely. Replaces them with a level-triggered Postgres loop (`DownloadTaskRunner`) over a new `download_tasks` table, so a restart at any point resumes rather than strands a download. See [download-manager.md](download-manager.md) and [docs/decisions/durable-download-state-machine-13-08-2026.md](../decisions/durable-download-state-machine-13-08-2026.md).

## Build and run

- Build/test: `./gradlew build` / `./gradlew test` (Java 21 toolchain). Tests that boot Spring need Docker (Testcontainers) - see [testing.md](testing.md).
- Run locally: `./gradlew bootRun`. `spring-boot-docker-compose` is a `developmentOnly` dependency, so `bootRun` auto-starts the Postgres in [compose.yaml](../../compose.yaml). It is NOT on the test classpath, so tests do not get it (they use Testcontainers). Flyway runs its migrations against that same Postgres at boot, over a blocking JDBC connection used only for that.
- Config: [application.yaml](../../src/main/resources/application.yaml) holds `spring.r2dbc.*`, `spring.flyway.*`, `last-fm-service.*`, `slskd-service.*`, `download-task.*`. Note secrets are currently committed there - see [gotchas.md](gotchas.md).

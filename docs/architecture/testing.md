# Testing

> Status: current as of 2026-06-29, branch `event-driven-download-queue`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

How tests are structured and how to run them. Two layers: fast unit tests (no Spring, no DB) and Spring integration tests backed by Testcontainers Postgres.

## Frameworks

- JUnit 5 (JUnit Platform).
- reactor-test `StepVerifier` for asserting reactive streams.
- Mockito for mocking. Note: [build.gradle](../../build.gradle) declares `io.mockk:mockk` and a "MockK" comment, but the actual Java tests use Mockito (`org.mockito.*`). Prefer Mockito for consistency with existing tests.
- Testcontainers (`postgresql`, `r2dbc`, `junit-jupiter`) for integration tests.

## Unit tests (no Spring context)

Construct the class under test directly, mock collaborators, assert with `StepVerifier`. Examples:

- [DownloadFulfillmentTest.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadFulfillmentTest.java) - mocks the two slskd processors; verifies the search -> select -> download chain (no download poll when no candidates; empty search short-circuits; success emits the `TransferedFile`).
- [DownloadWorkerTest.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadWorkerTest.java) - mocks `DownloadFulfillment` + `DownloadService`; verifies success -> `SUCCEEDED`, error -> `FAILED`, empty -> `FAILED`, and that a status-write failure is isolated (process still completes).
- [DownloadQueueTest.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadQueueTest.java) - uses `StepVerifier ... expectNoEvent(...)` to prove the queue sleeps while empty, then delivers on `enqueue`.
- [PendingDownloadRunnerTest.java](../../src/test/java/com/catacomb5099/naviseerr/download/PendingDownloadRunnerTest.java) - verifies claimed rows are enqueued and a claim error is swallowed.
- Also: `services/slskd/SlskdSearchResultProcessorTest`, `services/slskd/SlskdDownloadProcessorTest`, `util/networkcalls/ReactivePollerTest`, `util/TransferedFileUtilTest`.

### Testability convention: package-private methods

Methods that are an internal detail but worth unit-testing are left package-private (no modifier) and tested from a test class in the same package - e.g. `DownloadWorker.process` and `PendingDownloadRunner.processBatch`. Avoid reflection; keep the test in the same package instead.

## Integration tests (Spring + Testcontainers)

- [TestcontainersConfiguration.java](../../src/test/java/com/catacomb5099/naviseerr/TestcontainersConfiguration.java) - a `@TestConfiguration` exposing a `PostgreSQLContainer` bean annotated `@ServiceConnection`, so Spring Boot wires `spring.r2dbc.*` to the container automatically.
- [DownloadServiceClaimIT.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadServiceClaimIT.java) - `@SpringBootTest @Import(TestcontainersConfiguration.class)`; covers `claimPendingDownloads` (SKIP LOCKED batch claim, no double-claim) and `markStatus` (IN_PROGRESS guard). It sets `download-runner.interval-ms=3600000` to keep the background claimer from interfering during the test.
- [NaviseerrApplicationTests.java](../../src/test/java/com/catacomb5099/naviseerr/NaviseerrApplicationTests.java) - `contextLoads`; now also imports `TestcontainersConfiguration` so it boots against a container (otherwise it would need an external DB).

## Running

- All tests: `./gradlew test`. The `@SpringBootTest`/Testcontainers tests require a running Docker daemon; the first run pulls `postgres:16-alpine`.
- Unit tests only (no Docker): filter, e.g. `./gradlew test --tests "com.catacomb5099.naviseerr.download.DownloadWorkerTest" --tests "com.catacomb5099.naviseerr.services.slskd.*" --tests "com.catacomb5099.naviseerr.util.*"`.
- Per-class results land in `build/test-results/test/*.xml`.

## When to add tests

Per `AGENTS.md`: add or update tests when changing matching, polling, download orchestration, cancellation, or state transitions. Documentation-only changes need no Gradle run.

## Gotcha

`@SpringBootTest` tests fail without Docker (Testcontainers can't start Postgres). See [gotchas.md](gotchas.md).

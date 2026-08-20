# Testing

> Status: current as of 2026-08-13, branch `durable-download-state-machine`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

How tests are structured and how to run them. Two layers: fast unit tests (no Spring, no DB) and Spring integration tests backed by Testcontainers Postgres.

## Frameworks

- JUnit 5 (JUnit Platform).
- reactor-test `StepVerifier` for asserting reactive streams.
- Mockito for mocking. Note: [build.gradle](../../build.gradle) declares `io.mockk:mockk` and a "MockK" comment, but the actual Java tests use Mockito (`org.mockito.*`). Prefer Mockito for consistency with existing tests.
- Testcontainers (`postgresql`, `r2dbc`, `junit-jupiter`) for integration tests.

## Unit tests (no Spring context)

Construct the class under test directly, mock collaborators, assert with `StepVerifier`. Examples:

- [DownloadStateMachineTest.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadStateMachineTest.java) - `DownloadStateMachine` is a pure function (no fields, no I/O, no clock of its own), so its whole branch matrix - `Advance`/`Continue`/`Terminal` per phase, budget timeouts, retry-then-next-candidate - is tested with plain JUnit assertions against a fixed `Instant`, no mocking of HTTP or the clock.
- [DownloadStepExecutorTest.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadStepExecutorTest.java) - mocks `SlskdService`/`SlskdSearchResultProcessor`; verifies each phase makes the right call (or reads from the batched maps instead), and that an slskd failure never propagates as an error signal (it becomes `DownloadStateMachine.onCallFailed`).
- [DownloadTaskRunnerTest.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRunnerTest.java) - mocks the repository/executor/slskd service; verifies the admit/claim/step pass shape, the two capacity bounds, and that stepping claimed rows is isolated (one bad step doesn't abort the rest of the pass).
- Also: `services/slskd/SlskdSearchResultProcessorTest`, `schema/slskd/SlskdSearchStateTest`, `util/TransferedFileUtilTest`.

### Testability convention: package-private methods, and a clock bean

Methods that are an internal detail but worth unit-testing are left package-private (no modifier) and tested from a test class in the same package. Avoid reflection; keep the test in the same package instead. `TimeConfig` exposes `Clock` as a Spring bean specifically so budget/lease logic (`DownloadTask.isPastBudget`, lease expiry) can be driven by a fixed or mocked clock in tests instead of real sleeps.

## Integration tests (Spring + Testcontainers)

- [TestcontainersConfiguration.java](../../src/test/java/com/catacomb5099/naviseerr/TestcontainersConfiguration.java) - a `@TestConfiguration` exposing a `PostgreSQLContainer` bean annotated `@ServiceConnection`, so Spring Boot wires `spring.r2dbc.*` to the container automatically. Flyway runs its own migrations against the same container at boot.
- [DownloadServiceClaimIT.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadServiceClaimIT.java) - `@SpringBootTest @Import(TestcontainersConfiguration.class)`; still covers the pre-existing `claimPendingDownloads`/`markStatusIfInProgress` methods on `DownloadService`, which remain but are no longer called by the pass loop (kept for their own coverage; superseded by `DownloadTaskRepository.admitNewDownloads`/`claimDueTasks` and `DownloadService.finishDownload`, covered below).
- [DownloadTaskRepositoryIT.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRepositoryIT.java) - covers the admit CTE (creates a task, flips `PENDING`/orphaned `IN_PROGRESS` to `IN_PROGRESS`, ignores rows that already have a task or are terminal) and the lease-based claim (due rows only, skips a live lease, reclaims an expired one).
- [DownloadRecoveryIT.java](../../src/test/java/com/catacomb5099/naviseerr/download/DownloadRecoveryIT.java) - the crash-recovery scenarios end-to-end: a download killed mid-transfer resumes at the same step rather than from scratch; an `IN_PROGRESS` download that lost its task row is recovered rather than stranded forever; a terminal download is never re-admitted or re-stepped.
- All of the above (and `DownloadServiceClaimIT`) set `download-task.loop-interval-ms=3600000` via `@TestPropertySource` to keep the background `DownloadTaskRunner` from interfering while the test drives things directly.
- [NaviseerrApplicationTests.java](../../src/test/java/com/catacomb5099/naviseerr/NaviseerrApplicationTests.java) - `contextLoads`; now also imports `TestcontainersConfiguration` so it boots against a container (otherwise it would need an external DB).

## Running

- All tests: `./gradlew test`. The `@SpringBootTest`/Testcontainers tests require a running Docker daemon; the first run pulls `postgres:16-alpine`.
- Unit tests only (no Docker): filter, e.g. `./gradlew test --tests "com.catacomb5099.naviseerr.download.DownloadStateMachineTest" --tests "com.catacomb5099.naviseerr.services.slskd.*" --tests "com.catacomb5099.naviseerr.util.*"`.
- Per-class results land in `build/test-results/test/*.xml`.

## When to add tests

Per `AGENTS.md`: add or update tests when changing matching, polling, download orchestration, cancellation, or state transitions. Documentation-only changes need no Gradle run.

## Gotcha

`@SpringBootTest` tests fail without Docker (Testcontainers can't start Postgres). See [gotchas.md](gotchas.md).

# Durable Download State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the download pipeline's working state out of the JVM heap and into a `download_tasks` table driven by a level-triggered loop, so a download resumes from its exact step after a naviseerr restart and no row is ever stranded at `IN_PROGRESS`.

**Architecture:** Postgres is the workflow engine. One interval loop per pass: admit non-terminal downloads that have no task row, claim task rows whose `next_attempt_at` has passed and which hold no live lease (`FOR UPDATE SKIP LOCKED`), fetch slskd's search and transfer lists **once for the whole pass** (not once per row), then step every claimed row **concurrently** against those two maps, feed each response to a pure state machine, and write the resulting position back in one statement. Leases replace a stale-row reaper; `next_attempt_at` replaces retry-operator backoff; the in-heap queue is deleted; the two batched slskd calls replace one call per download per poll.

**Tech Stack:** Java 21, Spring Boot 4.0.2, Spring WebFlux + Reactor, Spring Data R2DBC over Postgres, Flyway (schema-only, boot-time; the runtime path stays on R2DBC), Gradle, Lombok, JUnit 5, Mockito (via `spring-boot-starter-webflux-test`), Testcontainers.

**Design spec:** [docs/superpowers/specs/2026-08-13-durable-download-state-machine-design.md](../specs/2026-08-13-durable-download-state-machine-design.md). Read it before Task 1.

## Global Constraints

- Base branch is `master` at `2ef2727`. Create a branch; do not commit to `master`.
- No new *runtime* infrastructure. **No RabbitMQ, no Redis, ever.** Flyway is the one exception to "no new dependencies" — see Task 3 — and it needs a blocking JDBC driver used only to run migrations at startup; the runtime path stays entirely on R2DBC.
- No change to the `downloads` table's existing columns or the `Download` entity in this plan. All new state lives in a new `download_tasks` table. Schema management itself moves from `schema.sql` + `spring.sql.init` to Flyway migrations under `src/main/resources/db/migration/` (Task 3) — this move is deliberate, not a relaxation of scope, because widening `downloads.status`'s `CHECK` constraint for collections and cancellation is coming and is not expressible idempotently in `schema.sql`.
- Keep the runtime path reactive. No blocking calls (`.block()`, `.toFuture().get()`) in production code.
- `SlskdSearchResultProcessor.selectBestFiles`, `isRelevant`, `isFlacAndHighBitrate` and `TrackMatchingService` must remain byte-for-byte unchanged, including the existing case-sensitive `"flac"` check. Their tests must pass unmodified — that is the guard that this work did not touch ranking.
- Add no new `DownloadStatus` values. Timeouts are `FAILED` plus a reason string carried in `DownloadDecision.Terminal.message()` and logged, not persisted.
- Follow the existing field-level `@Value` convention; this project has no `@ConfigurationProperties` class.
- Config keys use the existing `slskd-service.retry-count` (2) as the per-candidate retry limit and `slskd-service.max-files-per-download` (10) as the candidate cap. Do not duplicate them.
- Run `./gradlew cleanTest test` for verification, never a bare `./gradlew test` — a bare run reports `UP-TO-DATE` and executes nothing, which reads as a pass.
- Do not commit secrets. `application.yaml` already contains committed API keys; leave them alone, do not add more.

---

## File Structure

**Created — production**

| File | Responsibility |
|---|---|
| `src/main/java/com/catacomb5099/naviseerr/config/TimeConfig.java` | `Clock` bean, so budget/lease branches are unit-testable |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadPhase.java` | the four step names |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadCandidate.java` | one persistable (peer, file) candidate; converts to/from `SearchFile` |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java` | in-memory carrier for one `download_tasks` row |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadDecision.java` | sealed `Advance` / `Continue` / `Terminal` |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java` | **pure** — all branch logic, no I/O |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java` | all `download_tasks` SQL |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadStepExecutor.java` | I/O shell: one slskd call per phase (batched for the two poll phases) |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java` | the interval loop: admit, claim, step, apply |
| `src/main/java/com/catacomb5099/naviseerr/schema/slskd/SlskdSearchState.java` | slskd search-state classification |
| `src/main/resources/db/migration/V1__baseline.sql` | the existing `downloads` table, moved out of `schema.sql` |
| `src/main/resources/db/migration/V2__download_tasks.sql` | the `download_tasks` DDL |

**Modified — production**

| File | Change |
|---|---|
| `build.gradle` | add Flyway + a blocking Postgres driver (boot-only) |
| `src/main/resources/application.yaml` | add `download-task.*` (two capacity limits, per-phase intervals/budgets); replace `spring.sql.init` with `spring.flyway.*`; remove `download-runner.*`, `download-worker.*`, `slskd-service.max-poll-attempts`, `slskd-service.first-back-off-duration-ms`; add an operator-facing comment on pinning slskd's *own* `transfers.download.retry.attempts` (Task 5 Step 1 — naviseerr has no config key of its own for this, since it isn't naviseerr's setting) |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java` | add `finishDownload` (atomic CTE, retains the task row) |
| `src/main/java/com/catacomb5099/naviseerr/util/TransferedFileUtil.java` | match on `getValue()`, not `name()` |
| `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessor.java` | delete `pollUntilComplete` and its now-unused `@Value` fields |
| `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdDownloadProcessor.java` | delete `pollUntilComplete`; the class becomes unused and is deleted |
| `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdService.java` | add `getAllSearches()` and `getAllDownloads()` — the two batched polls |

**Deleted — production:** `src/main/resources/schema.sql` (superseded by the Flyway migrations above), `download/DownloadQueue.java`, `download/DownloadWorker.java`, `download/PendingDownloadRunner.java`, `download/DownloadFulfillment.java`, `util/networkcalls/ReactivePoller.java`, `services/slskd/SlskdDownloadProcessor.java`.

**Created — test:** `test/.../support/SlskdFixtures.java`, `test/.../support/DownloadTaskFixtures.java`, `download/DownloadStateMachineTest.java`, `download/DownloadStepExecutorTest.java`, `download/DownloadTaskRunnerTest.java`, `download/DownloadTaskRepositoryIT.java`, `download/DownloadRecoveryIT.java`, `schema/slskd/SlskdSearchStateTest.java`.

**Deleted — test:** `download/DownloadFulfillmentTest.java`, `download/DownloadQueueTest.java`, `download/DownloadWorkerTest.java`, `download/PendingDownloadRunnerTest.java`, `util/networkcalls/ReactivePollerTest.java`, `services/slskd/SlskdDownloadProcessorTest.java`, and the four `pollUntilComplete_*` tests inside `SlskdSearchResultProcessorTest`.

---

### Task 0: Branch

- [ ] **Step 1: Create the branch from master**

```bash
git checkout master && git pull && git checkout -b durable-download-state-machine
```

- [ ] **Step 2: Confirm the baseline suite is green before changing anything**

Run: `./gradlew cleanTest test`
Expected: `BUILD SUCCESSFUL`. If it is not green on `master`, stop and report — do not build on a red baseline.

---

### Task 1: Decision core (pure, unwired)

The largest test commit. Nothing calls any of this yet, so it cannot change runtime behaviour. Landing it first means every later task has a tested decision function to build on.

**Files:**
- Create: `src/main/java/com/catacomb5099/naviseerr/config/TimeConfig.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadPhase.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadCandidate.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadDecision.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java`
- Create: `src/test/java/com/catacomb5099/naviseerr/support/SlskdFixtures.java`
- Create: `src/test/java/com/catacomb5099/naviseerr/support/DownloadTaskFixtures.java`
- Test: `src/test/java/com/catacomb5099/naviseerr/download/DownloadStateMachineTest.java`

**Interfaces:**
- Consumes: existing `DownloadStatus`, `SearchState`, `SearchFile`, `SearchResponseItem`, `QueueDownloadResponse`, `TransferedFile`, `TransferState`, `TransferedFileUtil`.
- Produces:
  - `enum DownloadPhase { SEARCH_INIT, SEARCH_POLL, DOWNLOAD_INIT, DOWNLOAD_POLL }`
  - `record DownloadCandidate(String username, String filename, String extension, Integer bitRate, long size, long code, Boolean isLocked)` with `static DownloadCandidate from(Map.Entry<SearchResponseItem, SearchFile>)` and `SearchFile toSearchFile()`
  - `record DownloadTask(UUID downloadId, String songName, DownloadPhase phase, Instant phaseEnteredAt, Instant nextAttemptAt, String searchId, List<DownloadCandidate> candidates, int candidateIndex, int retryIndex, String slskdUsername, String slskdFilename, String slskdTransferId, String lastError)` with `static DownloadTask initial(UUID, String, Instant)`, `DownloadTask withPhase(DownloadPhase, Instant)`, `DownloadTask dueAt(Instant)`, `DownloadCandidate currentCandidate()`. No `enqueueAttempted` field — see the note below Step 4.
  - `sealed interface DownloadDecision` with `record Advance(DownloadTask next)`, `record Continue(DownloadTask next)`, `record Terminal(DownloadStatus status, String message)`
  - `DownloadStateMachine` with `afterSearchInit`, `afterSearchPoll`, `afterDownloadInit`, `afterDownloadPoll`, `onCallFailed` (exact signatures in Step 5)

- [ ] **Step 1: Add the `Clock` bean**

Without an injected `Clock`, none of the timeout branches can be tested without sleeping.

```java
package com.catacomb5099.naviseerr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 2: Add `DownloadPhase`**

```java
package com.catacomb5099.naviseerr.download;

/**
 * The four steps a download moves through. Each is exactly one slskd call, so a crash costs at
 * most one call's worth of work.
 */
public enum DownloadPhase {
    SEARCH_INIT,
    SEARCH_POLL,
    DOWNLOAD_INIT,
    DOWNLOAD_POLL
}
```

- [ ] **Step 3: Add `DownloadCandidate`**

Carries exactly the fields `SlskdService.enqueueDownload(String, SearchFile)` needs, so a candidate chosen before a restart can still be enqueued after it. Note `SearchFile`'s constructor order is `(filename, size, code, isLocked, extension, bitRate)`.

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;

import java.util.Map;
import java.util.Optional;

/**
 * One (peer, file) candidate, flattened into a persistable shape. Deliberately not
 * {@code Map.Entry<SearchResponseItem, SearchFile>}: that does not serialise cleanly and carries far
 * more of the slskd response than resuming needs.
 */
public record DownloadCandidate(
        String username,
        String filename,
        String extension,
        Integer bitRate,
        long size,
        long code,
        Boolean isLocked) {

    public static DownloadCandidate from(Map.Entry<SearchResponseItem, SearchFile> entry) {
        SearchFile file = entry.getValue();
        return new DownloadCandidate(
                entry.getKey().getUsername(),
                file.getFilename(),
                file.getExtension(),
                file.getBitRate().orElse(null),
                file.getSize(),
                file.getCode(),
                file.getIsLocked());
    }

    public SearchFile toSearchFile() {
        return new SearchFile(filename, size, code, isLocked, extension, Optional.ofNullable(bitRate));
    }
}
```

- [ ] **Step 4: Add `DownloadTask` and `DownloadDecision`**

`withPhase` resets `phaseEnteredAt` (a new phase gets a fresh duration budget); `dueAt` does not (a re-poll must not refresh its own deadline). Getting that backwards makes every timeout unreachable.

**No `enqueueAttempted` field, on purpose.** An earlier draft carried a boolean recording "we committed to calling slskd's enqueue endpoint but never recorded a transfer id" — insurance against a crash in that exact window causing a duplicate download. That insurance was removed by explicit decision: an occasional duplicate file after a crash (bandwidth for one extra download, once, per crash) is an acceptable cost for a self-hosted music downloader, and it is far cheaper than the column, the extra DB write before every enqueue, and the dedicated recovery branch that the insurance required. On restart mid-enqueue, a download simply re-enters `DOWNLOAD_INIT` and calls slskd again — see Task 4.

```java
package com.catacomb5099.naviseerr.download;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-memory carrier for one {@code download_tasks} row. This record IS the durable state, read from
 * and written back to the row on every step. Nothing about a download's position is held between
 * loop passes.
 */
public record DownloadTask(
        UUID downloadId,
        String songName,
        DownloadPhase phase,
        Instant phaseEnteredAt,
        Instant nextAttemptAt,
        String searchId,
        List<DownloadCandidate> candidates,
        int candidateIndex,
        int retryIndex,
        String slskdUsername,
        String slskdFilename,
        String slskdTransferId,
        String lastError) {

    public static DownloadTask initial(UUID downloadId, String songName, Instant now) {
        return new DownloadTask(downloadId, songName, DownloadPhase.SEARCH_INIT, now, now,
                null, List.of(), 0, 0, null, null, null, null);
    }

    /** Phase change: resets the phase budget. */
    public DownloadTask withPhase(DownloadPhase newPhase, Instant now) {
        return new DownloadTask(downloadId, songName, newPhase, now, now, searchId, candidates,
                candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId,
                lastError);
    }

    /** Reschedule within the same phase: preserves the phase budget. */
    public DownloadTask dueAt(Instant next) {
        return new DownloadTask(downloadId, songName, phase, phaseEnteredAt, next, searchId,
                candidates, candidateIndex, retryIndex, slskdUsername, slskdFilename,
                slskdTransferId, lastError);
    }

    public DownloadCandidate currentCandidate() {
        return candidates.get(candidateIndex);
    }

    public boolean isPastBudget(Instant now, java.time.Duration budget) {
        return !now.isBefore(phaseEnteredAt.plus(budget));
    }
}
```

```java
package com.catacomb5099.naviseerr.download;

/**
 * What the state machine decided. Only {@code Advance} runs again immediately; every non-progress
 * outcome is {@code Continue} so it is always rate-limited by the phase's poll interval. Retrying a
 * failed transfer with no delay would hammer slskd.
 */
public sealed interface DownloadDecision {

    /** Genuine phase transition — re-run on the next pass, no delay. */
    record Advance(DownloadTask next) implements DownloadDecision {}

    /** Re-poll, retry the same candidate, or move to the next candidate — re-run after a delay. */
    record Continue(DownloadTask next) implements DownloadDecision {}

    /**
     * Done. Write the download's status and mark the task terminal. {@code message} is the reason,
     * persisted as {@code failure_reason} so a self-hoster can see why. The task row is RETAINED, not
     * deleted — history a self-hoster needs, and free at read time because of a partial index (Task 3).
     */
    record Terminal(DownloadStatus status, String message) implements DownloadDecision {}
}
```

- [ ] **Step 5: Write the failing state machine test**

Create `src/test/java/com/catacomb5099/naviseerr/support/SlskdFixtures.java` first. The slskd DTOs are `@AllArgsConstructor` with 11 and 17 positional arguments and no builder; constructing them inline in tests is unreadable and the DTOs must not be changed to suit tests.

```java
package com.catacomb5099.naviseerr.support;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;

import java.util.List;
import java.util.Optional;

public final class SlskdFixtures {

    private SlskdFixtures() {}

    public static SearchState searchState(String id, boolean complete, String state) {
        return new SearchState(Optional.empty(), 0, id, complete, 0, 0, List.of(),
                "query", "2026-08-13T00:00:00Z", state, 1);
    }

    public static TransferedFile transfer(String id, String username, String state) {
        return new TransferedFile(id, username, "Download", "path/song.flac", 100L, null, state,
                null, null, null, null, 0L, 0f, 100L, null, 0f, null);
    }

    public static QueueDownloadResponse enqueued(String id, String username) {
        return new QueueDownloadResponse(List.of(transfer(id, username, "Queued")), List.of());
    }

    public static QueueDownloadResponse enqueueRejected() {
        return new QueueDownloadResponse(List.of(), List.of(transfer("x", "peer", "Rejected")));
    }
}
```

Create `src/test/java/com/catacomb5099/naviseerr/support/DownloadTaskFixtures.java`:

```java
package com.catacomb5099.naviseerr.support;

import com.catacomb5099.naviseerr.download.DownloadCandidate;
import com.catacomb5099.naviseerr.download.DownloadPhase;
import com.catacomb5099.naviseerr.download.DownloadTask;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DownloadTaskFixtures {

    public static final Instant T0 = Instant.parse("2026-08-13T12:00:00Z");
    public static final UUID ID = UUID.fromString("7f3a0000-0000-0000-0000-000000000001");

    private DownloadTaskFixtures() {}

    public static DownloadCandidate candidate(String username) {
        return new DownloadCandidate(username, "music/" + username + "/song.flac", "flac",
                1411, 1000L, 42L, false);
    }

    public static List<DownloadCandidate> candidates(String... usernames) {
        return java.util.Arrays.stream(usernames).map(DownloadTaskFixtures::candidate).toList();
    }

    public static DownloadTask at(DownloadPhase phase) {
        return DownloadTask.initial(ID, "never gonna give you up", T0).withPhase(phase, T0);
    }

    public static DownloadTask searchPolling(String searchId) {
        DownloadTask base = at(DownloadPhase.SEARCH_POLL);
        return new DownloadTask(base.downloadId(), base.songName(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), searchId, List.of(), 0, 0,
                null, null, null, null);
    }

    public static DownloadTask downloadPolling(List<DownloadCandidate> candidates,
                                              int candidateIndex, int retryIndex,
                                              String transferId) {
        DownloadTask base = at(DownloadPhase.DOWNLOAD_POLL);
        DownloadCandidate current = candidates.get(candidateIndex);
        return new DownloadTask(base.downloadId(), base.songName(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), "s1", candidates, candidateIndex,
                retryIndex, current.username(), current.filename(), transferId, null);
    }

    public static DownloadTask downloadInit(List<DownloadCandidate> candidates,
                                            int candidateIndex, int retryIndex) {
        DownloadTask base = at(DownloadPhase.DOWNLOAD_INIT);
        return new DownloadTask(base.downloadId(), base.songName(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), "s1", candidates, candidateIndex,
                retryIndex, null, null, null, null);
    }
}
```

Now the test. It has no mocks at all — that is the point of keeping the state machine pure.

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadStateMachineTest {

    private static final Duration SEARCH_BUDGET = Duration.ofSeconds(120);
    private static final Duration DOWNLOAD_BUDGET = Duration.ofSeconds(3600);
    private static final Duration SEARCH_POLL = Duration.ofSeconds(2);
    private static final Duration DOWNLOAD_POLL = Duration.ofSeconds(5);
    private static final int RETRY_LIMIT = 2;

    private final DownloadStateMachine machine = new DownloadStateMachine(
            SEARCH_POLL, DOWNLOAD_POLL, SEARCH_BUDGET, DOWNLOAD_BUDGET, RETRY_LIMIT);

    @Test
    void searchInit_recordsSearchId_andAdvancesToSearchPoll() {
        DownloadDecision d = machine.afterSearchInit(
                at(DownloadPhase.SEARCH_INIT), SlskdFixtures.searchState("s1", false, "InProgress"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.SEARCH_POLL, next.phase());
        assertEquals("s1", next.searchId());
    }

    @Test
    void searchInit_withNoSearchId_failsRatherThanPollingNothing() {
        DownloadDecision d = machine.afterSearchInit(
                at(DownloadPhase.SEARCH_INIT), SlskdFixtures.searchState(null, false, "InProgress"), T0);

        assertEquals(DownloadStatus.FAILED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).status());
    }

    @Test
    void searchPoll_incomplete_continuesAtPollInterval_andKeepsPhaseBudget() {
        DownloadTask task = searchPolling("s1");
        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", false, "InProgress"), List.of(), T0.plusSeconds(4));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.SEARCH_POLL, next.phase());
        assertEquals(T0.plusSeconds(6), next.nextAttemptAt());
        assertEquals(task.phaseEnteredAt(), next.phaseEnteredAt(), "budget must not be refreshed");
    }

    @Test
    void searchPoll_hardFailureState_failsImmediately() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", false, "Errored"), List.of(), T0);

        DownloadDecision.Terminal t = assertInstanceOf(DownloadDecision.Terminal.class, d);
        assertEquals(DownloadStatus.FAILED, t.status());
        assertEquals(DownloadStateMachine.SEARCH_FAILED, t.message());
    }

    @Test
    void searchPoll_timedOutIsNormalCompletionForASearch_notAFailure() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed, TimedOut"),
                candidates("alice"), T0);

        assertInstanceOf(DownloadDecision.Advance.class, d);
    }

    @Test
    void searchPoll_completeWithNoCandidates_fails() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed"), List.of(), T0);

        assertEquals(DownloadStateMachine.NO_CANDIDATES,
                assertInstanceOf(DownloadDecision.Terminal.class, d).message());
    }

    @Test
    void searchPoll_completeWithCandidates_storesThemAndAdvancesToDownloadInit() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed"),
                candidates("alice", "bob"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(2, next.candidates().size());
        assertEquals(0, next.candidateIndex());
    }

    @Test
    void searchPoll_missingFromBatchResponse_treatedAsStillRunning() {
        // The batched GET /searches simply omits a search it doesn't know about — a null SearchState,
        // not an error. Deliberately indistinguishable from "still running": there is no way to tell
        // "not there yet" apart from "slskd forgot it", so this rides the existing budget timeout
        // rather than needing a dedicated branch.
        DownloadDecision d = machine.afterSearchPoll(searchPolling("s1"), null, List.of(), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(T0.plus(SEARCH_POLL), next.nextAttemptAt());
    }

    @Test
    void searchPoll_pastBudgetWhileStillRunning_timesOut() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", false, "InProgress"),
                List.of(), T0.plus(SEARCH_BUDGET).plusSeconds(1));

        assertEquals(DownloadStateMachine.TIMED_OUT,
                assertInstanceOf(DownloadDecision.Terminal.class, d).message());
    }

    @Test
    void searchPoll_completeJustPastBudget_stillProceeds() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed"),
                candidates("alice"), T0.plus(SEARCH_BUDGET).plusSeconds(1));

        assertInstanceOf(DownloadDecision.Advance.class, d);
    }

    @Test
    void downloadInit_enqueued_advancesToPollWithTransferId() {
        DownloadDecision d = machine.afterDownloadInit(
                downloadInit(candidates("alice"), 0, 0),
                SlskdFixtures.enqueued("abc", "alice"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_POLL, next.phase());
        assertEquals("abc", next.slskdTransferId());
        assertEquals("alice", next.slskdUsername());
    }

    @Test
    void downloadInit_emptyEnqueuedList_retriesInsteadOfThrowing() {
        DownloadDecision d = machine.afterDownloadInit(
                downloadInit(candidates("alice"), 0, 0),
                SlskdFixtures.enqueueRejected(), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(1, next.retryIndex());
    }

    @Test
    void downloadPoll_succeeded_isTerminalSuccess() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, Succeeded"), T0);

        assertEquals(DownloadStatus.SUCCEEDED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).status());
    }

    @Test
    void downloadPoll_inProgress_continuesAtDownloadPollInterval() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "InProgress"), T0.plusSeconds(10));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(T0.plusSeconds(15), next.nextAttemptAt());
    }

    @Test
    void downloadPoll_transferMissingFromBatchResponse_treatedAsStillRunning() {
        // The batched GET /transfers/downloads simply omits a transfer it doesn't recognise —
        // TransferedFileUtil.getStateList already returns an empty list for a null file, which
        // matches neither the success nor the failure predicate, so this falls through to the same
        // "still running, check the budget" branch as an in-progress transfer. No dedicated branch
        // needed; this test exists to pin that behaviour down explicitly.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"), null, T0.plusSeconds(10));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(T0.plusSeconds(15), next.nextAttemptAt());
    }

    @Test
    void downloadPoll_failureUnderRetryLimit_retriesSameCandidate() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, TimedOut"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(0, next.candidateIndex());
        assertEquals(1, next.retryIndex());
    }

    @Test
    void downloadPoll_retriesExhausted_movesToNextCandidateAndResetsRetryIndex() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, RETRY_LIMIT, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Errored"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(1, next.candidateIndex());
        assertEquals(0, next.retryIndex());
    }

    @Test
    void downloadPoll_allCandidatesExhausted_fails() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, RETRY_LIMIT, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Errored"), T0);

        assertEquals(DownloadStateMachine.SOURCES_EXHAUSTED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).message());
    }

    @Test
    void downloadPoll_pastBudget_timesOut() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "InProgress"),
                T0.plus(DOWNLOAD_BUDGET).plusSeconds(1));

        assertEquals(DownloadStateMachine.TIMED_OUT,
                assertInstanceOf(DownloadDecision.Terminal.class, d).message());
    }

    @Test
    void callFailed_inSearchPhase_fails() {
        DownloadDecision d = machine.onCallFailed(
                at(DownloadPhase.SEARCH_POLL), new RuntimeException("boom"), T0);

        assertEquals(DownloadStateMachine.SEARCH_FAILED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).message());
    }

    @Test
    void callFailed_inDownloadPhase_retriesOrMovesOn() {
        DownloadDecision d = machine.onCallFailed(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                new RuntimeException("boom"), T0);

        assertEquals(1, assertInstanceOf(DownloadDecision.Continue.class, d).next().retryIndex());
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew cleanTest test --tests '*DownloadStateMachineTest'`
Expected: compilation failure — `cannot find symbol: class DownloadStateMachine`.

- [ ] **Step 7: Add `SlskdSearchState`**

Failure classification is a *shortcut* to `Terminal`, never the only guard: the real backstop is
"complete with no usable candidates also fails", so an unrecognised state string degrades to that path
rather than misbehaving. Note `TimedOut` is **not** a failure for a search — slskd completes a search
by running out its timeout, reporting `"Completed, TimedOut"`. That is the opposite of `TransferState`,
where `TimedOut` *is* a failure.

```java
package com.catacomb5099.naviseerr.schema.slskd;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * slskd search states, mirroring {@link TransferState}'s shape. Unlike a transfer, {@code TimedOut}
 * is a normal way for a search to finish, not a failure.
 *
 * <p>These strings must be confirmed against the live slskd API; the design is robust to getting them
 * wrong, because an unrecognised state falls through to the "completed with no candidates" failure
 * path rather than being treated as success.
 */
@Getter
@RequiredArgsConstructor
public enum SlskdSearchState {
    NONE("None"),
    REQUESTED("Requested"),
    IN_PROGRESS("InProgress"),
    COMPLETED("Completed"),
    TIMED_OUT("TimedOut"),
    RESPONSE_LIMIT_REACHED("ResponseLimitReached"),
    FILE_LIMIT_REACHED("FileLimitReached"),
    CANCELLED("Cancelled", true),
    ERRORED("Errored", true);

    private final String value;
    private final boolean failure;

    SlskdSearchState(String value) {
        this(value, false);
    }

    /** slskd reports compound states like {@code "Completed, TimedOut"}. */
    public static List<SlskdSearchState> parse(String state) {
        if (state == null || state.isBlank()) return List.of();
        return Arrays.stream(state.split(","))
                .map(String::trim)
                .map(part -> Arrays.stream(values())
                        .filter(candidate -> candidate.getValue().equalsIgnoreCase(part))
                        .findFirst())
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    public static boolean isFailure(String state) {
        return parse(state).stream().anyMatch(SlskdSearchState::isFailure);
    }
}
```

- [ ] **Step 8: Write `DownloadStateMachine`**

Decision order matters and is asserted by the tests above. In `afterSearchPoll` the budget check sits
**only** on the still-running branch: a search that completed with usable candidates should proceed even
if it finished a hair past budget.

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.SlskdSearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.util.TransferedFileUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Every branching decision in the download pipeline. Pure: no I/O, no Reactor, no clock of its own —
 * {@code now} is always passed in. That is what makes the whole branch matrix testable without mocking
 * HTTP or sleeping.
 */
@Component
public class DownloadStateMachine {

    public static final String SEARCH_FAILED = "Searching for downloads failed";
    public static final String NO_CANDIDATES = "No download candidates found";
    public static final String SOURCES_EXHAUSTED = "All download sources exhausted";
    public static final String TIMED_OUT = "timed out";

    private final Duration searchPollInterval;
    private final Duration downloadPollInterval;
    private final Duration searchBudget;
    private final Duration downloadBudget;
    private final int retryLimit;

    public DownloadStateMachine(
            @Value("${download-task.search-poll-interval-ms:2000}") Duration searchPollInterval,
            @Value("${download-task.download-poll-interval-ms:5000}") Duration downloadPollInterval,
            @Value("${download-task.search-budget-ms:120000}") Duration searchBudget,
            @Value("${download-task.download-budget-ms:3600000}") Duration downloadBudget,
            @Value("${slskd-service.retry-count}") int retryLimit) {
        this.searchPollInterval = searchPollInterval;
        this.downloadPollInterval = downloadPollInterval;
        this.searchBudget = searchBudget;
        this.downloadBudget = downloadBudget;
        this.retryLimit = retryLimit;
    }

    public DownloadDecision afterSearchInit(DownloadTask task, SearchState started, Instant now) {
        if (started == null || started.getId() == null || started.getId().isBlank()) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, SEARCH_FAILED);
        }
        DownloadTask next = task.withPhase(DownloadPhase.SEARCH_POLL, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.songName(),
                next.phase(), next.phaseEnteredAt(), now, started.getId(), next.candidates(),
                next.candidateIndex(), next.retryIndex(), null, null, null, null));
    }

    /**
     * {@code state} may be {@code null} — the batched {@code GET /searches} simply omits a search it
     * doesn't recognise. Deliberately indistinguishable from "still running": there is no reliable way
     * to tell "not there yet" apart from "slskd forgot it", so a missing search just falls through to
     * the same still-running branch and eventually resolves via the phase budget. Same reasoning
     * applies to {@code selected}, which is only consulted once the search is complete, so callers may
     * pass an empty list while it is still running or missing.
     */
    public DownloadDecision afterSearchPoll(DownloadTask task, SearchState state,
                                            List<DownloadCandidate> selected, Instant now) {
        if (state != null && SlskdSearchState.isFailure(state.getState())) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, SEARCH_FAILED);
        }
        boolean complete = state != null && Boolean.TRUE.equals(state.getIsComplete());
        if (!complete) {
            return task.isPastBudget(now, searchBudget)
                    ? new DownloadDecision.Terminal(DownloadStatus.FAILED, TIMED_OUT)
                    : new DownloadDecision.Continue(task.dueAt(now.plus(searchPollInterval)));
        }
        if (selected == null || selected.isEmpty()) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, NO_CANDIDATES);
        }
        DownloadTask next = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.songName(),
                next.phase(), next.phaseEnteredAt(), now, next.searchId(), selected, 0, 0,
                null, null, null, null));
    }

    public DownloadDecision afterDownloadInit(DownloadTask task, QueueDownloadResponse response,
                                              Instant now) {
        if (response == null || response.getEnqueued() == null || response.getEnqueued().isEmpty()) {
            return retryOrAdvanceCandidate(task, now);
        }
        TransferedFile enqueued = response.getEnqueued().getFirst();
        DownloadTask next = task.withPhase(DownloadPhase.DOWNLOAD_POLL, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.songName(),
                next.phase(), next.phaseEnteredAt(), now, next.searchId(), next.candidates(),
                next.candidateIndex(), next.retryIndex(), enqueued.getUsername(),
                enqueued.getFilename(), enqueued.getId(), null));
    }

    /**
     * {@code file} may be {@code null} — the batched {@code GET /transfers/downloads} simply omits a
     * transfer it doesn't recognise, and {@link TransferedFileUtil#getStateList} already returns an
     * empty list for a null file. An empty list matches neither the success nor the failure predicate
     * below, so a missing transfer falls through to the same still-running branch as an in-progress
     * one and eventually resolves via the phase budget — no dedicated branch needed.
     */
    public DownloadDecision afterDownloadPoll(DownloadTask task, TransferedFile file, Instant now) {
        List<TransferState> states = TransferedFileUtil.getStateList(file);
        if (states.stream().anyMatch(TransferState::isSuccess)) {
            return new DownloadDecision.Terminal(DownloadStatus.SUCCEEDED, null);
        }
        if (states.stream().anyMatch(TransferState::isFailure)) {
            return retryOrAdvanceCandidate(task, now);
        }
        return task.isPastBudget(now, downloadBudget)
                ? new DownloadDecision.Terminal(DownloadStatus.FAILED, TIMED_OUT)
                : new DownloadDecision.Continue(task.dueAt(now.plus(downloadPollInterval)));
    }

    public DownloadDecision onCallFailed(DownloadTask task, Throwable error, Instant now) {
        return switch (task.phase()) {
            case SEARCH_INIT, SEARCH_POLL ->
                    new DownloadDecision.Terminal(DownloadStatus.FAILED, SEARCH_FAILED);
            case DOWNLOAD_INIT, DOWNLOAD_POLL -> retryOrAdvanceCandidate(task, now);
        };
    }

    private DownloadDecision retryOrAdvanceCandidate(DownloadTask task, Instant now) {
        DownloadTask base = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now)
                .dueAt(now.plus(downloadPollInterval));
        if (task.retryIndex() < retryLimit) {
            return new DownloadDecision.Continue(rebuild(base, task.candidateIndex(),
                    task.retryIndex() + 1));
        }
        if (task.candidateIndex() + 1 < task.candidates().size()) {
            return new DownloadDecision.Continue(rebuild(base, task.candidateIndex() + 1, 0));
        }
        return new DownloadDecision.Terminal(DownloadStatus.FAILED, SOURCES_EXHAUSTED);
    }

    private DownloadTask rebuild(DownloadTask base, int candidateIndex, int retryIndex) {
        return new DownloadTask(base.downloadId(), base.songName(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), base.searchId(), base.candidates(),
                candidateIndex, retryIndex, null, null, null, null);
    }
}
```

Note the `dueAt` after `withPhase` in `retryOrAdvanceCandidate`: the phase budget resets (each candidate
attempt gets a fresh transfer budget) but the retry is still delayed by a poll interval so a failing peer
is not hammered.

- [ ] **Step 9: Run the test to verify it passes**

Run: `./gradlew cleanTest test --tests '*DownloadStateMachineTest'`
Expected: PASS, 21 tests.

- [ ] **Step 10: Run the whole suite — nothing else may change**

Run: `./gradlew cleanTest test`
Expected: `BUILD SUCCESSFUL`. Nothing wires the new code yet, so every existing test must still pass.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/catacomb5099/naviseerr/config/TimeConfig.java src/main/java/com/catacomb5099/naviseerr/download/DownloadPhase.java src/main/java/com/catacomb5099/naviseerr/download/DownloadCandidate.java src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java src/main/java/com/catacomb5099/naviseerr/download/DownloadDecision.java src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java src/main/java/com/catacomb5099/naviseerr/schema/slskd/SlskdSearchState.java src/test/java/com/catacomb5099/naviseerr/support src/test/java/com/catacomb5099/naviseerr/download/DownloadStateMachineTest.java
git commit -m "feat(download): add clock bean and the pure download state machine"
```

---

### Task 2: Fix slskd transfer-state classification

Independent bug fix. Without it, `DOWNLOAD_POLL`'s failure branch is unreachable for `TimedOut` — the most common Soulseek failure — so the retry tier Task 1 just built never fires.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/util/TransferedFileUtil.java:16`
- Test: `src/test/java/com/catacomb5099/naviseerr/util/TransferedFileUtilTest.java`

**Interfaces:**
- Consumes: `TransferState.getValue()` (already exists).
- Produces: no signature change. `TransferedFileUtil.getStateList(TransferedFile)` behaviour is corrected for `"InProgress"` and `"TimedOut"`.

- [ ] **Step 1: Add the failing regression tests**

Append to `TransferedFileUtilTest`. The existing `combos()` uses `values()[0]` (`NONE`) and `values()[1]` (`REQUESTED`), both single-word, so those cases pass either way and must keep passing.

```java
    @Test
    void multiWordStatesMatchOnSlskdValueNotEnumName() {
        TransferedFile file = mock(TransferedFile.class);
        when(file.getState()).thenReturn("InProgress");
        assertEquals(List.of(TransferState.IN_PROGRESS), TransferedFileUtil.getStateList(file));
    }

    @Test
    void timedOutIsRecognised_soTheRetryTierIsReachable() {
        TransferedFile file = mock(TransferedFile.class);
        when(file.getState()).thenReturn("Completed, TimedOut");
        List<TransferState> result = TransferedFileUtil.getStateList(file);
        assertEquals(List.of(TransferState.COMPLETED, TransferState.TIMED_OUT), result);
        assertTrue(result.stream().anyMatch(TransferState::isFailure));
    }

    @Test
    void succeededStillParses() {
        TransferedFile file = mock(TransferedFile.class);
        when(file.getState()).thenReturn("Completed, Succeeded");
        assertTrue(TransferedFileUtil.getStateList(file).stream().anyMatch(TransferState::isSuccess));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew cleanTest test --tests '*TransferedFileUtilTest'`
Expected: `multiWordStatesMatchOnSlskdValueNotEnumName` and `timedOutIsRecognised_soTheRetryTierIsReachable` FAIL with an empty / `[COMPLETED]` actual list. `succeededStillParses` passes already.

- [ ] **Step 3: Apply the one-word fix**

In `TransferedFileUtil.java` line 16, replace `transferState.name()` with `transferState.getValue()`:

```java
                .map(state -> Arrays.stream(TransferState.values())
                        .filter(transferState -> transferState.getValue().equalsIgnoreCase(state))
                        .findFirst())
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew cleanTest test --tests '*TransferedFileUtilTest'`
Expected: PASS, all 6 tests including the pre-existing parameterized cases.

- [ ] **Step 5: Add the search-state test**

Create `src/test/java/com/catacomb5099/naviseerr/schema/slskd/SlskdSearchStateTest.java`:

```java
package com.catacomb5099.naviseerr.schema.slskd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlskdSearchStateTest {

    @Test
    void erroredIsAFailure() {
        assertTrue(SlskdSearchState.isFailure("Errored"));
    }

    @Test
    void timedOutIsNotAFailureForASearch() {
        assertFalse(SlskdSearchState.isFailure("Completed, TimedOut"));
    }

    @Test
    void unknownStateIsNotTreatedAsFailure_soItFallsThroughToTheNoCandidateGuard() {
        assertFalse(SlskdSearchState.isFailure("SomethingSlskdAddedLater"));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertFalse(SlskdSearchState.isFailure(null));
        assertFalse(SlskdSearchState.isFailure("  "));
    }
}
```

- [ ] **Step 6: Run the full suite**

Run: `./gradlew cleanTest test`
Expected: `BUILD SUCCESSFUL`. `SlskdDownloadProcessorTest` still passes — it drives `TransferState` values through the same helper and the fix only makes more of them match.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/catacomb5099/naviseerr/util/TransferedFileUtil.java src/test/java/com/catacomb5099/naviseerr/util/TransferedFileUtilTest.java src/test/java/com/catacomb5099/naviseerr/schema/slskd/SlskdSearchStateTest.java
git commit -m "fix(slskd): match transfer states on slskd value rather than enum name"
```

---

### Task 3: `download_tasks` table and repository

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java`
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java`
- Test: `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRepositoryIT.java`

**Interfaces:**
- Consumes: `DownloadTask`, `DownloadPhase`, `DownloadCandidate`, `DownloadStatus`, `R2dbcEntityTemplate`.
- Produces:
  - `DownloadTaskRepository.admitNewDownloads(int limit, Instant now)` -> `Mono<Long>` (rows admitted)
  - `DownloadTaskRepository.countActiveDownloads()` -> `Mono<Long>` (`downloads` rows that are `IN_PROGRESS`)
  - `DownloadTaskRepository.countActiveTransfers()` -> `Mono<Long>` (tasks in a download phase)
  - `DownloadTaskRepository.claimDueTasks(int limit, String owner, Instant now, Duration lease, boolean transferSlotsFree)` -> `Flux<DownloadTask>`
  - `DownloadTaskRepository.save(DownloadTask task)` -> `Mono<Long>` (writes every field, clears the lease)
  - `DownloadService.finishDownload(UUID downloadId, DownloadStatus status, String reason, Instant now)` -> `Mono<Long>`

- [ ] **Step 1: Add the table**

Append to `src/main/resources/schema.sql`:

```sql
-- Working state for one download. Deliberately a separate table from `downloads`: it is written
-- every few seconds, whereas `downloads` is the low-churn user-facing record that history queries
-- read. `song_name` is denormalised so the hot due-work query needs no join.
--
-- Rows are KEPT FOREVER once terminal (phase SUCCEEDED/FAILED), not deleted. Self-hosters need to
-- be able to answer "which peers were tried and how did each fail?" from their own instance, and a
-- few MB of history is a trivial price. The partial index below is what keeps the due-work query
-- fast regardless of how much history accumulates: it only contains rows that are still live.
-- A retention job (delete after N months) can be added later if anyone ever asks.
CREATE TABLE IF NOT EXISTS download_tasks (
    download_id       UUID PRIMARY KEY REFERENCES downloads (download_id),
    song_name         TEXT        NOT NULL,
    phase             TEXT        NOT NULL
                                  CHECK (phase IN ('SEARCH_INIT', 'SEARCH_POLL',
                                                   'DOWNLOAD_INIT', 'DOWNLOAD_POLL',
                                                   'SUCCEEDED', 'FAILED')),
    phase_entered_at  TIMESTAMPTZ NOT NULL,
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    failure_reason    TEXT,
    lease_owner       TEXT,
    lease_expires_at  TIMESTAMPTZ,
    search_id         TEXT,
    -- JSON array of DownloadCandidate. TEXT, not JSONB: written once, read whole, never queried
    -- by content, so JSONB's indexing and operators would buy nothing. Storing it as JSON also
    -- means adding a field to DownloadCandidate later needs NO migration.
    candidates        TEXT        NOT NULL DEFAULT '[]',
    candidate_index   INT         NOT NULL DEFAULT 0,
    retry_index       INT         NOT NULL DEFAULT 0,
    -- No "did we already call enqueue" flag. A crash between slskd accepting an enqueue and this row
    -- recording the transfer id can cause a duplicate download on resume — accepted by explicit
    -- decision (docs/decisions/durable-download-state-machine-13-08-2026.md) rather than guarded
    -- against, because the guard cost more (a column, an extra write before every enqueue, a
    -- dedicated recovery branch) than an occasional duplicate file is worth.
    slskd_username    TEXT,
    slskd_filename    TEXT,
    slskd_transfer_id TEXT,
    last_error        TEXT
);

-- PARTIAL index: only live work is indexed, so the due-work query cost is independent of history
-- size. This is what makes "keep terminal rows forever" free.
CREATE INDEX IF NOT EXISTS idx_download_tasks_due ON download_tasks (next_attempt_at)
    WHERE phase NOT IN ('SUCCEEDED', 'FAILED');
```

**Introduce Flyway in this same step.** Widening the `downloads.status` CHECK constraint is coming
(collections need `PARTIAL_SUCCESS`, cancellation needs `CANCELLED`) and that is not expressible
idempotently in `schema.sql`. Doing the baseline now, while the schema is three tables, is far easier
than doing it under pressure later.

Add to `build.gradle`:

```groovy
	implementation 'org.flywaydb:flyway-core'
	implementation 'org.flywaydb:flyway-database-postgresql'
	// Flyway needs a blocking driver. Used ONLY to run migrations at startup; the runtime path
	// stays entirely on r2dbc-postgresql.
	runtimeOnly 'org.postgresql:postgresql'
```

Move `schema.sql`'s contents into `src/main/resources/db/migration/V1__baseline.sql` (the existing
`downloads` table and its index) and `V2__download_tasks.sql` (the DDL above, with the
`IF NOT EXISTS` clauses removed — Flyway runs each file exactly once). Delete `schema.sql` and
replace the `spring.sql.init` block in `application.yaml` with:

```yaml
spring:
  sql:
    init:
      mode: never
  flyway:
    enabled: true
    # Existing installs already have `downloads` with real data but no Flyway history table.
    # This tells Flyway to treat what is already there as V1 rather than trying to create it.
    baseline-on-migrate: true
    baseline-version: 1
    url: ${SPRING_FLYWAY_URL:jdbc:postgresql://localhost:5432/naviseerr}
    user: ${SPRING_R2DBC_USERNAME:naviseerr}
    password: ${SPRING_R2DBC_PASSWORD:naviseerr}
```

Note the JDBC URL is separate from the R2DBC one and must point at the same database.

- [ ] **Step 2: Write the failing integration test**

Create `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRepositoryIT.java`. Model it on the existing `DownloadServiceClaimIT`, including its `@TestPropertySource` trick to park the interval loop so it cannot interfere.

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import com.catacomb5099.naviseerr.support.DownloadTaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadTaskRepositoryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired DownloadTaskRepository repository;
    @Autowired DownloadService downloadService;

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    private UUID insertDownload(String status) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, song_name, status, created_at) "
                        + "VALUES (:id, 'song', :status, now())")
                .bind("id", id).bind("status", status)
                .fetch().rowsUpdated().block();
        return id;
    }

    @Test
    void admit_createsTaskAndFlipsPendingToInProgress() {
        UUID id = insertDownload("PENDING");

        Long admitted = repository.admitNewDownloads(10, NOW).block();

        assertEquals(1L, admitted);
        assertEquals("SEARCH_INIT", phaseOf(id));
        assertEquals("IN_PROGRESS", statusOf(id));
    }

    @Test
    void admit_alsoRecoversInProgressDownloadsThatLostTheirTask() {
        UUID id = insertDownload("IN_PROGRESS");

        assertEquals(1L, repository.admitNewDownloads(10, NOW).block());
        assertEquals("SEARCH_INIT", phaseOf(id));
    }

    @Test
    void admit_ignoresDownloadsThatAlreadyHaveATask() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        assertEquals(0L, repository.admitNewDownloads(10, NOW).block());
        assertEquals(1L, countTaskRows());
    }

    @Test
    void admit_ignoresTerminalDownloads() {
        insertDownload("SUCCEEDED");
        insertDownload("FAILED");

        assertEquals(0L, repository.admitNewDownloads(10, NOW).block());
    }

    @Test
    void claim_returnsOnlyDueRowsAndStampsALease() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "instance-a", NOW, Duration.ofSeconds(60), true)
                .collectList().block();

        assertEquals(1, claimed.size());
        assertEquals(id, claimed.getFirst().downloadId());
        assertEquals("song", claimed.getFirst().songName());
        assertEquals("instance-a", leaseOwnerOf(id));
    }

    @Test
    void claim_skipsRowsThatAreNotYetDue() {
        insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        assertTrue(repository.claimDueTasks(10, "a", NOW.minusSeconds(1), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_skipsRowsHeldByALiveLease() {
        insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).collectList().block();

        assertTrue(repository.claimDueTasks(10, "b", NOW.plusSeconds(1), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_reclaimsRowsWhoseLeaseHasExpired_thisIsCrashRecovery() {
        insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        repository.claimDueTasks(10, "dead", NOW, Duration.ofSeconds(60), true).collectList().block();

        List<DownloadTask> reclaimed = repository
                .claimDueTasks(10, "alive", NOW.plusSeconds(61), Duration.ofSeconds(60), true)
                .collectList().block();

        assertEquals(1, reclaimed.size());
    }

    @Test
    void save_roundTripsEveryFieldIncludingCandidates_andClearsTheLease() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).blockFirst();

        DownloadTask updated = new DownloadTask(id, "song", DownloadPhase.DOWNLOAD_POLL,
                NOW, NOW.plusSeconds(5), "s1", DownloadTaskFixtures.candidates("alice", "bob"),
                1, 2, "bob", "music/bob/song.flac", "abc", "some error");
        repository.save(updated).block();

        DownloadTask reread = repository
                .claimDueTasks(10, "b", NOW.plusSeconds(10), Duration.ofSeconds(60), true).blockFirst();

        assertNotNull(reread, "lease must have been cleared by save()");
        assertEquals(DownloadPhase.DOWNLOAD_POLL, reread.phase());
        assertEquals("s1", reread.searchId());
        assertEquals(2, reread.candidates().size());
        assertEquals("music/bob/song.flac", reread.candidates().get(1).filename());
        assertEquals(1411, reread.candidates().get(1).bitRate());
        assertEquals(1, reread.candidateIndex());
        assertEquals(2, reread.retryIndex());
        assertEquals("abc", reread.slskdTransferId());
    }

    @Test
    void finishDownload_writesStatusAndMarksTheTaskTerminalAtomically() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        assertEquals("SUCCEEDED", statusOf(id));
        assertEquals("SUCCEEDED", phaseOf(id));
        assertEquals(1L, countTaskRows(), "the task row is history now, not garbage");
    }

    @Test
    void finishDownload_recordsTheFailureReasonForLaterDebugging() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        downloadService.finishDownload(id, DownloadStatus.FAILED, "timed out", NOW).block();

        assertEquals("FAILED", phaseOf(id));
        assertEquals("timed out", template.getDatabaseClient()
                .sql("SELECT failure_reason FROM download_tasks WHERE download_id = :id")
                .bind("id", id)
                .map((row, meta) -> row.get("failure_reason", String.class)).one().block());
    }

    @Test
    void finishDownload_onAnAlreadyTerminalDownload_keepsTheFirstStatusAndStaysTerminal() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        // A duplicated step reaching Terminal a second time — legal, because a lease can expire
        // while the work is still alive.
        downloadService.finishDownload(id, DownloadStatus.FAILED, "boom", NOW).block();

        assertEquals("SUCCEEDED", statusOf(id), "must not overwrite a terminal status");
        assertEquals("FAILED", phaseOf(id),
                "the task write is unconditional, which is what prevents a livelock");
    }

    @Test
    void claimDueTasks_neverReturnsTerminalTasks() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        assertTrue(repository
                .claimDueTasks(10, "a", NOW.plusSeconds(86_400), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty(),
                "a finished download must never be stepped again");
    }

    @Test
    void claimDueTasks_withNoTransferSlots_stillReturnsSearchTasks_butNotDownloadInit() {
        UUID searching = insertDownload("PENDING");
        UUID starting = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        // Move one task to DOWNLOAD_INIT; leave the other at SEARCH_INIT.
        repository.save(new DownloadTask(starting, "song", DownloadPhase.DOWNLOAD_INIT, NOW, NOW,
                "s1", DownloadTaskFixtures.candidates("alice"), 0, 0,
                null, null, null, null)).block();

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), false)
                .collectList().block();

        assertEquals(1, claimed.size(), "polling and searching must never be starved by the cap");
        assertEquals(searching, claimed.getFirst().downloadId());
    }

    private String statusOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT status FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block();
    }

    private String phaseOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block();
    }

    private long countTaskRows() {
        return template.getDatabaseClient()
                .sql("SELECT count(*) AS total FROM download_tasks")
                .map((row, meta) -> row.get("total", Long.class)).one().block();
    }

    private String leaseOwnerOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT lease_owner FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("lease_owner", String.class)).one().block();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew cleanTest test --tests '*DownloadTaskRepositoryIT'`
Expected: compilation failure — `cannot find symbol: class DownloadTaskRepository`.

- [ ] **Step 4: Add `finishDownload` to `DownloadService`**

Add the SQL constant and the method. Keep `markStatusIfInProgress` — `DownloadServiceClaimIT` pins it and it must keep passing unchanged.

```java
    // Terminal write. One statement, so the download's status and the task's terminal phase cannot
    // be split by a crash. The task UPDATE is deliberately NOT conditional on the download UPDATE
    // matching: when the download is already terminal (reachable whenever an expired lease causes a
    // duplicated step) a conditional write would leave the task non-terminal, and the next pass
    // would re-step it, re-reach Terminal, and change nothing — one slskd call per interval,
    // forever. Postgres runs a data-modifying CTE exactly once even when nothing references it, so
    // both halves always execute.
    //
    // The task row is retained, not deleted: its terminal phase plus failure_reason is the history a
    // self-hoster needs to debug a failed download. The partial index on next_attempt_at is what
    // keeps the due-work query fast despite that.
    private static final String FINISH_DOWNLOAD_SQL = """
            WITH updated AS (
                UPDATE downloads
                   SET status = :status
                 WHERE download_id = :id
                   AND status NOT IN ('SUCCEEDED', 'FAILED')
                RETURNING download_id
            )
            UPDATE download_tasks
               SET phase = :status,
                   phase_entered_at = :now,
                   finished_at = :now,
                   failure_reason = :reason,
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
            """;

    public Mono<Long> finishDownload(UUID downloadId, DownloadStatus status, String reason,
                                     Instant now) {
        DatabaseClient.GenericExecuteSpec spec = entityTemplate.getDatabaseClient()
                .sql(FINISH_DOWNLOAD_SQL)
                .bind("status", status.name())
                .bind("id", downloadId)
                .bind("now", now);
        spec = reason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", reason);
        return spec.fetch()
                .rowsUpdated()
                .doOnError(error -> log.error("Could not finish download {} as {}",
                        downloadId, status, error));
    }
```

- [ ] **Step 5: Write `DownloadTaskRepository`**

`bindNull` is required for null values — R2DBC's `bind` rejects them. Getting this wrong produces an
`IllegalArgumentException` at runtime, not compile time.

```java
package com.catacomb5099.naviseerr.download;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All {@code download_tasks} SQL. Raw statements rather than Spring Data derived queries because
 * every one of them needs something the mapping layer cannot express: {@code FOR UPDATE SKIP LOCKED},
 * {@code RETURNING}, or a data-modifying CTE.
 */
@Slf4j
@Repository
public class DownloadTaskRepository {

    /**
     * Admits work in one atomic statement. Matches NON-TERMINAL downloads with no task row, not just
     * PENDING ones, which makes "every non-terminal download has a task row" an invariant the loop
     * continuously restores — so a download that somehow loses its task row is recovered rather than
     * stranded. NOT EXISTS rather than a LEFT JOIN because FOR UPDATE cannot be applied across an
     * outer join. ON CONFLICT DO NOTHING guards a concurrent admit.
     */
    private static final String ADMIT_SQL = """
            WITH admitted AS (
                SELECT d.download_id, d.song_name
                  FROM downloads d
                 WHERE d.status IN ('PENDING', 'IN_PROGRESS')
                   AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                    WHERE t.download_id = d.download_id)
                 ORDER BY d.created_at
                   FOR UPDATE SKIP LOCKED
                 LIMIT :limit
            ), created AS (
                INSERT INTO download_tasks
                    (download_id, song_name, phase, phase_entered_at, next_attempt_at)
                SELECT download_id, song_name, 'SEARCH_INIT', :now, :now FROM admitted
                ON CONFLICT (download_id) DO NOTHING
                RETURNING download_id
            )
            UPDATE downloads SET status = 'IN_PROGRESS'
             WHERE download_id IN (SELECT download_id FROM created)
            """;

    /**
     * Claims due, unleased, non-terminal tasks.
     *
     * <p>The {@code :transferSlotsFree} predicate is the "don't melt slskd" guard. When no transfer
     * slots are free, DOWNLOAD_INIT tasks are excluded from the claim entirely rather than claimed
     * and then deferred — otherwise a 500-track collection sitting at DOWNLOAD_INIT would consume
     * every pass claiming and re-deferring rows, crowding out the transfers that are actually
     * running. Everything else (searches, and polling live transfers) is never gated: polling is one
     * cheap GET, and starving it stalls a download that slskd is happily finishing.
     */
    private static final String CLAIM_DUE_SQL = """
            UPDATE download_tasks
               SET lease_owner = :owner,
                   lease_expires_at = :leaseExpiresAt
             WHERE download_id IN (
                   SELECT download_id FROM download_tasks
                    WHERE next_attempt_at <= :now
                      AND phase NOT IN ('SUCCEEDED', 'FAILED')
                      AND (:transferSlotsFree OR phase <> 'DOWNLOAD_INIT')
                      AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                    ORDER BY next_attempt_at
                      FOR UPDATE SKIP LOCKED
                    LIMIT :limit)
            RETURNING download_id, song_name, phase, phase_entered_at, next_attempt_at, search_id,
                      candidates, candidate_index, retry_index, slskd_username,
                      slskd_filename, slskd_transfer_id, last_error
            """;

    // Writes every field so there is no partial-update logic to get wrong: DownloadTask is the
    // complete state. Clearing the lease is what makes the row visible to the next pass.
    private static final String SAVE_SQL = """
            UPDATE download_tasks
               SET phase = :phase,
                   phase_entered_at = :phaseEnteredAt,
                   next_attempt_at = :nextAttemptAt,
                   search_id = :searchId,
                   candidates = :candidates,
                   candidate_index = :candidateIndex,
                   retry_index = :retryIndex,
                   slskd_username = :slskdUsername,
                   slskd_filename = :slskdFilename,
                   slskd_transfer_id = :slskdTransferId,
                   last_error = :lastError,
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
            """;

    // Counts DOWNLOADS in flight, not tasks. A 500-track collection is ONE in-flight download, so
    // one large request cannot lock every other request out of admission.
    private static final String COUNT_ACTIVE_DOWNLOADS_SQL = """
            SELECT count(*) AS total FROM downloads WHERE status = 'IN_PROGRESS'
            """;

    // Counts real or imminent slskd transfers. This is the resource that actually needs protecting:
    // bandwidth, and peers' upload queues. One collection may legitimately hold every slot.
    private static final String COUNT_ACTIVE_TRANSFERS_SQL = """
            SELECT count(*) AS total FROM download_tasks
             WHERE phase IN ('DOWNLOAD_INIT', 'DOWNLOAD_POLL')
            """;

    private static final TypeReference<List<DownloadCandidate>> CANDIDATE_LIST =
            new TypeReference<>() {};

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;

    public DownloadTaskRepository(R2dbcEntityTemplate entityTemplate, ObjectMapper objectMapper) {
        this.client = entityTemplate.getDatabaseClient();
        this.objectMapper = objectMapper;
    }

    public Mono<Long> admitNewDownloads(int limit, Instant now) {
        return client.sql(ADMIT_SQL)
                .bind("limit", limit)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> countActiveDownloads() {
        return client.sql(COUNT_ACTIVE_DOWNLOADS_SQL)
                .map((row, meta) -> row.get("total", Long.class))
                .one();
    }

    public Mono<Long> countActiveTransfers() {
        return client.sql(COUNT_ACTIVE_TRANSFERS_SQL)
                .map((row, meta) -> row.get("total", Long.class))
                .one();
    }

    public Flux<DownloadTask> claimDueTasks(int limit, String owner, Instant now, Duration lease,
                                            boolean transferSlotsFree) {
        return client.sql(CLAIM_DUE_SQL)
                .bind("owner", owner)
                .bind("leaseExpiresAt", now.plus(lease))
                .bind("now", now)
                .bind("transferSlotsFree", transferSlotsFree)
                .bind("limit", limit)
                .map(this::toTask)
                .all();
    }

    public Mono<Long> save(DownloadTask task) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(SAVE_SQL)
                .bind("id", task.downloadId())
                .bind("phase", task.phase().name())
                .bind("phaseEnteredAt", task.phaseEnteredAt())
                .bind("nextAttemptAt", task.nextAttemptAt())
                .bind("candidates", writeCandidates(task.candidates()))
                .bind("candidateIndex", task.candidateIndex())
                .bind("retryIndex", task.retryIndex());
        spec = bindNullable(spec, "searchId", task.searchId());
        spec = bindNullable(spec, "slskdUsername", task.slskdUsername());
        spec = bindNullable(spec, "slskdFilename", task.slskdFilename());
        spec = bindNullable(spec, "slskdTransferId", task.slskdTransferId());
        spec = bindNullable(spec, "lastError", task.lastError());
        return spec.fetch().rowsUpdated();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private DownloadTask toTask(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return new DownloadTask(
                row.get("download_id", UUID.class),
                row.get("song_name", String.class),
                DownloadPhase.valueOf(row.get("phase", String.class)),
                row.get("phase_entered_at", Instant.class),
                row.get("next_attempt_at", Instant.class),
                row.get("search_id", String.class),
                readCandidates(row.get("candidates", String.class)),
                row.get("candidate_index", Integer.class),
                row.get("retry_index", Integer.class),
                row.get("slskd_username", String.class),
                row.get("slskd_filename", String.class),
                row.get("slskd_transfer_id", String.class),
                row.get("last_error", String.class));
    }

    private String writeCandidates(List<DownloadCandidate> candidates) {
        try {
            return objectMapper.writeValueAsString(candidates == null ? List.of() : candidates);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise download candidates", e);
        }
    }

    private List<DownloadCandidate> readCandidates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, CANDIDATE_LIST);
        } catch (Exception e) {
            // A row we cannot read is a row we cannot drive. Fail the step rather than silently
            // dropping candidates and reporting "no sources".
            throw new IllegalStateException("Could not deserialise download candidates", e);
        }
    }
}
```

- [ ] **Step 6: Run to verify the integration test passes**

Run: `./gradlew cleanTest test --tests '*DownloadTaskRepositoryIT'`
Expected: PASS, 11 tests. Docker must be running for Testcontainers.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew cleanTest test`
Expected: `BUILD SUCCESSFUL`. `DownloadServiceClaimIT` must pass unmodified.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/schema.sql src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRepositoryIT.java
git commit -m "feat(download): add download_tasks table with lease-based claim and atomic terminal write"
```

---

### Task 4: Step executor

Two of the four phases are now batched: `SEARCH_POLL` and `DOWNLOAD_POLL` make **no slskd call of
their own**. `DownloadTaskRunner` (Task 5) fetches `GET /searches` and `GET /transfers/downloads`
**once per pass** and hands each claimed row its own entry from the resulting map — that is what turns
"one call per download per poll" into "two calls per pass, however many downloads are in flight."
`SEARCH_INIT` and `DOWNLOAD_INIT` are not batchable in slskd's API (starting a search or a transfer is
inherently per-request), so those two still call directly.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdService.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadStepExecutor.java`
- Test: `src/test/java/com/catacomb5099/naviseerr/download/DownloadStepExecutorTest.java`

**Interfaces:**
- Consumes: `SlskdService` (`searchResults`, `enqueueDownload`, and the two new batched/delete methods added in Step 1), `SlskdSearchResultProcessor.selectBestFiles`, `DownloadStateMachine`, `Clock`.
- Produces:
  - `SlskdService.getAllSearches()` -> `Flux<SearchState>`, `SlskdService.getAllDownloads()` -> `Flux<TransferedFile>`, `SlskdService.deleteSearch(String searchId)` -> `Mono<Void>`
  - `DownloadStepExecutor.execute(DownloadTask task, Map<String, SearchState> searchesById, Map<String, TransferedFile> transfersById)` -> `Mono<DownloadDecision>`. Never errors: an slskd failure is converted to a decision via `onCallFailed`. The two maps are ignored outside `SEARCH_POLL`/`DOWNLOAD_POLL`.

- [ ] **Step 1: Add the two batched calls and search deletion to `SlskdService`**

`GET /searches` returns every search slskd knows about, complete or not — that single call is what
replaces one `GET /searches/{id}` per download. `GET /transfers/downloads` is the same idea for
transfers. Both are read from the design's live-verification notes: `GET /searches` is confirmed to
carry each search's completion state; `GET /transfers/downloads` returning unbounded history (no
pagination, no date or state filter — only `includeRemoved`) is a known, accepted risk, not a defect
to fix here — see the note after Step 4.

`deleteSearch` is the other half of "pruning completed *searches* is safe, unlike pruning transfers":
`DELETE /searches/{id}` is a distinct verb from cancelling one (`PUT /searches/{id}`), so a
misclassification here cannot abort a running search the way `TransferedFileUtil`'s known bug could
have aborted a live transfer — see Task 2's rationale for why that distinction matters.

```java
    public Flux<SearchState> getAllSearches() {
        return webClient
                .get()
                .uri(SEARCHES_ENDPOINT)
                .retrieve()
                .bodyToFlux(SearchState.class);
    }

    public Mono<Void> deleteSearch(String searchId) {
        return webClient
                .delete()
                .uri(SEARCHES_ENDPOINT + "/" + searchId)
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Flux<TransferedFile> getAllDownloads() {
        return webClient
                .get()
                .uri(TRANSFERS_ENDPOINT)
                .retrieve()
                .bodyToFlux(TransferedFile.class);
    }
```

Add `import reactor.core.publisher.Flux;` to `SlskdService.java`'s imports.

- [ ] **Step 2: Write the failing test**

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DownloadStepExecutorTest {

    private SlskdService slskdService;
    private SlskdSearchResultProcessor searchProcessor;
    private DownloadStepExecutor executor;

    @BeforeEach
    void setUp() {
        slskdService = mock(SlskdService.class);
        searchProcessor = mock(SlskdSearchResultProcessor.class);
        DownloadStateMachine machine = new DownloadStateMachine(
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(120), Duration.ofSeconds(3600), 2);
        executor = new DownloadStepExecutor(slskdService, searchProcessor, machine,
                Clock.fixed(T0, ZoneOffset.UTC));
        when(slskdService.deleteSearch(any())).thenReturn(Mono.empty());
    }

    @Test
    void searchInit_callsSearchOnceAndAdvances() {
        when(slskdService.searchResults("never gonna give you up"))
                .thenReturn(Mono.just(SlskdFixtures.searchState("s1", false, "InProgress")));

        DownloadDecision d = executor
                .execute(at(DownloadPhase.SEARCH_INIT), Map.of(), Map.of()).block();

        assertEquals(DownloadPhase.SEARCH_POLL,
                assertInstanceOf(DownloadDecision.Advance.class, d).next().phase());
        verify(slskdService, times(1)).searchResults(any());
    }

    @Test
    void searchPoll_readsFromTheBatchedMap_makesNoPerRowCall() {
        SearchState state = SlskdFixtures.searchState("s1", false, "InProgress");

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", state), Map.of()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
        verify(slskdService, never()).getAllSearches();
        verifyNoInteractions(searchProcessor);
    }

    @Test
    void searchPoll_missingFromTheBatch_treatedAsStillRunning() {
        DownloadDecision d = executor.execute(searchPolling("s1"), Map.of(), Map.of()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
        verifyNoInteractions(searchProcessor);
    }

    @Test
    void searchPoll_complete_selectsCandidatesAndMapsThem_thenDeletesTheSearch() {
        var state = SlskdFixtures.searchState("s1", true, "Completed");
        var file = new SearchFile("music/alice/song.flac", 10L, 7L, false, "flac", Optional.of(1411));
        var peer = new SearchResponseItem(1, java.util.List.of(file), true, 0,
                java.util.List.of(), 0, 1, 900, "alice");
        when(searchProcessor.selectBestFiles(eq(state), any()))
                .thenReturn(Mono.just(java.util.List.of(Map.entry(peer, file))));

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", state), Map.of()).block();

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals("alice", next.candidates().getFirst().username());
        assertEquals(1411, next.candidates().getFirst().bitRate());
        verify(slskdService).deleteSearch("s1");
    }

    @Test
    void searchPoll_completeWithNoCandidates_stillDeletesTheSearch() {
        var state = SlskdFixtures.searchState("s1", true, "Completed");
        when(searchProcessor.selectBestFiles(eq(state), any())).thenReturn(Mono.just(List.of()));

        executor.execute(searchPolling("s1"), Map.of("s1", state), Map.of()).block();

        verify(slskdService).deleteSearch("s1");
    }

    @Test
    void searchPoll_stillRunning_neverDeletesTheSearch() {
        var state = SlskdFixtures.searchState("s1", false, "InProgress");

        executor.execute(searchPolling("s1"), Map.of("s1", state), Map.of()).block();

        verify(slskdService, never()).deleteSearch(any());
    }

    @Test
    void downloadInit_callsEnqueueDirectly() {
        when(slskdService.enqueueDownload(eq("alice"), any()))
                .thenReturn(Mono.just(SlskdFixtures.enqueued("abc", "alice")));

        DownloadDecision d = executor
                .execute(downloadInit(candidates("alice"), 0, 0), Map.of(), Map.of()).block();

        assertEquals("abc",
                assertInstanceOf(DownloadDecision.Advance.class, d).next().slskdTransferId());
        verify(slskdService).enqueueDownload(eq("alice"), any());
    }

    @Test
    void downloadPoll_readsFromTheBatchedMap_makesNoPerRowCall() {
        TransferedFile file = SlskdFixtures.transfer("abc", "alice", "Completed, Succeeded");

        DownloadDecision d = executor
                .execute(downloadPolling(candidates("alice"), 0, 0, "abc"), Map.of(), Map.of("abc", file))
                .block();

        assertEquals(DownloadStatus.SUCCEEDED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).status());
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void downloadPoll_missingFromTheBatch_treatedAsStillRunning() {
        DownloadDecision d = executor
                .execute(downloadPolling(candidates("alice"), 0, 0, "abc"), Map.of(), Map.of()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
    }

    @Test
    void anSlskdErrorDuringSearchInit_becomesADecision_notAnErrorSignal() {
        when(slskdService.searchResults(any()))
                .thenReturn(Mono.error(new RuntimeException("slskd is down")));

        StepVerifier.create(executor.execute(at(DownloadPhase.SEARCH_INIT), Map.of(), Map.of()))
                .assertNext(d -> assertEquals(DownloadStatus.FAILED,
                        assertInstanceOf(DownloadDecision.Terminal.class, d).status()))
                .verifyComplete();
    }

    @Test
    void anSlskdErrorDuringEnqueue_becomesADecision_notAnErrorSignal() {
        when(slskdService.enqueueDownload(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("slskd is down")));

        StepVerifier.create(executor.execute(
                        downloadInit(candidates("alice", "bob"), 0, 0), Map.of(), Map.of()))
                .assertNext(d -> assertEquals(1,
                        assertInstanceOf(DownloadDecision.Continue.class, d).next().retryIndex()))
                .verifyComplete();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew cleanTest test --tests '*DownloadStepExecutorTest'`
Expected: compilation failure — `cannot find symbol: class DownloadStepExecutor`.

- [ ] **Step 4: Write `DownloadStepExecutor`**

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The I/O shell around {@link DownloadStateMachine}. {@code SEARCH_POLL} and {@code DOWNLOAD_POLL}
 * make no slskd call of their own — they read from {@code searchesById}/{@code transfersById}, which
 * {@link DownloadTaskRunner} fetches once per pass via the two batched slskd calls. {@code SEARCH_INIT}
 * and {@code DOWNLOAD_INIT} are not batchable in slskd's API, so those two still call directly.
 *
 * <p>Never returns an error signal — an slskd failure is a decision too, so the caller always has
 * something to write.
 */
@Slf4j
@Component
public class DownloadStepExecutor {

    private final SlskdService slskdService;
    private final SlskdSearchResultProcessor searchResultProcessor;
    private final DownloadStateMachine stateMachine;
    private final Clock clock;

    public DownloadStepExecutor(SlskdService slskdService,
                                SlskdSearchResultProcessor searchResultProcessor,
                                DownloadStateMachine stateMachine,
                                Clock clock) {
        this.slskdService = slskdService;
        this.searchResultProcessor = searchResultProcessor;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    public Mono<DownloadDecision> execute(DownloadTask task, Map<String, SearchState> searchesById,
                                          Map<String, TransferedFile> transfersById) {
        Instant now = clock.instant();
        return step(task, searchesById, transfersById, now)
                .onErrorResume(error -> {
                    log.warn("Step {} for download {} failed", task.phase(), task.downloadId(), error);
                    return Mono.just(stateMachine.onCallFailed(task, error, now));
                });
    }

    private Mono<DownloadDecision> step(DownloadTask task, Map<String, SearchState> searchesById,
                                        Map<String, TransferedFile> transfersById, Instant now) {
        return switch (task.phase()) {
            case SEARCH_INIT -> slskdService.searchResults(task.songName())
                    .map(state -> stateMachine.afterSearchInit(task, state, now));

            // A missing entry (task.searchId() not in the map) is passed through as null and handled
            // by decideAfterSearchPoll/the state machine identically to "still running" — see the
            // Javadoc on DownloadStateMachine.afterSearchPoll.
            case SEARCH_POLL -> decideAfterSearchPoll(task, searchesById.get(task.searchId()), now);

            // No intent write before this call: an occasional duplicate download after a crash mid-
            // enqueue is an accepted cost, not guarded against. See the note under DownloadTask.
            case DOWNLOAD_INIT -> slskdService.enqueueDownload(
                            task.currentCandidate().username(), task.currentCandidate().toSearchFile())
                    .map(response -> stateMachine.afterDownloadInit(task, response, now));

            // Same "missing means still running" handling as SEARCH_POLL, via TransferedFileUtil's
            // existing null-safety — see the Javadoc on DownloadStateMachine.afterDownloadPoll.
            case DOWNLOAD_POLL -> Mono.just(stateMachine.afterDownloadPoll(
                    task, transfersById.get(task.slskdTransferId()), now));
        };
    }

    private Mono<DownloadDecision> decideAfterSearchPoll(DownloadTask task, SearchState state,
                                                         Instant now) {
        if (state == null || !Boolean.TRUE.equals(state.getIsComplete())) {
            return Mono.just(stateMachine.afterSearchPoll(task, state, List.of(), now));
        }
        return searchResultProcessor.selectBestFiles(state, task.songName())
                .map(selected -> selected.stream().map(DownloadCandidate::from).toList())
                .map(candidates -> stateMachine.afterSearchPoll(task, state, candidates, now))
                // The search is complete either way — with or without usable candidates — so its
                // slskd-side record is no longer needed. Pruning it (unlike pruning a transfer) is
                // safe: DELETE is a distinct verb from cancel, a search holds no partial-download
                // state to lose, and the candidates are already copied into this row. Best-effort:
                // a failed delete just leaves one harmless extra row in slskd.
                .flatMap(decision -> slskdService.deleteSearch(task.searchId())
                        .onErrorResume(error -> {
                            log.warn("Could not delete completed search {}", task.searchId(), error);
                            return Mono.empty();
                        })
                        .thenReturn(decision));
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew cleanTest test --tests '*DownloadStepExecutorTest'`
Expected: PASS, 11 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdService.java src/main/java/com/catacomb5099/naviseerr/download/DownloadStepExecutor.java src/test/java/com/catacomb5099/naviseerr/download/DownloadStepExecutorTest.java
git commit -m "feat(download): add per-phase slskd step executor fed by two batched polls per pass"
```

**Note — the accepted risk in `getAllDownloads()`, to revisit during Task 8.** `GET /transfers/downloads`
has no pagination, date filter, or state filter (confirmed against slskd source — only `includeRemoved`
exists). On an install with years of history this response could be large enough that batching costs
more than it saves. This plan pencils in the batched version because it is the architecturally simpler
default and the swap is cheap either way — Task 8 Step 2 includes measuring the real response size
(`curl ... | wc -c`) before this ships. If it is large, the fallback is per-transfer polling
(`slskdService.getDownloadProgress(username, transferId)`, already used nowhere else and safe to bring
back), parallelised with `flatMap` in `DownloadTaskRunner` (Task 5) instead of made from a shared map.
**That swap changes only the `case DOWNLOAD_POLL` branch above and `DownloadTaskRunner`'s call site —
the state machine, the columns, and every other test are unaffected**, because `afterDownloadPoll`
already takes a single `TransferedFile` regardless of where it came from. `GET /searches` carries no
equivalent risk: it is confirmed to report completion state directly, and completed searches are
pruned (Step 4 above), so the live set stays small regardless of history.

---

### Task 5: The loop, and the cutover

The only task that changes runtime behaviour. It deletes the in-heap path in the same commit, because two drivers competing over the same rows is not a state worth having even briefly.

**Files:**
- Create: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java`
- Modify: `src/main/resources/application.yaml`
- Delete: `src/main/java/com/catacomb5099/naviseerr/download/DownloadQueue.java`
- Delete: `src/main/java/com/catacomb5099/naviseerr/download/DownloadWorker.java`
- Delete: `src/main/java/com/catacomb5099/naviseerr/download/PendingDownloadRunner.java`
- Delete: `src/main/java/com/catacomb5099/naviseerr/download/DownloadFulfillment.java`
- Delete: `src/test/java/com/catacomb5099/naviseerr/download/DownloadQueueTest.java`
- Delete: `src/test/java/com/catacomb5099/naviseerr/download/DownloadWorkerTest.java`
- Delete: `src/test/java/com/catacomb5099/naviseerr/download/PendingDownloadRunnerTest.java`
- Delete: `src/test/java/com/catacomb5099/naviseerr/download/DownloadFulfillmentTest.java`
- Test: `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRunnerTest.java`

**Interfaces:**
- Consumes: `DownloadTaskRepository`, `DownloadStepExecutor`, `DownloadService.finishDownload`, `SlskdService` (`getAllSearches`, `getAllDownloads`), `Clock`.
- Produces: `DownloadTaskRunner.pass()` -> `Mono<Void>`, package-private so tests can drive one pass deterministically without waiting on `Flux.interval`.

- [ ] **Step 1: Add the config keys**

In `src/main/resources/application.yaml`, delete the `download-runner` and `download-worker` blocks and the two now-unused `slskd-service` keys, then add:

```yaml
download-task:
  loop-interval-ms: 2000
  batch-size: 10
  lease-duration-ms: 60000
  # Two SEPARATE limits, because they protect different things and conflating them is what the
  # deleted flatMap(3) did wrong.
  #
  # How many user requests are worked on at once. Counts `downloads` rows, so one collection of
  # 500 songs is ONE in-flight download and cannot lock everything else out of admission.
  max-concurrent-downloads: 20
  # How many slskd transfers may exist at once. This protects bandwidth and peers' upload queues.
  # Deliberately NOT scoped per collection: work is taken oldest-first, so one collection may
  # legitimately hold every slot until it is done. See "Future work" for the planned user-facing
  # option to change that.
  max-concurrent-transfers: 20
  # Searches finish in seconds; transfers run for minutes to hours. Polling both at one cadence
  # is waste, so they get separate intervals.
  search-poll-interval-ms: 2000
  download-poll-interval-ms: 5000
  # Real duration budgets, replacing slskd-service.max-poll-attempts — which, with a doubling
  # backoff and no cap, spanned roughly two years and was therefore not a timeout at all.
  search-budget-ms: 120000
  download-budget-ms: 3600000
```

Remove from `slskd-service`: `max-poll-attempts`, `first-back-off-duration-ms`. Keep `retry-count: 2`
(it is the per-candidate retry limit) and `max-files-per-download: 10` (the candidate cap, still applied
inside `selectBestFiles`).

**Operator action, documented here because this is where the retry tier it affects is configured.**
slskd 0.26.0+ retries a failed download itself (`transfers.download.retry.attempts`, in *slskd's own*
config — naviseerr has no access to it). Left at its default, this compounds with the candidate-retry
tier above in an unresolved way: if slskd marks a transfer `Errored` and only then retries, naviseerr's
poll sees the failure and moves on to the next candidate while slskd is still retrying the old one —
two transfers of the same file. Add this comment above the `slskd-service` block:

```yaml
# Operator note: this project owns candidate retry/failover (retry-count above). Set slskd's OWN
# config key `transfers.download.retry.attempts` to 0 (or 1) so its per-file retry does not compound
# with this tier — see docs/decisions/durable-download-state-machine-13-08-2026.md. Unverified against
# a live failure; confirm the interaction during Task 8.
```

- [ ] **Step 2: Write the failing test**

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DownloadTaskRunnerTest {

    private DownloadTaskRepository repository;
    private DownloadStepExecutor executor;
    private DownloadService downloadService;
    private SlskdService slskdService;
    private DownloadTaskRunner runner;

    @BeforeEach
    void setUp() {
        repository = mock(DownloadTaskRepository.class);
        executor = mock(DownloadStepExecutor.class);
        downloadService = mock(DownloadService.class);
        slskdService = mock(SlskdService.class);
        when(repository.admitNewDownloads(anyInt(), any())).thenReturn(Mono.just(0L));
        when(repository.countActiveDownloads()).thenReturn(Mono.just(0L));
        when(repository.countActiveTransfers()).thenReturn(Mono.just(0L));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(Flux.empty());
        when(repository.save(any())).thenReturn(Mono.just(1L));
        when(downloadService.finishDownload(any(), any(), any(), any())).thenReturn(Mono.just(1L));
        when(slskdService.getAllSearches()).thenReturn(Flux.empty());
        when(slskdService.getAllDownloads()).thenReturn(Flux.empty());
        runner = new DownloadTaskRunner(repository, executor, downloadService, slskdService,
                Clock.fixed(T0, ZoneOffset.UTC),
                Duration.ofSeconds(2), 10, Duration.ofSeconds(60), 20, 20);
    }

    @Test
    void atTheTransferCap_downloadInitTasksAreExcludedFromTheClaim() {
        when(repository.countActiveTransfers()).thenReturn(Mono.just(20L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(false));
    }

    @Test
    void belowTheTransferCap_downloadInitTasksAreClaimable() {
        when(repository.countActiveTransfers()).thenReturn(Mono.just(19L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(true));
    }

    @Test
    void pass_admitsUpToRemainingCapacity() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(18L));

        runner.pass().block();

        verify(repository).admitNewDownloads(2, T0);
    }

    @Test
    void pass_admitsNothingWhenAtCapacity() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(20L));

        runner.pass().block();

        verify(repository, never()).admitNewDownloads(anyInt(), any());
    }

    @Test
    void pass_admitsAtMostBatchSize() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(0L));

        runner.pass().block();

        verify(repository).admitNewDownloads(10, T0);
    }

    @Test
    void pass_whenNothingIsClaimed_neverCallsEitherBatchedSlskdEndpoint() {
        runner.pass().block();

        verify(slskdService, never()).getAllSearches();
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void aClaimedSearchPollTask_triggersGetAllSearches_butNotGetAllDownloads() {
        DownloadTask task = searchPolling("s1");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));

        runner.pass().block();

        verify(slskdService).getAllSearches();
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void aClaimedDownloadPollTask_triggersGetAllDownloads_butNotGetAllSearches() {
        DownloadTask task = downloadPolling(candidates("alice"), 0, 0, "abc");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(5)))));

        runner.pass().block();

        verify(slskdService).getAllDownloads();
        verify(slskdService, never()).getAllSearches();
    }

    @Test
    void theFetchedBatchesArePassedToTheExecutorForTheMatchingRow() {
        DownloadTask task = searchPolling("s1");
        SearchState state = SlskdFixtures.searchState("s1", true, "Completed");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(slskdService.getAllSearches()).thenReturn(Flux.just(state));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));

        runner.pass().block();

        verify(executor).execute(eq(task), eq(java.util.Map.of("s1", state)), eq(java.util.Map.of()));
    }

    @Test
    void advanceDecision_savesTheNextTask_andDoesNotFinishTheDownload() {
        DownloadTask task = at(DownloadPhase.SEARCH_INIT);
        DownloadTask next = task.withPhase(DownloadPhase.SEARCH_POLL, T0);
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Advance(next)));

        runner.pass().block();

        verify(repository).save(next);
        verify(downloadService, never()).finishDownload(any(), any(), any(), any());
    }

    @Test
    void continueDecision_savesTheNextTask() {
        DownloadTask task = searchPolling("s1");
        DownloadTask next = task.dueAt(T0.plusSeconds(2));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(next)));

        runner.pass().block();

        verify(repository).save(next);
    }

    @Test
    void terminalDecision_finishesTheDownload_andNeverSavesTheTask() {
        DownloadTask task = searchPolling("s1");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Terminal(DownloadStatus.FAILED, "no candidates")));

        runner.pass().block();

        verify(downloadService).finishDownload(eq(ID), eq(DownloadStatus.FAILED), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void oneFailingStepDoesNotStopTheOthersInTheSamePass() {
        DownloadTask bad = searchPolling("bad");
        DownloadTask good = searchPolling("good");
        DownloadTask goodNext = good.dueAt(T0.plusSeconds(2));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(Flux.just(bad, good));
        when(executor.execute(eq(bad), any(), any())).thenReturn(Mono.error(new RuntimeException("boom")));
        when(executor.execute(eq(good), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(goodNext)));

        runner.pass().block();

        verify(repository).save(goodNext);
    }

    @Test
    void aFailedWriteDoesNotStopThePass() {
        DownloadTask task = searchPolling("s1");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));
        when(repository.save(any())).thenReturn(Mono.error(new RuntimeException("db down")));

        runner.pass().block();   // must complete, not throw

        verify(repository).save(any());
    }

    @Test
    void claimUsesTheConfiguredBatchSizeAndLease() {
        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)), eq(true));
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew cleanTest test --tests '*DownloadTaskRunnerTest'`
Expected: compilation failure — `cannot find symbol: class DownloadTaskRunner`.

- [ ] **Step 4: Write `DownloadTaskRunner`**

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The level-triggered driver. Every pass asks the database what is due and acts on the answer, so a
 * lost wakeup costs one interval instead of a download and there is nothing in the heap to lose on
 * restart.
 *
 * <p>Passes are serialised with {@code concatMap}, so a slow pass delays the next one. Accepted for
 * simplicity: leases already make overlapping passes safe, so switching later needs no other change.
 * Stepping the rows CLAIMED WITHIN one pass is concurrent ({@code flatMap(batchSize)}) — the earlier
 * {@code concatMap} there processed claimed rows strictly one at a time, so a batch of 10 rows each
 * waiting on a 10s slskd timeout could make one pass take up to 100 seconds. Nothing needs a thread
 * pool for this: WebFlux already runs an event loop per core: the only thing wrong was the sequencing.
 */
@Slf4j
@Component
public class DownloadTaskRunner {

    private final DownloadTaskRepository repository;
    private final DownloadStepExecutor executor;
    private final DownloadService downloadService;
    private final SlskdService slskdService;
    private final Clock clock;
    private final Duration loopInterval;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxConcurrentDownloads;
    private final int maxConcurrentTransfers;
    /** Identifies this process in lease_owner. Nothing depends on it surviving a restart. */
    private final String instanceId = UUID.randomUUID().toString();
    private Disposable subscription;

    public DownloadTaskRunner(
            DownloadTaskRepository repository,
            DownloadStepExecutor executor,
            DownloadService downloadService,
            SlskdService slskdService,
            Clock clock,
            @Value("${download-task.loop-interval-ms:2000}") Duration loopInterval,
            @Value("${download-task.batch-size:10}") int batchSize,
            @Value("${download-task.lease-duration-ms:60000}") Duration leaseDuration,
            @Value("${download-task.max-concurrent-downloads:20}") int maxConcurrentDownloads,
            @Value("${download-task.max-concurrent-transfers:20}") int maxConcurrentTransfers) {
        this.repository = repository;
        this.executor = executor;
        this.downloadService = downloadService;
        this.slskdService = slskdService;
        this.clock = clock;
        this.loopInterval = loopInterval;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxConcurrentDownloads = maxConcurrentDownloads;
        this.maxConcurrentTransfers = maxConcurrentTransfers;
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(loopInterval)
                .onBackpressureDrop()
                .concatMap(tick -> pass())
                .subscribe();
    }

    Mono<Void> pass() {
        Instant now = clock.instant();
        return admit(now)
                .then(stepDueTasks(now))
                .onErrorResume(error -> {
                    log.error("Download task pass failed", error);
                    return Mono.empty();
                });
    }

    /**
     * Claims only up to remaining capacity, so no download is ever admitted and then rejected — which
     * is why nothing needs to revert a row to PENDING. Counts in-flight DOWNLOADS, not tasks: a
     * 500-song collection is one download, so it cannot starve admission for everything else.
     */
    private Mono<Void> admit(Instant now) {
        return repository.countActiveDownloads()
                .flatMap(inFlight -> {
                    int slots = Math.min(batchSize, maxConcurrentDownloads - inFlight.intValue());
                    if (slots <= 0) {
                        return Mono.empty();
                    }
                    return repository.admitNewDownloads(slots, now)
                            .doOnNext(admitted -> {
                                if (admitted > 0) log.info("Admitted {} download(s)", admitted);
                            });
                })
                .then();
    }

    /**
     * Gates only the step that STARTS a transfer. Searches and polls of already-running transfers are
     * never gated — polling is one cheap GET, and starving it stalls a download slskd is finishing.
     */
    private Mono<Void> stepDueTasks(Instant now) {
        return repository.countActiveTransfers()
                .flatMapMany(active -> {
                    boolean transferSlotsFree = active < maxConcurrentTransfers;
                    return repository.claimDueTasks(batchSize, instanceId, now, leaseDuration,
                            transferSlotsFree);
                })
                .collectList()
                .flatMap(this::stepAll);
    }

    /**
     * Fetches the two batched slskd lists ONCE per pass — not once per claimed row — and only when at
     * least one claimed row actually needs one, so an idle pass (nothing claimed) and a pass with only
     * SEARCH_INIT/DOWNLOAD_INIT rows make zero calls to either endpoint. This, together with
     * {@link DownloadStepExecutor} reading from the resulting maps instead of calling slskd itself, is
     * what turns "one call per download per poll" into "two calls per pass, however many downloads
     * are in flight."
     */
    private Mono<Void> stepAll(List<DownloadTask> claimed) {
        if (claimed.isEmpty()) {
            return Mono.empty();
        }
        boolean needsSearches = claimed.stream().anyMatch(t -> t.phase() == DownloadPhase.SEARCH_POLL);
        boolean needsTransfers = claimed.stream().anyMatch(t -> t.phase() == DownloadPhase.DOWNLOAD_POLL);

        Mono<Map<String, SearchState>> searches = needsSearches
                ? slskdService.getAllSearches().collectMap(SearchState::getId)
                : Mono.just(Map.of());
        Mono<Map<String, TransferedFile>> transfers = needsTransfers
                ? slskdService.getAllDownloads().collectMap(TransferedFile::getId)
                : Mono.just(Map.of());

        return Mono.zip(searches, transfers)
                .flatMap(fetched -> Flux.fromIterable(claimed)
                        .flatMap(task -> stepOne(task, fetched.getT1(), fetched.getT2()), batchSize)
                        .then());
    }

    /** Every task is isolated: one bad step must never abort the rest of the pass. */
    private Mono<Void> stepOne(DownloadTask task, Map<String, SearchState> searchesById,
                               Map<String, TransferedFile> transfersById) {
        return executor.execute(task, searchesById, transfersById)
                .flatMap(decision -> apply(task, decision))
                .onErrorResume(error -> {
                    log.error("Download {} step {} could not be applied; the lease will expire and "
                            + "the row will be retried", task.downloadId(), task.phase(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> apply(DownloadTask task, DownloadDecision decision) {
        return switch (decision) {
            case DownloadDecision.Advance advance -> repository.save(advance.next()).then();
            case DownloadDecision.Continue proceed -> repository.save(proceed.next()).then();
            case DownloadDecision.Terminal terminal -> {
                log.info("Download {} finished as {}{}", task.downloadId(), terminal.status(),
                        terminal.message() == null ? "" : " (" + terminal.message() + ")");
                yield downloadService.finishDownload(task.downloadId(), terminal.status(),
                        terminal.message(), clock.instant()).then();
            }
        };
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew cleanTest test --tests '*DownloadTaskRunnerTest'`
Expected: PASS, 13 tests.

- [ ] **Step 6: Delete the in-heap path**

```bash
git rm src/main/java/com/catacomb5099/naviseerr/download/DownloadQueue.java \
       src/main/java/com/catacomb5099/naviseerr/download/DownloadWorker.java \
       src/main/java/com/catacomb5099/naviseerr/download/PendingDownloadRunner.java \
       src/main/java/com/catacomb5099/naviseerr/download/DownloadFulfillment.java \
       src/test/java/com/catacomb5099/naviseerr/download/DownloadQueueTest.java \
       src/test/java/com/catacomb5099/naviseerr/download/DownloadWorkerTest.java \
       src/test/java/com/catacomb5099/naviseerr/download/PendingDownloadRunnerTest.java \
       src/test/java/com/catacomb5099/naviseerr/download/DownloadFulfillmentTest.java
```

- [ ] **Step 7: Fix the one stale property reference**

`DownloadServiceClaimIT` line 23 has `@TestPropertySource(properties = "download-runner.interval-ms=3600000")`, which now parks nothing. Change it to:

```java
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
```

Leave every test method in that class untouched — it is the guard that `downloads` and the `Download`
entity were not changed.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew cleanTest test`
Expected: `BUILD SUCCESSFUL`. Confirm the executed test count in `build/test-results/test/*.xml` rather
than trusting `BUILD SUCCESSFUL` alone.

- [ ] **Step 9: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(download): drive downloads from a durable task loop and delete the in-heap queue"
```

---

### Task 6: Remove the in-process polling machinery

Pure deletion — Task 5 already replaced every one of these paths. Separate commit so a bisect stays meaningful and review is incremental.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessor.java`
- Modify: `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdService.java`
- Delete: `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdDownloadProcessor.java`
- Delete: `src/main/java/com/catacomb5099/naviseerr/util/networkcalls/ReactivePoller.java`
- Delete: `src/test/java/com/catacomb5099/naviseerr/util/networkcalls/ReactivePollerTest.java`
- Delete: `src/test/java/com/catacomb5099/naviseerr/services/slskd/SlskdDownloadProcessorTest.java`
- Modify: `src/test/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessorTest.java`

**Interfaces:**
- Produces: `SlskdSearchResultProcessor` retains only `selectBestFiles(SearchState, String)` plus its two private predicates. No other class's signature changes. `SlskdService.getSearchResultsProgress(String)` is removed.

- [ ] **Step 1: Confirm nothing still calls the doomed methods**

Run: `grep -rn "pollUntilComplete\|ReactivePoller\|SlskdDownloadProcessor\|getSearchResultsProgress" src/main src/test`
Expected: hits only inside the files listed above. If anything else appears, stop and report.

`getSearchResultsProgress` is going away because after Tasks 4–5 it has zero callers: search polling is
batched (Task 4), and the batching is unconditional, not the accepted-risk fallback that
`getDownloadProgress` is for download polling (see Task 4's accepted-risk note and Task 8 Step 3).
**Do not remove `getDownloadProgress`** — it is dead code today for the same reason, but it is the
documented fallback if the batched download-poll response turns out to be too large in Task 8, so
removing it now would mean re-adding it later for no benefit.

- [ ] **Step 2: Trim `SlskdSearchResultProcessor`**

Delete the `pollUntilComplete` method entirely, along with the now-unused `maxPollAttempts` and
`firstBackOffDuration` `@Value` fields and the `ReactivePoller`, `RetryBackoffSpec`, `Duration`,
`Predicate`, `Supplier` and `Function` imports. Keep `selectBestFiles`, `isRelevant`,
`isFlacAndHighBitrate`, the `minBitRate` and `maxFilesPerDownload` fields, the constructor, and the
`log` static import used by `selectBestFiles`.

- [ ] **Step 3: Delete the rest, and remove the now-dead `getSearchResultsProgress`**

```bash
git rm src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdDownloadProcessor.java \
       src/main/java/com/catacomb5099/naviseerr/util/networkcalls/ReactivePoller.java \
       src/test/java/com/catacomb5099/naviseerr/util/networkcalls/ReactivePollerTest.java \
       src/test/java/com/catacomb5099/naviseerr/services/slskd/SlskdDownloadProcessorTest.java
rmdir src/main/java/com/catacomb5099/naviseerr/util/networkcalls src/test/java/com/catacomb5099/naviseerr/util/networkcalls 2>/dev/null || true
```

In `SlskdService.java`, delete the `getSearchResultsProgress` method (the `GET /searches/{id}` single-search
lookup). Leave `getDownloadProgress`, `getAllSearches`, `getAllDownloads`, `deleteSearch`,
`searchResults`, and `enqueueDownload` exactly as they are.

- [ ] **Step 4: Prune the four dead tests from `SlskdSearchResultProcessorTest`**

Delete exactly these methods:

- `pollUntilComplete_emptyQuery_returnsEmptyMono_andDoesNotPollProgress`
- `pollUntilComplete_searchResultsErrors_propagatesError_andDoesNotCallProgress`
- `pollUntilComplete_searchReturnsStartState_butProgressErrors_calledOnceThenError`
- `pollUntilComplete_progressReturnsCompleteState_emitsThatState`

Their behaviour is now covered by `DownloadStateMachineTest` (`searchInit_*`, `searchPoll_*`) and
`DownloadStepExecutorTest` (`searchInit_callsSearchOnceAndAdvances`,
`anSlskdErrorDuringSearchInit_becomesADecision_notAnErrorSignal`).

**Leave these four completely untouched** — they are the guard that ranking was not changed:

- `selectBestFiles_emptyResponses_returnsEmptyList`
- `selectBestFiles_filtersByRelevance_onlyRelevantIncluded`
- `selectBestFiles_keepsAboveMinAndFlac_filtersOutBelowMin`
- `selectBestFiles_ordersByUploadSpeed_descending`

Remove any `setUp` stubs and imports that only the deleted tests used; keep whatever the four
`selectBestFiles` tests need.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew cleanTest test`
Expected: `BUILD SUCCESSFUL`, with the four `selectBestFiles_*` tests passing with zero edits to their
bodies.

- [ ] **Step 6: Commit**

```bash
git add -A src/main src/test
git commit -m "refactor(slskd): remove in-process polling now that steps are durable"
```

---

### Task 7: Crash-recovery integration test

Proves the property the whole change exists for. Without this, nothing verifies end-to-end that a killed process resumes rather than stranding a row.

**Files:**
- Test: `src/test/java/com/catacomb5099/naviseerr/download/DownloadRecoveryIT.java`

**Interfaces:**
- Consumes: `DownloadTaskRepository`, `DownloadService`, `DownloadTaskRunner` (all Spring beans).

- [ ] **Step 1: Write the test**

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The properties this whole change exists to provide. Each test simulates a crash by leaving state
 * exactly as a dead process would have left it, then checks that the loop's own queries recover it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadRecoveryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired DownloadTaskRepository repository;
    @Autowired DownloadService downloadService;

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    private UUID insert(String status) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, song_name, status, created_at) "
                        + "VALUES (:id, 'song', :status, now())")
                .bind("id", id).bind("status", status).fetch().rowsUpdated().block();
        return id;
    }

    @Test
    void aDownloadKilledMidTransferResumesAtTheSameStep_notFromScratch() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository.claimDueTasks(10, "dead", NOW, LEASE, true).blockFirst();

        // The dead process had got as far as polling candidate 1's transfer.
        repository.save(new DownloadTask(id, "song", DownloadPhase.DOWNLOAD_POLL, NOW,
                NOW.plusSeconds(5), "s1",
                com.catacomb5099.naviseerr.support.DownloadTaskFixtures.candidates("alice", "bob"),
                1, 0, "bob", "music/bob/song.flac", "abc", null)).block();
        // ...then took the lease and died without clearing it.
        repository.claimDueTasks(10, "dead", NOW.plusSeconds(5), LEASE, true).blockFirst();

        DownloadTask resumed = repository
                .claimDueTasks(10, "alive", NOW.plusSeconds(70), LEASE, true).blockFirst();

        assertNotNull(resumed, "an expired lease must make the row claimable again");
        assertEquals(DownloadPhase.DOWNLOAD_POLL, resumed.phase());
        assertEquals("abc", resumed.slskdTransferId());
        assertEquals(1, resumed.candidateIndex());
    }

    @Test
    void anInProgressDownloadThatLostItsTaskRowIsRecovered_notStrandedForever() {
        UUID id = insert("IN_PROGRESS");   // exactly today's stranded-row state

        repository.admitNewDownloads(10, NOW).block();

        DownloadTask recovered = repository.claimDueTasks(10, "a", NOW, LEASE, true).blockFirst();
        assertNotNull(recovered);
        assertEquals(id, recovered.downloadId());
        assertEquals(DownloadPhase.SEARCH_INIT, recovered.phase());
    }

    @Test
    void aTerminalDownloadIsNeverReadmitted() {
        insert("SUCCEEDED");

        assertEquals(0L, repository.admitNewDownloads(10, NOW).block());
        assertEquals(0L, repository.countActiveTransfers().block());
    }

    @Test
    void aDownloadReachingTerminalTwiceKeepsItsFirstStatus() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.FAILED, "boom", NOW).block();

        assertEquals("SUCCEEDED", template.getDatabaseClient()
                .sql("SELECT status FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block());
        assertEquals("FAILED", template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block(),
                "the task write is unconditional, which is what prevents a livelock");
    }

    @Test
    void aFinishedDownloadIsNeverSteppedAgain() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        // Far in the future, so next_attempt_at is long past. Only the terminal-phase filter and
        // the partial index stop this row coming back.
        assertNull(repository.claimDueTasks(10, "a", NOW.plusSeconds(86_400), LEASE, true)
                .blockFirst());
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew cleanTest test --tests '*DownloadRecoveryIT'`
Expected: PASS, 5 tests.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew cleanTest test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/catacomb5099/naviseerr/download/DownloadRecoveryIT.java
git commit -m "test(download): cover lease-expiry resume and lost-task-row recovery"
```

---

### Task 8: Manual end-to-end verification against live slskd

The only place the slskd state strings in `SlskdSearchState` can actually be confirmed. Automated tests cannot do this.

**Files:** none. This task changes code only if a check fails.

- [ ] **Step 1: Start Postgres and the app**

```bash
docker compose up -d postgres
```

Then run the application with your local slskd reachable at `slskd-service.url`.

- [ ] **Step 2: Confirm concurrency — the acceptance test for the whole change**

Request one song that will resolve fast and two that will be slow:

```bash
curl -X POST 'http://localhost:8080/download/never%20gonna%20give%20you%20up'
```

Expected: the fast song reaches `SUCCEEDED` **while** a slow transfer is still mid-flight. Under the
deleted code a slow download held one of three `flatMap` slots for its whole duration.

- [ ] **Step 3: Measure the batched download-poll response — decides whether Task 4's batching stays**

```bash
curl -s -H "X-API-Key: $SLSKD_API_KEY" 'http://<slskd-host>:5030/api/v0/transfers/downloads' | wc -c
```

Expected: a small number (tens of KB) on a fresh or lightly-used slskd instance. If this is large on a
real, long-used instance, `GET /transfers/downloads` is returning more history than is comfortable to
fetch every pass — see Task 4's accepted-risk note for the fallback (per-transfer polling via
`slskdService.getDownloadProgress`, parallelised in `DownloadTaskRunner` instead of read from a shared
map). That swap touches only `DownloadStepExecutor`'s `DOWNLOAD_POLL` branch and the runner's call
site — the state machine, the schema, and every other test are unaffected.

- [ ] **Step 4: Confirm resume, by killing the process mid-transfer**

While a transfer is in `DOWNLOAD_POLL`, kill the JVM. Restart it and watch the logs.

Expected: within one `lease-duration-ms` the row is reclaimed and polling continues against the **same**
`slskd_transfer_id`. Confirm no second transfer appeared in slskd for that file. Under the deleted code
the row would sit at `IN_PROGRESS` forever.

- [ ] **Step 5: Confirm the search-state strings**

```bash
curl -s -H "X-API-Key: $SLSKD_API_KEY" 'http://<slskd-host>:5030/api/v0/searches' | grep -o '"state":"[^"]*"' | sort -u
```

Expected: every distinct value appears in `SlskdSearchState`. If a value is missing, add it — and if it
represents a genuine failure, set its `failure` flag. Any value left unmapped is safe (it falls through
to the no-candidates guard) but loses the fast-fail shortcut.

- [ ] **Step 6: Confirm the timeout branch**

Temporarily set `download-task.download-budget-ms: 30000` and request a song whose transfer will not
finish in 30 seconds.

Expected: the download lands `FAILED` with `timed out` in the logs, and `download_tasks.phase` for that
row reads `FAILED` with `failure_reason = 'timed out'` — retained, not deleted, per the retention
decision in Task 3. Revert the config afterwards.

- [ ] **Step 7: Force a real transfer failure and observe the slskd-retry interaction**

Pick a candidate likely to fail (an offline or heavily-queued peer) and let the transfer fail naturally,
or induce it by disconnecting mid-transfer. Watch both naviseerr's logs and slskd's own transfer list for
that file.

Expected, one of two outcomes, per the open question in "Future work — Handing same-peer retry to
slskd": either slskd's own retry is already effectively disabled (single failure observed, naviseerr
moves to the next candidate cleanly), or it is not, in which case set `transfers.download.retry.attempts`
in slskd's own config to `0` and re-run this step to confirm the compounding stops. Record whichever it
was — this closes the one open question that note left unresolved.

- [ ] **Step 8: Record the results**

Note any corrections in the PR description. If Step 5 changed `SlskdSearchState`, commit that separately:

```bash
git add src/main/java/com/catacomb5099/naviseerr/schema/slskd/SlskdSearchState.java
git commit -m "fix(slskd): correct search state values against the live API"
```

---

### Task 9: Documentation

The architecture docs describe an in-heap queue that no longer exists and present `ReactivePoller` as the project's core pattern. Leaving them would actively mislead the next reader — human or agent.

**Already done ahead of implementation** (these record decisions, which are true regardless of when the
code lands — do not redo them, but do check them against what you actually built):

- `AGENTS.md` "Target Download Manager Architecture" — rewritten; the RabbitMQ/Redis pipeline is marked
  rejected rather than pending.
- `AGENTS.md` "Schema Management Approach" — corrected; `schema.sql` *can* express additive column
  changes via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, so Flyway is scoped to renames, type changes,
  backfills, and altering the `downloads` status `CHECK` constraint.
- `AGENTS.md` engineering guideline on high-frequency progress — scoped to byte-level percentages, so it
  no longer reads as prohibiting this design.
- `docs/decisions/durable-download-state-machine-13-08-2026.md` — the full ADR.
- `docs/superpowers/specs/2026-08-13-durable-download-state-machine-design.md` — the design spec.

**Files:**
- Modify: `AGENTS.md` (the "Current Implementation State" section only)
- Rewrite: `docs/architecture/download-manager.md`
- Modify: `docs/architecture/README.md`
- Modify: `docs/architecture/reactive-patterns.md`
- Modify: `docs/architecture/slskd-integration.md`
- Modify: `docs/architecture/persistence.md`
- Modify: `docs/architecture/gotchas.md`
- Modify: `docs/architecture/codebase-map.md`
- Modify: `docs/conversations/INDEX.md`

- [ ] **Step 1: Rewrite `AGENTS.md`'s "Current Implementation State"**

This is the one `AGENTS.md` section still describing the deleted code. Replace the `Download Execution
Flow` numbered list with the admit / claim / step pass. **Delete the `> [!IMPORTANT]` block about missing
crash recovery** — it is now done, and leaving it is the single most misleading line in the file. In the
"does not have" list, remove "Resilience/recovery for in-flight work" and restate Redis and RabbitMQ as
**rejected** rather than absent, pointing at the new ADR. Add `download_tasks` beside `downloads` in the
table description, noting that its rows are retained in a terminal phase so a self-hoster can see
which peers were tried and how each failed.

- [ ] **Step 2: Rewrite `docs/architecture/download-manager.md`**

Describe the pass, the phases, leases, `next_attempt_at`, per-phase intervals and budgets, the two
three bounds (`batch-size / loop-interval` caps slskd request rate; `max-concurrent-downloads` caps
user requests worked on at once, counting `downloads` so a collection is one; `max-concurrent-transfers`
caps real slskd transfers and gates only the step that starts one),
the atomic terminal write, and the recovery walkthrough. Include the `download_tasks` DDL and the
timeline table from the design spec. Cover the two batched slskd calls (`GET /searches`,
`GET /transfers/downloads`, fetched once per pass and only when a claimed row needs one), completed-search
pruning, and that stepping the claimed rows within a pass is concurrent (`flatMap`), not sequential.
Update the doc's freshness header to `current as of 2026-08-13, branch durable-download-state-machine`.

- [ ] **Step 3: Update the remaining architecture docs**

- `reactive-patterns.md` — delete the `ReactivePoller` polling-via-retry section (the class is gone) and
  the `Sinks` work-queue section. Replace with the pass-level interval-`concatMap` loop (unchanged: passes
  are still serialised) plus the "level-triggered beats edge-triggered" rationale, and note that
  *stepping the rows claimed within one pass* uses `flatMap`, not `concatMap` — the two are easy to
  conflate and this project got it wrong once already.
- `slskd-integration.md` — replace both `pollUntilComplete` descriptions with the four steps. Keep the
  `selectBestFiles` and track-matching sections. Remove the `TransferedFileUtil` known-bug note (fixed in
  Task 2), the `max-poll-attempts` / `first-back-off-duration-ms` knobs, and `getSearchResultsProgress`
  (removed in Task 6). Add `SlskdSearchState`, and note that `TimedOut` is a failure for a transfer but
  normal completion for a search. Document `getAllSearches`/`getAllDownloads`/`deleteSearch`, the
  accepted risk in batching download polls (no pagination/date/state filter on
  `GET /transfers/downloads` — Task 4's note, resolved during Task 8), and that naviseerr owns
  candidate-level retry deliberately rather than delegating to slskd's own `transfers.download.retry`
  (`docs/decisions/durable-download-state-machine-13-08-2026.md`).
- `persistence.md` — add `download_tasks`, the admit CTE, the lease-based claim, and the terminal CTE.
  Note that task rows are retained in a terminal phase, and that the partial index on
  `next_attempt_at` is what keeps the due-work query fast despite unbounded history. Also cover the
  Flyway layout (`db/migration/V1__baseline.sql`, `V2__download_tasks.sql`) and `baseline-on-migrate`.
- `gotchas.md` — remove the `TransferedFileUtil` entry; keep the committed-secrets and
  `TrackMatchingService` `"-"` separator entries. Add: `SlskdSearchState`'s values are unverified
  guesses that fail safe, and the `"flac"` check is still case-sensitive.
- `codebase-map.md` — update the `download` package listing and remove `util/networkcalls`.
- `README.md` — its one-line descriptions of `download-manager.md` ("the event-driven download queue")
  and `reactive-patterns.md` ("polling-via-retry and the Sinks work queue") both describe deleted code.
  Also update the freshness header at the top.
- `conversations/INDEX.md` — add a row for this session following the existing table format.

Every architecture doc carries a `> Status: current as of <date>, branch <name>` header. Update it in each
file you touch; a stale header is worse than no header because it invites trust.

- [ ] **Step 4: Check every doc link still resolves**

Run: `grep -rhoE '\]\(\.\.?/[^)]+\)' docs AGENTS.md | tr -d '](' | sed 's/)$//' | sort -u`
Then confirm each path exists. Expected: no references to `ReactivePoller.java`, `DownloadQueue.java`,
`DownloadWorker.java`, `PendingDownloadRunner.java`, `DownloadFulfillment.java`, or
`SlskdDownloadProcessor.java`.

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md docs
git commit -m "docs: describe the durable download state machine and retire the broker-based target"
```

---

### Task 10: Rank candidates by availability, not just claimed speed

**Runs last, deliberately.** Tasks 1–9 must pass with `selectBestFiles` byte-for-byte unchanged — that is the guard proving the pipeline work did not touch ranking. This task changes ranking on purpose, with its own tests, once that guard has done its job.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessor.java`
- Test: `src/test/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessorTest.java`

**Why.** `selectBestFiles` currently sorts purely on `uploadSpeed`:

```java
.sorted(Comparator.comparingInt(entry -> -entry.getKey().getUploadSpeed()))
```

`uploadSpeed` is the remote client's **self-reported historical average across all its uploads to anyone** — not a measurement of the route to you, and not verified. Meanwhile two fields sitting next to it on `SearchResponseItem` are ignored entirely:

```java
Boolean hasFreeUploadsSlot;   // can they start right now?
int queueLength;              // how many people are ahead of you?
```

So today a peer claiming 10 MB/s with 40 people queued outranks a peer claiming 2 MB/s that can start immediately — and the first delivers nothing for an hour. `hasFreeUploadsSlot` is a fact about the present; `uploadSpeed` is a claim about the past. Prefer the fact.

- [ ] **Step 1: Write the failing tests**

Add to `SlskdSearchResultProcessorTest`. Reuse whatever helpers the existing four `selectBestFiles_*` tests use to build `SearchResponseItem` and `SearchFile`.

```java
    @Test
    void selectBestFiles_prefersAFreeSlotOverAFasterBusyPeer() {
        // fast, but 40 people ahead of you
        SearchResponseItem busy = peer("busy", 10_000_000, false, 40, file("busy/song.flac"));
        // slower, but can start right now
        SearchResponseItem free = peer("free", 2_000_000, true, 0, file("free/song.flac"));

        var result = processor.selectBestFiles(state(busy, free), "song").block();

        assertEquals("free", result.getFirst().getKey().getUsername());
    }

    @Test
    void selectBestFiles_amongFreePeers_prefersTheShorterQueue() {
        SearchResponseItem longer = peer("longer", 9_000_000, true, 5, file("longer/song.flac"));
        SearchResponseItem shorter = peer("shorter", 8_000_000, true, 1, file("shorter/song.flac"));

        var result = processor.selectBestFiles(state(longer, shorter), "song").block();

        assertEquals("shorter", result.getFirst().getKey().getUsername());
    }

    @Test
    void selectBestFiles_allElseEqual_stillPrefersTheFasterPeer() {
        SearchResponseItem slow = peer("slow", 1_000_000, true, 0, file("slow/song.flac"));
        SearchResponseItem fast = peer("fast", 9_000_000, true, 0, file("fast/song.flac"));

        var result = processor.selectBestFiles(state(slow, fast), "song").block();

        assertEquals("fast", result.getFirst().getKey().getUsername());
    }
```

- [ ] **Step 2: Run to verify the first two fail**

Run: `./gradlew cleanTest test --tests '*SlskdSearchResultProcessorTest'`
Expected: `selectBestFiles_prefersAFreeSlotOverAFasterBusyPeer` and `selectBestFiles_amongFreePeers_prefersTheShorterQueue` FAIL (both return the faster peer). `selectBestFiles_allElseEqual_stillPrefersTheFasterPeer` already passes.

- [ ] **Step 3: Replace the comparator**

```java
    // A free slot is a fact about now; uploadSpeed is the peer's own unverified claim about its
    // history. So: can they start at all, then how many people are ahead of you, then speed.
    private static final Comparator<Map.Entry<SearchResponseItem, SearchFile>> BY_AVAILABILITY =
            Comparator
                    .comparing((Map.Entry<SearchResponseItem, SearchFile> entry) ->
                            !Boolean.TRUE.equals(entry.getKey().getHasFreeUploadsSlot()))
                    .thenComparingInt(entry -> entry.getKey().getQueueLength())
                    .thenComparingInt(entry -> -entry.getKey().getUploadSpeed());
```

and use `.sorted(BY_AVAILABILITY)` in place of the existing `.sorted(...)` call. Nothing else in the method changes — the filters, the `maxFilesPerDownload` cap, and the log line stay as they are.

The first comparator key is negated (`!hasFreeUploadsSlot`) because `false` sorts before `true`, so peers *with* a free slot come first.

- [ ] **Step 4: Run to verify all pass**

Run: `./gradlew cleanTest test --tests '*SlskdSearchResultProcessorTest'`
Expected: PASS, including the four pre-existing `selectBestFiles_*` tests **unmodified** — relevance filtering, bitrate filtering, and the empty-response case are unaffected by ordering.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessor.java src/test/java/com/catacomb5099/naviseerr/services/slskd/SlskdSearchResultProcessorTest.java
git commit -m "feat(slskd): rank candidates by upload availability before claimed speed"
```

---

## Future work — deliberately not in this plan

Recorded so the reasoning is not lost, and so the next person does not rediscover it.

### Scheduling by estimated duration

Order the songs *waiting to start a transfer* by `file size / peer's claimed upload speed`, shortest first, so more downloads finish per hour. Sketch: a nullable `estimated_seconds` column on `download_tasks`, filled where the state machine already picks a candidate, and an `ORDER BY` on the claim query restricted to `DOWNLOAD_INIT` rows.

**Not built, and the reason matters.** The estimate would *not* need recomputing — both inputs come from the search response and neither changes afterwards. (`queueLength` and `hasFreeUploadsSlot` do go stale, but they are not part of the estimate.) The reason to skip it is that **the estimate only earns anything when more songs are waiting to start than there are free transfer slots.** With one user and 20 slots that is rare, so the payoff is near zero today.

**Revisit when there is queueing pressure** — large collections, multiple users, or a much lower transfer cap. Not when someone thinks of a better estimate.

Two guards it will need when it arrives:
- Shortest-first starves large files indefinitely. Cheap fix: one extra ordering term ahead of the estimate, e.g. `ORDER BY (phase_entered_at < now() - interval '30 minutes') DESC, estimated_seconds ASC` — anything waiting too long jumps the queue.
- It must apply **only** to the step that starts a transfer, never to polling. The claim query already has that seam (the `:transferSlotsFree` predicate), so this is an added `ORDER BY`, not a restructure.

Note that `DownloadCandidate` is stored as JSON in a `TEXT` column, so adding `uploadSpeed`, `queueLength` and `hasFreeUploadsSlot` to it later needs **no migration**. Nothing needs to be carried speculatively now.

### A user-facing choice between "finish one thing at a time" and "spread across everything"

Today one collection may hold every transfer slot until it is done, because work is taken oldest-first and there is no per-collection cap. That is the right default: if you asked for something, you probably want it finished.

But both behaviours are legitimate — sometimes you want one album complete now, sometimes you want a bit of everything. So this should eventually be an **option**, not a hardcoded policy. Implementation would be a per-collection in-flight cap read from user preference.

**Explicitly rejected:** anything adaptive that measures achieved throughput per peer and re-prioritises from it. It needs per-peer history, cannot be tuned without real usage data, and is far more machinery than a self-hosted music downloader warrants.

### Collections of songs

Reviewed in depth already; the agreed shape, so a later implementer does not redesign it:

- `downloads` gains `is_collection BOOLEAN`. **One `downloads` row per user request** — a 500-track playlist is one row, because the user made one request. A boolean is sufficient: the pipeline only needs to know "does this expand into children", and album-vs-playlist is display metadata belonging elsewhere.
- A separate `download_collection_tasks` table for the collection's own working state — *not* mixed into `download_tasks`, whose `phase` values and typed slskd columns are meaningless for a collection.
- Each song is a `download_tasks` row pointing at the collection task. Per-song history survives because task rows are now retained.
- **Create the collection task and every song task in one transaction.** All-or-nothing; there is no half-expanded state to detect or recover.
- **The collection counts its own songs; songs never notify the collection.** A song telling its parent "I'm done" is a message that can be lost — crash right after the song is marked terminal and nothing ever re-sends it, so the collection waits forever. Instead the collection is an ordinary row in the same loop: when due, it counts its unfinished songs and either finalises or reschedules. Nothing to lose.
- **Never store a succeeded/failed counter.** Counting rows is naturally idempotent; `n = n + 1` is not, and a step can run twice whenever a lease expires while work is still alive. Postgres makes the increment *atomic* but nothing can make it *idempotent* — those are different problems and only the second one matters here. Index `(collection_task_id, phase)` and count.
- Needs `PARTIAL_SUCCESS` on the `downloads.status` CHECK — hence the Flyway baseline in Task 3.

**Open question, not yet decided:** does the client send the tracklist, or does the server look it up? If the client sends it (it has just displayed the album, so it already knows), the whole thing is one transaction at request time and there is no expansion step at all. If the server must call a provider, a network call has to happen before the children can be inserted, which reintroduces a partially-created-list failure mode. Decide this before writing collection code — it determines whether the expansion step exists.

### Handing same-peer retry to slskd — considered, deferred

slskd 0.26.0 retries failed downloads itself:

```yaml
transfers:
  download:
    retry:
      partial: resume    # resumes from the incomplete file's size
      attempts: 3
      delay: 5000        # initial, ms
      max_delay: 60000   # ms
```

Per-file, same peer, exponential backoff. That overlaps exactly with naviseerr's inner retry tier (`slskd-service.retry-count`, applied per candidate), and slskd's version is technically better on two counts: real backoff, and it **resumes** a partial file where naviseerr re-enqueues from zero.

**Decided 2026-08-15: naviseerr keeps its own retry. Do not hand this over.** The reasoning is ownership, not capability — most users host slskd as a separate service, and splitting the retry tier across two applications means naviseerr can no longer confidently describe or guarantee its own behaviour. slskd also has no knowledge of the ranked candidate list, so it can only ever help with one of the two tiers, which limits the payoff. Revisit only if partial-resume becomes valuable enough to outweigh that.

**But there is an action item that is not optional.** slskd's retry appears to be **on by default** (`attempts: 3`), and naviseerr's config does not set it — so today the two tiers silently compound. Worse, the interaction is ambiguous and needs one observed real failure to resolve: if slskd marks a transfer `Errored` and *then* retries, naviseerr sees the failure, moves to the next candidate, and slskd keeps retrying the old one — two transfers of the same file. If slskd instead holds the transfer in a non-failure state while retrying, naviseerr never sees a failure and its own retry tier is dead code.

So: **explicitly pin slskd's `retry.attempts` to a known value** (0 or 1) so naviseerr owns the tier it thinks it owns. Verify the default and the state-during-retry behaviour against a live failure. The operator-facing config comment for this lives in Task 5 Step 1; verifying the actual interaction against a live failure is Task 8.

### Smaller items

- Retention: delete terminal `download_tasks` rows older than N months. Only if anyone ever complains about size.
- An append-only per-attempt log, for a UI view showing exactly which peers were tried and how each failed. The retained task row covers the common case; a full log is only needed to see *every* attempt rather than the last one.
### Cancellation — agreed direction

A `CANCELLED` status plus a flag the loop checks. The atomic terminal write already behaves correctly for it — a cancelled download's terminal step finds the status already set, skips the update, and still marks the task terminal so work stops.

Cancelling rows is only half of it. **A song in `DOWNLOAD_POLL` has a live transfer inside slskd that keeps downloading and keeps writing files after the row says cancelled.** Those transfers have to be cancelled upstream too, via `DELETE /api/v0/transfers/downloads/{username}/{id}` (which is a cancel verb — the destructive behaviour avoided elsewhere is exactly what is wanted here).

The agreed shape, decided 2026-08-15:

1. **One atomic statement** cancels the download, its collection task if any, and every non-terminal song task under it — and uses `RETURNING` to hand back the `(slskd_username, slskd_transfer_id)` pairs it just cancelled.
2. **Then fire best-effort cancels at slskd** for those pairs. Not atomic with step 1, and deliberately not made so.

**Failure here is accepted, explicitly.** If naviseerr crashes between the two steps, some transfers keep running and the user ends up with a few extra songs. That is not worth engineering against for a self-hosted music downloader. Do not add a compensating mechanism on the assumption it is needed.

The escape hatch, if that judgement ever changes: because task rows are now retained, a level-triggered sweep for *"cancelled tasks that still hold a `slskd_transfer_id`"* closes the gap completely, and needs no schema change to add. Note it, don't build it.

## Self-Review

**Spec coverage.** Every section of the design spec maps to a task: pattern and invariants -> Tasks 1, 5
(the loop and its comments); a separate table plus the Flyway baseline -> Task 3; one loop with an admit
and a claim step, batched fetch as a third -> Tasks 3–5; widened admit -> Tasks 3 and 7; leases replacing
a reaper -> Tasks 3, 5, 7; state machine as a function -> Task 1; per-phase intervals -> Tasks 1, 5; two
capacity bounds -> Task 5; atomic terminal write, task rows retained -> Task 3; the `DOWNLOAD_INIT` crash
window -> accepted by explicit decision rather than guarded against (Task 1's note under `DownloadTask`,
`docs/decisions/durable-download-state-machine-13-08-2026.md`); the two batched polls and completed-search
pruning -> Task 4; row-level concurrency (`flatMap` replacing `concatMap`) -> Task 5; prerequisite bug
fixes -> Task 2; deletions -> Tasks 5 and 6; ranking by availability -> Task 10; documentation -> Task 9;
cancellation's agreed shape -> "Future work". The deferred items (orphan adoption, an append-only attempt
log, collections, SSE) are named as non-goals and appear in "Future work" rather than as tasks.

**Placeholder scan.** No `TBD`/`TODO` steps; every code step carries complete code; every run step has an
exact command and expected outcome. Task 9 is the only prose-only task, which is appropriate — it edits
prose, and each step names the exact file and the exact content change. Task 4's accepted-risk note names
the one genuinely open empirical question (the size of `GET /transfers/downloads` on an old install) and
points at the exact command and fallback that resolve it, rather than asserting an answer.

**Type consistency.** `DownloadTask`'s 13 components (no `enqueueAttempted` — see Task 1's note) are used
with the same names and order in `DownloadTaskFixtures`, `DownloadStateMachine`, `DownloadTaskRepository`'s
`toTask`/`save`, and both ITs. `DownloadStateMachine`'s five constructor parameters match the four
`download-task.*` keys plus `slskd-service.retry-count`. `DownloadStepExecutor.execute` takes the task
plus both batch maps at every call site — `DownloadTaskRunner`, `DownloadStepExecutorTest`, and
`DownloadTaskRunnerTest`. `DownloadTaskRepository`'s public methods are each used by `DownloadTaskRunner`
or a test, and `save`/`claimDueTasks`/`admitNewDownloads`/`countActiveDownloads`/`countActiveTransfers`/
`finishDownload` are spelled identically everywhere; `markEnqueueAttempted` does not exist anywhere.
`claimDueTasks` takes five arguments and `finishDownload` four at every call site, production and test.
`SlskdService.getAllSearches`/`getAllDownloads`/`deleteSearch` are added once (Task 4 Step 1) and consumed
consistently by `DownloadStepExecutor` (deletion) and `DownloadTaskRunner` (the two batched fetches).
`DownloadDecision`'s three records are exhaustively switched in `DownloadTaskRunner.apply`.

**Ordering check.** Tasks 1–4 are additive and change no runtime behaviour, so they are independently
reviewable and can merge on their own. Task 5 is the only behavioural cutover. Task 6 is pure deletion and
is only safe after 5. Tasks 7–9 verify and document. A bisect through this sequence is meaningful.

# Download Progress Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every song being downloaded a durable percentage that a client can poll, so a progress bar can be built without adding infrastructure.

**Architecture:** One column, `download_tasks.progress_percent`, written by the `DOWNLOAD_POLL` step that already writes the row every poll — so the feature adds zero database round-trips. The value is read from slskd's `percentComplete` and overwritten (never accumulated), which makes it idempotent when a step re-runs after a lease expiry. One `GET` endpoint returns every active download with its current percentage; the client polls it and animates the bar itself with a CSS transition, so smoothness costs nothing on the server.

**Tech Stack:** Java 21, Spring Boot 4.0.2, Spring WebFlux, R2DBC (`r2dbc-postgresql`), Flyway, Postgres 16, JUnit 5, Mockito, Reactor `StepVerifier`, Testcontainers.

## Global Constraints

- **Java 21.** `build.gradle` pins `JavaLanguageVersion.of(21)`. Records, sealed interfaces and `List.getFirst()` are all in use already.
- **Spring Boot 4.0.2.** Do not change the version.
- **No new dependencies.** Everything needed is already on the classpath.
- **Runtime data access is R2DBC only.** No blocking JDBC calls on any request or loop path. The blocking `org.postgresql:postgresql` driver exists solely so Flyway can run migrations at boot.
- **Never block a reactive chain.** No `.block()` outside test code.
- **Progress is expressed as 0–100, everywhere, with no exceptions.** Column, Java field, JSON field, and slskd's own `percentComplete` all use the same scale. There is no 0–1 representation anywhere in this codebase, and introducing one is a defect.
- **Progress is only ever written by a statement that also guards on the row being non-terminal and lease-owned.** Never a standalone `UPDATE`. See Task 1 Step 4.
- **Run tests with `./gradlew cleanTest test`.** A bare `./gradlew test` reports `UP-TO-DATE` and runs nothing, which reads as a pass.
- **Migrations are immutable once written.** Add `V4__`, never edit `V3__`.

---

## Prerequisites

**This plan does not stand alone.** It extends
`docs/superpowers/plans/2026-08-13-durable-download-state-machine.md`, and every file it modifies is
created by that plan. Tasks 1–9 of that plan must be complete and its test suite green before starting
here. Specifically, this plan assumes these already exist:

| Type | Path |
|---|---|
| `DownloadTask` | `src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java` |
| `DownloadPhase` | `src/main/java/com/catacomb5099/naviseerr/download/DownloadPhase.java` |
| `DownloadDecision` | `src/main/java/com/catacomb5099/naviseerr/download/DownloadDecision.java` |
| `DownloadStateMachine` | `src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java` |
| `DownloadTaskRepository` | `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java` |
| Flyway migrations `V1__baseline.sql`, `V2__download_tasks.sql` | `src/main/resources/db/migration/` |

The design decisions this plan implements are recorded in
`docs/decisions/download-progress-reporting-17-08-2026.md`. Read it before starting — it explains
*why* several of the steps below look defensive, and two of its items are deliberately still open.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V3__download_progress.sql` | Adds the `progress_percent` column. |
| `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadView.java` | One active download as the client sees it. Read-side DTO only. |
| `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadsResponse.java` | Response envelope: the list plus the server's poll interval. |
| `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepository.java` | The single read query behind the endpoint. Separate from `DownloadTaskRepository` because reading for a UI and driving the loop are different responsibilities with different SQL shapes. |
| `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java` | Round-trip, write-guard and terminal-write integration tests. |
| `src/test/java/com/catacomb5099/naviseerr/download/DownloadStateMachineProgressTest.java` | Pure unit tests for progress observation, clamping and reset. |
| `src/test/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepositoryIT.java` | Integration tests for the read query. |
| `src/test/java/com/catacomb5099/naviseerr/download/DownloadControllerActiveTest.java` | Unit tests for the endpoint. |

**Modified:**

| File | Change |
|---|---|
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java` | Adds a 14th component, `progressPercent`, plus a 13-arg convenience constructor and `withProgress`. |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java` | `progress_percent` in `SAVE_SQL`, `CLAIM_DUE_SQL`'s `RETURNING`, `toTask`, and the terminal write. `SAVE_SQL` also gains its missing guard. |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java` | Observes progress on poll, resets it on retry/failover, clamps and null-guards it. |
| `src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java` | Adds `GET /downloads/active`. |
| `AGENTS.md` | Records the new column and endpoint. |
| `docs/decisions/download-progress-reporting-17-08-2026.md` | Flips the status line from "implementation deferred". |

**Nothing is deleted.**

---

### Task 1: The column, the round-trip, and the missing write guard

Additive by design. `DownloadTask` gains a component, but a 13-arg convenience constructor keeps every
existing construction site — in `DownloadStateMachine` and in the test fixtures — compiling untouched.
That is deliberate: without it this task would have to rewrite roughly twenty call sites it has no
other reason to touch.

This task also fixes something that is wrong today independently of progress. `SAVE_SQL`'s `WHERE`
clause is `WHERE download_id = :id` with no guard at all. When a lease expires while work is still
alive, two passes can both step the same row and both save, and the later save wins with stale state.
Adding `progress_percent` to an unguarded statement would also mean a poll in flight when a row goes
terminal could resurrect it into the due-work index. Both are fixed by the same guard.

**Files:**
- Create: `src/main/resources/db/migration/V3__download_progress.sql`
- Create: `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java`
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java`
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java`

**Interfaces:**
- Consumes: `DownloadTask` (13 components), `DownloadTaskRepository.save(DownloadTask) -> Mono<Long>`, `DownloadTaskRepository.claimDueTasks(int, String, Instant, Duration, boolean) -> Flux<DownloadTask>`, `DownloadPhase`, `DownloadStatus`.
- Produces:
  - `DownloadTask` with a 14th component `BigDecimal progressPercent`
  - `DownloadTask(UUID, String, DownloadPhase, Instant, Instant, String, List<DownloadCandidate>, int, int, String, String, String, String)` — 13-arg convenience constructor, defaults progress to `BigDecimal.ZERO`
  - `DownloadTask.withProgress(BigDecimal observed) -> DownloadTask` — returns `this` unchanged when `observed` is null
  - `DownloadTaskRepository.save` now writes `progress_percent` and only affects non-terminal rows the caller's lease owns

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V3__download_progress.sql`:

```sql
-- Byte-level progress for the transfer currently in flight, 0-100, matching slskd's own
-- percentComplete scale exactly so no conversion exists anywhere in the path.
--
-- NOT NULL DEFAULT 0 rather than nullable: "we have not observed a percentage yet" and "zero bytes
-- have moved" are the same thing to a progress bar, and a nullable column would push a null check
-- into every read site to express a distinction nobody consumes. slskd's percentComplete IS
-- nullable, but that is handled at the write (DownloadStateMachine), not by the column.
--
-- The value is overwritten from slskd on every poll, never incremented, so a step that runs twice
-- after a lease expiry writes the same value. Adding this column costs no extra writes: the poll
-- already updates this row to move next_attempt_at.
ALTER TABLE download_tasks
    ADD COLUMN progress_percent NUMERIC(5, 2) NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Add the component, the convenience constructor, and `withProgress`**

In `src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java`, add the import:

```java
import java.math.BigDecimal;
```

Add `progressPercent` as the final component of the record header, so the header becomes:

```java
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
        String lastError,
        BigDecimal progressPercent) {
```

Then add these three members inside the record body, and update the three existing copy methods so
they carry `progressPercent` instead of dropping it:

```java
    /**
     * Convenience constructor for every phase before any bytes have moved, defaulting progress to
     * zero. Its real job is compatibility: it keeps the existing 13-argument construction sites in
     * {@link DownloadStateMachine} and the test fixtures compiling unchanged. Do not use it on a path
     * where progress must be preserved — {@link #dueAt} and {@link #withPhase} exist for that.
     */
    public DownloadTask(UUID downloadId, String songName, DownloadPhase phase, Instant phaseEnteredAt,
                        Instant nextAttemptAt, String searchId, List<DownloadCandidate> candidates,
                        int candidateIndex, int retryIndex, String slskdUsername,
                        String slskdFilename, String slskdTransferId, String lastError) {
        this(downloadId, songName, phase, phaseEnteredAt, nextAttemptAt, searchId, candidates,
                candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId, lastError,
                BigDecimal.ZERO);
    }

    /**
     * Records an observed percentage. A null {@code observed} leaves the previous value untouched,
     * which is the whole null guard for this feature in one place: slskd omits {@code percentComplete}
     * depending on transfer state, and the batched transfer fetch omits a transfer it does not
     * recognise. Writing a null through as zero would drop a healthy download's bar from 87% to 0 and
     * back on the next poll.
     */
    public DownloadTask withProgress(BigDecimal observed) {
        return observed == null ? this : new DownloadTask(downloadId, songName, phase, phaseEnteredAt,
                nextAttemptAt, searchId, candidates, candidateIndex, retryIndex, slskdUsername,
                slskdFilename, slskdTransferId, lastError, observed);
    }

    /** Progress reset to zero — a new attempt at the same or the next candidate starts from nothing. */
    public DownloadTask withProgressReset() {
        return new DownloadTask(downloadId, songName, phase, phaseEnteredAt, nextAttemptAt, searchId,
                candidates, candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId,
                lastError, BigDecimal.ZERO);
    }
```

Replace the bodies of `initial`, `withPhase` and `dueAt` with these. `withPhase` and `dueAt` **must**
preserve progress — a re-poll of a live transfer keeps its bytes, and if `dueAt` reset progress the bar
would flatten on every single poll:

```java
    public static DownloadTask initial(UUID downloadId, String songName, Instant now) {
        return new DownloadTask(downloadId, songName, DownloadPhase.SEARCH_INIT, now, now,
                null, List.of(), 0, 0, null, null, null, null, BigDecimal.ZERO);
    }

    /** Phase change: resets the phase budget, preserves progress. */
    public DownloadTask withPhase(DownloadPhase newPhase, Instant now) {
        return new DownloadTask(downloadId, songName, newPhase, now, now, searchId, candidates,
                candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId,
                lastError, progressPercent);
    }

    /** Reschedule within the same phase: preserves the phase budget and progress. */
    public DownloadTask dueAt(Instant next) {
        return new DownloadTask(downloadId, songName, phase, phaseEnteredAt, next, searchId,
                candidates, candidateIndex, retryIndex, slskdUsername, slskdFilename,
                slskdTransferId, lastError, progressPercent);
    }
```

- [ ] **Step 3: Write the failing integration test**

Create `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java`. It is a new
file rather than additions to `DownloadTaskRepositoryIT` so it owns its own fixtures and cannot
collide with that file's helpers.

`BigDecimal` equality is the trap in this file: Postgres returns `NUMERIC(5,2)` as `43.00`, and
`BigDecimal.valueOf(43).equals(new BigDecimal("43.00"))` is **false** because the scales differ.
Every assertion below therefore compares with `compareTo`. Using `assertEquals` on `BigDecimal`
directly will fail on correct code.

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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-runner.interval-ms=3600000")
class DownloadTaskProgressIT {

    private static final String OWNER = "test-owner";

    @Autowired
    DownloadTaskRepository repository;

    @Autowired
    R2dbcEntityTemplate template;

    @BeforeEach
    void clean() {
        exec("DELETE FROM download_tasks");
        exec("DELETE FROM downloads");
    }

    @Test
    void save_roundTripsProgressPercent() {
        UUID id = seedTask(DownloadPhase.DOWNLOAD_POLL, OWNER);

        DownloadTask task = new DownloadTask(id, "song", DownloadPhase.DOWNLOAD_POLL, Instant.now(),
                Instant.now(), "search-1", List.of(), 0, 0, "peer", "song.flac", "transfer-1", null,
                new BigDecimal("43.25"));

        Long updated = repository.save(task).block();

        assertEquals(1L, updated.longValue());
        assertEquals(0, new BigDecimal("43.25").compareTo(readProgress(id)));
    }

    @Test
    void newTaskDefaultsToZeroProgress() {
        UUID id = seedTask(DownloadPhase.SEARCH_INIT, null);

        assertEquals(0, BigDecimal.ZERO.compareTo(readProgress(id)));
    }

    @Test
    void save_doesNotWriteToATerminalRow() {
        UUID id = seedTask(DownloadPhase.SUCCEEDED, OWNER);

        DownloadTask task = new DownloadTask(id, "song", DownloadPhase.DOWNLOAD_POLL, Instant.now(),
                Instant.now(), null, List.of(), 0, 0, null, null, null, null,
                new BigDecimal("55.00"));

        Long updated = repository.save(task).block();

        assertEquals(0L, updated.longValue());
        assertEquals("SUCCEEDED", readPhase(id));
        assertEquals(0, BigDecimal.ZERO.compareTo(readProgress(id)));
    }

    @Test
    void save_doesNotWriteWhenAnotherOwnerHoldsTheLease() {
        UUID id = seedTask(DownloadPhase.DOWNLOAD_POLL, "someone-else");

        DownloadTask task = new DownloadTask(id, "song", DownloadPhase.DOWNLOAD_POLL, Instant.now(),
                Instant.now(), null, List.of(), 0, 0, null, null, null, null,
                new BigDecimal("77.00"));

        Long updated = repository.save(task).block();

        assertEquals(0L, updated.longValue());
        assertEquals(0, BigDecimal.ZERO.compareTo(readProgress(id)));
    }

    @Test
    void claimDueTasks_returnsProgressPercent() {
        UUID id = seedTask(DownloadPhase.DOWNLOAD_POLL, null);
        exec("UPDATE download_tasks SET progress_percent = 61.50 WHERE download_id = '" + id + "'");

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, OWNER, Instant.now(), Duration.ofSeconds(30), true)
                .collectList()
                .block();

        assertEquals(1, claimed.size());
        assertNotNull(claimed.getFirst().progressPercent());
        assertEquals(0, new BigDecimal("61.50").compareTo(claimed.getFirst().progressPercent()));
    }

    private UUID seedTask(DownloadPhase phase, String leaseOwner) {
        UUID id = UUID.randomUUID();
        template.insert(Download.builder()
                .downloadId(id)
                .songName("song")
                .status(DownloadStatus.IN_PROGRESS)
                .createdAt(Instant.now())
                .build()).block();
        Instant past = Instant.now().minusSeconds(60);
        template.getDatabaseClient()
                .sql("""
                     INSERT INTO download_tasks
                         (download_id, song_name, phase, phase_entered_at, next_attempt_at, lease_owner)
                     VALUES (:id, 'song', :phase, :past, :past, :owner)
                     """)
                .bind("id", id)
                .bind("phase", phase.name())
                .bind("past", past)
                .bind("owner", leaseOwner == null
                        ? io.r2dbc.spi.Parameters.in(String.class)
                        : io.r2dbc.spi.Parameters.in(leaseOwner))
                .fetch()
                .rowsUpdated()
                .block();
        return id;
    }

    private BigDecimal readProgress(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT progress_percent FROM download_tasks WHERE download_id = :id")
                .bind("id", id)
                .map((row, meta) -> row.get("progress_percent", BigDecimal.class))
                .one()
                .block();
    }

    private String readPhase(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id")
                .bind("id", id)
                .map((row, meta) -> row.get("phase", String.class))
                .one()
                .block();
    }

    private void exec(String sql) {
        template.getDatabaseClient().sql(sql).fetch().rowsUpdated().block();
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadTaskProgressIT"`

Expected: FAIL. Compilation succeeds (the migration created the column and the record has the
component), but `save_roundTripsProgressPercent` fails because `SAVE_SQL` does not write the column
yet, and both guard tests fail with `assertEquals(0L, ...)` receiving `1L` because `SAVE_SQL` has no
guard.

- [ ] **Step 5: Add the column and the guard to the repository**

In `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java`, add the import:

```java
import java.math.BigDecimal;
```

Replace `SAVE_SQL` with:

```java
    // Writes every field so there is no partial-update logic to get wrong: DownloadTask is the
    // complete state. Clearing the lease is what makes the row visible to the next pass.
    //
    // The WHERE clause has two guards beyond the id, and progress is the reason both are load-bearing.
    // `phase NOT IN (terminal)` stops a poll that was already in flight when the row went terminal
    // from writing to it — without it, that write would also reset next_attempt_at and put a finished
    // or cancelled row back into the partial due-work index, so the loop would resume finished work.
    // `lease_owner = :owner` stops a pass whose lease has expired from overwriting the state written
    // by the pass that took over. Both are single-statement guards on purpose: a read-then-write has
    // the same race it is meant to close.
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
                   progress_percent = :progressPercent,
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
               AND phase NOT IN ('SUCCEEDED', 'FAILED')
               AND lease_owner = :owner
            """;
```

Change the `save` signature to take the owner and bind the two new parameters:

```java
    public Mono<Long> save(DownloadTask task, String owner) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(SAVE_SQL)
                .bind("id", task.downloadId())
                .bind("owner", owner)
                .bind("phase", task.phase().name())
                .bind("phaseEnteredAt", task.phaseEnteredAt())
                .bind("nextAttemptAt", task.nextAttemptAt())
                .bind("candidates", writeCandidates(task.candidates()))
                .bind("candidateIndex", task.candidateIndex())
                .bind("retryIndex", task.retryIndex())
                .bind("progressPercent", task.progressPercent() == null
                        ? BigDecimal.ZERO : task.progressPercent());
        spec = bindNullable(spec, "searchId", task.searchId());
        spec = bindNullable(spec, "slskdUsername", task.slskdUsername());
        spec = bindNullable(spec, "slskdFilename", task.slskdFilename());
        spec = bindNullable(spec, "slskdTransferId", task.slskdTransferId());
        spec = bindNullable(spec, "lastError", task.lastError());
        return spec.fetch().rowsUpdated();
    }
```

Add `progress_percent` to the end of `CLAIM_DUE_SQL`'s `RETURNING` list, so the clause reads:

```java
            RETURNING download_id, song_name, phase, phase_entered_at, next_attempt_at, search_id,
                      candidates, candidate_index, retry_index, slskd_username,
                      slskd_filename, slskd_transfer_id, last_error, progress_percent
```

And add the final argument to `toTask`, so its `new DownloadTask(...)` call ends:

```java
                row.get("slskd_transfer_id", String.class),
                row.get("last_error", String.class),
                row.get("progress_percent", BigDecimal.class));
```

- [ ] **Step 6: Update the one caller of `save`**

`save` now takes an owner. In `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java`,
find the call in the method that applies a decision and pass the runner's instance id — the same value
it already passes to `claimDueTasks` as `owner`. It is stored in the field named `instanceId`:

```java
        return repository.save(next, instanceId);
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadTaskProgressIT"`

Expected: PASS, 5 tests.

- [ ] **Step 8: Run the whole suite**

Run: `./gradlew cleanTest test`

Expected: PASS. Nothing else should have moved — the 13-arg convenience constructor is what keeps the
existing `DownloadStateMachine` and fixture call sites compiling. If anything fails to compile with
"constructor DownloadTask cannot be applied", the convenience constructor in Step 2 was not added.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V3__download_progress.sql src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java
git commit -m "feat(download): persist per-song download progress, and guard the task save"
```

---

### Task 2: The state machine observes, clamps, and resets progress

Pure logic, no I/O. Three behaviours: record what slskd reported, ignore what it did not report, and
zero the value whenever the current attempt is abandoned.

**Files:**
- Create: `src/test/java/com/catacomb5099/naviseerr/download/DownloadStateMachineProgressTest.java`
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java`

**Interfaces:**
- Consumes: `DownloadTask.withProgress(BigDecimal)`, `DownloadTask.withProgressReset()`, `DownloadDecision`, `TransferedFile.getPercentComplete() -> Float`, `DownloadTaskFixtures`, `SlskdFixtures`.
- Produces: `DownloadStateMachine.toProgress(Float) -> BigDecimal` (package-private static; returns null when there is no usable value).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/catacomb5099/naviseerr/download/DownloadStateMachineProgressTest.java`.
`SlskdFixtures.transfer(...)` builds a `TransferedFile` with `percentComplete` fixed at `0f`, so this
file needs its own builder to vary that one field. `TransferedFile` is `@AllArgsConstructor` with 17
positional arguments in this order: `id, username, direction, filename, size, startOffset, state,
requestedAt, enqueuedAt, startedAt, endedAt, bytesTransferred, averageSpeed, bytesRemaining,
elapsedTime, percentComplete, remainingTime`.

```java
package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.support.DownloadTaskFixtures;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DownloadStateMachineProgressTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    private final DownloadStateMachine machine = new DownloadStateMachine(
            Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofMinutes(2), Duration.ofMinutes(60), 2);

    @Test
    void downloadPoll_inProgress_recordsPercentComplete() {
        DownloadTask task = polling(BigDecimal.ZERO);

        DownloadDecision decision = machine.afterDownloadPoll(task, transfer("InProgress", 43.3f), NOW);

        assertProgress("43.30", decision);
    }

    @Test
    void downloadPoll_nullPercentComplete_keepsThePreviousValue() {
        DownloadTask task = polling(new BigDecimal("87.00"));

        DownloadDecision decision = machine.afterDownloadPoll(task, transfer("InProgress", null), NOW);

        assertProgress("87.00", decision);
    }

    @Test
    void downloadPoll_transferMissingFromTheBatch_keepsThePreviousValue() {
        DownloadTask task = polling(new BigDecimal("87.00"));

        DownloadDecision decision = machine.afterDownloadPoll(task, null, NOW);

        assertProgress("87.00", decision);
    }

    @Test
    void downloadPoll_percentAboveOneHundred_isClamped() {
        DownloadDecision decision = machine.afterDownloadPoll(
                polling(BigDecimal.ZERO), transfer("InProgress", 140f), NOW);

        assertProgress("100.00", decision);
    }

    @Test
    void downloadPoll_negativePercent_isClamped() {
        DownloadDecision decision = machine.afterDownloadPoll(
                polling(new BigDecimal("12.00")), transfer("InProgress", -5f), NOW);

        assertProgress("0.00", decision);
    }

    @Test
    void downloadPoll_nanPercent_keepsThePreviousValue() {
        DownloadDecision decision = machine.afterDownloadPoll(
                polling(new BigDecimal("12.00")), transfer("InProgress", Float.NaN), NOW);

        assertProgress("12.00", decision);
    }

    @Test
    void downloadPoll_failedTransfer_resetsProgressToZero() {
        DownloadTask task = polling(new BigDecimal("87.00"));

        DownloadDecision decision = machine.afterDownloadPoll(task, transfer("Errored", 87f), NOW);

        assertProgress("0.00", decision);
    }

    @Test
    void downloadInit_rejected_resetsProgressToZero() {
        DownloadTask task = DownloadTaskFixtures.downloadInit(
                        List.of(DownloadTaskFixtures.candidate("peer", "a.flac")), 0, 0)
                .withProgress(new BigDecimal("30.00"));

        DownloadDecision decision = machine.afterDownloadInit(
                task, SlskdFixtures.enqueueRejected(), NOW);

        assertProgress("0.00", decision);
    }

    @Test
    void onCallFailed_duringDownloadPoll_resetsProgressToZero() {
        DownloadTask task = polling(new BigDecimal("64.00"));

        DownloadDecision decision = machine.onCallFailed(task, new RuntimeException("boom"), NOW);

        assertProgress("0.00", decision);
    }

    @Test
    void dueAt_preservesProgress() {
        DownloadTask task = polling(new BigDecimal("55.50"));

        assertEquals(0, new BigDecimal("55.50").compareTo(task.dueAt(NOW).progressPercent()));
    }

    @Test
    void withPhase_preservesProgress() {
        DownloadTask task = polling(new BigDecimal("55.50"));

        assertEquals(0, new BigDecimal("55.50")
                .compareTo(task.withPhase(DownloadPhase.DOWNLOAD_INIT, NOW).progressPercent()));
    }

    private DownloadTask polling(BigDecimal progress) {
        return new DownloadTask(UUID.randomUUID(), "song", DownloadPhase.DOWNLOAD_POLL, NOW, NOW,
                "search-1", List.of(DownloadTaskFixtures.candidate("peer", "a.flac")), 0, 0,
                "peer", "a.flac", "transfer-1", null, progress);
    }

    private static TransferedFile transfer(String state, Float percentComplete) {
        return new TransferedFile("transfer-1", "peer", "Download", "a.flac", 100L, null, state,
                null, null, null, null, 0L, 0f, 100L, null, percentComplete, null);
    }

    private static void assertProgress(String expected, DownloadDecision decision) {
        DownloadDecision.Continue cont = assertInstanceOf(DownloadDecision.Continue.class, decision);
        assertEquals(0, new BigDecimal(expected).compareTo(cont.next().progressPercent()),
                "expected " + expected + " but was " + cont.next().progressPercent());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadStateMachineProgressTest"`

Expected: FAIL. `downloadPoll_inProgress_recordsPercentComplete` fails with expected `43.30` but was
`0.00`, because `afterDownloadPoll` does not read `percentComplete` yet. The reset tests fail for the
same reason inverted — progress survives a failover it should not.

If instead this fails to compile on `DownloadTaskFixtures.candidate(...)`, that helper has a different
name in the fixtures file. Read
`src/test/java/com/catacomb5099/naviseerr/support/DownloadTaskFixtures.java` and use whatever it
provides to build one `DownloadCandidate`; nothing about these assertions depends on the candidate's
contents.

- [ ] **Step 3: Implement the conversion, the observation and the resets**

In `src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java`, add the imports:

```java
import java.math.BigDecimal;
import java.math.RoundingMode;
```

Add this static method to the class:

```java
    /**
     * slskd's {@code percentComplete} as a value this project can store: 0-100, two decimal places,
     * or null meaning "no usable observation, keep whatever we had".
     *
     * <p>Null is returned rather than zero for three separate reasons that all lead to the same place.
     * slskd omits the field depending on transfer state. The batched transfer fetch omits transfers it
     * does not recognise. And a float can be NaN. In every case the honest answer is "we did not learn
     * anything this poll", and writing zero instead would drop a healthy download's bar to 0 and
     * restore it on the next poll.
     *
     * <p>The value is clamped because a bar is a bar: slskd is the authority on the number, not on
     * whether the number is renderable. {@code setScale} is required — widening a float to a double
     * gives values like 43.29999923706055, which is not what NUMERIC(5,2) should hold.
     */
    static BigDecimal toProgress(Float percentComplete) {
        if (percentComplete == null || percentComplete.isNaN() || percentComplete.isInfinite()) {
            return null;
        }
        double clamped = Math.max(0d, Math.min(100d, percentComplete.doubleValue()));
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
    }
```

Replace `afterDownloadPoll` with this version. Only the still-running branch changes; success and
failure are untouched, because `Terminal` carries no task and the failure branch delegates to
`retryOrAdvanceCandidate`:

```java
    public DownloadDecision afterDownloadPoll(DownloadTask task, TransferedFile file, Instant now) {
        List<TransferState> states = TransferedFileUtil.getStateList(file);
        if (states.stream().anyMatch(TransferState::isSuccess)) {
            return new DownloadDecision.Terminal(DownloadStatus.SUCCEEDED, null);
        }
        if (states.stream().anyMatch(TransferState::isFailure)) {
            return retryOrAdvanceCandidate(task, now);
        }
        DownloadTask observed = task.withProgress(
                toProgress(file == null ? null : file.getPercentComplete()));
        return observed.isPastBudget(now, downloadBudget)
                ? new DownloadDecision.Terminal(DownloadStatus.FAILED, TIMED_OUT)
                : new DownloadDecision.Continue(observed.dueAt(now.plus(downloadPollInterval)));
    }
```

Replace the first line of `retryOrAdvanceCandidate` so every abandoned attempt starts from zero. This
one line covers all three entry points — a failed transfer, a rejected enqueue, and a slskd call that
threw:

```java
    private DownloadDecision retryOrAdvanceCandidate(DownloadTask task, Instant now) {
        DownloadTask base = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now)
                .dueAt(now.plus(downloadPollInterval))
                .withProgressReset();
```

Finally, `rebuild` constructs a `DownloadTask` explicitly and must carry the reset value through
rather than dropping back to the convenience constructor's default — same result today, but it stops
being accidental. Add `base.progressPercent()` as the final argument of its `new DownloadTask(...)`
call.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadStateMachineProgressTest"`

Expected: PASS, 11 tests.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew cleanTest test`

Expected: PASS. The existing `DownloadStateMachineTest` must pass **unmodified** — that is the guard
that this task changed only progress and not any transition.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/catacomb5099/naviseerr/download/DownloadStateMachine.java src/test/java/com/catacomb5099/naviseerr/download/DownloadStateMachineProgressTest.java
git commit -m "feat(download): observe, clamp and reset transfer progress in the state machine"
```

---

### Task 3: A succeeded download reads 100%

`Terminal` carries no `DownloadTask`, so the last poll before success never gets to write `100`. A
song that succeeds between polls would otherwise sit at whatever its last observation was — 96%, next
to the word "Succeeded". The terminal write is the only place this can be fixed, and it is one `CASE`
inside a statement that already runs.

Failure deliberately does **not** set 100. It leaves the last observed value, so the retained row
records how far the download actually got. See the open item in the decision record.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java`
- Modify: `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java`

**Interfaces:**
- Consumes: `DownloadTaskRepository.finishDownload(UUID, DownloadStatus, String, Instant) -> Mono<Long>`.
- Produces: no signature change. `finishDownload` additionally sets `progress_percent = 100` when the status is `SUCCEEDED`.

- [ ] **Step 1: Write the failing tests**

Add these two tests and one helper to
`src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java`:

```java
    @Test
    void finishDownload_succeeded_setsProgressToOneHundred() {
        UUID id = seedTask(DownloadPhase.DOWNLOAD_POLL, OWNER);
        setProgress(id, "96.40");

        Long updated = repository
                .finishDownload(id, DownloadStatus.SUCCEEDED, null, Instant.now())
                .block();

        assertEquals(1L, updated.longValue());
        assertEquals("SUCCEEDED", readPhase(id));
        assertEquals(0, new BigDecimal("100.00").compareTo(readProgress(id)));
    }

    @Test
    void finishDownload_failed_leavesProgressAtItsLastObservedValue() {
        UUID id = seedTask(DownloadPhase.DOWNLOAD_POLL, OWNER);
        setProgress(id, "38.75");

        repository.finishDownload(id, DownloadStatus.FAILED, "All download sources exhausted",
                Instant.now()).block();

        assertEquals("FAILED", readPhase(id));
        assertEquals(0, new BigDecimal("38.75").compareTo(readProgress(id)));
    }

    private void setProgress(UUID id, String value) {
        exec("UPDATE download_tasks SET progress_percent = " + value
                + " WHERE download_id = '" + id + "'");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadTaskProgressIT"`

Expected: FAIL on `finishDownload_succeeded_setsProgressToOneHundred` with expected `100.00` but was
`96.40`. `finishDownload_failed_leavesProgressAtItsLastObservedValue` already passes — it is here to
pin the behaviour that must *not* change in the next step.

- [ ] **Step 3: Add the `CASE` to the terminal write**

In `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java`, add one line to
`FINISH_DOWNLOAD_SQL`'s second `UPDATE`, between `failure_reason` and `lease_owner`:

```sql
                   progress_percent = CASE WHEN :status = 'SUCCEEDED'
                                           THEN 100 ELSE progress_percent END,
```

No binding change is needed: `:status` is already bound, and Spring's `DatabaseClient` expands a named
parameter at every occurrence. The `CASE` keeps this a single statement, so a crash cannot leave the
phase terminal with a stale percentage.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadTaskProgressIT"`

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java
git commit -m "feat(download): a succeeded download reports 100% progress"
```

---

### Task 4: `GET /downloads/active`

One endpoint. It returns every non-terminal download with its phase and percentage, plus the server's
poll interval so the client can set its CSS transition duration to match rather than hardcoding a
guess. That single field is what makes the bar smooth: the browser interpolates between two values it
already has, which is the only honest way to smooth a source that only moves once per poll.

The route is `/downloads/active`, plural, because it returns a collection. The existing
`POST /download/{songName}` keeps its singular path — renaming it is a breaking API change unrelated
to this work.

**Files:**
- Create: `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadView.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadsResponse.java`
- Create: `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepository.java`
- Create: `src/test/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepositoryIT.java`
- Create: `src/test/java/com/catacomb5099/naviseerr/download/DownloadControllerActiveTest.java`
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java`

**Interfaces:**
- Consumes: `DownloadStatus`, `DownloadPhase`, `R2dbcEntityTemplate`, `DownloadService`.
- Produces:
  - `record ActiveDownloadView(UUID downloadId, String songName, DownloadStatus status, DownloadPhase phase, BigDecimal progressPercent, Instant phaseEnteredAt)`
  - `record ActiveDownloadsResponse(long pollIntervalMs, List<ActiveDownloadView> downloads)`
  - `ActiveDownloadRepository.findActive() -> Flux<ActiveDownloadView>`
  - `DownloadController.activeDownloads() -> Mono<ResponseEntity<ActiveDownloadsResponse>>` bound to `GET /downloads/active`

- [ ] **Step 1: Write the failing repository test**

Create `src/test/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepositoryIT.java`:

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-runner.interval-ms=3600000")
class ActiveDownloadRepositoryIT {

    @Autowired
    ActiveDownloadRepository repository;

    @Autowired
    R2dbcEntityTemplate template;

    @BeforeEach
    void clean() {
        exec("DELETE FROM download_tasks");
        exec("DELETE FROM downloads");
    }

    @Test
    void findActive_returnsPhaseAndProgressForANonTerminalDownload() {
        UUID id = seed("song-a", DownloadStatus.IN_PROGRESS, DownloadPhase.DOWNLOAD_POLL,
                "72.25", Instant.now());

        List<ActiveDownloadView> found = repository.findActive().collectList().block();

        assertEquals(1, found.size());
        ActiveDownloadView view = found.getFirst();
        assertEquals(id, view.downloadId());
        assertEquals("song-a", view.songName());
        assertEquals(DownloadStatus.IN_PROGRESS, view.status());
        assertEquals(DownloadPhase.DOWNLOAD_POLL, view.phase());
        assertEquals(0, new BigDecimal("72.25").compareTo(view.progressPercent()));
    }

    @Test
    void findActive_excludesTerminalDownloads() {
        seed("done", DownloadStatus.SUCCEEDED, DownloadPhase.SUCCEEDED, "100.00", Instant.now());
        seed("dead", DownloadStatus.FAILED, DownloadPhase.FAILED, "12.00", Instant.now());

        assertTrue(repository.findActive().collectList().block().isEmpty());
    }

    @Test
    void findActive_ordersByCreatedAtOldestFirst() {
        Instant base = Instant.now().minusSeconds(300);
        seed("second", DownloadStatus.IN_PROGRESS, DownloadPhase.SEARCH_POLL, "0", base.plusSeconds(60));
        seed("first", DownloadStatus.PENDING, DownloadPhase.SEARCH_INIT, "0", base);

        List<ActiveDownloadView> found = repository.findActive().collectList().block();

        assertEquals(List.of("first", "second"), found.stream().map(ActiveDownloadView::songName).toList());
    }

    private UUID seed(String songName, DownloadStatus status, DownloadPhase phase,
                      String progress, Instant createdAt) {
        UUID id = UUID.randomUUID();
        template.insert(Download.builder()
                .downloadId(id)
                .songName(songName)
                .status(status)
                .createdAt(createdAt)
                .build()).block();
        template.getDatabaseClient()
                .sql("""
                     INSERT INTO download_tasks
                         (download_id, song_name, phase, phase_entered_at, next_attempt_at,
                          progress_percent)
                     VALUES (:id, :song, :phase, :now, :now, :progress)
                     """)
                .bind("id", id)
                .bind("song", songName)
                .bind("phase", phase.name())
                .bind("now", createdAt)
                .bind("progress", new BigDecimal(progress))
                .fetch()
                .rowsUpdated()
                .block();
        return id;
    }

    private void exec(String sql) {
        template.getDatabaseClient().sql(sql).fetch().rowsUpdated().block();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.ActiveDownloadRepositoryIT"`

Expected: FAIL to compile — `ActiveDownloadRepository` and `ActiveDownloadView` do not exist.

- [ ] **Step 3: Write the DTOs and the repository**

Create `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadView.java`:

```java
package com.catacomb5099.naviseerr.download;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One in-flight download as a client renders it.
 *
 * <p>{@code status} and {@code progressPercent} answer two different questions and neither substitutes
 * for the other: status says whether the request is over, progress says how far it got. A client must
 * not infer completion from {@code progressPercent == 100}.
 *
 * <p>{@code progressPercent} is 0-100 and is only meaningful while {@code phase} is
 * {@code DOWNLOAD_POLL}. Searching has no denominator — slskd reports response counts, not a total —
 * so a client should render the earlier phases as indeterminate and use {@code phaseEnteredAt} for an
 * elapsed time instead of inventing a number.
 */
public record ActiveDownloadView(
        UUID downloadId,
        String songName,
        DownloadStatus status,
        DownloadPhase phase,
        BigDecimal progressPercent,
        Instant phaseEnteredAt) {
}
```

Create `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadsResponse.java`:

```java
package com.catacomb5099.naviseerr.download;

import java.util.List;

/**
 * @param pollIntervalMs how often the server refreshes a transfer's percentage. Two uses on the
 *                       client: poll no faster than this (a faster poll returns the same number and
 *                       reads as frozen), and set the progress bar's CSS transition duration to it so
 *                       the browser interpolates between values and the bar glides instead of
 *                       stepping. Interpolate toward a value already received — never extrapolate
 *                       past it, or a stalled transfer will show progress that is not happening.
 * @param downloads      oldest request first.
 */
public record ActiveDownloadsResponse(long pollIntervalMs, List<ActiveDownloadView> downloads) {
}
```

Create `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepository.java`:

```java
package com.catacomb5099.naviseerr.download;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The read side of download progress. Deliberately separate from {@link DownloadTaskRepository}: that
 * class drives the reconciliation loop and every statement in it needs {@code FOR UPDATE SKIP LOCKED},
 * {@code RETURNING}, or a data-modifying CTE. This is one plain projection for a UI.
 */
@Repository
public class ActiveDownloadRepository {

    // INNER JOIN, not LEFT: the loop's admit query maintains "every non-terminal download has a task
    // row" as an invariant it continuously restores, so a download with no task row is a row the loop
    // is about to create one for, and it has nothing to report yet. Ordered oldest-first to match the
    // order the loop admits work in, so the list does not reshuffle between polls.
    private static final String FIND_ACTIVE_SQL = """
            SELECT d.download_id, d.song_name, d.status, t.phase, t.progress_percent,
                   t.phase_entered_at
              FROM downloads d
              JOIN download_tasks t ON t.download_id = d.download_id
             WHERE d.status NOT IN ('SUCCEEDED', 'FAILED')
             ORDER BY d.created_at
            """;

    private final DatabaseClient client;

    public ActiveDownloadRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    public Flux<ActiveDownloadView> findActive() {
        return client.sql(FIND_ACTIVE_SQL)
                .map((row, meta) -> new ActiveDownloadView(
                        row.get("download_id", UUID.class),
                        row.get("song_name", String.class),
                        DownloadStatus.valueOf(row.get("status", String.class)),
                        DownloadPhase.valueOf(row.get("phase", String.class)),
                        row.get("progress_percent", BigDecimal.class),
                        row.get("phase_entered_at", Instant.class)))
                .all();
    }
}
```

- [ ] **Step 4: Run the repository test to verify it passes**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.ActiveDownloadRepositoryIT"`

Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing controller test**

Create `src/test/java/com/catacomb5099/naviseerr/download/DownloadControllerActiveTest.java`:

```java
package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DownloadControllerActiveTest {

    private final DownloadService downloadService = mock(DownloadService.class);
    private final ActiveDownloadRepository activeDownloads = mock(ActiveDownloadRepository.class);

    private final DownloadController controller =
            new DownloadController(downloadService, activeDownloads, Duration.ofMillis(5000));

    @Test
    void activeDownloads_returnsTheListAndThePollInterval() {
        ActiveDownloadView view = new ActiveDownloadView(UUID.randomUUID(), "song-a",
                DownloadStatus.IN_PROGRESS, DownloadPhase.DOWNLOAD_POLL,
                new BigDecimal("72.25"), Instant.parse("2026-08-17T12:00:00Z"));
        when(activeDownloads.findActive()).thenReturn(Flux.just(view));

        StepVerifier.create(controller.activeDownloads())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(5000L, response.getBody().pollIntervalMs());
                    assertEquals(1, response.getBody().downloads().size());
                    assertEquals("song-a", response.getBody().downloads().getFirst().songName());
                })
                .verifyComplete();
    }

    @Test
    void activeDownloads_returnsAnEmptyListWhenNothingIsActive() {
        when(activeDownloads.findActive()).thenReturn(Flux.empty());

        StepVerifier.create(controller.activeDownloads())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertTrue(response.getBody().downloads().isEmpty());
                    assertEquals(5000L, response.getBody().pollIntervalMs());
                })
                .verifyComplete();
    }

    @Test
    void activeDownloads_mapsAFailedQueryToFiveHundred() {
        when(activeDownloads.findActive()).thenReturn(Flux.error(new RuntimeException("db down")));

        StepVerifier.create(controller.activeDownloads())
                .assertNext(response ->
                        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode()))
                .verifyComplete();
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadControllerActiveTest"`

Expected: FAIL to compile — `DownloadController` has a one-argument constructor and no
`activeDownloads` method.

- [ ] **Step 7: Add the endpoint**

Replace `src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java` with:

```java
package com.catacomb5099.naviseerr.download;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;

@RestController
public class DownloadController {

    private final DownloadService downloadService;
    private final ActiveDownloadRepository activeDownloads;
    private final Duration downloadPollInterval;

    public DownloadController(
            DownloadService downloadService,
            ActiveDownloadRepository activeDownloads,
            @Value("${download-task.download-poll-interval-ms:5000}") Duration downloadPollInterval) {
        this.downloadService = downloadService;
        this.activeDownloads = activeDownloads;
        this.downloadPollInterval = downloadPollInterval;
    }

    @PostMapping("/download/{songName}")
    Mono<ResponseEntity<Download>> download(@PathVariable String songName) {
        if (songName == null || songName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return downloadService.requestDownload(songName)
                .map(saved -> ResponseEntity.status(HttpStatus.ACCEPTED).body(saved))
                .onErrorResume(error -> Mono.just(
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()));
    }

    /**
     * Every in-flight download with its current progress. Poll this no faster than the
     * {@code pollIntervalMs} it returns — the underlying value only changes when the reconciliation
     * loop polls slskd, so a faster client poll returns an identical response.
     */
    @GetMapping("/downloads/active")
    Mono<ResponseEntity<ActiveDownloadsResponse>> activeDownloads() {
        return activeDownloads.findActive()
                .collectList()
                .map(downloads -> ResponseEntity.ok(
                        new ActiveDownloadsResponse(downloadPollInterval.toMillis(), downloads)))
                .onErrorResume(error -> Mono.just(
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()));
    }
}
```

- [ ] **Step 8: Run the controller test to verify it passes**

Run: `./gradlew cleanTest test --tests "com.catacomb5099.naviseerr.download.DownloadControllerActiveTest"`

Expected: PASS, 3 tests.

- [ ] **Step 9: Run the whole suite**

Run: `./gradlew cleanTest test`

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadView.java src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadsResponse.java src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepository.java src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java src/test/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepositoryIT.java src/test/java/com/catacomb5099/naviseerr/download/DownloadControllerActiveTest.java
git commit -m "feat(download): add GET /downloads/active returning progress and the poll interval"
```

---

### Task 5: Documentation

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/decisions/download-progress-reporting-17-08-2026.md`

- [ ] **Step 1: Record the endpoint and the column in `AGENTS.md`**

In the section describing the current implementation state and API surface, add:

```markdown
- `GET /downloads/active` returns every non-terminal download with its `phase`, its `progressPercent`
  (0-100, only meaningful during `DOWNLOAD_POLL`), and `pollIntervalMs`. Clients poll it at or above
  `pollIntervalMs` and animate the bar with a CSS transition of that duration; there is no SSE or
  WebSocket progress channel, deliberately — see
  `docs/decisions/download-progress-reporting-17-08-2026.md`.
- `download_tasks.progress_percent` (`NUMERIC(5,2)`, 0-100) is written by the `DOWNLOAD_POLL` step
  inside the update that already runs every poll, so progress costs no extra database writes. It is
  overwritten from slskd, never accumulated.
```

Also confirm the existing guideline about byte-level progress writes still reads correctly against
this implementation. It should: the rule prohibits progress writes measured in hundreds per download,
and this adds zero writes. If the wording implies no progress may be persisted at all, tighten it to
match the rule's stated reasoning rather than weakening the rule.

- [ ] **Step 2: Update the decision record's status**

In `docs/decisions/download-progress-reporting-17-08-2026.md`, change the status line from:

```markdown
- **Status:** shape agreed, implementation deferred. Two items open pending a UX test (below).
```

to:

```markdown
- **Status:** implemented for single-song downloads (`docs/superpowers/plans/2026-08-17-download-progress-tracking.md`). The collection aggregate is still deferred to the collections work. Two items remain open pending a UX test (below).
```

- [ ] **Step 3: Run the whole suite one final time**

Run: `./gradlew cleanTest test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md docs/decisions/download-progress-reporting-17-08-2026.md
git commit -m "docs: record the download progress column and endpoint"
```

---

## Deliberately out of scope

Each of these was considered and left out on purpose. None is blocked by this work.

- **The collection percentage.** Decided (count completed songs, never average their progress) but not
  buildable yet: collections do not exist, and `download_tasks.download_id` is still a `PRIMARY KEY`,
  which permits exactly one task row per download. Both the schema change and the aggregate belong in
  the collections migration. Recorded in the decision document.
- **`ETag` / `304` on the endpoint.** Worth adding only if idle bandwidth turns out to matter. The
  active set is small and the response is a few hundred bytes.
- **SSE or WebSocket progress.** Rejected with reasons in the decision record. The one case worth
  revisiting later is genuinely eventful updates — a download finishing or failing — not percentages.
- **Bytes transferred, average speed, and ETA.** `TransferedFile` carries `bytesTransferred`,
  `averageSpeed`, `bytesRemaining` and `remainingTime`, so exposing them later is additive. A client
  can already derive a rate from two consecutive polls without any of them.
- **Search progress.** There is no denominator to compute one from. `phase` plus `phaseEnteredAt`
  carry everything a client needs for the pre-transfer phases.
- **A reference client widget.** Not requested for this plan.

---

## Self-Review

**Spec coverage.** Every decision in `docs/decisions/download-progress-reporting-17-08-2026.md` maps
to a task: the column and the free write (Task 1 Steps 1, 5); download-progress-only, no search
progress (Task 4's `ActiveDownloadView` javadoc plus the out-of-scope note); reset on retry and
failover (Task 2 Step 3); never write a null or absent value (Task 2, `toProgress` returning null and
`withProgress` short-circuiting, with four tests); non-terminal single-statement guard (Task 1 Step 5);
succeeded reads 100 (Task 3); polled REST with no SSE (Task 4). The collection aggregate and the
`download_tasks` PK change are the two documented decisions with no task, both listed under
deliberately out of scope with the reason.

**Placeholder scan.** No TBDs. Every code step carries the actual code. Two steps describe an edit by
quoting the exact clause to change rather than restating a whole file — Task 3 Step 3 (one `CASE` line)
and Task 1 Step 5's `RETURNING` and `toTask` edits — and both quote enough surrounding text to locate
unambiguously. Task 1 Step 6 and Task 2 Step 2 each name a fallback if the referenced symbol turns out
to be named differently in the prerequisite plan's output, because that code does not exist yet and
this plan cannot read it.

**Type consistency.** `progressPercent` is `BigDecimal` in `DownloadTask`, `ActiveDownloadView`, and
every test; `progress_percent` is `NUMERIC(5,2)` in the migration, `SAVE_SQL`, `CLAIM_DUE_SQL`,
`FINISH_DOWNLOAD_SQL` and `FIND_ACTIVE_SQL`; the scale is 0–100 in all of them, matching slskd's
`percentComplete`. `toProgress` takes `Float` (slskd's declared type) and returns `BigDecimal` or null.
`withProgress` is the only null-tolerant path; `withProgressReset` is the only zeroing path. `save`
gains a second parameter in Task 1 and every call site is updated in the same task. Every `BigDecimal`
assertion uses `compareTo`, never `assertEquals` on the object, because `43.00` and `43` differ in
scale.

**One risk worth naming.** Task 1 changes `save`'s signature and adds two `WHERE` predicates to a
statement the prerequisite plan's own tests exercise. If those tests call `save` on a task whose
`lease_owner` was never set, they will start failing with `0 rows updated` — correctly. Expect to fix
call sites in `DownloadTaskRepositoryIT` and `DownloadTaskRunnerTest`, and read the failure as the
guard working rather than as a regression.

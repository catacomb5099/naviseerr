# Retry and Cancel Downloads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user retry a finished download (every song without a file) and cancel a download or one song of it, idempotently, from both the panel and the Downloads page.

**Architecture:** No migration. A cancelled song is a `FAILED` task row with `failure_reason = 'CANCELLED'`; a retry resets `FAILED` task rows in place to `SEARCH_INIT` and reopens the download in one statement whose row guards make a repeated request update zero rows. Two loop fixes land first (a lease-owner guard on the finish statement; admission flips the download's status before inserting its songs). The client learns one new failure code and one new count, relaxes `mergeCard`'s "terminal is final" rule to a timestamp rule, and grows two buttons.

**Tech Stack:** Server: Java 21, Spring Boot 4 WebFlux, R2DBC + raw SQL, JUnit 5 + Mockito + Testcontainers Postgres (Docker required). Client: React 18 + TypeScript + Vite + Tailwind + lucide-react; `npm run check` (plain assertion scripts), `npm run lint`, `npm run build`.

**Spec:** `docs/superpowers/specs/2026-09-28-retry-and-cancel-design.md` (same repo). Read it first; the race table and the "Decisions in one screen" table are the contract.

## Global Constraints

- Two repos: server `/Users/alpascal/IdeaProjects/naviseerr`, client `/Users/alpascal/IdeaProjects/naviseerr-client`. Tasks 1–6 are server, 7–11 client. Within a repo, each task's branch is cut from the previous task's branch (stacked); the first branch in each repo is cut from `move-fast-break-things` on the remote (the server repo's remote is `origin`; the client repo's remote is named `remote`).
- **Every `git` and `gh` command that touches GitHub, and every commit, runs through `~/.gh-catacombs/ghc`** (e.g. `~/.gh-catacombs/ghc git commit -m "..."`, `~/.gh-catacombs/ghc git push -u origin <branch>`, `~/.gh-catacombs/ghc gh pr create ...`). Run it from inside the repo. Its own progress lines (`level=warning`, `Container ...`, `authenticated as catacomb5099`) are noise.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- PR titles: `feat(AI): ...` or `fix(AI): ...`, lowercase after the prefix, plain words. Label `AI`. Base = the parent task's branch (the first task in each repo: `move-fast-break-things`). PR body sections: **What this does** (plain English, for a product manager), **Checked** (what you ran and the counts), **Docs** (if any), **Merge order** ("Nth of 6 stacked naviseerr PRs; retarget to `move-fast-break-things` once its parent has merged").
- `git add` names files explicitly. Never `git add -A`/`-u`. The client has a pre-existing uncommitted Vite bump in `package.json` and `package-lock.json` that is **not ours**: never stage those two files.
- Wording rules (client): the code `CANCELLED` reads "Cancelled"; a cancelled download is grey (`text-zinc-400`), never red; X keeps meaning dismiss.
- Failure codes are persisted by NAME; the client owns the wording. `DownloadStage` on the wire does not change.
- Server tests: unit tests run without Docker; `*IT` classes need Docker. Run a class with `./gradlew test --tests "com.catacomb5099.naviseerr.download.<Class>"`; the whole suite with `./gradlew test` (423 tests green on 2026-09-28 before this work).
- Client checks: `npm run check && npm run lint && npm run build` (all green on 2026-09-28 before this work).
- Do not touch `docs/superpowers/plans/2026-08-31-download-request-metadata.md` (untracked, pre-existing).

---

## Server

### Task 1: A song's finish only counts if its step still holds the lease

**Branch:** `fix/ai-finish-needs-lease` from `origin/move-fast-break-things`. PR base `move-fast-break-things`.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java` (`FINISH_TASK_SQL`, `finishTask`)
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java:472-474` (`apply`, Terminal branch)
- Modify tests: `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRunnerTest.java` (4 `finishTask` mock sites), `DownloadTaskRepositoryIT.java` (14 call sites), `ActiveDownloadRepositoryIT.java` (its `finish(...)` helper at line 78 wraps all 7), `DownloadRecoveryIT.java` (3), `DownloadTaskProgressIT.java` (3)

**Interfaces:**
- Produces: `public Mono<Long> finishTask(UUID taskId, DownloadStatus status, DownloadFailureCode failureCode, Instant now, String owner)` — one added parameter, the lease owner the caller holds. Returns rows updated (0 or 1).

- [ ] **Step 1: Write the failing integration tests** in `DownloadTaskRepositoryIT`:

```java
@Test
void finishTask_byAnotherOwner_isANoOp() {
    UUID id = admitOneSong("PENDING");
    DownloadTask claimed = repository.claimDueTasks(10, "owner-a", NOW, Duration.ofMinutes(1), true, 2)
            .blockFirst();

    Long rows = downloadService.finishTask(claimed.taskId(), DownloadStatus.SUCCEEDED, null, NOW, "owner-b").block();

    assertEquals(0L, rows, "a finish from a process that does not hold the lease must not land");
    assertEquals("SEARCH_INIT", phaseOf(claimed.taskId()));
}

@Test
void finishTask_onARowWithNoLease_isANoOp() {
    UUID id = admitOneSong("PENDING");
    UUID taskId = taskIdsOf(id).getFirst();

    Long rows = downloadService.finishTask(taskId, DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT, NOW, "anyone").block();

    assertEquals(0L, rows, "no lease means nobody is entitled to finish the row");
}
```

Add a private helper if the class lacks one: `private String phaseOf(UUID taskId)` (`SELECT phase FROM download_tasks WHERE task_id = :id`) and `private List<UUID> taskIdsOf(UUID downloadId)`.

- [ ] **Step 2: Run to verify they fail** (compile error on the 5-arg signature is the expected failure):

Run: `./gradlew test --tests "com.catacomb5099.naviseerr.download.DownloadTaskRepositoryIT"`
Expected: compilation failure "method finishTask ... cannot be applied".

- [ ] **Step 3: Implement.** In `DownloadService`:

```java
private static final String FINISH_TASK_SQL = """
        UPDATE download_tasks
           SET phase = :status,
               phase_entered_at = :now,
               updated_at = now(),
               finished_at = :now,
               failure_reason = :reason,
               progress_percent = CASE WHEN :status = 'SUCCEEDED' THEN 100 ELSE progress_percent END,
               lease_owner = NULL,
               lease_expires_at = NULL
         WHERE task_id = :id
           AND phase NOT IN ('SUCCEEDED', 'FAILED')
           -- Same guard as DownloadTaskRepository.SAVE_SQL. A cancel clears the lease and a retry
           -- reopens the row with none, so a step that was mid-flight when its song was cancelled
           -- must not land its stale outcome on the fresh attempt.
           AND lease_owner = :owner
        """;

public Mono<Long> finishTask(UUID taskId, DownloadStatus status,
                             DownloadFailureCode failureCode, Instant now, String owner) {
    DatabaseClient.GenericExecuteSpec spec = entityTemplate.getDatabaseClient()
            .sql(FINISH_TASK_SQL)
            .bind("status", status.name())
            .bind("id", taskId)
            .bind("now", now)
            .bind("owner", owner);
    ...
```

Keep the existing comments above the statement (the idempotence reasoning still holds). Update the Javadoc: "Idempotent, and owner-checked: a call for an already-terminal task, or from a caller that does not hold the row's lease, updates nothing and returns 0."

In `DownloadTaskRunner.apply`, Terminal branch: `downloadService.finishTask(task.taskId(), terminal.status(), terminal.failureCode(), clock.instant(), instanceId)`.

- [ ] **Step 4: Fix the existing tests.**
  - `DownloadTaskRunnerTest`: every `finishTask(any(), any(), any(), any())` stub/verify gains a fifth `any()`; where a test verifies the call, prefer `eq(<the runner's instance id>)` only if the test already reads it, else `anyString()`.
  - `ActiveDownloadRepositoryIT.finish(...)` (line 78): before calling `finishTask`, stamp a lease: `template.getDatabaseClient().sql("UPDATE download_tasks SET lease_owner = 'it' WHERE download_id = :id").bind("id", downloadId).fetch().rowsUpdated().block();` then call `finishTask(taskId, status, code, NOW, "it")`.
  - `DownloadTaskRepositoryIT`, `DownloadRecoveryIT`, `DownloadTaskProgressIT`: add the same private helper `finish(UUID taskId, DownloadStatus status, DownloadFailureCode code)` (stamp `lease_owner = 'it'` on that task, then `finishTask(..., NOW, "it")`), and route the existing direct calls through it. Where a test already claimed the row with `claimDueTasks(..., owner, ...)`, pass that owner instead of stamping.

- [ ] **Step 5: Run the download package tests and the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL; test count = 423 + 2.

- [ ] **Step 6: Commit, push, PR**

```bash
~/.gh-catacombs/ghc git add src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRunnerTest.java src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRepositoryIT.java src/test/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepositoryIT.java src/test/java/com/catacomb5099/naviseerr/download/DownloadRecoveryIT.java src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskProgressIT.java
~/.gh-catacombs/ghc git commit -m "fix(AI): a song's finish only counts if its step still holds the lease

The statement that marks one song finished now checks the lease owner, as the statement that moves a song forward already did. Cancel clears the lease and retry reopens the row without one, so a Soulseek call that was mid-flight when its song was cancelled can no longer write its stale outcome onto the new attempt.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
~/.gh-catacombs/ghc git push -u origin fix/ai-finish-needs-lease
~/.gh-catacombs/ghc gh pr create --base move-fast-break-things --label AI --title "fix(AI): a song's finish only counts if its step still holds the lease" --body "<per Global Constraints>"
```

PR body "What this does": a plain sentence that today a slow Soulseek call could finish a song after the user had cancelled and retried it, marking the new attempt done (or failed) with no file; now the finish only lands if the call still owns the row. Note it is groundwork for cancel and retry.

---

### Task 2: Admission marks a download started before adding its songs

**Branch:** `fix/ai-admit-status-first` from `fix/ai-finish-needs-lease`. PR base `fix/ai-finish-needs-lease`.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java` (`CREATE_TASKS_SQL` and the Javadoc of `createTasks`)
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java:190-194` (the DEBUG log wording)
- Test: `src/test/java/com/catacomb5099/naviseerr/download/DownloadTaskRepositoryIT.java`

**Interfaces:**
- Produces: `createTasks(UUID downloadId, List<DownloadTask> tasks, Instant now)` now returns the number of **task rows created** (N for an N-song collection), 0 when the download was not `PENDING` or already had songs. Callers only test `> 0`.

- [ ] **Step 1: Write the failing test** in `DownloadTaskRepositoryIT`:

```java
@Test
void createTasks_afterTheDownloadWasFailed_insertsNothing() {
    UUID id = insertDownload("PENDING", "ALBUM");
    // Admission selected the row, then the request was failed (or cancelled) before the song rows were written.
    repository.failUnadmitted(id, DownloadFailureCode.METADATA_UNAVAILABLE, NOW).block();

    Long created = admit(id, "a", "b", "c");

    assertEquals(0L, created, "a download that is no longer pending must not get songs");
    assertEquals(0L, countTaskRows());
    assertEquals("FAILED", statusOf(id));
}
```

Change the existing assertion at line 123 from `assertEquals(1L, admitted, "one download was admitted, whatever its song count")` to `assertEquals(3L, admitted, "one row per song was created")` (that test admits three songs; confirm the count it uses).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.catacomb5099.naviseerr.download.DownloadTaskRepositoryIT"`
Expected: `createTasks_afterTheDownloadWasFailed_insertsNothing` FAILS (3 rows inserted under a FAILED download); the line-123 test FAILS (1 != 3).

- [ ] **Step 3: Implement** — replace `CREATE_TASKS_SQL`:

```java
/**
 * Creates every task row for one download and admits it, in one statement. One row per song.
 *
 * <p>The status flip comes FIRST and the insert depends on it. The {@code UPDATE} takes the row
 * lock, so a cancel (or admission failure) that lands in the gap between selecting the download
 * and running this statement wins or loses cleanly: under READ COMMITTED an {@code UPDATE} that
 * waited on the row re-checks {@code status = 'PENDING'} against the committed version, finds it
 * false, and the insert then has nothing to depend on. The old order (insert first, then flip)
 * left a finished download with live song rows the loop would search for.
 *
 * <p>{@code unnest} of two parallel arrays rather than a multi-row VALUES list, because the song
 * count is only known at runtime. {@code WITH ORDINALITY} numbers the songs in the provider's order.
 * {@code song_name} is the Soulseek query wording, not a display title; see V6.
 *
 * @return task rows created: N for an admitted N-song download, 0 when it was no longer PENDING
 *         or already had songs
 */
private static final String CREATE_TASKS_SQL = """
        WITH admitted AS (
            UPDATE downloads
               SET status = 'IN_PROGRESS',
                   admitted_at = :now
             WHERE download_id = :downloadId
               AND status = 'PENDING'
               AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                WHERE t.download_id = :downloadId)
            RETURNING download_id
        )
        INSERT INTO download_tasks
            (task_id, download_id, youtube_id, song_name, position, phase,
             phase_entered_at, next_attempt_at)
        SELECT gen_random_uuid(), :downloadId, s.youtube_id, s.song_name, s.position,
               'SEARCH_INIT', :now, :now
          FROM unnest(:youtubeIds::text[], :songNames::text[])
               WITH ORDINALITY AS s(youtube_id, song_name, position)
         WHERE EXISTS (SELECT 1 FROM admitted)
        """;
```

In `DownloadTaskRunner.gatherMetadata`, the `else` DEBUG line becomes: `log.debug("Download {} was already admitted or is no longer pending; no tasks created", download.getDownloadId());` and the INFO line's `admitted` value is now the row count — reword to `"Admitted download {} ({} '{}') as {} task(s)"` using `admitted` in place of `tasks.size()` if you like; either is fine.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.catacomb5099.naviseerr.download.*"`
Expected: PASS.

- [ ] **Step 5: Commit, push, PR** (files: the repository, the runner, the IT). Title `fix(AI): admission marks a download started before adding its songs`. Body: cancelling a queued download in the split second while the loop was fetching its track list could leave a finished download with live songs that still got searched; the two halves are now the other way round so that cannot happen. Base `fix/ai-finish-needs-lease`; "2nd of 6".

---

### Task 3: Cards count cancelled songs separately from failed ones

**Branch:** `feat/ai-cancelled-count` from `fix/ai-admit-status-first`. PR base `fix/ai-admit-status-first`.

**Files:**
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/DownloadFailureCode.java` (add `CANCELLED`)
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepository.java` (`TASK_AGGREGATE`, `PROJECTION`, `toView`)
- Modify: `src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadView.java` (new component `int songsCancelled` right after `songsFailed`)
- Modify tests: `DownloadControllerTest.view()` (constructor call gains a `0`), `ActiveDownloadRepositoryIT`

**Interfaces:**
- Produces: `DownloadFailureCode.CANCELLED`; `ActiveDownloadView.songsCancelled()`; wire field `songsCancelled`. `songsFailed` excludes cancelled songs. `failureCode` prefers a non-`CANCELLED` reason.

- [ ] **Step 1: Write the failing tests** in `ActiveDownloadRepositoryIT` (use its existing `insertDownload`, `admit`, `finish`, `active()` helpers; `finish` takes a downloadId today — add an overload or a small helper that finishes ONE task by id with a given reason, stamping the lease as in Task 1):

```java
@Test
void aCollection_countsCancelledSongsApartFromFailedOnes_andKeepsTheRealReason() {
    UUID id = insertDownload("IN_PROGRESS", "ALBUM");
    admit(id, "one", "two", "three");
    List<UUID> tasks = taskIdsOf(id);
    finishTask(tasks.get(0), DownloadStatus.SUCCEEDED, null);
    finishTask(tasks.get(1), DownloadStatus.FAILED, DownloadFailureCode.NO_CANDIDATES);
    finishTask(tasks.get(2), DownloadStatus.FAILED, DownloadFailureCode.CANCELLED);
    repository.concludeDownloads().block();   // the loop's end-of-pass step

    ActiveDownloadView view = active().stream().filter(v -> v.downloadId().equals(id)).findFirst().orElseThrow();

    assertEquals(1, view.songsSucceeded());
    assertEquals(1, view.songsFailed(), "the user's own cancel is not a failure");
    assertEquals(1, view.songsCancelled());
    assertEquals("NO_CANDIDATES", view.failureCode(), "a real failure outranks a cancellation");
    assertEquals(DownloadStage.PARTIAL_SUCCESS, view.stage());
}

@Test
void anAllCancelledCollection_readsCancelled() {
    UUID id = insertDownload("IN_PROGRESS", "ALBUM");
    admit(id, "one", "two");
    taskIdsOf(id).forEach(t -> finishTask(t, DownloadStatus.FAILED, DownloadFailureCode.CANCELLED));
    repository.concludeDownloads().block();

    ActiveDownloadView view = active().stream().filter(v -> v.downloadId().equals(id)).findFirst().orElseThrow();

    assertEquals(0, view.songsFailed());
    assertEquals(2, view.songsCancelled());
    assertEquals("CANCELLED", view.failureCode());
    assertEquals(DownloadStage.FAILED, view.stage());
}
```

(`repository` here is `DownloadTaskRepository`; autowire it if the class does not already.)

- [ ] **Step 2: Run to verify they fail** (compile error: no `CANCELLED`, no `songsCancelled()`).

- [ ] **Step 3: Implement.**

`DownloadFailureCode`:
```java
/**
 * The user stopped it. A cancelled song is a FAILED row carrying this code rather than a phase of
 * its own, so every "is this song finished?" test in the SQL and the client already treats it as
 * finished; the read model counts these rows apart from real failures (songsCancelled) and the
 * client words and colours them differently. Retrying a download resets these rows like any
 * other failure, which is how a cancel is undone.
 */
CANCELLED
```

`ActiveDownloadRepository.TASK_AGGREGATE` — replace the `failure_reason` and `songs_failed` lines and add `songs_cancelled`:
```sql
-- A real failure outranks the user's own cancel: MIN alone would sort 'CANCELLED' first.
COALESCE(MIN(t.failure_reason) FILTER (WHERE t.failure_reason <> 'CANCELLED'),
         MIN(t.failure_reason))                                        AS failure_reason,
...
COUNT(*) FILTER (WHERE t.phase = 'FAILED'
                   AND t.failure_reason IS DISTINCT FROM 'CANCELLED')  AS songs_failed,
COUNT(*) FILTER (WHERE t.phase = 'FAILED'
                   AND t.failure_reason = 'CANCELLED')                 AS songs_cancelled
```
`PROJECTION`: add `COALESCE(t.songs_cancelled, 0) AS songs_cancelled` after `songs_failed`. `toView`: `row.get("songs_cancelled", Long.class).intValue()` in the new position. `ActiveDownloadView`: add `int songsCancelled` after `songsFailed`, with `@param songsCancelled how many the user stopped. Kept apart from {@code songsFailed} so a card never calls the user's own action a failure.` Update the class Javadoc on `failureCode`: "for a collection, the first song's reason, preferring a real failure over CANCELLED". Fix `DownloadControllerTest.view()` (add `0` after the `0, 0` for succeeded/failed).

- [ ] **Step 4: Run** `./gradlew test`; expected PASS (all ITs run the aggregate).

- [ ] **Step 5: Commit, push, PR.** Title `feat(AI): cards count cancelled songs separately from failed ones`. Body: adds a "cancelled" count to every download card and stops counting a cancelled song as failed; nothing cancels yet (next PR), so this is invisible on its own. Base `fix/ai-admit-status-first`; "3rd of 6".

---

### Task 4: Cancel a download, or one song of it

**Branch:** `feat/ai-cancel-download` from `feat/ai-cancelled-count`. PR base `feat/ai-cancelled-count`.

**Files:**
- Modify: `DownloadTaskRepository.java` (add `CANCEL_SQL`, `cancelTasks`)
- Modify: `DownloadService.java` (constructor deps, `cancel`)
- Modify: `DownloadController.java` (`POST /downloads/{id}/cancel`, `outcome` helper)
- Create: `src/test/java/com/catacomb5099/naviseerr/download/DownloadServiceTest.java`
- Modify tests: `DownloadTaskRepositoryIT`, `DownloadControllerTest`
- Modify docs: `AGENTS.md` (endpoints list; line 102 "does not have"); Create `docs/decisions/retry-and-cancel-28-09-2026.md`

**Interfaces:**
- Produces: `Flux<DownloadTask> DownloadTaskRepository.cancelTasks(UUID downloadId, UUID taskId /* null = every song */, Instant now)` — the rows it cancelled, each a `DownloadTask` with `taskId, downloadId, candidates, candidateIndex, slskdUsername, slskdFilename, slskdTransferId` populated (other fields null/0).
- Produces: `Mono<Long> DownloadService.cancel(UUID downloadId, UUID taskId, Instant now)` — rows cancelled (the unadmitted download counts as 1).
- Produces: `POST /downloads/{id}/cancel?taskId=` → 200 `ActiveDownloadView` / 409 `ActiveDownloadView` / 404.
- `DownloadService` constructor becomes `(R2dbcEntityTemplate, DownloadTaskRepository, SlskdService, LibraryOrganiser)`.

- [ ] **Step 1: Failing repository ITs** in `DownloadTaskRepositoryIT`:

```java
@Test
void cancelTasks_marksLiveSongsCancelled_leavesFinishedOnesAlone_andReturnsTheirTransfers() {
    UUID id = insertDownload("IN_PROGRESS", "ALBUM");
    admit(id, "done", "polling", "searching");
    List<UUID> tasks = taskIdsOf(id);
    finish(tasks.get(0), DownloadStatus.SUCCEEDED, null);
    template.getDatabaseClient().sql("UPDATE download_tasks SET phase = 'DOWNLOAD_POLL', slskd_username = 'alice', "
            + "slskd_transfer_id = 't-1', lease_owner = 'x' WHERE task_id = :id").bind("id", tasks.get(1)).fetch().rowsUpdated().block();

    List<DownloadTask> cancelled = repository.cancelTasks(id, null, NOW).collectList().block();

    assertEquals(2, cancelled.size());
    assertEquals("SUCCEEDED", phaseOf(tasks.get(0)), "a finished song is not cancelled");
    assertEquals("FAILED", phaseOf(tasks.get(1)));
    assertEquals("CANCELLED", failureReasonOf(tasks.get(1)));
    assertNull(leaseOwnerOf(tasks.get(1)), "the lease is released so nothing else can write the row");
    DownloadTask polling = cancelled.stream().filter(t -> t.taskId().equals(tasks.get(1))).findFirst().orElseThrow();
    assertEquals("alice", polling.slskdUsername());
    assertEquals("t-1", polling.slskdTransferId());
    assertTrue(repository.claimDueTasks(10, "me", NOW.plusSeconds(1), Duration.ofMinutes(1), true, 2)
            .collectList().block().isEmpty(), "cancelled rows are never claimed again");
}

@Test
void cancelTasks_forOneSong_touchesOnlyThatSong() {
    UUID id = insertDownload("IN_PROGRESS", "ALBUM");
    admit(id, "a", "b");
    List<UUID> tasks = taskIdsOf(id);

    List<DownloadTask> cancelled = repository.cancelTasks(id, tasks.get(0), NOW).collectList().block();

    assertEquals(List.of(tasks.get(0)), cancelled.stream().map(DownloadTask::taskId).toList());
    assertEquals("SEARCH_INIT", phaseOf(tasks.get(1)));
}

@Test
void cancelTasks_twice_theSecondIsANoOp() {
    UUID id = admitOneSong("IN_PROGRESS");
    assertEquals(1, repository.cancelTasks(id, null, NOW).collectList().block().size());
    assertEquals(0, repository.cancelTasks(id, null, NOW).collectList().block().size());
}
```

Add helpers `failureReasonOf(UUID taskId)`, `leaseOwnerOf(UUID taskId)` as one-column selects.

- [ ] **Step 2: Failing service test** — create `DownloadServiceTest`:

```java
class DownloadServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final DownloadTaskRepository repository = mock(DownloadTaskRepository.class);
    private final SlskdService slskd = mock(SlskdService.class);
    private final LibraryOrganiser organiser = mock(LibraryOrganiser.class);
    private final R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
    private final DownloadService service = new DownloadService(template, repository, slskd, organiser);

    @BeforeEach
    void defaults() {
        when(repository.concludeDownloads()).thenReturn(Mono.just(0L));
        when(organiser.deletePartials(any())).thenReturn(Mono.empty());
        when(slskd.cancelDownload(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void cancel_ofAQueuedDownload_failsItUnadmitted_andNeverTouchesTasks() {
        when(repository.failUnadmitted(id, DownloadFailureCode.CANCELLED, NOW)).thenReturn(Mono.just(1L));

        assertEquals(1L, service.cancel(id, null, NOW).block());

        verify(repository, never()).cancelTasks(any(), any(), any());
        verify(repository).concludeDownloads();
    }

    @Test
    void cancel_ofARunningDownload_cancelsItsSongs_stopsTheirTransfers_andConcludes() {
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(0L));
        DownloadTask polling = DownloadTaskFixtures.downloadPolling(DownloadTaskFixtures.candidates("alice"), 0, 0, "t-1");
        DownloadTask searching = DownloadTaskFixtures.searchPolling("s-1");
        when(repository.cancelTasks(id, null, NOW)).thenReturn(Flux.just(polling, searching));

        assertEquals(2L, service.cancel(id, null, NOW).block());

        verify(slskd).cancelDownload("alice", "t-1");
        verify(slskd, never()).cancelDownload(eq(searching.slskdUsername()), any());
        verify(organiser, times(2)).deletePartials(any());
        verify(repository).concludeDownloads();
    }

    @Test
    void cancel_ofOneSong_skipsTheUnadmittedCheck() {
        UUID taskId = UUID.randomUUID();
        when(repository.cancelTasks(id, taskId, NOW)).thenReturn(Flux.empty());

        assertEquals(0L, service.cancel(id, taskId, NOW).block());

        verify(repository, never()).failUnadmitted(any(), any(), any());
        verify(repository).concludeDownloads();   // always, so the response body is never the "Waiting" quirk
    }

    @Test
    void cancel_whenSlskdRefuses_stillCancelsTheRow() {
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(0L));
        DownloadTask polling = DownloadTaskFixtures.downloadPolling(DownloadTaskFixtures.candidates("alice"), 0, 0, "t-1");
        when(repository.cancelTasks(id, null, NOW)).thenReturn(Flux.just(polling));
        when(slskd.cancelDownload(any(), any())).thenReturn(Mono.error(new RuntimeException("slskd down")));

        assertEquals(1L, service.cancel(id, null, NOW).block());
    }
}
```

- [ ] **Step 3: Failing controller tests** in `DownloadControllerTest` (the controller's `clock` is `Clock.fixed(NOW, UTC)`):

```java
@Test
void cancel_whenSomethingWasCancelled_is200WithTheFreshCard() {
    ActiveDownloadView card = view();
    when(downloadService.cancel(card.downloadId(), null, NOW)).thenReturn(Mono.just(2L));
    when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

    ResponseEntity<ActiveDownloadView> response = controller.cancel(card.downloadId(), null).block();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(card, response.getBody());
}

@Test
void cancel_whenNothingWasLeftToCancel_is409WithTheCurrentCard() {
    ActiveDownloadView card = view();
    when(downloadService.cancel(card.downloadId(), null, NOW)).thenReturn(Mono.just(0L));
    when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

    assertEquals(HttpStatus.CONFLICT, controller.cancel(card.downloadId(), null).block().getStatusCode());
}

@Test
void cancel_ofAnUnknownDownload_is404() {
    UUID unknown = UUID.randomUUID();
    when(downloadService.cancel(unknown, null, NOW)).thenReturn(Mono.just(0L));
    when(activeDownloadRepository.findByIds(List.of(unknown))).thenReturn(Flux.empty());

    assertEquals(HttpStatus.NOT_FOUND, controller.cancel(unknown, null).block().getStatusCode());
}

@Test
void cancel_ofOneSong_passesTheTaskIdThrough() {
    ActiveDownloadView card = view();
    UUID taskId = UUID.randomUUID();
    when(downloadService.cancel(card.downloadId(), taskId, NOW)).thenReturn(Mono.just(1L));
    when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

    assertEquals(HttpStatus.OK, controller.cancel(card.downloadId(), taskId).block().getStatusCode());
    verify(downloadService).cancel(card.downloadId(), taskId, NOW);
}
```

- [ ] **Step 4: Run** the three classes; expected: compile failures (missing methods).

- [ ] **Step 5: Implement.**

`DownloadTaskRepository`:
```java
/**
 * Cancels every unfinished song of a download, or one song when {@code :taskId} is given. The SET
 * is {@link DownloadService#FINISH_TASK_SQL}'s with the reason fixed to CANCELLED; the guard is the
 * same "still unfinished" test, and deliberately NOT a lease test: a step that is mid-flight will
 * find the row finished when it comes back and write nothing (SAVE_SQL/FINISH_TASK_SQL guards).
 * RETURNING hands back what the caller needs to stop the transfer in slskd and remove partial
 * files. Bind the whole-download case with {@code bindNull("taskId", UUID.class)}.
 */
private static final String CANCEL_SQL = """
        UPDATE download_tasks
           SET phase = 'FAILED',
               failure_reason = 'CANCELLED',
               phase_entered_at = :now,
               finished_at = :now,
               updated_at = now(),
               lease_owner = NULL,
               lease_expires_at = NULL
         WHERE download_id = :id
           AND phase NOT IN ('SUCCEEDED', 'FAILED')
           AND (:taskId::uuid IS NULL OR task_id = :taskId)
        RETURNING task_id, download_id, candidates, candidate_index,
                  slskd_username, slskd_filename, slskd_transfer_id
        """;

/** The songs this call cancelled, with what stopping them in slskd and on disk needs. Empty when nothing was left to cancel. */
public Flux<DownloadTask> cancelTasks(UUID downloadId, UUID taskId, Instant now) {
    DatabaseClient.GenericExecuteSpec spec = client.sql(CANCEL_SQL)
            .bind("id", downloadId)
            .bind("now", now);
    spec = taskId == null ? spec.bindNull("taskId", UUID.class) : spec.bind("taskId", taskId);
    return spec.map((row, meta) -> DownloadTask.builder()
                    .taskId(row.get("task_id", UUID.class))
                    .downloadId(row.get("download_id", UUID.class))
                    .candidates(readCandidates(row.get("candidates", String.class)))
                    .candidateIndex(row.get("candidate_index", Integer.class))
                    .slskdUsername(row.get("slskd_username", String.class))
                    .slskdFilename(row.get("slskd_filename", String.class))
                    .slskdTransferId(row.get("slskd_transfer_id", String.class))
                    .build())
            .all();
}
```

`DownloadService` — constructor and `cancel`:
```java
private final R2dbcEntityTemplate entityTemplate;
private final DownloadTaskRepository repository;
private final SlskdService slskdService;
private final LibraryOrganiser organiser;

public DownloadService(R2dbcEntityTemplate entityTemplate, DownloadTaskRepository repository,
                       SlskdService slskdService, LibraryOrganiser organiser) { ... }

/**
 * Cancels a whole download ({@code taskId} null) or one of its songs. Three statements, in an
 * order that matters:
 * <ol>
 *   <li>Whole download only: fail it as unadmitted if it is still PENDING (guarded on that status,
 *       so admission and cancel serialise on the row: whichever commits first wins).</li>
 *   <li>Cancel every unfinished song row; for each, best-effort stop its slskd transfer and remove
 *       partial files. Fire-and-forget: the row is cancelled whether or not slskd hears this.</li>
 *   <li>Derive the download's status NOW rather than at the end of the next pass, so the response
 *       body is never the two-second window in which every song is finished but the download is
 *       still IN_PROGRESS (which the feed renders as QUEUED). Idempotent; runs even when nothing
 *       was cancelled, because the step that finished the last song may have beaten us to it.</li>
 * </ol>
 * Not one CTE: a single statement sees one snapshot, so when admission wins the row lock it could
 * not see the song rows admission just committed and would report "nothing to cancel".
 *
 * @return rows cancelled: the unadmitted download counts as one; 0 means nothing was left to cancel
 */
public Mono<Long> cancel(UUID downloadId, UUID taskId, Instant now) {
    Mono<Long> unadmitted = taskId == null
            ? repository.failUnadmitted(downloadId, DownloadFailureCode.CANCELLED, now)
            : Mono.just(0L);
    return unadmitted
            .flatMap(rows -> rows > 0
                    ? Mono.just(rows)
                    : repository.cancelTasks(downloadId, taskId, now)
                            .doOnNext(this::stopInSlskd)
                            .count())
            .doOnNext(rows -> {
                if (rows > 0) log.info("Cancelled {} song(s) of download {}", rows, downloadId);
            })
            .flatMap(rows -> repository.concludeDownloads().thenReturn(rows));
}

/** Same shape as DownloadStepExecutor.cancelIfAbandoned: the decision is written; slskd is told after, best effort. */
private void stopInSlskd(DownloadTask task) {
    if (task.slskdTransferId() != null) {
        Mono.defer(() -> slskdService.cancelDownload(task.slskdUsername(), task.slskdTransferId()))
                .subscribe(ignored -> { },
                        error -> log.warn("Could not cancel transfer {} from '{}' for song {}; it stays in slskd's list",
                                task.slskdTransferId(), task.slskdUsername(), task.taskId(), error),
                        () -> log.info("Cancelled transfer {} from '{}' for song {}",
                                task.slskdTransferId(), task.slskdUsername(), task.taskId()));
    }
    organiser.deletePartials(task).subscribe();
}
```

`DownloadController`:
```java
/**
 * Cancels a download, or one song of it when {@code taskId} is given. Songs already downloaded are
 * untouched; the rest are marked cancelled and their transfers stopped. 200 with the fresh card,
 * 409 with the current card when nothing was left to cancel (already finished, or a second click),
 * 404 for an unknown id.
 */
@PostMapping("/downloads/{id}/cancel")
Mono<ResponseEntity<ActiveDownloadView>> cancel(@PathVariable UUID id,
                                                @RequestParam(required = false) UUID taskId) {
    return outcome(id, downloadService.cancel(id, taskId, clock.instant()), HttpStatus.OK);
}

/** Runs a write, then reads the card back: rows > 0 is the happy status, 0 is 409, no card is 404. */
private Mono<ResponseEntity<ActiveDownloadView>> outcome(UUID id, Mono<Long> rows, HttpStatus onSuccess) {
    return rows.flatMap(n -> activeDownloadRepository.findByIds(List.of(id)).next()
            .map(view -> ResponseEntity.status(n > 0 ? onSuccess : HttpStatus.CONFLICT).body(view))
            .defaultIfEmpty(ResponseEntity.notFound().build()));
}
```

- [ ] **Step 6: Run** `./gradlew test`; expected PASS.

- [ ] **Step 7: Docs.** `AGENTS.md`: in the endpoint list add
  `- POST /downloads/{id}/cancel?taskId= — cancels every unfinished song of a download, or one song. A cancelled song is a FAILED task row with failure_reason CANCELLED (no status of its own); the download's status is then derived as usual (FAILED when nothing downloaded, PARTIAL_SUCCESS otherwise). Best-effort DELETE of the live slskd transfers. 200 with the fresh card, 409 with the current card when nothing was left to cancel, 404 unknown. See docs/decisions/retry-and-cancel-28-09-2026.md`.
  Line 102 becomes: `- A CANCELLED status or SKIPPED: a cancelled song is a FAILED row with reason CANCELLED (see docs/decisions/retry-and-cancel-28-09-2026.md); retry lands in the PR after cancel.`
  Create `docs/decisions/retry-and-cancel-28-09-2026.md` in the style of `curated-download-28-09-2026.md` (Date, Status "Accepted, cancel implemented; retry in the next PR", Builds on, Context, Decisions numbered 1–6 from the spec's table, "The two loop fixes", Trade-offs, "Not built" incl. the sweep escape hatch). Keep it under 120 lines; the spec has the detail.

- [ ] **Step 8: Commit, push, PR.** Title `feat(AI): cancel a download, or one song of it`. Body: what the user can do (stop a queued/running download, or one song of an album; downloaded songs are kept; the card shows the cancelled count in grey once the client PR lands), the product choice ("Cancelled" is stored as a failed song with the reason "Cancelled", so no database change; retrying later un-cancels), and Checked. Base `feat/ai-cancelled-count`; "4th of 6".

---

### Task 5: Stop a transfer that started after its song was cancelled

**Branch:** `fix/ai-orphaned-transfer` from `feat/ai-cancel-download`. PR base `feat/ai-cancel-download`.

**Files:**
- Modify: `DownloadTaskRunner.java:448-457` (`apply`, Advance branch)
- Test: `DownloadTaskRunnerTest.java`

- [ ] **Step 1: Failing test** in `DownloadTaskRunnerTest` (follow the class's existing way of driving one step: mock `executor.execute(...)` to return a decision, mock `repository.save(...)`, then run `runner.pass().block()` with the claim returning the task):

```java
@Test
void anEnqueueTheSaveRefused_cancelsItsTransferInSlskd() {
    DownloadTask task = DownloadTaskFixtures.downloadInit(DownloadTaskFixtures.candidates("alice"), 0, 0);
    DownloadTask enqueued = task.withPhase(DownloadPhase.DOWNLOAD_POLL, NOW).toBuilder()
            .slskdUsername("alice").slskdTransferId("t-9").build();
    // <existing setup that makes claimDueTasks return `task` and execute return Advance(enqueued)>
    when(repository.save(any(), any())).thenReturn(Mono.just(0L));   // the row was cancelled meanwhile
    when(slskdService.cancelDownload("alice", "t-9")).thenReturn(Mono.empty());

    runner.pass().block();

    verify(slskdService).cancelDownload("alice", "t-9");
}

@Test
void anEnqueueTheSaveAccepted_isLeftRunning() {
    // same setup, save returns 1
    runner.pass().block();
    verify(slskdService, never()).cancelDownload(any(), any());
}
```

- [ ] **Step 2: Run to verify the first fails** (no cancel call).

- [ ] **Step 3: Implement** — replace line 456:

```java
yield repository.save(advance.next(), instanceId)
        // Zero rows means the row went terminal under us -- cancelled by the user while this
        // step was enqueueing. slskd now has a transfer nobody tracks; stop it. A crash before
        // this line still orphans one (accepted in the 13-08-2026 ADR).
        .flatMap(rows -> rows == 0
                && advance.next().phase() == DownloadPhase.DOWNLOAD_POLL
                && advance.next().slskdTransferId() != null
                ? slskdService.cancelDownload(advance.next().slskdUsername(), advance.next().slskdTransferId())
                        .doOnSuccess(v -> log.info("Cancelled transfer {} from '{}' that started after song {} was cancelled",
                                advance.next().slskdTransferId(), advance.next().slskdUsername(), task.taskId()))
                        .onErrorResume(error -> {
                            log.warn("Could not cancel orphaned transfer {} from '{}' for song {}",
                                    advance.next().slskdTransferId(), advance.next().slskdUsername(), task.taskId(), error);
                            return Mono.empty();
                        })
                : Mono.empty())
        .then();
```

- [ ] **Step 4: Run** `./gradlew test --tests "com.catacomb5099.naviseerr.download.DownloadTaskRunnerTest"`; PASS.

- [ ] **Step 5: Commit, push, PR.** Title `fix(AI): stop a transfer that started after its song was cancelled`. Base `feat/ai-cancel-download`; "5th of 6".

---

### Task 6: Retry a failed download

**Branch:** `feat/ai-retry-download` from `fix/ai-orphaned-transfer`. PR base `fix/ai-orphaned-transfer`.

**Files:**
- Modify: `DownloadTaskRepository.java` (`RETRY_SQL`, `READMIT_SQL`, `SET_ORGANISED_AT_SQL`, `retry`, `readmit`)
- Modify: `DownloadService.java` (`retry`)
- Modify: `DownloadController.java` (`POST /downloads/{id}/retry`)
- Modify tests: `DownloadTaskRepositoryIT`, `DownloadServiceTest`, `DownloadControllerTest`
- Modify docs: `AGENTS.md`, `docs/decisions/retry-and-cancel-28-09-2026.md` (status line)

**Interfaces:**
- Produces: `Mono<Long> DownloadTaskRepository.retry(UUID downloadId, Instant now)` (0/1), `Mono<Long> DownloadTaskRepository.readmit(UUID downloadId)` (0/1), `Mono<Long> DownloadService.retry(UUID downloadId, Instant now)` (0/1), `POST /downloads/{id}/retry` → 202/409/404 with `ActiveDownloadView`.

- [ ] **Step 1: Failing repository ITs** in `DownloadTaskRepositoryIT`:

```java
@Test
void retry_resetsOnlyFailedSongs_reopensTheDownload_andClearsOrganisedAt() {
    UUID id = insertDownload("IN_PROGRESS", "PLAYLIST");
    admit(id, "ok", "bad", "stopped");
    List<UUID> tasks = taskIdsOf(id);
    finish(tasks.get(0), DownloadStatus.SUCCEEDED, null);
    finish(tasks.get(1), DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED);
    repository.cancelTasks(id, tasks.get(2), NOW).blockLast();
    repository.concludeDownloads().block();
    repository.setOrganisedAt(id, NOW).block();
    assertEquals("PARTIAL_SUCCESS", statusOf(id));

    Long rows = repository.retry(id, NOW.plusSeconds(60)).block();

    assertEquals(1L, rows);
    assertEquals("IN_PROGRESS", statusOf(id));
    assertNull(organisedAtOf(id), "the playlist file is rewritten once the retried songs land");
    assertEquals("SUCCEEDED", phaseOf(tasks.get(0)), "a song with a file is left alone");
    assertEquals("SEARCH_INIT", phaseOf(tasks.get(1)));
    assertEquals("SEARCH_INIT", phaseOf(tasks.get(2)), "a cancelled song is retried too: that is how a cancel is undone");
    assertNull(failureReasonOf(tasks.get(1)));
    assertEquals(2, repository.claimDueTasks(10, "me", NOW.plusSeconds(61), Duration.ofMinutes(1), true, 2)
            .collectList().block().size(), "both reset songs are due again");
}

@Test
void retry_twice_theSecondIsANoOp() {
    UUID id = failedSong();   // helper: admitOneSong("IN_PROGRESS"), finish FAILED, concludeDownloads
    assertEquals(1L, repository.retry(id, NOW).block());
    assertEquals(0L, repository.retry(id, NOW).block(), "the second click finds no failed song left");
}

@Test
void retry_twoConcurrentCalls_exactlyOneWins() {
    UUID id = failedSong();
    Tuple2<Long, Long> both = Mono.zip(repository.retry(id, NOW), repository.retry(id, NOW)).block();
    assertEquals(1L, both.getT1() + both.getT2());
}

@Test
void retry_whileInProgress_isANoOp() {
    UUID id = admitOneSong("IN_PROGRESS");
    assertEquals(0L, repository.retry(id, NOW).block());
    assertEquals("SEARCH_INIT", phaseOf(taskIdsOf(id).getFirst()));
}

@Test
void retry_ofAFullyDownloadedDownload_isANoOp() {
    UUID id = admitOneSong("IN_PROGRESS");
    finish(taskIdsOf(id).getFirst(), DownloadStatus.SUCCEEDED, null);
    repository.concludeDownloads().block();
    assertEquals(0L, repository.retry(id, NOW).block());
    assertEquals(0L, repository.readmit(id).block(), "songs exist, so this is not an unadmitted failure either");
}

@Test
void readmit_ofAnUnadmittedFailure_returnsItToPending() {
    UUID id = insertDownload("PENDING");
    repository.failUnadmitted(id, DownloadFailureCode.CANCELLED, NOW).block();

    assertEquals(1L, repository.readmit(id).block());
    assertEquals("PENDING", statusOf(id));
    assertNull(failureReasonOfDownload(id));
    assertEquals(1, repository.admitDownloads(10).collectList().block().size(), "admission picks it up again");
}

@Test
void setOrganisedAt_afterARetry_updatesNothing() {
    UUID id = failedSong();
    repository.retry(id, NOW).block();
    assertEquals(0L, repository.setOrganisedAt(id, NOW).block(), "a stamp in flight when the user clicked Retry must not land");
}

@Test
void finishTask_afterTheRowWasCancelledAndRetried_isANoOp() {
    UUID id = admitOneSong("IN_PROGRESS");
    DownloadTask claimed = repository.claimDueTasks(10, "owner-a", NOW, Duration.ofMinutes(1), true, 2).blockFirst();
    repository.cancelTasks(id, null, NOW).blockLast();
    repository.concludeDownloads().block();
    repository.retry(id, NOW.plusSeconds(2)).block();

    Long rows = downloadService.finishTask(claimed.taskId(), DownloadStatus.SUCCEEDED, null, NOW.plusSeconds(3), "owner-a").block();

    assertEquals(0L, rows, "the old step's outcome must not land on the new attempt");
    assertEquals("SEARCH_INIT", phaseOf(claimed.taskId()));
}
```

Helpers to add: `failedSong()`, `organisedAtOf(UUID)`, `failureReasonOfDownload(UUID)`.

- [ ] **Step 2: Failing service test** in `DownloadServiceTest`:

```java
@Test
void retry_thatResetSongs_doesNotReadmit() {
    when(repository.retry(id, NOW)).thenReturn(Mono.just(1L));
    assertEquals(1L, service.retry(id, NOW).block());
    verify(repository, never()).readmit(any());
}

@Test
void retry_withNothingToReset_fallsBackToReadmitting() {
    when(repository.retry(id, NOW)).thenReturn(Mono.just(0L));
    when(repository.readmit(id)).thenReturn(Mono.just(1L));
    assertEquals(1L, service.retry(id, NOW).block());
}
```

- [ ] **Step 3: Failing controller tests** in `DownloadControllerTest`: `retry_whenSomethingWasRetried_is202WithTheFreshCard` (service returns 1 → `HttpStatus.ACCEPTED`, body = card), `retry_whenNothingToRetry_is409WithTheCurrentCard` (0 → CONFLICT), `retry_ofAnUnknownDownload_is404` (0 and `findByIds` empty). Same shape as the cancel tests.

- [ ] **Step 4: Run** the three classes; expected compile failures.

- [ ] **Step 5: Implement.** `DownloadTaskRepository`:

```java
/**
 * Retries a finished download: every FAILED song (cancelled ones included -- "retry" means "the
 * ones I did not get") goes back to the start of its pipeline, in place, and the download reopens.
 * One statement, so a double-click's second request finds no FAILED rows left, the reset CTE is
 * empty, and the outer UPDATE matches nothing: rows updated is 0 or 1 and IS the idempotence.
 *
 * <p>The previous attempt's peers and error are not kept on the row; apply() logged them when the
 * song failed. A second row per song would break every COUNT the cards read.
 *
 * <p>{@code failure_reason = NULL} on downloads: the feed prefers the download's own reason over
 * its songs', so a stale one would outrank the fresh rows. {@code organised_at = NULL}: the playlist
 * file is rewritten whole once the retried songs are filed; songs already filed keep library_path.
 */
private static final String RETRY_SQL = """
        WITH reset AS (
            UPDATE download_tasks
               SET phase = 'SEARCH_INIT',
                   phase_entered_at = :now,
                   next_attempt_at = :now,
                   finished_at = NULL,
                   failure_reason = NULL,
                   last_error = NULL,
                   search_id = NULL,
                   search_tier = 0,
                   candidates = '[]',
                   candidate_index = 0,
                   retry_index = 0,
                   slskd_username = NULL,
                   slskd_filename = NULL,
                   slskd_transfer_id = NULL,
                   progress_percent = 0,
                   updated_at = now(),
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
               AND phase = 'FAILED'
               AND EXISTS (SELECT 1 FROM downloads
                            WHERE download_id = :id
                              AND status IN ('FAILED', 'PARTIAL_SUCCESS'))
            RETURNING task_id
        )
        UPDATE downloads
           SET status = 'IN_PROGRESS',
               failure_reason = NULL,
               finished_at = NULL,
               organised_at = NULL
         WHERE download_id = :id
           AND status IN ('FAILED', 'PARTIAL_SUCCESS')
           AND EXISTS (SELECT 1 FROM reset)
        """;

/** A download that failed before it had any songs (bad id, or cancelled while queued) goes back to PENDING; admission fetches the track list again. */
private static final String READMIT_SQL = """
        UPDATE downloads
           SET status = 'PENDING',
               failure_reason = NULL,
               finished_at = NULL
         WHERE download_id = :id
           AND status = 'FAILED'
           AND NOT EXISTS (SELECT 1 FROM download_tasks WHERE download_id = :id)
        """;

// SET_ORGANISED_AT_SQL: add the status guard
private static final String SET_ORGANISED_AT_SQL = """
        UPDATE downloads
           SET organised_at = :now
         WHERE download_id = :id
           AND organised_at IS NULL
           -- A retry between downloadsToFinalise's SELECT and this stamp reopens the download;
           -- stamping it then would leave the playlist file short until the next retry.
           AND status IN ('SUCCEEDED', 'PARTIAL_SUCCESS')
        """;

public Mono<Long> retry(UUID downloadId, Instant now) {
    return client.sql(RETRY_SQL).bind("id", downloadId).bind("now", now).fetch().rowsUpdated();
}

public Mono<Long> readmit(UUID downloadId) {
    return client.sql(READMIT_SQL).bind("id", downloadId).fetch().rowsUpdated();
}
```

`DownloadService`:
```java
/**
 * Retries a finished download. Songs that failed or were cancelled start again; songs with a file
 * are left alone. A download that never got songs is re-queued for admission instead. 0 means
 * nothing to retry: still running, fully downloaded, or a concurrent retry got there first.
 */
public Mono<Long> retry(UUID downloadId, Instant now) {
    return repository.retry(downloadId, now)
            .flatMap(rows -> rows > 0 ? Mono.just(rows) : repository.readmit(downloadId))
            .doOnNext(rows -> {
                if (rows > 0) log.info("Retrying download {}", downloadId);
            });
}
```

`DownloadController`:
```java
/**
 * Retries a finished download: every song without a file starts again. 202 with the fresh card
 * (like a new request: the work follows), 409 with the current card when there is nothing to retry
 * -- still running, fully downloaded, or a second click -- and 404 for an unknown id.
 */
@PostMapping("/downloads/{id}/retry")
Mono<ResponseEntity<ActiveDownloadView>> retry(@PathVariable UUID id) {
    return outcome(id, downloadService.retry(id, clock.instant()), HttpStatus.ACCEPTED);
}
```

- [ ] **Step 6: Run** `./gradlew test`; expected PASS (count the new tests in the PR body).

- [ ] **Step 7: Docs.** `AGENTS.md` endpoint list: `- POST /downloads/{id}/retry — retries a finished download: every FAILED song (cancelled included) is reset in place to SEARCH_INIT and the download reopened, in one statement; a download that failed before it had songs goes back to PENDING. 202 with the fresh card, 409 with the current card when there is nothing to retry (running, fully downloaded, or a second click), 404 unknown.` Update line 102 ("retry lands in the PR after cancel" → drop that clause). ADR status line → "Accepted, implemented".

- [ ] **Step 8: Commit, push, PR.** Title `feat(AI): retry a failed download`. Body: what the user gets (Retry on a failed or partly downloaded item; only the missing songs are fetched again; unlimited retries; a double-click retries once), the choices (retry only once the download has finished; retrying a cancelled download un-cancels it), Checked, Docs. Base `fix/ai-orphaned-transfer`; "6th of 6 — the client PRs need this deployed for the Retry button".

---

## Client

### Task 7: Show cancelled downloads in grey, not red

**Branch:** `feat/ai-cancelled-grey` from `remote/move-fast-break-things` (the client remote is `remote`). PR base `move-fast-break-things`.

**Files:**
- Modify: `src/api/types.ts` (`DownloadFailureCode`, `ActiveDownloadView.songsCancelled`)
- Modify: `src/lib/downloadPanel.ts` (`FAILURE_COPY`, `isCancelled`, `DownloadCardState.songsCancelled`, `mergeCard`)
- Modify: `src/lib/collectionProgress.ts` (`CollectionTally`, `CollectionSegments`, `collectionSegments`, `collectionSummary`)
- Modify: `src/components/CollectionProgress.tsx` (new segment + prop)
- Modify: `src/components/DownloadCard.tsx`, `src/components/DownloadRow.tsx`, `src/components/DownloadPanel.tsx`
- Modify: `src/lib/downloadLibrary.ts` (`DownloadItem.songsCancelled`, `pageItems`)
- Modify: `src/hooks/useActiveDownloads.ts` (optimistic card gets `songsCancelled: 0`)
- Modify: `src/api/mockData.ts` (`toView` and the two fixtures get `songsCancelled`)
- Modify: `scripts/check-download-state.ts`, `scripts/check-collection-progress.ts`

**Interfaces:**
- Produces: `isCancelled(x: { stage: DownloadStage; failureCode: string | null }): boolean` in `lib/downloadPanel.ts` = `x.stage === 'FAILED' && x.failureCode === 'CANCELLED'`; `songsCancelled: number` on `ActiveDownloadView`, `DownloadCardState`, `DownloadItem`, `CollectionTally`; `CollectionSegments.cancelled`; `CollectionProgress` prop `songsCancelled`.

- [ ] **Step 1: Failing checks.** In `scripts/check-download-state.ts` (near the `isTerminal` asserts):

```ts
import { failureCopy, isCancelled } from '../src/lib/downloadPanel'
assert(failureCopy('CANCELLED') === 'Cancelled', 'cancelled wording')
assert(isCancelled({ stage: 'FAILED', failureCode: 'CANCELLED' }), 'a failed card with the cancelled code is cancelled')
assert(!isCancelled({ stage: 'DOWNLOADING', failureCode: 'CANCELLED' }), 'a live album with one cancelled song is not itself cancelled')
assert(!isCancelled({ stage: 'PARTIAL_SUCCESS', failureCode: 'CANCELLED' }), 'a partly downloaded album is not cancelled')
```

In `scripts/check-collection-progress.ts`, extend `seg`/`sum` with a `songsCancelled` argument as the LAST parameter (default 0) and add:

```ts
assert(JSON.stringify(seg(12, 7, 2, 'DOWNLOADING', 1)) === JSON.stringify({ succeeded: 7, failed: 2, cancelled: 1, inProgress: 2 }),
  'cancelled songs are settled, never in progress')
assert(sum(12, 7, 3, 'PARTIAL_SUCCESS', 'NO_CANDIDATES', 2) === '7 of 12 downloaded · 3 failed · 2 cancelled', 'partial with cancels')
assert(sum(12, 0, 0, 'FAILED', 'CANCELLED', 12) === 'Cancelled', 'all cancelled reads one word')
assert(sum(0, 0, 0, 'FAILED', 'CANCELLED') === 'Cancelled', 'cancelled while queued reads one word')
assert(sum(12, 7, 0, 'DOWNLOADING', null, 1) === '7 done · 1 cancelled · 4 in progress', 'live album with one cancelled song')
```
Every existing `JSON.stringify(seg(...))` expectation gains `cancelled: 0` in the right position (`succeeded, failed, cancelled, inProgress`).

- [ ] **Step 2: Run** `npm run check` — expected: tsc error (no `isCancelled`, no `songsCancelled`).

- [ ] **Step 3: Implement.**

`types.ts`: add `| 'CANCELLED'` to `DownloadFailureCode` with the comment `/** The user stopped it. Comes with stage FAILED; shown in grey, not red. */`; add to `ActiveDownloadView` after `songsFailed`: `/** Songs the user stopped. Kept apart from songsFailed so the card never calls the user's own action a failure. Absent from an older server; read as 0. */ songsCancelled?: number`.

`downloadPanel.ts`: `FAILURE_COPY.CANCELLED = 'Cancelled'`; `DownloadCardState.songsCancelled: number`; export
```ts
/** A download the user stopped. Both conditions: a live album with one cancelled song carries the code too,
 *  and must not be painted grey. */
export function isCancelled(x: { stage: DownloadStage; failureCode: string | null }): boolean {
  return x.stage === 'FAILED' && x.failureCode === 'CANCELLED'
}
```
`mergeCard`: `const songsCancelled = row.songsCancelled ?? 0`; include `existing.songsCancelled !== songsCancelled` in `changed`; set `songsCancelled` on the result.

`collectionProgress.ts`:
```ts
export interface CollectionTally { songCount: number; songsSucceeded: number; songsFailed: number; songsCancelled: number; stage: DownloadStage; failureCode: string | null }
export interface CollectionSegments { succeeded: number; failed: number; cancelled: number; inProgress: number }
export function collectionSegments(t: CollectionTally): CollectionSegments {
  const settled = t.songsSucceeded + t.songsFailed + t.songsCancelled
  const inProgress = isTerminal(t.stage) ? 0 : Math.max(0, t.songCount - settled)
  return { succeeded: t.songsSucceeded, failed: t.songsFailed, cancelled: t.songsCancelled, inProgress }
}
```
`collectionSummary` terminal branch: after the failure-before-admission case, if `failed && songsSucceeded === 0 && songsFailed === 0 && songsCancelled > 0` return `[{ text: 'Cancelled', tone: 'muted' }]`; also when `songCount === 0 && failed && failureCode === 'CANCELLED'` the existing first branch already returns `failureCopy` = 'Cancelled'. Then the tokens: `N of M downloaded` (or `None of M downloaded`), `F failed` (failed tone) if > 0, `C cancelled` (muted tone) if > 0, and the trailing reason token only when `failed && failureCode !== 'CANCELLED'`. Live branch: add `{ text: `${songsCancelled} cancelled`, tone: 'muted' }` after the failed token when > 0; the "nothing settled yet" test becomes `songsSucceeded === 0 && songsFailed === 0 && songsCancelled === 0`.

`CollectionProgress.tsx`: prop `songsCancelled: number`; `ORDER = ['succeeded', 'failed', 'cancelled', 'inProgress']`; `FILL.cancelled = 'bg-zinc-600'`; pass `songsCancelled` into `collectionSegments`; `settled` includes it. Both call sites (`DownloadCard`, `DownloadRow`) pass `songsCancelled={card.songsCancelled}` / `{item.songsCancelled}`.

`DownloadCard.tsx`: import `Ban` and `isCancelled`; in the `case 'FAILED':` arm: `if (isCancelled(card)) { glyph = <Ban className="w-4 h-4 text-zinc-400" aria-hidden="true" />; subColor = 'text-zinc-400' } else { ...existing red... }`.

`DownloadRow.tsx`: `stageColor`: `if (isCancelled(item)) return 'text-zinc-400'` before the FAILED red. `songStatus`: `case 'FAILED'`: if `song.failureCode === 'CANCELLED'` return `{ glyph: <Ban .../>, word: 'Cancelled', color: 'text-zinc-400' }`.

`DownloadPanel.tsx`: inside the existing `else if (card.stage === 'FAILED')` branch: `setAnnouncement(isCancelled(card) ? `${title} cancelled.` : `${title} failed to download. ${failureCopy(card.failureCode)}.`)`.

`downloadLibrary.ts`: `DownloadItem.songsCancelled: number`; `pageItems` sets `songsCancelled: source.songsCancelled ?? 0`. `useActiveDownloads.requestDownload`: `songsCancelled: 0` in the optimistic card. `mockData.ts`: `toView` sets `songsCancelled: 0`; both fixtures get `songsCancelled: 0`. Any other object literal typed as `ActiveDownloadView`/`DownloadCardState`/`CollectionTally` in `scripts/` gets `songsCancelled: 0`.

- [ ] **Step 4: Run** `npm run check && npm run lint && npm run build`; all green.

- [ ] **Step 5: Commit, push, PR.** Files: everything above (never `package.json`/`package-lock.json`). Title `feat(AI): show cancelled downloads in grey, not red`. Body: what changes on screen once the server can cancel (grey "Cancelled", a separate "2 cancelled" count, cancelled never called failed); safe to ship before the server. Base `move-fast-break-things`; "1st of 5 stacked client PRs".

---

### Task 8: A download that starts again replaces its finished card

**Branch:** `feat/ai-reopened-card` from `feat/ai-cancelled-grey`. PR base `feat/ai-cancelled-grey`.

**Files:**
- Modify: `src/lib/downloadPanel.ts` (`mergeCard`)
- Modify: `src/hooks/useActiveDownloads.ts` (`applyRows`)
- Modify: `src/components/DownloadPanel.tsx` (announcement)
- Modify: `scripts/check-download-state.ts`
- Create: `docs/decisions/retry-and-cancel-28-09-2026.md` (client ADR)

- [ ] **Step 1: Failing checks** in `scripts/check-download-state.ts` (keep the existing `done` assertion: equal timestamps, live row over a finished card → unchanged):

```ts
const T1 = '2026-01-01T00:00:10.000Z', T2 = '2026-01-01T00:00:20.000Z'
const failedCard: DownloadCardState = { ...card, stage: 'FAILED', failureCode: 'TIMED_OUT', progressPercent: 40, updatedAt: T1 }
// A strictly newer live row reopens a finished card, and the old outcome does not leak into the new attempt.
const reopened = mergeCard(failedCard, { ...row, stage: 'STARTING', progressPercent: null, failureCode: null, updatedAt: T2 })
assert(reopened.stage === 'STARTING', 'a newer live row reopens a finished card')
assert(reopened.failureCode === null, 'the old failure code does not survive a retry')
assert(reopened.progressPercent === null, 'the old progress does not survive a retry')
assert(reopened.lastChangedAt > failedCard.lastChangedAt, 'reopening is a change')
assert(mergeCard(failedCard, { ...row, stage: 'STARTING', updatedAt: T1 }) === failedCard, 'an equal-timestamp live row is the two-second quirk, not a retry')
// A stale finished row must not close a card that has since been reopened.
const liveCard: DownloadCardState = { ...card, stage: 'STARTING', updatedAt: T2 }
assert(mergeCard(liveCard, { ...row, stage: 'FAILED', failureCode: 'TIMED_OUT', updatedAt: T1 }) === liveCard, 'an older finished row is dropped')
assert(mergeCard(liveCard, { ...row, stage: 'FAILED', failureCode: 'TIMED_OUT', updatedAt: T2 }).stage === 'FAILED', 'an equal-timestamp finished row lands (fail-before-admission)')
// Live to live is untouched by the timestamp rule: the optimistic card's clock is the server's JVM, the rows' is Postgres.
assert(mergeCard(liveCard, { ...row, stage: 'SEARCHING', updatedAt: T1 }).stage === 'SEARCHING', 'live rows merge regardless of timestamp')
```

- [ ] **Step 2: Run** `npm run check`; expected: the first `reopened` assertion fails (rule returns `failedCard`).

- [ ] **Step 3: Implement** `mergeCard` — replace the `if (existing && isTerminal(existing.stage) && !isTerminal(row.stage)) return existing` block and the two coalesces:

```ts
  // Crossing between finished and live is decided by the server's updatedAt, which is monotonic per
  // download (every write stamps it; concluding does not).
  //   finished -> live: only if strictly newer. A retry stamps now(); the two-second row in which
  //     every song is finished but the download is not yet concluded has the SAME timestamp as the
  //     finished card and must not reopen it.
  //   live -> finished: only if not strictly older. A stale poll answer from before a retry must not
  //     close the reopened card; but a fail-before-admission row carries the download's created_at,
  //     equal to the optimistic card's, and must land.
  // Live -> live is merged as before: the optimistic card's clock is the JVM's, the rows' is Postgres.
  const crossing = !!existing && isTerminal(existing.stage) !== isTerminal(row.stage)
  if (existing && crossing) {
    const rowAt = Date.parse(row.updatedAt), knownAt = Date.parse(existing.updatedAt)
    if (isTerminal(existing.stage) ? rowAt <= knownAt : rowAt < knownAt) return existing
  }
  const reopened = !!existing && crossing && isTerminal(existing.stage)

  // A reopened card's old outcome is not an observation about the new attempt.
  const progressPercent = row.progressPercent ?? (reopened ? null : existing?.progressPercent ?? null)
  const failureCode = reopened ? row.failureCode ?? null : row.failureCode ?? existing?.failureCode ?? null
```
Update the function's Javadoc bullet "**A terminal stage is final**" to describe the timestamp rule.

`useActiveDownloads.applyRows`: replace `if (dismissedRef.current.has(row.downloadId)) continue` with
```ts
if (dismissedRef.current.has(row.downloadId)) {
  // A finished row for a dismissed card stays dismissed. A LIVE row for one can only mean the
  // download was retried (from this tab or another), and a retried download gets its card back.
  if (isTerminal(row.stage)) continue
  dismissedRef.current.delete(row.downloadId)
}
```

`DownloadPanel.tsx`: in the effect, before the `if (announcedRef.current.has(card.downloadId)) return`, add: `if (announcedRef.current.has(card.downloadId) && !isTerminal(card.stage)) { announcedRef.current.delete(card.downloadId); setAnnouncement(`Retrying ${displayTitle(card)}.`); return }`.

- [ ] **Step 4: Run** `npm run check && npm run lint && npm run build`; green.

- [ ] **Step 5: Client ADR** `docs/decisions/retry-and-cancel-28-09-2026.md` in the style of `adaptive-polling-28-09-2026.md`: the problem (finished cards were final; retry needs them to reopen), what was already true, the rule (timestamps, both directions, live-to-live untouched), the un-dismiss rule, what the buttons will do (next PRs), when to revisit. Under 80 lines.

- [ ] **Step 6: Commit, push, PR.** Title `feat(AI): a download that starts again replaces its finished card`. Base `feat/ai-cancelled-grey`; "2nd of 5".

---

### Task 9: Cancel button on downloads

**Branch:** `feat/ai-cancel-button` from `feat/ai-reopened-card`. PR base `feat/ai-reopened-card`.

**Files:**
- Modify: `src/api/client.ts` (`ApiError.body`)
- Modify: `src/api/endpoints.ts` (`cancelDownload`), `src/api/mockData.ts` (`cancelMockDownload`)
- Modify: `src/lib/downloadPanel.ts` (`replaceCard`)
- Modify: `src/hooks/useActiveDownloads.ts` (`act`, `cancel`, `inFlight`)
- Modify: `src/components/DownloadCard.tsx`, `src/components/DownloadPanel.tsx`, `src/components/DownloadRow.tsx`, `src/pages/DownloadsPage.tsx`, `src/App.tsx`
- Modify: `scripts/check-download-state.ts`

**Interfaces:**
- Produces: `cancelDownload(id: string, taskId?: string): Promise<ActiveDownloadView>` (`POST /downloads/{id}/cancel[?taskId=]`); `ApiError.body: unknown` (parsed JSON of a non-2xx response when present); `replaceCard(existing: DownloadCardState | undefined, view: ActiveDownloadView): DownloadCardState`; hook returns `cancel(id: string, taskId?: string): Promise<void>` and `inFlight: Set<string>` (keys are `taskId ?? downloadId`); `DownloadPanel`/`DownloadCard` props `onCancel: (id: string) => void`, `inFlight: Set<string>`; `DownloadsPage`/`DownloadRow` props `onCancel: (id: string, taskId?: string) => void`, `inFlight: Set<string>`.

- [ ] **Step 1: Failing check** in `scripts/check-download-state.ts`:

```ts
import { replaceCard } from '../src/lib/downloadPanel'
// The body of the user's own click replaces stage and counts outright, but metadata still only fills in.
const fromBody = replaceCard(card, { ...row, stage: 'FAILED', failureCode: 'CANCELLED', title: null, updatedAt: T0 })
assert(fromBody.stage === 'FAILED' && fromBody.failureCode === 'CANCELLED', 'the body wins on stage and outcome')
assert(fromBody.title === 'Down' && fromBody.imageUrl === 'https://img/1.png', 'a null title in the body does not blank the card')
assert(fromBody.lastChangedAt > card.lastChangedAt, 'an action restarts the dismiss clock')
```

- [ ] **Step 2: Run** `npm run check`; expected tsc error (no `replaceCard`).

- [ ] **Step 3: Implement.**

`client.ts`: `ApiError` gains `public body: unknown = undefined` as a fourth constructor parameter; in `apiClient`, on `!response.ok`, `const body = response.headers.get('content-type')?.includes('application/json') ? await response.json().catch(() => undefined) : undefined` and pass it.

`endpoints.ts`:
```ts
/**
 * Cancel a download, or one song of it
 * POST /downloads/{id}/cancel[?taskId=]  (200 with the fresh card; 409 with the current card when nothing was left to cancel; 404 unknown)
 */
export async function cancelDownload(id: string, taskId?: string): Promise<ActiveDownloadView> {
  if (USE_MOCK_DATA) return Promise.resolve(cancelMockDownload(id))
  const query = taskId ? `?taskId=${encodeURIComponent(taskId)}` : ''
  return apiClient<ActiveDownloadView>(`/downloads/${encodeURIComponent(id)}/cancel${query}`, { method: 'POST' })
}
```
`mockData.ts` `cancelMockDownload(id)`: look up the entry; if `stageAt(entry, Date.now()).stage` is terminal throw `new ApiError('conflict', 409, 'Conflict', toView(entry, Date.now()))`; else set the entry's outcome to `FAILED` with `failureCode: 'CANCELLED'`, rewind `entry.createdAt` so `stageAt` is terminal now (subtract the simulator's total duration constant), persist, return `toView(entry, Date.now())`. Fixtures (the album/partial ids) throw the 409 too.

`downloadPanel.ts`:
```ts
/** The card after the user's own retry/cancel: the response body is computed after the write, so it is
 *  authoritative on stage, outcome and counts, and it restarts the dismiss clock. Metadata still only
 *  fills in: a re-queued download's body has no title yet. */
export function replaceCard(existing: DownloadCardState | undefined, view: ActiveDownloadView): DownloadCardState {
  const fresh = mergeCard(undefined, view)
  return {
    ...fresh,
    title: fresh.title ?? existing?.title ?? null,
    artists: fresh.artists.length > 0 ? fresh.artists : existing?.artists ?? [],
    imageUrl: fresh.imageUrl ?? existing?.imageUrl ?? null,
    lastChangedAt: Date.now(),
  }
}
```

`useActiveDownloads.ts`:
```ts
const inFlightRef = useRef<Set<string>>(new Set())
const [inFlight, setInFlight] = useState<Set<string>>(new Set())

/**
 * One retry or cancel. `key` is the task id for a song, else the download id, so two songs of one
 * album can be cancelled at once while the whole-download button stays single-flight. The server
 * refuses a duplicate anyway (409); this stops the duplicate being sent at all.
 */
const act = useCallback(async (id: string, key: string, call: () => Promise<ActiveDownloadView>) => {
  if (inFlightRef.current.has(key)) return
  inFlightRef.current.add(key); setInFlight(new Set(inFlightRef.current))
  try {
    const view = await call()
    dismissedRef.current.delete(id)   // a dismissed card that the user acts on comes back
    setCards(prev => ({ ...prev, [id]: replaceCard(prev[id], view) }))
    lastRequestedAtRef.current = Date.now()   // the 30 s fast-poll window, as after a new request
    void pollNow()
  } catch (err) {
    if (err instanceof ApiError && err.status === 409 && isView(err.body)) {
      applyRows([err.body]); void pollNow()   // the server's answer is settled; show it
    } else if (err instanceof ApiError && err.status === 404) {
      dismiss(id, { silent: true })
    } else {
      console.error('Download action failed:', err)
    }
  } finally {
    inFlightRef.current.delete(key); setInFlight(new Set(inFlightRef.current))
  }
}, [applyRows, dismiss, pollNow])

const cancel = useCallback((id: string, taskId?: string) =>
  act(id, taskId ?? id, () => cancelDownload(id, taskId)), [act])
```
with `function isView(x: unknown): x is ActiveDownloadView { return typeof x === 'object' && x !== null && 'downloadId' in x && 'stage' in x }`. Return `cancel` and `inFlight` from the hook. The auto-dismiss interval skips a card whose id is in `inFlightRef.current`.

`DownloadCard.tsx`: props `onCancel: () => void; inFlight: boolean`; for `!terminal` render before the (absent) X: `<button type="button" className="flex-none p-1 rounded text-zinc-400 hover:text-white hover:bg-zinc-700 disabled:opacity-50" aria-label={`Cancel ${title}`} disabled={inFlight} onClick={onCancel}><Square className="w-3.5 h-3.5" aria-hidden="true" /></button>`. `DownloadPanel.tsx`: props `onCancel: (id: string) => void; inFlight: Set<string>`, passed per card (`inFlight={inFlight.has(card.downloadId)}`).

`DownloadRow.tsx`: props `onCancel: (id: string, taskId?: string) => void; inFlight: Set<string>`; a right-aligned button column: live → Cancel (`Square`, `aria-label={`Cancel ${item.title}`}`, `disabled={inFlight.has(item.downloadId)}`, `onClick={e => { e.stopPropagation(); onCancel(item.downloadId) }}`). `DownloadsPage.tsx` threads `onCancel`, `inFlight` from props; `App.tsx` takes `cancel, inFlight` from the hook and passes them to `DownloadPanel` and `DownloadsPage`.

- [ ] **Step 4: Run** `npm run check && npm run lint && npm run build`; green.

- [ ] **Step 5: Commit, push, PR.** Title `feat(AI): cancel button on downloads`. Body: where the button is (panel card and Downloads page row, on anything not finished, including "Waiting"), what happens (downloaded songs kept, the rest marked cancelled in grey, transfer stopped), that a double-click cannot send twice, and that it needs the server's cancel PR. Base `feat/ai-reopened-card`; "3rd of 5".

---

### Task 10: Retry button on failed downloads

**Branch:** `feat/ai-retry-button` from `feat/ai-cancel-button`. PR base `feat/ai-cancel-button`.

**Files:**
- Modify: `src/api/endpoints.ts` (`retryDownload`), `src/api/mockData.ts` (`retryMockDownload`)
- Modify: `src/hooks/useActiveDownloads.ts` (`retry`)
- Modify: `src/components/DownloadCard.tsx`, `src/components/DownloadPanel.tsx`, `src/components/DownloadRow.tsx`, `src/pages/DownloadsPage.tsx`, `src/App.tsx`

**Interfaces:**
- Produces: `retryDownload(id: string): Promise<ActiveDownloadView>` (`POST /downloads/{id}/retry`, 202); hook `retry(id: string): Promise<void>`; props `onRetry: (id: string) => void` on `DownloadPanel`, `DownloadCard`, `DownloadsPage`, `DownloadRow`.

- [ ] **Step 1: Implement** (no new pure logic; the check script already covers `replaceCard` and the reopen rule):

`endpoints.ts`:
```ts
/**
 * Retry a finished download: every song without a file starts again
 * POST /downloads/{id}/retry  (202 with the fresh card; 409 with the current card when there is nothing to retry; 404 unknown)
 */
export async function retryDownload(id: string): Promise<ActiveDownloadView> {
  if (USE_MOCK_DATA) return Promise.resolve(retryMockDownload(id))
  return apiClient<ActiveDownloadView>(`/downloads/${encodeURIComponent(id)}/retry`, { method: 'POST' })
}
```
`mockData.ts` `retryMockDownload(id)`: if the entry's current stage is not terminal throw `ApiError(409)` with the view; else reset `createdAt = Date.now()`, pick a fresh outcome, persist, return the view (stage will read QUEUED/STARTING).

Hook: `const retry = useCallback((id: string) => act(id, id, () => retryDownload(id)), [act])`; return it.

`DownloadCard.tsx`: prop `onRetry: () => void`; when `card.stage === 'FAILED' || card.stage === 'PARTIAL_SUCCESS'` render, before the X: `<button type="button" className="flex-none p-1 rounded text-zinc-400 hover:text-white hover:bg-zinc-700 disabled:opacity-50" aria-label={`Retry ${title}`} disabled={inFlight} onClick={onRetry}><RotateCcw className="w-3.5 h-3.5" aria-hidden="true" /></button>`. `DownloadPanel.tsx`: prop `onRetry: (id: string) => void`. `DownloadRow.tsx`: prop `onRetry: (id: string) => void`; failed/partial → Retry button (`RotateCcw`, `aria-label={`Retry ${item.title}`}`, `disabled={inFlight.has(item.downloadId)}`, stopPropagation). `DownloadsPage.tsx`, `App.tsx`: thread `onRetry`/`retry`.

- [ ] **Step 2: Run** `npm run check && npm run lint && npm run build`; green.

- [ ] **Step 3: Commit, push, PR.** Title `feat(AI): retry button on failed downloads`. Body: where it is (a failed or partly downloaded card or row), what it does (only the songs without a file are fetched again, as many times as you like; a double-click retries once; retrying a cancelled download un-cancels it), and that a failed card in the panel still auto-dismisses after 30 s so the Downloads page is where to retry later. Needs the server's retry PR. Base `feat/ai-cancel-button`; "4th of 5".

---

### Task 11: Cancel one song inside an album or playlist

**Branch:** `feat/ai-cancel-song` from `feat/ai-retry-button`. PR base `feat/ai-retry-button`.

**Files:**
- Modify: `src/components/DownloadRow.tsx` (`SongRow`)

- [ ] **Step 1: Implement.** `SongRow` gains props `downloadId: string; onCancel: (id: string, taskId?: string) => void; inFlight: boolean; onActed: () => void`. For a live song (`!isTerminal(song.stage)`) render after the status span: `<button type="button" className="p-1 rounded text-zinc-400 hover:text-white hover:bg-zinc-700 disabled:opacity-50" aria-label={`Cancel ${song.title ?? 'song'}`} disabled={inFlight} onClick={() => { onCancel(downloadId, song.taskId); onActed() }}><Square className="w-3.5 h-3.5" aria-hidden="true" /></button>`. `DownloadRow` passes `downloadId={item.downloadId}`, `onCancel={onCancel}`, `inFlight={inFlight.has(song.taskId)}`, `onActed={() => setAttempt(a => a + 1)}` (reloads the song list so the row shows "Cancelled" without waiting for the next refresh).

- [ ] **Step 2: Run** `npm run check && npm run lint && npm run build`; green.

- [ ] **Step 3: Commit, push, PR.** Title `feat(AI): cancel one song inside an album or playlist`. Body: open an album on the Downloads page, each song that is still running has a Cancel; the rest of the album carries on; the song reads "Cancelled" in grey; retrying the album later fetches it. Base `feat/ai-retry-button`; "5th of 5".

---

## Verification after all eleven PRs

- Server: `./gradlew test` green on `feat/ai-retry-download`; count reported in each PR.
- Client: `npm run check && npm run lint && npm run build` green on `feat/ai-cancel-song`.
- Each PR: base branch is its parent; label `AI`; body ends with the Claude Code line; no `package.json`/`package-lock.json` changes in any client PR.
- Final message to the owner: the two lists of PR links in merge order, the product choices (the six decisions) and how to flip each, and what was deliberately not built.

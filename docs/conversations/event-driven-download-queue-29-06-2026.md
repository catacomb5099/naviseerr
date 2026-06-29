# Conversation: event-driven-download-queue
- **Date:** 2026-06-29
- **Topic:** event-driven-download-queue

---

## Summary
Implemented an event-driven (sleep/wake) download queue on top of the
`background-runner-pending-downloads` branch. The HTTP request flow stays as
insert-`PENDING`-and-return. A separate interval claimer flips `PENDING -> IN_PROGRESS`
and emits each claimed download into a new in-memory Reactor `Sinks` queue. A worker
subscribes once and processes claimed downloads with bounded concurrency, running the
slskd acquisition flow (search -> select -> download, with the processors' retry/failover)
and writing a terminal `SUCCEEDED`/`FAILED` status in a DB transaction. No crash-recovery
for in-flight (`IN_PROGRESS`) work yet — explicitly deferred and documented as a must-do.

## Key Actions
1. Created worktree `event-driven-download-queue` off `background-runner-pending-downloads`.
2. Added `DownloadQueue` (unicast `Sinks.Many<Download>` with `enqueue`/`asFlux`).
3. Added `DownloadFulfillment.fulfill(songName)` (the slskd search/select/download chain).
4. Added transactional `DownloadService.markStatus(id, status)` (guarded to IN_PROGRESS).
5. Changed `PendingDownloadRunner` to emit claimed rows into the queue (kept the interval).
6. Added `DownloadWorker` consuming the queue via `flatMap(process, concurrency=3)`,
   mapping success/error/empty to terminal status and isolating per-item failures.
7. Added `download-worker.concurrency: 3` config.
8. Converted the broken `SearchServiceTest` into `DownloadFulfillmentTest`; added
   `DownloadQueueTest`, `DownloadWorkerTest`, `PendingDownloadRunnerTest`, and DB-backed
   `markStatus` coverage in `DownloadServiceClaimIT`; made `NaviseerrApplicationTests`
   self-contained via Testcontainers.
9. Updated `AGENTS.md` (new download execution flow + prominent must-do resilience note).

## Files Changed
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadQueue.java` — created
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadFulfillment.java` — created
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadWorker.java` — created
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java` — modified (markStatus)
- `src/main/java/com/catacomb5099/naviseerr/download/PendingDownloadRunner.java` — modified (emit to queue)
- `src/main/resources/application.yaml` — modified (download-worker.concurrency)
- `src/test/java/com/catacomb5099/naviseerr/download/DownloadFulfillmentTest.java` — created (replaces SearchServiceTest)
- `src/test/java/com/catacomb5099/naviseerr/download/DownloadQueueTest.java` — created
- `src/test/java/com/catacomb5099/naviseerr/download/DownloadWorkerTest.java` — created
- `src/test/java/com/catacomb5099/naviseerr/download/PendingDownloadRunnerTest.java` — created
- `src/test/java/com/catacomb5099/naviseerr/download/DownloadServiceClaimIT.java` — modified (markStatus tests)
- `src/test/java/com/catacomb5099/naviseerr/NaviseerrApplicationTests.java` — modified (Testcontainers import)
- `src/test/java/com/catacomb5099/naviseerr/services/SearchServiceTest.java` — deleted (stale/broken)
- `AGENTS.md` — modified (download execution flow + must-do resilience note)

## Outcome
`./gradlew test` is green: 56 tests across 10 classes, 0 failures/errors/skipped,
including the Testcontainers-backed integration tests. Out of scope / next:
crash-recovery (reaper for orphaned `IN_PROGRESS`), queue overflow/backpressure,
durable broker, cancellation, collections, SSE.

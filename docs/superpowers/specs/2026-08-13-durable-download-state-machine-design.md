# Durable Download State Machine — Design

> Status: approved 2026-08-13. Supersedes the RabbitMQ/Redis "target download manager architecture"
> previously described in `AGENTS.md`.

## Problem

A download request must advance through several steps, each of which is a call to slskd — an external
system that owns the authoritative state of that step. Most steps are *start something remote*, then
*poll until the remote side finishes*. Failure handling is a nested hierarchy: retry the same peer,
then move to the next candidate peer, then fail. A request may live for minutes to hours.

Today every part of "where has this request got to" lives in the JVM heap: which step, which slskd
search id, which candidate, how many retries remain. Postgres knows only
`PENDING | IN_PROGRESS | SUCCEEDED | FAILED`, which is not enough to resume anything.

Three concrete failures follow:

1. **naviseerr restarts mid-download.** `PendingDownloadRunner` flips a row `PENDING -> IN_PROGRESS`
   and enqueues it into an in-heap `Sinks` buffer in the same instant — destroying the durable
   evidence at the moment it creates the fragile reminder. After a restart the claimer only looks for
   `PENDING`, so the row is stranded at `IN_PROGRESS` forever.
2. **naviseerr restarts just after `POST /transfers/downloads/{user}`.** slskd is downloading a file
   naviseerr has no record of.
3. **slskd restarts.** `ReactivePoller.pollUntil` only retries `PollingInProgressException`, so an
   HTTP error from a vanished search propagates and the download is marked `FAILED`.

## Pattern

This is a **process manager** driving **async request-reply with polling correlation**, made
crash-safe by a **reconciliation loop** over a durable store. It is deliberately *not* modelled as a
saga (there is exactly one compensable effect in the whole system) and *not* as a queue problem (a
queue cannot be queried, so it cannot answer "which downloads are stuck in `DOWNLOAD_POLL`?").

Two invariants drive every decision below.

**Level-triggered, not edge-triggered.** Never act on a notification that cannot be regenerated.
Repeatedly ask the durable store what is due, and act on the answer. A lost wakeup then costs one
loop interval instead of a download.

**A wait held in memory is a wait that dies with the process.** Persisting the wait as a
`next_attempt_at` timestamp both frees the worker and survives restart — one change, two problems.

## Chosen approach

Postgres is the workflow engine. No new runtime infrastructure — **no RabbitMQ, no Redis, ever**.
That constraint is a deliberate product decision: naviseerr competes with Lidarr and Jellyseerr,
which self-host as a single container, so requiring a broker is an adoption tax the project will not
pay.

### State lives in a new table, not new columns

`download_tasks`, keyed by `download_id`, holds the working state. `downloads` keeps its existing
four-status user-facing lifecycle and is not altered.

Consequences, all of them wanted:

- **Churn is isolated.** The table written every few seconds is not the table history queries read.
- **Task rows are retained after completion** in a terminal phase (`SUCCEEDED`/`FAILED`) with
  `finished_at` and `failure_reason`. A self-hoster filing a bug report needs to answer "which peers
  were tried, and how did each fail?" from their own instance, and this is also the per-song history a
  collection will need later. A **partial index** on `next_attempt_at` covering only non-terminal rows
  is what keeps the due-work query fast regardless of how much history accumulates — so retention costs
  nothing. Retention pruning (delete after N months) can be added later; disk is not a real constraint.
- `"a non-terminal task row exists" == "this download is in flight"` becomes a queryable invariant.
- The `Download` entity is untouched, so `DownloadServiceClaimIT` stays green.

**Flyway owns the schema from this change onward.** `schema.sql` + `spring.sql.init` are retired in
favour of `db/migration/V1__baseline.sql` and `V2__download_tasks.sql`, with `baseline-on-migrate: true`
so installs that already hold data are adopted rather than recreated.

This reverses an earlier conclusion in this document, and the reasoning is worth keeping. `CREATE TABLE
IF NOT EXISTS` genuinely would have sufficed for this change, and the note in `AGENTS.md` claiming
`schema.sql` cannot express column additions was simply wrong (Postgres has `ALTER TABLE ... ADD COLUMN
IF NOT EXISTS`). But naviseerr is continuously updated software installed by other people: a user on an
old version pulls a new image with a year of history in their database. `PARTIAL_SUCCESS` (collections)
and `CANCELLED` both require altering the `downloads.status` `CHECK` constraint, which is not
idempotent, so this is unavoidable. And adopting Flyway *later* is harder than adopting it now, because
baselining across a population of installs at varying versions is worse than baselining a three-table
schema. Cost of moving early is near zero. Flyway needs a blocking JDBC driver used only at boot; the
runtime path stays entirely on R2DBC.

`song_name` is denormalised into `download_tasks` so the hot due-work query needs no join.
`candidates` is stored as JSON in a `TEXT` column — the list is written once and read whole, never
queried by content, so `JSONB` would buy nothing and a child table would add a join plus ordering
columns for nothing.

### One loop, three steps per pass

```
PASS (every download-task.loop-interval-ms):

  (a) ADMIT   non-terminal downloads with no task row  ->  create task (SEARCH_INIT, due now)
              and flip downloads.status to IN_PROGRESS.  Bounded by max-concurrent-downloads.

  (b) CLAIM   non-terminal task rows where next_attempt_at <= now AND no live lease,
              FOR UPDATE SKIP LOCKED LIMIT batch-size, stamping a lease.
              DOWNLOAD_INIT rows are excluded when no transfer slots are free.

  (c) STEP    per claimed row: at most one slskd call (searches and downloads are read from a
              per-pass batch instead — see below) -> pure state machine -> one write.
```

The admit query deliberately matches **non-terminal** downloads (`PENDING` *or* `IN_PROGRESS`) with no
task row, not just `PENDING`. That makes *"every non-terminal download has a task row"* an invariant
the loop continuously restores rather than one the code hopes for. Recovery restarts that download
from `SEARCH_INIT`, which is acceptable for a should-not-happen state.

Passes are serialised (`concatMap`), so a slow pass delays the next one. Accepted: it keeps the first
version simple, and because leases already exist, switching to overlapping passes later is safe and
requires no other change. **This is a separate axis from how the rows claimed *within* one pass are
stepped, which is concurrent (`flatMap(batch-size)`).** An earlier draft used `concatMap` for both,
which meant a batch of `batch-size` claimed rows was stepped strictly one at a time — with a 10s slskd
timeout and `batch-size: 10`, a single pass could take up to 100 seconds, reproducing this design's
core problem inside one pass. Stepping rows concurrently needs no thread pool: WebFlux already runs an
event loop per core.

### Leases, not a reaper

Claiming a row stamps `lease_owner` and `lease_expires_at`; the due-work query skips rows with a live
lease. The same field does two jobs — it stops a second pass double-stepping a row whose slskd call is
still outstanding (slskd's timeout is 10s, the loop interval is 2s, so this happens routinely), and it
detects a dead process. **No separate stale-row reaper is needed**, and lease expiry is precise where a
time-since-`updated_at` heuristic is a guess.

### The state machine is a function, not an object

`DownloadStateMachine` has no fields, does no I/O, and remembers nothing between calls. It takes the
row plus what slskd just said and returns one of three decisions:

- `Advance` — genuine phase transition; re-run immediately.
- `Continue` — re-poll, retry, or next candidate; re-run after the phase's poll interval.
- `Terminal` — write the download's status and mark the task terminal (retained, not deleted — see
  above).

Anything that is not forward progress is `Continue`, so it is always rate-limited. `phase_entered_at`
resets whenever the phase changes and is preserved otherwise, so each phase gets a real duration
budget — replacing `max-poll-attempts: 30`, which with a doubling backoff and no cap currently spans
roughly two years and is therefore not a timeout at all.

### Batching the two poll phases

`SEARCH_POLL` and `DOWNLOAD_POLL` are, individually, one slskd call per download per poll — the cost
scales with how many downloads are in flight, forever. slskd's own API exposes both as full lists:
`GET /searches` (confirmed to report each search's completion state) and `GET /transfers/downloads`
(confirmed to exist, but with **no pagination, date filter, or state filter** — only
`includeRemoved`). Fetching each once per pass, only when at least one claimed row needs it, and
looking each row up by id turns "one call per download per poll" into "at most two calls per pass,
however many downloads are in flight."

The two phases are not symmetric. `GET /searches` has no discovered downside, and it is what makes
pruning completed searches worthwhile — `DELETE /searches/{id}` is a distinct verb from cancelling one,
and a search holds no partial-download state to lose, so deleting it once its candidates are copied
into this row is safe, unlike deleting a transfer (see the crash-window section below for why
transfers are different). `GET /transfers/downloads`'s unfiltered response size on an install with
years of history is a genuinely open question from here — deliberately left open rather than guessed
at, with a named live-verification step and a same-shape fallback (per-transfer polling,
parallelised instead of read from a shared map — one branch and one call site change, nothing else).

A row missing from either batched map (claimed, but absent from the fetch) is treated identically to
"still running" rather than as a distinct failure: there is no reliable way to distinguish "not there
yet" from "slskd forgot it," so both resolve via the existing phase-budget timeout.

### Per-phase poll intervals

Searches complete in seconds; transfers run for minutes to hours. Polling both at the same cadence is
waste. Search polls every 2s, download polls every 5s.

This is also why Redis is unnecessary. The reason people put progress in Redis is byte-level percentage
churn — thousands of writes per second. This design writes **one row per poll per download**: 20
in-flight downloads at a 5s cadence is 4 writes/second, which Postgres does not notice. In exchange the
progress state is queryable and survives restart. Batching the poll calls (above) does not change this
write volume — it only changes how many slskd calls a pass makes, not how many `download_tasks` rows
get updated.

### Three independent bounds, where today there is one

`flatMap(this::process, 3)` conflates three unrelated limits. They separate cleanly:

- `batch-size / loop-interval` is a hard ceiling on slskd request rate.
- `max-concurrent-downloads` caps how many user requests are worked on at once. It counts **`downloads`
  rows**, not task rows, so a collection of 500 songs is *one* in-flight download and cannot lock every
  other request out of admission.
- `max-concurrent-transfers` caps how many slskd transfers exist at once, protecting bandwidth and
  peers' upload queues. It gates **only** the step that starts a transfer, never polling: starving a
  poll does not deprioritise a download, it stalls one that slskd is happily finishing, because nothing
  is looking at it. Implementation excludes `DOWNLOAD_INIT` rows from the claim query rather than
  claiming and deferring them, so a large collection cannot spend every pass being re-deferred.

Downloads parked in `SEARCH_POLL` cost one table row each and nothing else, so they need no bound.

Work is taken oldest-first with **no per-collection cap**. One collection may legitimately hold every
transfer slot until it is done — that is the intended default, since a user who asked for something
usually wants it finished. Making that a user preference is planned; hardcoding either behaviour is not.
Anything adaptive (measuring achieved throughput per peer and re-prioritising) is explicitly rejected as
far more machinery than this product warrants.

### Terminal writes are atomic

One statement, using a data-modifying CTE, so the download's status and the task's terminal phase cannot
be split by a crash. The task write is **unconditional on the download update's outcome**: if the status
is already terminal (reachable whenever an expired lease causes a duplicated step) a conditional write
would leave the task non-terminal, and the next pass would re-step it, re-reach `Terminal`, and change
nothing — one slskd call per interval, forever.

```sql
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
```

Postgres executes a data-modifying CTE exactly once even when nothing references it, so both halves
always run inside one statement's transaction. The predicate is `status NOT IN ('SUCCEEDED','FAILED')`
rather than `= 'IN_PROGRESS'` so the write is correct from any non-terminal status, which removes the
ambiguity in a zero-row result. The row count becomes a log line, not a branch.

This also gives correct cancellation semantics for free later: a cancelled download's terminal step finds
the status already set, skips the update, and still marks the task terminal so the work stops.

### The one remaining crash window, and why it is left open

Between `POST /transfers/downloads/{user}` returning and the transfer id being written, a crash leaves
slskd downloading a file naviseerr has no record of. Re-running that step asks the same peer for the
same file again — the one step, of four, that is not free to simply repeat: a duplicate `POST
/searches` is wasteful but harmless, and both `GET`s are free (and now batched away entirely for the
poll phases — see above).

**Decided: do nothing about it.** An earlier version of this design closed the window with an
intent-before-effect flag (`enqueue_attempted`, written and committed before calling slskd; a crash
found on re-entry with the flag already set would burn that candidate attempt rather than re-enqueue).
That mechanism is removed. The window is single-digit milliseconds — between an HTTP response landing
and one `UPDATE` committing — and the cost when a crash actually lands inside it is one duplicate
downloaded file, once, per crash. That is an acceptable cost for a self-hosted music downloader, and
it is cheaper than the column, the extra write before *every* enqueue (not just the ones that crash),
and the dedicated state-machine branch the guard required. On restart mid-enqueue, a download simply
re-enters `DOWNLOAD_INIT` and calls slskd again.

**Deliberately deferred, and still the right follow-up if this judgement changes:** ask slskd for that
peer's existing transfers and *adopt* a match instead of either abandoning the attempt or blindly
re-enqueueing. That needs an endpoint `SlskdService` does not wrap yet
(`GET /transfers/downloads/{username}`) whose response shape must be confirmed against a live slskd
instance first. It is also what would make mid-transfer **slskd-side** restarts fully recoverable
rather than merely survivable, which the current design does not attempt.

### Prerequisite bug fixes

- `TransferedFileUtil.getStateList` matches slskd's state strings against the enum's `name()`, so
  `"TimedOut"` never matches `TIMED_OUT` and `"InProgress"` never matches `IN_PROGRESS`. Single-word
  states match by luck. A timed-out transfer is therefore never classified as a failure, making the
  retry-then-next-candidate tier unreachable for the most common Soulseek failure. Must match on
  `getValue()`.
- `SlskdSearchResultProcessor` line 53 hardcodes `Predicate<SearchState> failed = s -> false`, so a
  search can never fail. Needs a real classification, designed to fail safe: an unrecognised state
  string falls through to the "completed with no usable candidates" path rather than misbehaving.

## What this deletes

| Removed | Replaced by |
|---|---|
| `DownloadQueue` (in-heap `Sinks` buffer) | the due-work query |
| `PendingDownloadRunner` | step (a) of the same loop |
| `DownloadWorker` | step (c) of the same loop |
| `DownloadFulfillment` (one long chain) | four independent one-call steps |
| `ReactivePoller` + doubling backoff | `next_attempt_at`, a column |
| `SlskdSearchResultProcessor.pollUntilComplete` | `SEARCH_INIT` / `SEARCH_POLL` steps |
| `SlskdDownloadProcessor.pollUntilComplete` | `DOWNLOAD_INIT` / `DOWNLOAD_POLL` steps |
| `SlskdService.getSearchResultsProgress` (one call per download per search poll) | `getAllSearches`, fetched once per pass |
| one `getDownloadProgress` call per download per transfer poll | `getAllDownloads`, fetched once per pass (accepted-risk, see above) |
| `enqueue_attempted` column + `onEnqueueAbandoned` (intent-before-effect guard) | nothing — an occasional duplicate download after a crash is accepted |
| row-stepping `concatMap` (serialised claimed rows within a pass) | `flatMap(batch-size)` |
| a stale-`IN_PROGRESS` reaper (never built; PR #6) | lease expiry |
| `max-poll-attempts` | `phase_entered_at` + a duration budget |

Kept byte-for-byte: `selectBestFiles` and everything under it (`isRelevant`, `isFlacAndHighBitrate`,
`TrackMatchingService`), `searchResults`, `enqueueDownload`, and `getDownloadProgress` (retained as the
documented fallback for the accepted-risk batched download poll, not called by default).

## Non-goals

Collection/playlist downloads (shape agreed separately, implementation deferred), SSE progress
streaming, an append-only per-attempt log, duration-based queue scheduling, and adopting orphaned slskd
transfers (the follow-up named in the crash-window section above). Cancellation's shape is agreed
separately too, but its implementation is likewise not part of this design. Collections and
cancellation are the most likely immediate follow-ups.

Candidate **ranking** is a special case: `selectBestFiles` must stay byte-for-byte unchanged through the
pipeline work, because its existing tests passing unmodified is the guard that this work did not touch
ranking. One ranking change is then made deliberately, sequenced last — `selectBestFiles` currently sorts
purely on `uploadSpeed` and ignores `hasFreeUploadsSlot` and `queueLength`, so a peer claiming 10 MB/s
with 40 people queued outranks one claiming 2 MB/s that can start immediately. `uploadSpeed` is the
remote client's self-reported historical average across all its uploads to anyone, not a measurement of
the route to you; a free slot is a fact about the present. Prefer the fact.

Scheduling by estimated duration (`size / uploadSpeed`, shortest first) was considered and deferred. The
estimate would *not* need recomputing — both inputs come from the search response and neither changes.
It was deferred because **it only earns anything when more songs are waiting to start than there are free
transfer slots**, which is rare with one user and 20 slots. Revisit on queueing pressure, not on a better
estimate. It will need an aging term (shortest-first starves large files) and must apply only to starting
transfers, never to polling — the claim query already has that seam. Because candidates are stored as
JSON in a `TEXT` column, adding fields to `DownloadCandidate` later needs no migration, so nothing is
carried speculatively now.

See the plan's "Future work" section for the agreed collections shape and the remaining open question
there (whether the client sends the tracklist or the server looks it up).

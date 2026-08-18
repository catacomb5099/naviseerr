# Decisions: durable-download-state-machine

- **Date:** 13-08-2026
- **Topic:** durable-download-state-machine
- **Design spec:** `docs/superpowers/specs/2026-08-13-durable-download-state-machine-design.md`
- **Implementation plan:** `docs/superpowers/plans/2026-08-13-durable-download-state-machine.md`

---

## Decision: Postgres is the workflow engine — no broker, no Redis, indefinitely

**Context:** The download pipeline's working state (which step, slskd search id, candidate index, retry counters) lives entirely in the JVM heap. A restart strands rows at `IN_PROGRESS` forever, because `PendingDownloadRunner` destroys the durable evidence (`PENDING -> IN_PROGRESS`) at the same instant it creates the fragile in-heap reminder. The previously documented target architecture was RabbitMQ + Redis.

**Options Considered:**
1. *Durable state machine in Postgres, level-triggered loop with leases* — no new infrastructure; state is queryable; deletes more code than it adds; hand-rolls leases, backoff and timeouts.
2. *Event-sourced attempt log* — best audit trail and the dataset needed to tune ranking/matching; but "what's due?" still needs an indexed projection, so it reduces to option 1 plus a log.
3. *RabbitMQ step pipeline with delayed messages* — redelivery, DLQ, delayed messages and prefetch all come free; but a queue cannot be queried, so phase must be mirrored into Postgres anyway, state then lives in two places that can disagree, it still needs a reaper, and at-least-once still forces idempotent steps.
4. *Durable execution engine (Temporal / Restate / DBOS)* — exactly the right abstraction; would delete the most code. Temporal needs its own server; Restate's Java SDK is blocking-oriented; DBOS's Java/WebFlux story is immature. Replay determinism rules are hostile to occasional contributors.
5. *Keep the in-heap queue, add only a stale-row reaper* — smallest change, but recovery restarts the whole download and, worse, it *causes* the duplicate-download bug it was meant to avoid, since slskd is still transferring the earlier attempt.

**Final Choice:** Option 1, permanently — not as a stepping stone toward option 3.

**Rationale:** Primarily a product decision. naviseerr competes with Lidarr and Jellyseerr, which self-host as a single container; requiring Postgres *and* RabbitMQ *and* Redis is an adoption tax the project will not pay. Technically, option 3's decisive flaw is that a queue is not queryable — "which downloads are stuck polling a transfer?" is one `SELECT` under option 1 and unanswerable under option 3 without duplicating the state into Postgres regardless. Option 4 is the correct pattern and is worth treating as the reference model: option 1 is deliberately the small subset of it this project needs, so it borrows its vocabulary (activity, lease, heartbeat timeout, idempotency key, non-retryable failure).

Notably, option 1 **removes** machinery rather than adding it: the in-heap queue, the emission lock and slot accounting it would have needed, `ReactivePoller`, `DownloadFulfillment`, and the never-built stale-row reaper all disappear.

---

## Decision: New `download_tasks` table, not new columns on `downloads`

**Context:** The working state has to live somewhere. `downloads` already exists with a four-value status `CHECK` constraint.

**Options Considered:**
1. *Add columns to `downloads`* — one row per download, no join, but mixes a hot write path into the table history queries read, and needs `ALTER TABLE`.
2. *New `download_tasks` table keyed by `download_id`* — `CREATE TABLE IF NOT EXISTS` works today with no change to schema management.
3. *A child `download_candidates` table for the candidate list* — normalised, but adds a join and explicit ordering columns for a list that is written once and read whole.

**Final Choice:** Option 2, with candidates stored as JSON in a `TEXT` column (not option 3, and not `JSONB`).

**Rationale:** It isolates the churn (one write per poll per download) away from the table users query, and leaves the `Download` entity and `DownloadServiceClaimIT` untouched. `TEXT` over `JSONB` because the list is never queried by content, so `JSONB`'s operators and indexing would buy nothing — and storing it as JSON has the additional benefit that adding a field to `DownloadCandidate` later requires no migration at all. `song_name` is denormalised into the table so the hot due-work query needs no join.

---

## Decision: Task rows are retained forever, not deleted on completion (revised)

**Context:** The first version of this design deleted the `download_tasks` row when a download reached a terminal status, so the table only ever held live work.

**Options Considered:**
1. *Delete on completion* — the hot table stays small, so the due-work query stays fast. `downloads` retains the user-facing outcome, so nothing user-visible is lost.
2. *Retain in a terminal phase, with a partial index* — history is preserved; index only covers non-terminal rows so query cost is unchanged.
3. *Delete, but write an append-only attempt log alongside* — best of both, most work.

**Final Choice:** Option 2, revised from Option 1.

**Rationale:** Option 1's real justification was query speed, not disk — and a partial index gets that without deleting anything:

```sql
CREATE INDEX idx_download_tasks_due ON download_tasks (next_attempt_at)
    WHERE phase NOT IN ('SUCCEEDED', 'FAILED');
```

The index contains only live work, so the due-work query cost is independent of history size. The table can grow indefinitely and the loop does not care.

Against that, deletion has two costs that were underweighted:

- **Self-hosters need the history to debug.** "Which peers were tried and how did each fail?" is exactly the question a user filing a bug report needs to answer, and it is unanswerable once the row is gone.
- **It would have become a defect for collections.** A collection's per-song outcome lives in its song task rows. Deleting them means that after a 30-track album finishes, "which 3 tracks failed?" has no answer — and therefore no retry-the-failures affordance either. Retention resolves that before it is written.

Consequences: `phase` gains `SUCCEEDED` and `FAILED`; the table gains `finished_at` and `failure_reason`; the claim query gains `phase NOT IN ('SUCCEEDED','FAILED')`; and the terminal write marks the task terminal rather than deleting it (the write stays a single atomic statement, and the task half stays unconditional for the same anti-livelock reason). Retention (delete after N months) can be added later; disk is not a real constraint at this scale.

Note this invalidates `"a task row exists" == "this download is in flight"`. The queryable invariant becomes `"a non-terminal task row exists"`.

---

## Decision: Two separate capacity limits, and no per-collection cap

**Context:** The first version had a single `max-in-flight` counting task rows. Combined with retaining terminal rows and creating all of a collection's song rows up front, that number breaks twice: it counts finished work, and a 500-track collection puts it permanently over the limit so nothing else is ever admitted.

**Options Considered:**
1. *One limit on non-terminal task rows* — fixes the counting bug, but a large collection still starves every other request out of admission.
2. *Two limits: concurrent downloads (counting `downloads`) and concurrent transfers (counting download-phase tasks).*
3. *Option 2 plus a per-collection cap* — guarantees fairness between requests.

**Final Choice:** Option 2. Option 3's per-collection cap was considered and **deliberately rejected for now.**

**Rationale:** The two limits protect genuinely different resources and conflating them is what the deleted `flatMap(this::process, 3)` did wrong. Counting `downloads` rather than tasks means a 500-song collection is one in-flight download, so it cannot starve admission. Counting download-phase tasks caps real slskd transfers, which is what actually consumes bandwidth and peers' queue slots.

The transfer cap gates **only** the step that starts a transfer, never polling. Starving a poll does not deprioritise a download — it stalls one that slskd is happily finishing, because nothing is looking at it. Implementation excludes `DOWNLOAD_INIT` rows from the claim query when no slots are free, rather than claiming and deferring them, so a large collection cannot spend every pass being re-deferred.

The transfer count itself counts only `DOWNLOAD_POLL` rows (real, already-enqueued slskd transfers), not `DOWNLOAD_INIT` (which is "about to attempt an enqueue," not a real transfer yet). `DOWNLOAD_INIT` must be excluded from the count: counting it would make the cap count exactly the rows the claim query above refuses to claim once the cap is reached, so a batch of downloads landing in `DOWNLOAD_INIT` together could close the gate permanently — those rows can never be claimed, so they can never advance, so the count never drops. Counting only `DOWNLOAD_POLL` keeps the gate self-healing, since it can only decrease via a transfer reaching a terminal state, which always happens eventually.

Per-collection capping was rejected because both behaviours are legitimate — sometimes you want one album finished now, sometimes a bit of everything — so it belongs to the user as an option rather than being hardcoded. The default is oldest-first with no cap: if someone asked for something, they usually want it finished. This is also much safer than it sounds today, because there are no user accounts, so it is one person deciding their own priorities. It becomes a real fairness problem only when accounts land.

**Explicitly rejected:** anything adaptive that measures achieved throughput per peer and re-prioritises from it. Needs per-peer history, untunable without real usage data, and far more machinery than a self-hosted music downloader warrants.

---

## Decision: Rank candidates by availability before claimed speed; no duration estimate yet

**Context:** `selectBestFiles` sorts candidates purely on `SearchResponseItem.uploadSpeed` and ignores `hasFreeUploadsSlot` and `queueLength` entirely. A proposal was raised to schedule the whole queue by estimated duration (`size / uploadSpeed`), shortest first.

**Options Considered:**
1. *Change nothing.*
2. *Fix the comparator only* — free slot, then queue length, then speed.
3. *Fix the comparator and add duration-based queue scheduling* — an `estimated_seconds` column plus priority ordering on the claim query.

**Final Choice:** Option 2 now. Option 3's scheduling half is recorded as future work.

**Rationale:** `uploadSpeed` is the remote client's **self-reported historical average across all its uploads to anyone** — not a measurement of the route to you, and unverified. `hasFreeUploadsSlot` is a fact about the present. Today a peer claiming 10 MB/s with 40 people queued outranks a peer claiming 2 MB/s that can start immediately, and the first delivers nothing for an hour. Fixing that is a change to one comparator with no schema impact, and it rests on the more reliable of the two signals.

Duration-based scheduling was deferred for a reason worth recording precisely, because the obvious reason is wrong: the estimate would **not** need recomputing (both inputs come from the search response and neither changes afterwards; `queueLength` and `hasFreeUploadsSlot` do go stale but are not part of the estimate). The actual reason is that **the estimate only earns anything when more songs are waiting to start than there are free transfer slots** — rare with one user and 20 slots. So the trigger for revisiting is *queueing pressure*, not *a better estimate*.

Two guards it will need when it arrives: an aging term, because shortest-first starves large files indefinitely; and it must apply only to starting transfers, never to polling. The claim query already has that seam.

Because candidates are stored as JSON in a `TEXT` column, adding `uploadSpeed`, `queueLength` and `hasFreeUploadsSlot` to `DownloadCandidate` later needs no migration — so nothing is carried speculatively now.

The comparator change is sequenced **last** in the implementation plan, after the pipeline work, so that "the existing `selectBestFiles` tests pass unmodified" remains a valid guard that the pipeline work did not touch ranking.

---

## Decision: Introduce Flyway now (revised)

**Context:** Earlier guidance in `AGENTS.md` stated that `schema.sql` cannot express column-level migrations, implying this work required Flyway. That claim was wrong — Postgres has `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` — and the first version of this record therefore deferred Flyway.

**Options Considered:**
1. *Keep `schema.sql`; put new state in a new table* — needs nothing beyond `CREATE TABLE IF NOT EXISTS`, which is genuinely sufficient for this change.
2. *Add Flyway now* — versioned, ordered, recorded migrations; costs a blocking JDBC driver used only at boot.
3. *Add Flyway at the first change that needs it* — minimal work now, but the baseline has to be established later, on installs that already hold real data.

**Final Choice:** Option 2, revised from Option 1 after discussion.

**Rationale:** Option 1 is technically adequate for *this* change and was the original conclusion. It was reversed on a product argument that outweighs it: naviseerr is continuously updated software installed by other people. A user on v1.2 pulls v1.5 with a year of history in their database. There is no realistic future in which this project never needs to evolve an existing table, and `PARTIAL_SUCCESS` (collections) plus `CANCELLED` both require altering the `downloads.status` `CHECK` constraint, which is not idempotent.

Option 3 is worse than Option 2 for a non-obvious reason: adopting Flyway means baselining — telling it to treat existing tables as version 1 via `baseline-on-migrate`. Doing that while the schema is three tables is materially easier to reason about than doing it later across a population of installs at varying versions. Cost of moving early is near zero; cost of moving late is real.

`AGENTS.md`'s schema section has been rewritten accordingly.

---

## Decision: Leases with an expiry, not a stale-row reaper

**Context:** Something has to notice that a process died holding a row. PR #6 proposed a reaper resetting `IN_PROGRESS` rows older than a fixed age.

**Options Considered:**
1. *Time-since-`updated_at` reaper* — simple, but there is no good timeout: too short duplicates work on a slow-but-healthy download, too long makes recovery crawl.
2. *`lease_owner` + `lease_expires_at`, stamped on claim and cleared on write* — precise, and the due-work query skips leased rows.

**Final Choice:** Option 2. No reaper is built.

**Rationale:** One mechanism does two jobs. It stops a second pass double-stepping a row whose slskd call is still outstanding — which happens routinely, since slskd's timeout is 10s and the loop interval is 2s — and it detects a dead process. Lease expiry is precise where an age heuristic is a guess, and it is multi-instance safe for free.

---

## Decision: Terminal write is one atomic statement, with an unconditional task-terminal write

**Context:** Finishing a download touches two tables: set `downloads.status`, mark the `download_tasks` row terminal (originally: delete it — superseded by the retention decision above, which keeps the row and adds `finished_at`/`failure_reason`). Split by a crash, the bad direction (task marked terminal, download still non-terminal, or vice versa) reopens the stranded-row bug this whole design exists to close.

**Options Considered:**
1. *Two statements, `UPDATE` then `UPDATE`, no transaction* — self-heals in one direction only, and leaves the ambiguity of a zero-row first `UPDATE` unaddressed.
2. *Explicit reactive transaction* — correct; introduces transaction management to a codebase that has none.
3. *One data-modifying CTE* — atomic by construction, stays a single SQL string in the existing `DatabaseClient` style.

**Final Choice:** Option 3, with the task-terminal `UPDATE` **not** conditional on the `downloads` `UPDATE` matching, and that first predicate `status NOT IN ('SUCCEEDED','FAILED')` rather than `= 'IN_PROGRESS'`.

**Rationale:** Postgres runs a data-modifying CTE exactly once even when nothing references it, so both halves always execute inside one statement's transaction. Making the task write conditional introduces a **livelock**: when the status is already terminal (reachable whenever an expired lease causes a duplicated step) the `downloads` `UPDATE` matches nothing, so a conditional task write leaves the task non-terminal, the next pass re-steps it, re-reaches `Terminal`, and changes nothing — one slskd call per interval, forever. The widened predicate removes the ambiguity in a zero-row result, reducing the row count to a log line rather than a branch. This ordering also gives correct cancellation semantics for free later: a cancelled download's terminal step finds the status already set, skips the `downloads` update, and still marks the task terminal so work stops.

---

## Decision: Admit non-terminal downloads with no task row, not just `PENDING` ones

**Context:** The loop's admission step has to decide which `downloads` rows need a task created.

**Options Considered:**
1. *Only `PENDING`* — mirrors the existing `claimPendingDownloads`.
2. *`PENDING` or `IN_PROGRESS`, where no task row exists* — same query, one widened predicate.

**Final Choice:** Option 2.

**Rationale:** It converts *"every non-terminal download has a task row"* from an invariant the code hopes for into one the loop continuously restores. Any route into the bad state — an atomicity bug, a bad migration, hand-edited rows — recovers automatically on the next pass, at zero extra cost. Recovery restarts that download from `SEARCH_INIT`, losing its position, which is the right trade for a should-not-happen state.

---

## Decision: Serialise loop passes; step the rows claimed within one pass concurrently (revised)

**Context:** A pass makes at least one due-work query and, since the batching decision below, up to two slskd calls; each claimed row then has its own phase-specific step. There are two independent concurrency questions here, and the first version of this design accidentally answered them the same way, which was a bug: the pass loop used `concatMap(tick -> pass())`, correctly, and the *row-stepping* inside a pass also used `concatMap`, which meant a batch of `batch-size` claimed rows were stepped strictly one at a time. With `batch-size: 10` and slskd's 10s HTTP timeout, a single pass could take up to 100 seconds — reproducing, inside one pass, the exact head-of-line blocking this whole redesign exists to remove from the old `flatMap(concurrency)` worker.

**Options Considered, pass level:**
1. *`concatMap` — one pass at a time* — a slow pass delays the next, so the effective cadence degrades to the slowest call.
2. *`flatMap` — overlapping passes* — better throughput, more concurrency to reason about.

**Options Considered, row level (within one pass):**
1. *`concatMap` — one claimed row at a time* — what shipped in the first draft; serialises independent downloads inside a single pass for no reason, since each row's step is independent I/O.
2. *`flatMap(batchSize)` — claimed rows stepped concurrently* — matches the actual dependency graph: nothing about stepping row A requires row B to finish first.

**Final Choice:** Pass level: option 1, unchanged. Row level: option 2, correcting the original mistake.

**Rationale:** Pass-level serialisation is still the right call for simplicity — leases already make overlapping passes safe, so that upgrade path stays open with no other change, and there is no need to pay for it now. Row-level serialisation was never a deliberate simplification; it was an oversight that silently reintroduced this design's core problem at a smaller scale. `flatMap(batchSize)` needs no thread pool — WebFlux already runs an event loop per core — so the fix is purely a matter of using the right Reactor operator for independent work.

---

## Decision: Accept an occasional duplicate download after a crash, rather than guard against it (revised)

**Context:** Between `POST /transfers/downloads/{user}` returning and the transfer id being persisted, a crash leaves slskd downloading a file naviseerr has no record of. Re-running that step would ask the same peer for the same file again. Every other step is safe to re-run: a duplicate `POST /searches` is wasteful but harmless, and both `GET`s are free — so exactly one step needs protection, if it is protected at all.

**Options Considered:**
1. *Do nothing* — routine crashes cause duplicate transfers racing to the same output path.
2. *Intent before effect, then abandon the attempt* — persist `enqueue_attempted = true` and wait for the commit before calling slskd; on re-entry with the flag already set, fall through to retry / next-candidate without calling slskd. This shipped first.
3. *Intent before effect, then adopt the orphan* — ask slskd for that peer's existing transfers and resume polling the one that matches.

**Final Choice:** Option 1 — reversed from option 2, which was the original choice.

**Rationale:** The crash window this guards is single-digit milliseconds (between an HTTP response landing and one `UPDATE` committing), so the failure it prevents is rare, and its cost when it does happen is one extra downloaded file, once, per crash — acceptable for a self-hosted music downloader. Option 2's cost was disproportionate to that: a dedicated column, an extra DB write before *every* enqueue (not just the ones that crash), a dedicated state-machine branch (`onEnqueueAbandoned`), and its own test coverage — all paid on every enqueue to prevent a failure mode whose worst outcome is one duplicate file. Option 3 remains strictly better than either and is still the right follow-up, because it is also what would make **slskd-side** restarts recoverable rather than merely survivable — but it needs `GET /transfers/downloads/{username}`'s response shape confirmed against a live instance first, and is not worth blocking this change on. Removing option 2's machinery also shrinks `DownloadTask` by one field and removes an entire method and test from `DownloadStateMachine`.

---

## Decision: Batch the two poll phases against slskd; accept an unresolved risk on one of them

**Context:** Polling every in-flight download's search or transfer individually costs one slskd call per download per poll — 20 downloads at a 2–5s cadence is a meaningful, unbounded-with-scale request rate. slskd's own API turns out to expose both lists in full: `GET /searches` (confirmed to include each search's completion state) and `GET /transfers/downloads` (confirmed to exist, but — unlike `GET /searches` — with **no pagination, date filter, or state filter**; only `includeRemoved`).

**Options Considered:**
1. *Keep per-download polling for both phases* — no risk, but the call count is O(in-flight downloads) forever.
2. *Batch both phases: one `GET /searches` and one `GET /transfers/downloads` per pass, fetched only when a claimed row needs one, looked up by id per row* — O(1) calls per pass regardless of concurrency, at the cost of one unresolved question for the transfers endpoint.
3. *Batch only `GET /searches`* — the download side keeps the O(in-flight) cost, sidestepping the open question, at the cost of forgoing a real win now.

**Final Choice:** Option 2, both phases batched, with the risk in `GET /transfers/downloads` explicitly named and deferred to live verification (Task 8) rather than resolved by design.

**Rationale:** `GET /searches` has no discovered downside, so batching it is an unconditional win — and it is what makes pruning completed searches worthwhile, since the live set then stays small regardless of how much history accumulates (`DELETE /searches/{id}` is a distinct verb from cancelling one, and a search holds no partial-download state to lose, so pruning it — unlike pruning a transfer — is safe). `GET /transfers/downloads`'s lack of any filter means its response size on an install with years of history is genuinely unknown from here; rather than block on that, the plan pencils in the batched version as the architecturally simpler default and makes the fallback (per-transfer polling via the existing `getDownloadProgress`, parallelised instead of read from a shared map) a same-shape swap: it changes one branch inside `DownloadStepExecutor` and one call site in `DownloadTaskRunner`, and nothing about the state machine, the schema, or any other test. Cheap to guess wrong about, so guessing (with a named, dated verification step) is the right call rather than stalling on an API measurement no one has taken yet. A missing entry in either batched map (present in the claim, absent from the fetch) is deliberately treated as "still running" rather than a distinct failure mode, because there is no reliable way to distinguish "not there yet" from "slskd forgot it" — both fall through to the existing phase-budget timeout.

---

## Decision: naviseerr keeps candidate-level retry; does not hand any of it to slskd

**Context:** slskd 0.26.0 added its own per-file retry with real exponential backoff and partial-file resume (`transfers.download.retry.*`, in slskd's own config). This overlaps with naviseerr's per-candidate retry tier (`slskd-service.retry-count`), and slskd's version is technically better on two counts: real backoff, and resuming a partial download where naviseerr's re-enqueues from zero.

**Options Considered:**
1. *Hand same-peer retry to slskd entirely* — technically stronger per retry (backoff, resume), but splits one behavioural concern across two applications most users run as separate services, and slskd has no visibility into the ranked candidate list, so it could only ever take on one of naviseerr's two retry tiers (same-peer, never next-candidate).
2. *Keep both tiers in naviseerr, pin slskd's retry off* — naviseerr's own tier is weaker (no backoff, no resume) but the whole retry story lives in one place the project can describe and guarantee.

**Final Choice:** Option 2.

**Rationale:** This is a decision about ownership, not about which implementation is technically superior — splitting the tier means naviseerr can no longer fully explain its own retry behaviour to a user or a bug reporter, and the payoff for the split is capped by slskd's blindness to candidate ranking regardless. There is a genuine, unresolved compounding risk left by *not* pinning slskd's setting: if slskd marks a transfer `Errored` and only then begins its own retry, naviseerr's poll sees the failure and advances to the next candidate while slskd is still retrying the old one — two transfers of the same file. The fix is operational, not code: document that operators must set slskd's own `transfers.download.retry.attempts` to `0` (or `1`), and verify the actual interaction against one real observed failure before relying on it (Task 8).

---

## Decision: Cancellation cascades atomically in Postgres; cancelling the live slskd transfers is best-effort and separate

**Context:** Cancelling a download (and, for a collection, all its songs) needs two things: marking the relevant rows cancelled, and stopping any transfer currently running inside slskd for them. The first is entirely within Postgres and can be atomic. The second is an external call and cannot be made atomic with the first — there is no operation that spans a Postgres transaction and an slskd HTTP call.

**Options Considered:**
1. *Cascade the DB write and the slskd cancel calls, treat a crash between them as needing a compensating mechanism* — closes the gap completely, but adds machinery (a mechanism to find and retry failed upstream cancels) for a failure mode this product does not need to close immediately.
2. *Cascade the DB write atomically, fire slskd cancels best-effort, accept that a crash in between leaves a transfer running* — the DB state is always correct; the slskd side is eventually correct only if nothing crashes at exactly the wrong moment.

**Final Choice:** Option 2.

**Rationale:** The DB half is one atomic statement: cancel the download, its collection task if any, and every non-terminal song task beneath it, using `RETURNING` to hand back the `(slskd_username, slskd_transfer_id)` pairs that were just cancelled — those pairs are what get best-effort cancel calls fired at slskd afterward (`DELETE /transfers/downloads/{username}/{id}`, itself a distinct cancel verb, not a destructive removal). A crash between the two costs, at worst, a few extra songs still downloading for a request the user already cancelled — explicitly judged not worth engineering against for a self-hosted music downloader, consistent with the enqueue-duplicate decision above. The escape hatch, if that judgement changes: because task rows are retained, a level-triggered sweep for "cancelled tasks that still hold a `slskd_transfer_id`" would close the gap completely and needs no schema change to add later — noted, not built.

---

## Decision: Per-phase poll intervals and real duration budgets

**Context:** `ReactivePoller.defaultBackoff` uses `Retry.backoff(30, 50ms)` with no maximum backoff, so the gap between polls doubles indefinitely: ~51s by attempt 11, ~14 minutes by attempt 15, ~7 hours by attempt 20, and the 30-attempt budget spans roughly two years.

**Options Considered:**
1. *Keep exponential backoff per phase* — familiar, but a long transfer ends up checked every 14 minutes and `max-poll-attempts` is not a timeout in any useful sense.
2. *Fixed interval per phase, plus a separate duration budget* — searches poll every 2s, transfers every 5s; timeouts come from `phase_entered_at` plus a duration.

**Final Choice:** Option 2. `slskd-service.max-poll-attempts` and `first-back-off-duration-ms` are removed.

**Rationale:** Searches complete in seconds and transfers run for minutes to hours, so one cadence for both is waste in one direction and uselessness in the other. A duration budget is something an operator can reason about; an attempt count under a doubling backoff is not. Crucially, `phase_entered_at` must reset on a phase change and be preserved on a re-poll — inverting that makes every timeout unreachable.

---

## Decision: Prerequisite bug fixes carried by this work

**Context:** Two existing bugs make the new failure handling unreachable.

**Final Choice:** Fix both, in their own commit, before the cutover.

**Rationale:**
- `TransferedFileUtil.getStateList` matches slskd's state strings against the enum's `name()`, so `"TimedOut"` never matches `TIMED_OUT` and `"InProgress"` never matches `IN_PROGRESS`; single-word states match by luck. A timed-out transfer is therefore never classified as a failure, making the retry-then-next-candidate tier unreachable for the most common Soulseek failure. Must match on `getValue()`.
- `SlskdSearchResultProcessor` hardcoded `Predicate<SearchState> failed = s -> false`, so a search could never fail. Replaced by `SlskdSearchState`, designed to fail safe: an unrecognised state string falls through to the "completed with no usable candidates" failure path rather than misbehaving, so correctness never depends on that enum being complete. Note the asymmetry — `TimedOut` is a *failure* for a transfer but *normal completion* for a search, since slskd finishes a search by running out its timeout.

---

## Decision: Collections — agreed shape, deferred implementation

**Context:** Collections (albums, playlists) are the next milestone. The concern was whether they would force a large refactor of the design above. They do not; this records the agreed shape so it is not redesigned later.

**Final Choice:**
- `downloads` gains `is_collection BOOLEAN`. **One `downloads` row per user request** — a 500-track playlist is one row, because the user made one request. A boolean is sufficient: the pipeline only needs to know "does this expand into children". Album-vs-playlist is display metadata and belongs with the user-facing metadata, not in the behaviour switch. A `download_type` enum was proposed and rejected on that basis.
- A separate `download_collection_tasks` table for the collection's own working state — not mixed into `download_tasks`, whose phases and typed slskd columns are meaningless for a collection.
- Each song is a `download_tasks` row referencing the collection task. Per-song history survives because task rows are now retained.
- The collection task and every song task are created in **one transaction**. All-or-nothing, so there is no half-created state to detect or recover.
- **The collection counts its own songs; songs never notify the collection.** A song telling its parent "I'm done" is a message that can be lost — crash right after the song is marked terminal and nothing re-sends it, so the collection waits forever. That is the edge-triggered failure this entire design exists to eliminate, reintroduced one level up. Instead the collection is an ordinary row in the same loop: when due, count unfinished songs, then finalise or reschedule.
- **Never store a succeeded/failed counter.** Counting rows is naturally idempotent; `n = n + 1` is not, and a step can run twice whenever a lease expires while work is still alive. Postgres makes the increment *atomic* but nothing makes it *idempotent* — different problems, and only the second matters here. Index `(collection_task_id, phase)` and count.
- Requires `PARTIAL_SUCCESS` on the `downloads.status` CHECK, hence the Flyway baseline.

**Open, not decided:** whether the client sends the tracklist or the server looks it up. If the client sends it — it has just displayed the album, so it knows — the whole thing is one transaction at request time and there is no expansion step at all. If the server must call a provider, a network call precedes the inserts and reintroduces a partially-created-list failure mode. This determines whether an expansion step exists and should be settled before writing collection code.

**Rationale:** The two-table split (`downloads` as the landmark record the user reads, `download_tasks` as the transient functional payload) is what makes collections cheap. Concretely, `DownloadStateMachine`, `DownloadStepExecutor`, `DownloadTask`, `DownloadDecision` and the `download_tasks` schema are all unchanged; `downloads` gains one column, the admit query gains one branch, and the new code is a collection step plus an aggregate query.

---

## Non-goals recorded deliberately

Collection/playlist downloads (shape agreed above, implementation deferred), SSE progress streaming, an append-only per-attempt log, duration-based queue scheduling, and adopting orphaned slskd transfers. Cancellation is no longer a pure non-goal — its shape is agreed above — but its implementation is not part of this plan either. The most likely immediate follow-ups are collections and cancellation.

Progress reporting is likewise out of scope here, but its shape is now agreed separately in `docs/decisions/download-progress-reporting-17-08-2026.md`. Two things there bear on the work above: the progress write must ride inside the existing `Continue` `UPDATE` and inherit its guard rather than becoming a statement of its own, and the `download_tasks.download_id PRIMARY KEY` declared in the plan contradicts the one-task-row-per-song shape recorded for collections — deliberately left for the collections migration, not this one.

`selectBestFiles` and everything under it stays byte-for-byte unchanged **through the pipeline work**, including the known case-sensitive `"flac"` check — its existing tests passing unmodified is the guard that the pipeline work did not touch ranking. The comparator change is sequenced after that guard has served its purpose.

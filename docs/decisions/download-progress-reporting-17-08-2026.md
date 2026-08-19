# Decisions: download-progress-reporting

- **Date:** 17-08-2026
- **Topic:** download-progress-reporting
- **Depends on:** `docs/decisions/durable-download-state-machine-13-08-2026.md`
- **Status:** shape agreed, implementation deferred. Two items open pending a UX test (below).

Progress reporting is a follow-up to the durable state machine, not part of it. This records the
agreed shape so it is not redesigned later, and records the two questions deliberately left open.

---

## Decision: progress lives on the task row, written by the poll that already writes it

**Context:** A progress bar needs a number per download. The previously documented target
architecture reached for Redis for this.

**Options Considered:**
1. *A `progress_percent` column on `download_tasks`, written by the existing per-poll update* — no new
   infrastructure, survives restart, queryable.
2. *Redis cache in front of the DB* — the conventional answer; second failure domain, non-durable,
   needs invalidation.
3. *Derive it on read from slskd* — no storage at all, but couples every UI read to a slskd call and
   returns nothing for a transfer slskd has retired.

**Final Choice:** Option 1. `progress_percent NUMERIC(5,2)`, **0-100**, matching slskd's own units so
no conversion exists anywhere in the path.

**Rationale:** The write is genuinely free. The `Continue` decision already updates the row on every
poll (`next_attempt_at`, lease columns), and the partial due-work index is on `next_attempt_at`, so
that index churn already exists. Adding a column to that same `UPDATE` adds zero statements and zero
index maintenance. This is what keeps the design inside the `AGENTS.md` guideline forbidding
byte-level progress writes: that rule is scoped to "hundreds of writes per download" and explicitly
permits roughly one row-write per poll, which is what this is. **The moment progress becomes its own
`UPDATE`, the design violates that guideline** — see the guard decision below.

Redis is unnecessary for a reason worth stating: it earns its place when progress changes faster than
storage can absorb. This value changes once per poll interval (2s, configured), so Redis would cache a
number that only moves every two seconds, non-durably, in a second failure domain.

The value is read from `TransferedFile.percentComplete` and overwritten, never accumulated. That makes
it idempotent, so a poll re-run after a lease expiry writes the same value — the same property that
drives the "count rows, never `n = n + 1`" rule for collections.

---

## Decision: download progress only. There is no search progress.

**Context:** An earlier sketch had separate search-progress and download-progress, rendered as two
colours.

**Final Choice:** No search progress. `SEARCH_INIT` / `SEARCH_POLL` render as indeterminate; only
`DOWNLOAD_POLL` has a percentage.

**Rationale:** `SearchState` exposes `fileCount`, `responseCount`, `lockedFileCount`, `isComplete` —
**no total to divide by.** A Soulseek search ends on slskd's own response and timeout thresholds, not
by counting down a known quantity, so any percentage here is invented and will freeze at an arbitrary
point looking broken. The honest signal already exists on the row for free: `phase` distinguishes
searching from downloading, and `phase_entered_at` gives elapsed time, which is what indeterminate
progress is supposed to look like.

---

## Decision: reset progress on retry and on candidate failover

**Final Choice:** Progress is monotonic within one candidate attempt. It resets to 0 at a retry or a
failover to the next candidate. The bar moving backwards there is correct — the bytes are gone.

**Open, unverified:** whether slskd's `percentComplete` accounts for `startOffset` on a partial resume
(slskd 0.26.0 has `transfers.download.retry.partial`). If a resumed transfer reports 0% while the file
is 60% on disk, this reset is exactly right; if it reports 60%, the reset is a harmless one-poll
flicker. **Either way, never hand-compute `bytesTransferred / size` — `startOffset` makes that wrong.**
Use `percentComplete`. Fold the observation into the Task 8 verification.

---

## Decision: never write progress from a null or absent source value

**Context:** `TransferedFile.percentComplete` is a boxed `Float`, and that is deliberate — the class
javadoc records that slskd omits numeric fields depending on transfer state, and that "an absent
primitive is a decoding error rather than a defaulted zero." Separately, the batched poll treats a
transfer *missing from the response map* as "still running", which yields no value at all.

**Final Choice:** Only write `progress_percent` when the source value is non-null. A null read, or a
transfer absent from the batch map, leaves the stored value unchanged. The column itself may be
`NOT NULL DEFAULT 0`; the guard is on the write, not the column.

**Rejected:** making the wire field non-nullable. It is slskd's JSON — declaring `float` produces a
silent 0% instead of a detectable absence, which is strictly worse.

**Rationale:** Without the guard, one null poll mid-transfer writes 87% down to 0 and the next poll
restores it. A progress bar that jumps backwards on a *healthy* download is the single most
trust-destroying failure mode this feature has.

---

## Decision: progress is only written to non-terminal rows, in one statement

**Context:** The terminal write is a guarded CTE (`status NOT IN ('SUCCEEDED','FAILED')`) and
cancellation is a separate atomic statement. A poll can be in flight when either commits.

**Final Choice:** The progress write rides inside the existing `Continue` `UPDATE` and inherits its
guard: `WHERE lease_owner = :me AND phase NOT IN ('SUCCEEDED', 'FAILED')`. Never a standalone
statement, and never a read-then-write.

**Rationale:** A standalone progress `UPDATE` that also carries `next_attempt_at` would put a
cancelled or completed row **back into the partial due-work index**, and the loop would resume
cancelled work. A read-then-write has the same race it is meant to close. Riding inside the existing
update also preserves the zero-extra-write property the first decision depends on.

**Acceptance test:** cancel a download while a `DOWNLOAD_POLL` is in flight; assert the row stays
terminal and does not reappear in the due-work query.

---

## Decision: a collection's percentage counts completed songs; it never averages their progress

**Context:** For a collection, the number could be `completed / total` (count) or
`(completed + SUM(partial progress of in-flight songs)) / total` (sum).

**Final Choice:** Count. `count(*) FILTER (WHERE phase = 'SUCCEEDED') / count(*)` over the collection's
song rows. No stored counter, no averaging.

**Rationale:** Counting is idempotent; a stored counter is not, and a step can run twice whenever a
lease expires while work is still alive. That much was already decided for collections generally.

The sum variant was considered and rejected on monotonicity: `completed` never decreases, but the
partial component resets on retry, so the total can move backwards while the user watches. Worth
recording the actual magnitude, because it is smaller than it feels: the non-zero partial component is
bounded by the songs *actively transferring* (a failed-over song resets to 0 and then holds 0 while it
waits for a transfer slot), so the worst-case backwards jump is
**`max-concurrent-transfers / total-songs`** — about 2% on a 500-song playlist at a concurrency of 10.
So the sum variant is *safest* in exactly the big-playlist case that motivated rejecting it.

It is worst at small N, where a 2-song collection can swing the full width of the bar. But count-only
is also worst at small N — a 3-track EP reads `0%` for ten minutes. **So the real conclusion is that
neither single number works for a small collection, and the fix is not an aggregate:** the collection
shows `3 of 12` (monotonic, never lies) and the songs currently transferring show their own byte
percentages beneath it. That costs nothing, because per-song progress is already the feature.

---

## Decision: a failed song counts as incomplete; done-ness comes from `downloads.status`

**Final Choice:** A `FAILED` song never counts toward a collection's numerator. Consequently a
collection can finish at `11 of 12` and never reach 100%, and that is fine: **`downloads.status` says
whether the request is over, `progress` says how far it got.** `PARTIAL_SUCCESS` — already required on
the status CHECK for collections — is the terminal state that stops the UI showing it as active.

**Rationale:** Two fields that already exist, rather than a tri-state bar. It also answers the
green-and-red-segment question without a segmented renderer: the display is `11 of 12 - partial
success`. If a segmented bar is wanted later it is one query and no schema change:

```sql
SELECT count(*) FILTER (WHERE phase = 'SUCCEEDED') AS done,
       count(*) FILTER (WHERE phase = 'FAILED')    AS failed,
       count(*)                                     AS total
FROM download_tasks WHERE download_id = $1;
```

---

## Decision: the client polls one endpoint. No SSE, no push.

**Final Choice:** One read endpoint; the client polls it at or above the server's poll interval.

**Rationale:** The stored value can never be fresher than the last poll, so any transport faster than
the poll interval is wasted. That collapses the choice rather than opening it. SSE progress streaming
is already a recorded non-goal, and the existing guideline to batch updates to ~5% points the same way.

**One risk to handle in the client:** if it polls faster than the server, it sees an unchanging number
and looks frozen. Expose `phase_entered_at` so it can render "checking..." rather than a stalled
figure.

---

## Blocked on: `download_tasks.download_id` must stop being the primary key

**Context:** The intended cardinality is one `downloads` row per user request, `0..1`
`download_collection_tasks` per download, and **one `download_tasks` row per song** — so a 500-song
playlist is 1 + 1 + 500 rows. The `download_tasks` DDL in the implementation plan declares
`download_id UUID PRIMARY KEY`, which permits exactly one task row per download. The two cannot both
hold, and every query in this document that counts a collection's songs depends on the N:1 shape.

This is not caused by progress reporting; it is a stale artifact from before collections were designed,
surfaced by tracing the collection count query.

**Final Choice:** Fix it in the **collections migration, not the state-machine one.** The plan already
commits to a later migration for collections (`PARTIAL_SUCCESS`, `is_collection`,
`download_collection_tasks`), and this belongs in it. Nothing FK-references
`download_tasks.download_id` today, so deferring is safe, and shipping an unused surrogate key earlier
would make every save and claim carry an identity no code needs yet.

Not a one-line change when it comes, which is why it is recorded rather than assumed: `download_id` is
the row identity in the admit query's `ON CONFLICT` target, the claim query's `WHERE ... IN`, the save
statement's `WHERE`, and the `DownloadTask` record itself.

**Recorded now so it is not rediscovered:** make `download_collection_tasks.download_id` its own
primary key. That enforces the 1:1 between a download and its collection task for free, and it means
the collection task is already keyed by download — so `download_tasks` may need no separate
`collection_task_id` column at all, since songs carry `download_id` and `downloads.is_collection` says
whether to expand. Settle that when collections are written.

---

## Open, to settle after a UX test

1. **Count vs sum for collections.** Count is chosen. Revisit after watching a real multi-song
   download, specifically a small collection reading `0 of 3` with no per-song rows visible. The
   quantified bound above is the evidence to weigh it against.
2. **What a terminal row reports.** `SUCCEEDED` sets 100%, agreed. `FAILED` is **not settled.** Setting
   it to 100% is a latent trap: if the count-vs-sum decision is ever revisited, a failed song reporting
   100 counts as complete in a sum and inflates a partly-failed collection to 100%. Preferred
   alternative is that `FAILED` leaves the last-known value and the UI draws no bar for a failed row,
   which keeps the column meaning exactly one thing forever.
3. **`percentComplete` and `startOffset` on a partial resume** — see the reset decision above. Observe
   during Task 8.

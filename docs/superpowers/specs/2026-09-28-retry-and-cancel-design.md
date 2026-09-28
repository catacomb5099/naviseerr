# Retry and cancel downloads — Design

> Status: proposed 2026-09-28, awaiting owner review. Covers both repos: the server contract lives
> here; the client changes are listed in the same document so the two cannot drift.
> Builds on `docs/decisions/durable-download-state-machine-13-08-2026.md` (the cancellation shape
> agreed there: an atomic Postgres cascade, then best-effort slskd cancels) and
> `docs/decisions/collection-downloads-14-09-2026.md` (a download's status is derived from its songs).

## What the owner asked for

1. A **Retry** button on a failed download. The server retries it.
2. Retry as many times as you like, **but never while a retry is already running**.
3. **Idempotent**: a double-click sends two requests; the download is retried once and the second
   request is refused.
4. **Cancel**, on the server and in the UI, for **one song** or for a **whole collection** (album,
   playlist, suggested playlist).

## Decisions in one screen (the parts a reviewer may want to change)

| # | Decision | Why | If you disagree |
|---|---|---|---|
| 1 | A cancelled song is stored as **`FAILED` with the reason `CANCELLED`**. No new status, no migration. The card counts cancelled songs separately and shows "Cancelled" in grey, never red. | Zero schema change and zero risk of a cancelled song being picked up by the loop (every "is this song finished?" test in the code already treats `FAILED` as finished). A real `CANCELLED` status needs a migration, two index rebuilds and thirteen code edits for the same words on screen. | Upgrade path is recorded: one migration plus `UPDATE download_tasks SET phase = 'CANCELLED' WHERE failure_reason = 'CANCELLED'`. Nothing is lost by starting here. |
| 2 | **Retry works on a finished download** (failed, or partly downloaded) and retries **every song that has no file**, including cancelled ones. Songs that succeeded are left alone. | "Retry" means "get me the ones I did not get". A per-song retry was not asked for and would double the endpoints and buttons. | Per-song retry is a small follow-up: the same statement with a `task_id` filter. |
| 3 | **Retrying a cancelled download is how you un-cancel it.** | Falls out of decision 1 for free. | — |
| 4 | Retry **resets the song's row in place** (back to "start searching"). The previous attempt's peers and error are not kept on the row; they are already in the log. | A second row per song would break every count the cards read ("7 of 12"). | An append-only attempt log (`download_events`) is the recorded upgrade, as before. |
| 5 | **Cancel one song inside a live collection is allowed**; the other songs carry on. | Asked for. | — |
| 6 | The panel's auto-dismiss of finished cards is unchanged. The Downloads page is where you retry something later. | Not asked for; the page already lists everything. | Pause-on-hover is a separate client change. |

## How the system works today (the parts this design touches)

One `downloads` row per request; one `download_tasks` row per song. A loop runs every two seconds:
admit new requests (fetch the track list, create one song row each), claim due song rows under a
lease, step each one (one slskd call), and finally **derive** each download's status once all of its
songs are finished (`concludeDownloads`). Two guards make the loop safe against its own slow steps:
the write that moves a song forward only lands if the row is still unfinished **and still held by
the writer's lease** (`SAVE_SQL`), and the write that finishes a song only lands if the row is still
unfinished (`FINISH_TASK_SQL`). The client polls one feed and folds each row into a card; a rule in
`mergeCard` says a finished card never goes back to a live stage.

Two of those facts have to change for this feature, and both changes are bug fixes in their own
right:

- `FINISH_TASK_SQL` does not check the lease. Today that is harmless. With cancel and retry it is
  not: cancel a song whose slskd call is mid-flight, retry two seconds later, and the old call's
  "finished" lands on the fresh attempt. The fix is the one line `SAVE_SQL` already has.
- Admission creates the song rows first and marks the download started second. Cancel a queued
  download in the gap and it ends up finished with live songs the loop will happily search for. The
  fix is to swap the two halves so the status flip locks the row first.

## Server

### Vocabulary

- `DownloadFailureCode` gains `CANCELLED`. Persisted by name in `failure_reason` like every other
  code; the client owns the wording.
- `ActiveDownloadView` gains `int songsCancelled`. `songsFailed` no longer counts cancelled songs.
  `failureCode` on a collection prefers a real failure over a cancellation, so "3 failed, 8
  cancelled" never reads as just "Cancelled".

In `ActiveDownloadRepository.TASK_AGGREGATE` (three lines change, one is added):

```sql
COALESCE(MIN(t.failure_reason) FILTER (WHERE t.failure_reason <> 'CANCELLED'),
         MIN(t.failure_reason))                                        AS failure_reason,
COUNT(*) FILTER (WHERE t.phase = 'FAILED'
                   AND t.failure_reason IS DISTINCT FROM 'CANCELLED')  AS songs_failed,
COUNT(*) FILTER (WHERE t.phase = 'FAILED'
                   AND t.failure_reason = 'CANCELLED')                 AS songs_cancelled
```

`PROJECTION` adds `COALESCE(t.songs_cancelled, 0) AS songs_cancelled`. `DownloadStage` is
unchanged: an all-cancelled download is `FAILED` on the wire with `failureCode = CANCELLED`; a
collection with some files is `PARTIAL_SUCCESS` as today.

### Prerequisite 1: a song's finish only counts if the step still holds its lease

```sql
-- DownloadService.FINISH_TASK_SQL, one added predicate
 WHERE task_id = :id
   AND phase NOT IN ('SUCCEEDED', 'FAILED')
   AND lease_owner = :owner
```

`finishTask(taskId, status, code, now, owner)`; the runner passes its `instanceId`. Retry and cancel
both clear the lease, so a stale step's finish finds `lease_owner NULL` and writes nothing. No work is
lost: within one process, passes are serialised, so a slow step always lands (and is refused) before
the same process can re-claim the row; another process has a different owner. Cost: the integration
tests finish never-leased rows in about thirty places; they go through one helper that stamps a
lease first.

### Prerequisite 2: admission marks the download started before adding its songs

```sql
-- DownloadTaskRepository.CREATE_TASKS_SQL, halves swapped
WITH admitted AS (
    UPDATE downloads
       SET status = 'IN_PROGRESS', admitted_at = :now
     WHERE download_id = :downloadId
       AND status = 'PENDING'
       AND NOT EXISTS (SELECT 1 FROM download_tasks t WHERE t.download_id = :downloadId)
    RETURNING download_id
)
INSERT INTO download_tasks
    (task_id, download_id, youtube_id, song_name, position, phase, phase_entered_at, next_attempt_at)
SELECT gen_random_uuid(), :downloadId, s.youtube_id, s.song_name, s.position, 'SEARCH_INIT', :now, :now
  FROM unnest(:youtubeIds::text[], :songNames::text[]) WITH ORDINALITY AS s(youtube_id, song_name, position)
 WHERE EXISTS (SELECT 1 FROM admitted)
```

Under READ COMMITTED an `UPDATE` that waits on a row another statement is changing re-checks its
`WHERE` on the committed version, so a cancel that lands first makes `status = 'PENDING'` false and
nothing is inserted. The statement now returns the number of song rows created rather than `1`;
the runner only tests `> 0`, and one integration test assertion changes from `1` to `3`.

### Cancel

```sql
-- DownloadTaskRepository.CANCEL_SQL: FINISH_TASK_SQL's SET and guard, over a download or one song
UPDATE download_tasks
   SET phase = 'FAILED', failure_reason = 'CANCELLED', phase_entered_at = :now,
       finished_at = :now, updated_at = now(), lease_owner = NULL, lease_expires_at = NULL
 WHERE download_id = :id
   AND phase NOT IN ('SUCCEEDED', 'FAILED')
   AND (:taskId::uuid IS NULL OR task_id = :taskId)
RETURNING task_id, candidates, candidate_index, slskd_username, slskd_filename, slskd_transfer_id
```

Bind the whole-download case with `bindNull("taskId", UUID.class)`. Deliberately no lease check:
a leased step's later save or finish finds the row finished (and, after a retry, not its own) and
writes nothing.

`DownloadService.cancel(id, taskId, now)` runs three statements **in this order** (the order is what
makes the admission race come out right, see the table below):

1. Whole-download only: `failUnadmitted(id, CANCELLED, now)` — the existing statement, guarded on
   `status = 'PENDING'`. If it updated a row the download had no songs and we are done.
2. `CANCEL_SQL`. For each returned row: if it holds an slskd transfer id, fire-and-forget
   `SlskdService.cancelDownload(user, id)` (same shape as `cancelIfAbandoned`: a failure is a WARN);
   then `LibraryOrganiser.deletePartials` best-effort, as for any failed song. Searches are left
   alone (the 13-08 ADR found deleting them unsafe).
3. `concludeDownloads()` — **always**, even when nothing was cancelled. It is idempotent, and
   without it the response body can be the two-second window in which every song is finished but
   the download is still "in progress", which the feed renders as "Waiting".

Why three statements and not one CTE: one statement sees one snapshot, so when admission wins the
row lock a single-statement cancel cannot see the song rows admission just committed and would
answer 409 for a download that is now live. The atomic cascade the ADR asked for is the cascade
over song rows, which `CANCEL_SQL` is.

**Orphaned transfer.** In `DownloadTaskRunner.apply`, when the save after a `DOWNLOAD_INIT` step
updates zero rows and the next state carries a transfer id, cancel that transfer in slskd (five
lines, the runner already has `slskdService`). This closes "enqueued in slskd, then found the song
cancelled". A crash between the enqueue and this point still orphans a transfer, as the ADR accepts.

**Endpoint.** `POST /downloads/{id}/cancel[?taskId=]` → **200** with the fresh `ActiveDownloadView`
when something was cancelled; **409** with the current view when nothing was (already finished, or
a second click); **404** when the id is unknown.

### Retry

```sql
-- DownloadTaskRepository.RETRY_SQL
WITH reset AS (
    UPDATE download_tasks
       SET phase = 'SEARCH_INIT', phase_entered_at = :now, next_attempt_at = :now,
           finished_at = NULL, failure_reason = NULL, last_error = NULL,
           search_id = NULL, search_tier = 0, candidates = '[]', candidate_index = 0,
           retry_index = 0, slskd_username = NULL, slskd_filename = NULL,
           slskd_transfer_id = NULL, progress_percent = 0, updated_at = now(),
           lease_owner = NULL, lease_expires_at = NULL
     WHERE download_id = :id
       AND phase = 'FAILED'
       AND EXISTS (SELECT 1 FROM downloads WHERE download_id = :id
                    AND status IN ('FAILED', 'PARTIAL_SUCCESS'))
    RETURNING task_id
)
UPDATE downloads
   SET status = 'IN_PROGRESS', failure_reason = NULL, finished_at = NULL, organised_at = NULL
 WHERE download_id = :id
   AND status IN ('FAILED', 'PARTIAL_SUCCESS')
   AND EXISTS (SELECT 1 FROM reset)
```

- Rows updated is `0` or `1` (the outer statement is the `downloads` update), like every other
  statement in the repository. `1` means "retried", `0` means "nothing to retry": still running,
  fully downloaded, or a concurrent retry got there first. That `0` is the idempotence the owner
  asked for: the second click of a double-click finds no `FAILED` rows left and updates nothing.
- `failure_reason = NULL` on `downloads` matters: the feed reads the download's own reason before
  the songs', so a stale one would outrank the fresh rows.
- `organised_at = NULL` so the library organiser rewrites the playlist file once the retried songs
  land; already-filed songs are untouched (`SET_LIBRARY_PATH_SQL` writes only when the path is null).
- `SET_ORGANISED_AT_SQL` gains `AND status IN ('SUCCEEDED', 'PARTIAL_SUCCESS')` so a stamp that was
  in flight when the user clicked Retry (the click is correlated with exactly that moment: the card
  just turned red) updates nothing, and the file is rewritten after the retried songs finish.

```sql
-- DownloadTaskRepository.READMIT_SQL: failed before it had any songs (bad id, or cancelled while queued)
UPDATE downloads
   SET status = 'PENDING', failure_reason = NULL, finished_at = NULL
 WHERE download_id = :id
   AND status = 'FAILED'
   AND NOT EXISTS (SELECT 1 FROM download_tasks WHERE download_id = :id)
```

Admission fetches the track list again on the next pass. A genuinely unknown id fails again with
the same code; cheap and honest.

`DownloadService.retry(id, now)`: run `RETRY_SQL`; if it updated nothing, run `READMIT_SQL`. Log
"Retrying download {}" when either did.

**Endpoint.** `POST /downloads/{id}/retry` → **202** with the fresh view (stage `STARTING`, or
`QUEUED` after a readmit; matches the existing 202 for new requests); **409** with the current view
when nothing was retried; **404** unknown. One controller helper serves both endpoints: run the
statement, then read the card back; `rows > 0` picks the success status, `0` picks 409, an empty
read is 404.

### Races and idempotence

| Scenario | Outcome |
|---|---|
| Retry double-click, both requests in flight | The first resets the `FAILED` rows and flips the download. The second waits on the same rows, re-checks `phase = 'FAILED'`, finds none, updates nothing → 409 with the (now live) card. |
| Retry while running, or after full success | `status IN ('FAILED','PARTIAL_SUCCESS')` false → 0; `READMIT_SQL` 0 (songs exist) → 409. |
| Cancel, then retry two seconds later, while the cancelled song's slskd call is still in flight | Cancel cleared the lease; retry reopened the row with no lease; the old step's save/finish require `lease_owner = :owner` → dropped. |
| Retry, then the pre-retry "organised" stamp lands | Status guard on `SET_ORGANISED_AT_SQL` sees `IN_PROGRESS` → 0; the playlist file is rewritten later. |
| Retry after cancel | Cancelled songs are `FAILED` → reset like any failure. Cancelled while queued → `READMIT_SQL`. |
| Cancel double-click | Second finds no unfinished rows → 0; `failUnadmitted` 0; conclude runs → 409 with the settled card. |
| Cancel a leased, in-flight step | Row finished, lease cleared; the step's save updates 0 rows. If it had just enqueued a transfer, the zero-row branch cancels it in slskd. |
| Cancel races the step's own "succeeded" | First writer wins. Cancel first: a finished file sits in slskd's folder unfiled. Step first: cancel updates 0, conclude runs anyway → 409 with `SUCCEEDED`, never "Waiting". Accepted in the ADR. |
| Cancel a queued download while admission is creating its songs, cancel commits first | Admission's status flip re-checks `PENDING`, finds `FAILED`, inserts nothing. |
| …admission commits first | `failUnadmitted` re-checks `PENDING`, finds `IN_PROGRESS` → 0; `CANCEL_SQL` runs in a fresh snapshot and cancels the new songs → 200. |
| Cancel vs. `concludeDownloads` at the end of a pass | Both idempotent under `d.status = 'IN_PROGRESS'`. |
| Cancel one song of a live collection | That row → `FAILED`/`CANCELLED`; siblings run on; later `PARTIAL_SUCCESS` or `FAILED`; the card says "… · 1 cancelled". |
| Cancel a finished download | 0 + 0 → 409. |

### Tests

- `DownloadTaskRepositoryIT`: retry resets only failed songs, reopens the download and clears
  `organised_at`; retry twice, the second is a no-op; two concurrent retries, exactly one wins
  (`Mono.zip`, sum of rows is 1); retry while in progress is a no-op; readmit of an unadmitted
  failure returns it to pending; cancel marks live songs cancelled, leaves finished ones alone and
  returns the transfer pairs; cancel one song touches only that song; cancel twice, the second is a
  no-op; `createTasks` after a cancel inserts nothing; the organised stamp after a retry updates
  nothing; `finishTask` by another owner is a no-op.
- `ActiveDownloadRepositoryIT`: `songsCancelled` counted; `songsFailed` excludes cancelled;
  `failureCode` is the real failure on a mixed collection and `CANCELLED` only when all cancelled.
- `DownloadServiceTest` (Mockito): the three cancel statements run in order and `failUnadmitted`
  short-circuits; one slskd cancel per row with a transfer id; conclude runs even when nothing was
  cancelled; retry falls through to readmit on zero.
- `DownloadTaskRunnerTest`: an enqueue refused by the save cancels its transfer; `finishTask`
  receives the instance id.
- `DownloadControllerTest`: retry 202/409/404 with body; cancel 200/409/404 with body; `taskId`
  passed through.

### Docs

`AGENTS.md`: the two new endpoints; drop "Cancellation" from the "does not have" list; note that a
cancelled song is a `FAILED` row with reason `CANCELLED`. New ADR
`docs/decisions/retry-and-cancel-28-09-2026.md` recording decisions 1–4 above, the two
prerequisite fixes, and the not-built escape hatch: a level-triggered sweep in `pass()` for
"cancelled songs that still hold an slskd transfer id" if best-effort slskd cancels ever prove
insufficient.

## Client

### Contract additions

`DownloadFailureCode` += `'CANCELLED'`; `ActiveDownloadView.songsCancelled: number` (read as
`row.songsCancelled ?? 0` so an older server still works). `retryDownload(id)` and
`cancelDownload(id, taskId?)` in `api/endpoints.ts`, returning the view; on a non-2xx the `ApiError`
carries the parsed body so a 409's card can be applied. Mocks in `api/mockData.ts`: cancelling
rewinds the entry's clock so the simulated stage is terminal at once, and both mocks throw a 409
when the entry is in the wrong state.

### One rule change in `mergeCard`

Today: a finished card ignores any live row. That rule would make every retry invisible. Replacement,
using the server's `updatedAt`, which is monotonic per download (every write stamps it; conclusion
does not):

```ts
const crossing = !!existing && isTerminal(existing.stage) !== isTerminal(row.stage)
if (crossing) {
  const rowAt = Date.parse(row.updatedAt), knownAt = Date.parse(existing!.updatedAt)
  // finished -> live: only if strictly newer (the two-second "Waiting" quirk row is equal, never newer)
  // live -> finished: only if not strictly older (a fail-before-admission row is equal to the
  //                   optimistic card's requestedAt and must land)
  if (isTerminal(existing!.stage) ? rowAt <= knownAt : rowAt < knownAt) return existing!
}
const reopened = crossing && isTerminal(existing!.stage)
// A reopened card's old outcome is not an observation about the new attempt.
const progressPercent = row.progressPercent ?? (reopened ? null : existing?.progressPercent ?? null)
const failureCode = reopened ? row.failureCode ?? null : row.failureCode ?? existing?.failureCode ?? null
```

Live-to-live rows are merged exactly as today. The existing check-script assertion (equal
timestamps, live row over a finished card → unchanged) still passes.

### The hook

`useActiveDownloads` gains `retry(id)` and `cancel(id, taskId?)`, sharing one `act(key, call)`:

- **In-flight guard**, keyed on `taskId ?? downloadId`, so two sibling songs can be cancelled at
  once while the whole-download button is single-flight. Buttons render `disabled` while their key
  is in flight, so a double-click cannot even be sent; the server's 409 is the second line.
- On success: forget the id in `dismissedRef` (a dismissed failed card retried from the Downloads
  page must come back), apply the body as a fresh card (stage, counts, `failureCode` from the body;
  **title, artists and artwork still only fill in**, because a readmitted download's body has no
  metadata yet), set `lastChangedAt = now` so the auto-dismiss clock restarts, reuse the 30-second
  fast-poll window, and poll now.
- On 409: apply the body through `applyRows` (it is settled, see cancel step 3) and poll now.
  On 404: dismiss silently.
- `applyRows`: a live row for a dismissed id is un-dismissed (it can only be a retry, from any
  tab); a finished row for a dismissed id is still skipped.
- The auto-dismiss loop skips ids with an in-flight action.

### What the user sees

- **Panel card** (`DownloadCard`): a live card, including "Waiting", gets a `Square` "Cancel
  {title}" button. A failed or partly downloaded card gets a `RotateCcw` "Retry {title}" button
  before the existing X, which keeps meaning dismiss. A downloaded card keeps X only. A cancelled
  download (`stage === 'FAILED' && failureCode === 'CANCELLED'`, both conditions, so a live album
  with one cancelled track is not painted grey) shows a `Ban` glyph and grey text reading
  "Cancelled".
- **Downloads page row** (`DownloadRow`): the same two buttons; grey when cancelled. An expanded
  collection's `SongRow` gets Cancel on a live song and shows "Cancelled" in grey on a cancelled one;
  the song list reloads after the action.
- **Counts** (`collectionSummary`): "7 of 12 downloaded · 3 failed · 2 cancelled"; all cancelled
  with nothing downloaded reads simply "Cancelled". `collectionSegments` counts cancelled songs as
  settled (never as "in progress") and the bar gets a darker grey segment for them.
- **Announcements** (`DownloadPanel`, aria-live): "{title} cancelled." inside the existing failed
  branch; "Retrying {title}." when a card the panel had announced as finished is seen live again.
- Wording in `failureCopy`: `CANCELLED → 'Cancelled'`.

### Checks (`npm run check`)

`mergeCard`: equal-timestamp live row over a finished card → unchanged (existing); newer → stage
`STARTING`, `failureCode` null, progress from the row; finished row strictly older than a live card
→ unchanged; equal → applied; live-to-live older → still merged. `failureCopy('CANCELLED') ===
'Cancelled'`. `isCancelled` false for a downloading card and for a partly downloaded card with
`failureCode 'CANCELLED'`, true for a failed one. `collectionSummary(12, 7, 3, 2, PARTIAL_SUCCESS)`
→ "7 of 12 downloaded · 3 failed · 2 cancelled"; `(12, 0, 0, 12, FAILED, CANCELLED)` → "Cancelled".

## PR sequence (all target `move-fast-break-things`, label `AI`, one purpose each)

Server, in order:

1. `fix(AI): a song's finish only counts if its step still holds the lease` — the `FINISH_TASK_SQL`
   guard, the runner passing its id, the test helper. The biggest diff of the set, so it goes first.
2. `fix(AI): admission marks a download started before adding its songs` — the `CREATE_TASKS_SQL`
   swap and its test.
3. `feat(AI): cards count cancelled songs separately from failed ones` — the aggregate and the wire
   field. Harmless alone: nothing writes `CANCELLED` yet.
4. `feat(AI): cancel a download, or one song of it` — the code, `CANCEL_SQL`, the service, the
   endpoint, tests, AGENTS.md, the ADR.
5. `fix(AI): stop a transfer that started after its song was cancelled` — the zero-row branch in
   `apply`.
6. `feat(AI): retry a failed download` — `RETRY_SQL`, `READMIT_SQL`, the organised-stamp guard, the
   service, the endpoint, tests, ADR addendum.

Client, in order (each works against a server with or without the matching PR):

7. `feat(AI): show cancelled downloads in grey, not red` — types, wording, glyph, counts, mocks.
8. `feat(AI): a download that starts again replaces its finished card` — the `mergeCard` rule,
   un-dismiss, the "Retrying" announcement, checks, client ADR.
9. `feat(AI): cancel button on downloads` — endpoint, hook, buttons on card and row.
10. `feat(AI): retry button on failed downloads` — endpoint, hook, buttons.
11. `feat(AI): cancel one song inside an album or playlist` — `SongRow`.

## Deliberately not built

- A `CANCELLED` status or stage (decision 1; upgrade path recorded).
- Per-song retry, an attempt counter, an attempt history table (decision 2 and 4).
- A transaction around cancel's three statements: correctness rests on their order and on the
  admission lock, documented in the service's Javadoc and pinned by `DownloadServiceTest`.
- A sweep for transfers whose slskd cancel was lost to a crash (the ADR's escape hatch).
- Pause-on-hover for the panel's auto-dismiss (decision 6).
- Authentication on the new endpoints, as on every endpoint today.

## Known edges

- A download cancelled while queued, or that fails again after a readmit, has no song rows and so
  never appears in the feed's "recently finished" branch. The endpoint's body shows the card at
  once, and the existing stale-card reconciliation (`/downloads?ids=`) settles it within one grace
  period if it fails again. Pre-existing for bad ids; now reachable by a user action; noted in the ADR.
- Retry is available only once the whole download has finished. A failed song inside a still-running
  collection waits for its siblings.
- Cancelling deletes slskd's partial files for that song (as a failure does), so a retry starts the
  transfer from zero.
- Cancelling in slskd is best-effort. A crash between the database write and the slskd call leaves
  that transfer running in slskd until it finishes; the row is already cancelled and nothing files it.

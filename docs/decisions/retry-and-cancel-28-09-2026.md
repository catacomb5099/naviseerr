# Retry and cancel downloads

**Date:** 28-09-2026
**Status:** Accepted, implemented
**Builds on:** `durable-download-state-machine-13-08-2026.md` (cancellation is a database write first,
then best-effort slskd cancels) and `collection-downloads-14-09-2026.md` (a download's status is derived
from its songs). The full design, with the SQL and the race table, is
`docs/superpowers/specs/2026-09-28-retry-and-cancel-design.md`.

## Context

The owner asked for four things: a Retry button on a failed download; retry as often as you like but never
while one is already running; a double-click retries once and refuses the second request; and Cancel, for
one song or for a whole album, playlist or suggested playlist. Until now a download could only run to the
end, and a failed one could only be requested again from scratch.

## Decisions

1. **A cancelled song is stored as `FAILED` with the reason `CANCELLED`.** No new status and no migration.
   Every "is this song finished?" test in the loop already treats `FAILED` as finished, so a cancelled song
   can never be picked up again. The card counts cancelled songs separately (`songsCancelled`) and the
   client shows "Cancelled" in grey, never red. A real `CANCELLED` status would need a migration, two index
   rebuilds and about thirteen code edits for the same words on screen. Upgrade path, if ever wanted: one
   migration plus `UPDATE download_tasks SET phase = 'CANCELLED' WHERE failure_reason = 'CANCELLED'`.
2. **Retry works on a finished download** (failed, or partly downloaded) and retries every song that has
   no file, cancelled ones included. Songs that succeeded are left alone. "Retry" means "get me the ones I
   did not get"; per-song retry was not asked for and is the same statement with a `task_id` filter.
3. **Retrying a cancelled download is how you un-cancel it.** Falls out of decision 1.
4. **Retry resets the song's row in place**, back to "start searching". The previous attempt's peers and
   error are not kept on the row (they are in the log); a second row per song would break every "7 of 12"
   count the cards read. An append-only attempt log is the recorded upgrade.
5. **Cancelling one song inside a live collection is allowed**; the other songs carry on, and the download
   later ends `PARTIAL_SUCCESS` or `FAILED` as usual.
6. **The panel's auto-dismiss of finished cards is unchanged.** The Downloads page is where you retry
   something later.

## How cancel works

`POST /downloads/{id}/cancel[?taskId=]` runs three statements, in this order (`DownloadService.cancel`):

1. Whole download only: if it is still `PENDING` (no songs yet), fail it with `CANCELLED` and stop.
2. Mark every unfinished song (or the one song) `FAILED`/`CANCELLED` and clear its lease
   (`DownloadTaskRepository.CANCEL_SQL`). For each, best-effort cancel its live slskd transfer and remove
   its partial files. Searches are left alone: the 13-08 ADR found deleting them unsafe.
3. Derive the download's status straight away (`concludeDownloads`), even when nothing was cancelled, so
   the response never shows an all-finished download as still waiting.

200 with the fresh card when something was cancelled; 409 with the current card when nothing was left to
cancel (already finished, or a second click); 404 for an unknown id.

Three statements rather than one: a single statement sees one snapshot, so when admission wins the row
lock it could not see the songs admission just created and would wrongly answer 409.

## The two loop fixes

Both are bug fixes in their own right and landed first:

- **A song's finish only counts if its step still holds the lease** (`FINISH_TASK_SQL` gained the
  `lease_owner = :owner` check `SAVE_SQL` already had). Cancel and retry both clear the lease, so a slow
  slskd call that was mid-flight when its song was cancelled cannot land its old outcome on a fresh attempt.
- **Admission marks the download started before adding its songs** (`CREATE_TASKS_SQL`'s halves swapped).
  The status flip takes the row lock first, so cancelling a queued download while admission is fetching its
  track list resolves cleanly one way or the other instead of leaving a finished download with live songs.

## Trade-offs

- **Cancelling in slskd is best-effort.** A crash (or an slskd outage) between the database write and the
  slskd call leaves that transfer running in slskd until it finishes; the song is already cancelled and
  nothing files the file.
- **A song cancelled in the instant its transfer is being queued** can leave that transfer running in
  slskd. The runner stops it: when the save after queuing finds the song cancelled, it cancels the
  transfer it just queued (`DownloadTaskRunner.apply`).
- **Cancel races the song's own success: first writer wins.** If cancel wins, a finished file may sit in
  slskd's folder unfiled. If the song wins, cancel answers 409 with the downloaded card.
- **Cancelling deletes slskd's partial files** for that song, as any failure does, so a later retry starts
  the transfer from zero.
- **A download cancelled while still queued has no songs**, so it never appears in the feed's "recently
  finished" window. The cancel response carries the card, and `/downloads?ids=` settles any card a client
  kept. Already true of a bad id; now reachable by a user action.
- **No transaction around the three statements.** Correctness rests on their order and on the admission
  lock; `DownloadServiceTest` pins the order.

## Not built

- A `CANCELLED` status or stage (decision 1).
- An attempt counter, an attempt history table (decision 4). Per-song retry: built 07-10-2026, see the addendum.
- **A sweep for transfers whose slskd cancel was lost.** The escape hatch if best-effort cancels ever prove
  insufficient: a level-triggered step in the loop's pass that finds cancelled songs still holding an slskd
  transfer id, cancels the transfer, and clears the id.
- Pause-on-hover for the panel's auto-dismiss (decision 6).
- Authentication on the new endpoints, as on every endpoint today.

## Addendum 07-10-2026: retry one song, even while the collection runs

The owner asked for a Retry on a failed song inside a collection that is still downloading. Decision 2
predicted it: it is `RETRY_SQL` with a `task_id` filter.

- **`POST /downloads/{id}/retry?taskId=`**, `taskId` optional, mirroring cancel. Without it, today's
  behaviour; with it, that one `FAILED` song (a cancelled one included, decision 3) is reset in place
  whatever the download's status. A failed song's download can only be running, failed or partly
  downloaded, so no status test is needed for the one-song case.
- **The whole-download Retry is still refused while the download runs** (decision 2). Only one song at a
  time may be retried on a live collection.
- **A live download is left alone.** The reset song is `SEARCH_INIT` and due now, so the next pass claims
  it like a new one; the conclusion rule cannot close the download while it has a live song. A download that
  had concluded reopens exactly as a whole retry reopens it (`organised_at` cleared too, so the playlist
  file is rewritten once the song lands).
- **The statement now reports how many songs it reset** (N for a whole retry, 1 or 0 for one song) instead
  of whether the download reopened; the endpoint only tests "more than zero". The reopen is a side CTE.
- **The fallback to re-queueing an unadmitted failure runs only for a whole retry**: a download with no
  songs has no song to retry, and a wrong `taskId` must not re-queue it.
- **A retried album track searches on its own**; the album's folder search is not re-run (as for a whole
  retry). `track_number` and `duration_seconds` stay on the row, so the length filter still applies.
- **The collection's stage word steps back** to "Starting"/"Searching" while the retried song searches:
  the card reports its least advanced song. Existing rule, not a regression.
- **No library re-check on retry**, as before: a song someone filed in the meantime is searched again.

### Known race, recorded 07-10-2026, closed 09-10-2026

Closed on 09-10-2026 with exactly the statement recorded below, `REOPEN_SQL`, run by `concludeDownloads()`
before `CONCLUDE_SQL` on every pass; it also clears `failure_reason` and `organised_at`, as `RETRY_SQL`'s
reopen does, so the playlist file is written again once the song lands. Why it mattered for playlists, and
the rest of the post-processing story: [playlist-post-processing-09-10-2026.md](playlist-post-processing-09-10-2026.md).
The 07-10 record, as written:

A one-song retry on a live download can commit between the conclusion statement's snapshot (every song
terminal) and its write. The download then reads failed or partly downloaded while one song is live; the
song still runs and finishes, but the download is never re-concluded, so the card keeps a terminal word
while its counts move and the whole Retry answers 409. Window: the milliseconds one conclude statement
takes, once per pass; a whole retry cannot hit it because conclusion skips finished downloads at scan
time. The recorded fix, the loop's own level-triggered style: one idempotent statement in the pass after
conclusion, `UPDATE downloads SET status = 'IN_PROGRESS', finished_at = NULL WHERE status IN ('FAILED',
'PARTIAL_SUCCESS', 'SUCCEEDED') AND EXISTS (SELECT 1 FROM download_tasks t WHERE t.download_id =
downloads.download_id AND t.phase NOT IN ('SUCCEEDED', 'FAILED'))`, driven by `idx_download_tasks_due`.
Shipped without it (a `ponytail:` note on `RETRY_SQL` names the window and this statement); add it the
first time a stuck card is seen.

## Addendum 09-10-2026: a whole-album retry looks for one sharer again

The owner asked for a "Try again" inside the choose-a-file pop-up (manual pick). For a song it is the
per-song retry above. For an album the pop-up lists the folders the album's own search found, and that
search never ran again: a whole retry reset the failed songs and they searched one by one, so the album
list could never fill after it. Now:

- **`POST /downloads/{id}/retry` on an album also resets `album_searches`** (one more CTE in `RETRY_SQL`):
  `DONE` back to `SEARCH_INIT`, tier 0, no search id, no outcome, clocks at now, no lease. Only for a
  whole retry of a finished download (`FAILED`/`PARTIAL_SUCCESS`) with at least one `FAILED` song: the
  count of songs reset stays the idempotence, so a second click changes nothing.
- **The reset songs are held**, due at `AlbumSearchStep.holdUntil(now)` (2 x `search-budget-ms`, 4 min by
  default) instead of now, exactly as `CREATE_TASKS_SQL` writes a fresh album's songs. Without the hold they
  would start their own searches within a pass and the restarted album search would find nothing waiting
  (`NOTHING_TO_SEARCH`). `DownloadService.retry` passes the hold; the step re-extends it when the search
  starts, and if the step dies the hold runs out and the songs search on their own, as always.
- **Songs with a file are untouched**, so the restarted search plans only for the missing ones: a sharer
  holding every missing song counts as a whole folder.
- **The remembered folders stay** on the row while the new search runs, as a song's file list does on a
  per-song retry; the step overwrites them when it finds any. Flip: clear `folders`/`folders_at` in the
  `album` CTE so the pop-up says "still searching" at once.
- **One track's retry is unchanged**: the search stays done, the track searches on its own.
- **Visible change for every whole-album retry**: the card reads "Searching" and the songs wait for the
  folder search (typically 10-30 s; up to the hold if slskd leaves the search "InProgress") instead of
  starting solo at once. A fresh album behaves the same; "retry the album" now means what it says.
  Flip: drop the `album` CTE and the `CASE` on `next_attempt_at`.
- Lock order `album_searches` -> `download_tasks` -> `downloads`, the order `CANCEL_SQL`, the album save
  and the release use (the `count(*)` line in the `reset` CTE pins it, as in `CANCEL_SQL`).

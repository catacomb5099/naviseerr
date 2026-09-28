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
- Per-song retry, an attempt counter, an attempt history table (decisions 2 and 4).
- **A sweep for transfers whose slskd cancel was lost.** The escape hatch if best-effort cancels ever prove
  insufficient: a level-triggered step in the loop's pass that finds cancelled songs still holding an slskd
  transfer id, cancels the transfer, and clears the id.
- Pause-on-hover for the panel's auto-dismiss (decision 6).
- Authentication on the new endpoints, as on every endpoint today.

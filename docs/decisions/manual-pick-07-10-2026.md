# Choosing the file by hand (manual pick)

**Date:** 07-10-2026
**Status:** Accepted, implemented (server side; the web client's dialog is a separate change)
**Builds on:** `retry-and-cancel-28-09-2026.md` (a pick is a retry-shaped reset plus the cancel code),
`whole-album-downloads-04-10-2026.md` (album folders), `durable-download-state-machine-13-08-2026.md`
(the lease guards that make the race safe). The research notes and the API contract both sides built against are
kept in the owner's private sweep notes, not in this repository.

## Context

The owner asked for Sonarr-style manual import: a person icon on a song (and on an album as a whole)
opens a table of every file Soulseek offered -- sharer, speed, length, bit rate, format -- and a Download
button per row replaces the current download in place, cancelling the running transfer. The list must
come from the search already done, with no spinner.

Until now the pipeline threw away everything it learned about a song's files the moment it picked ten
of them (`SlskdSearchResultProcessor.selectBestFiles`, `max-files-per-download`). There was nothing to
show.

## Decisions

1. **Remember the search result in Postgres, at the one moment the server holds it.** Two JSONB side
   caches (V15): `download_tasks.search_results` (every file of the completed search that is the song,
   any format or bit rate, the picker's order, the first 100) written by `DownloadStepExecutor.remember`
   right after the one refetch; `album_searches.folders` (the judged folders, the first 20, with the
   matched file per song) written by `AlbumSearchStep.judge`, also when the step moves on to the next
   wording. Not slskd's own stored search: that depends on slskd being up and on `retention.search`
   (which the config doc tells self-hosters to set short), takes a 1 MiB fetch and a re-grade per open
   (the spinner the owner did not want), and cannot serve a row whose search aged out. Reading slskd's
   stored search remains the recorded upgrade for rows from before V15 (about 15 lines using `search_id`).
2. **The cache write can never fail a search.** Its own statement, errors swallowed and logged, pinned by
   a test. An empty list stamps a fresh row ("searched, nothing relevant") but never wipes a list another
   wording found: the title alone and "title - artist" each see files the other misses.
3. **The list ignores the automatic picker's filters and shows the grade instead.** The 320 kbps /
   lossless rule, the album-length rule and the unverified-artist rule are judgement calls; the point of
   choosing by hand is to overrule them. EXACT, OTHER_VERSION and UNVERIFIED are shown as a badge.
4. **A pick replaces the candidate list with the one chosen file, in place.** `PICK_SQL` resets the row
   to `DOWNLOAD_INIT` with `candidates = [that file]` (grade EXACT, source `MANUAL`), index 0, retries 0,
   slskd columns and progress cleared, lease cleared, and reopens a `FAILED`/`PARTIAL_SUCCESS` download.
   Same row, same task id, same titles: no second row, so every "7 of 12" count still holds. If the
   chosen file fails (rejected, stalled ten minutes, errored past two retries) the one-element list ends
   `SOURCES_EXHAUSTED` and the person picks again. No silent fallback to the automatic list.
5. **The statement returns the OLD row**, through a second scan of the table in the same statement, so
   `DownloadService.stopInSlskd` cancels the right transfer and `deletePartials` walks the right list
   (it deletes `candidates[0..candidate_index]` of the attempt that ran). That is the same two-step
   shape as cancel. Postgres 16 has no `RETURNING OLD`; the self-join is the portable form.
6. **A manual pick is graded EXACT.** The person says it is the song, so "already downloaded" and filing
   treat it as the real one, not a stand-in. Flip: keep the file's own grade.
7. **A `SUCCEEDED` song cannot be re-picked (409).** Replacing a filed file needs "delete the old library
   file" logic the organiser lacks (`LibraryOrganiser.file` moves atomically and would leave a `.flac`
   beside an `.mp3`). Flip: drop `phase <> 'SUCCEEDED'` from `PICK_SQL` and reset `library_path`.
8. **An album pick re-points every unfinished song the folder holds and leaves the rest alone**, running,
   failed or done. The `album_searches` row is not touched: a release still running only touches songs
   still at the start with no files, which the picked songs no longer are.
9. **Album folders remembered are what the picker ranks** (whole folders, then parts holding at least half
   and at least two). A 3-of-11 folder never appears. Flip: run `folders()` again with `fewest = 1` for
   the cache only.
10. **No search is started from the request thread, and no 404 for an empty list.** `GET .../candidates`
    answers `SEARCHING` while the loop is still searching (the client polls the same GET) and `NONE` with
    a reason when it never will (`BEFORE_CACHE`, `NO_RESULTS`, `ALREADY_IN_LIBRARY`; for an album
    `NO_WHOLE_FOLDER`, `BEFORE_CACHE`, `NO_ALBUM_SEARCH`). `POST /searches` must run one at a time under
    the two-slot gate; doing it from a controller would race the loop and wait the full search timeout
    anyway. Retry is the one lazy way to a fresh search, and it fills the cache. Since 09-10-2026 a
    whole-album retry re-runs the album's folder search too, so the pop-up can offer the retry as
    "Try again" for an album as well as for a song (`retry-and-cancel-28-09-2026.md`, addendum 09-10-2026).
11. **Pick responses return the card** (like cancel and retry), not the song row; the client re-reads
    `GET /downloads/{id}` as it already does. 404s and 400s carry `{message}`; the album routes answer
    409 `{reason: NOT_AN_ALBUM, message}` for anything but an album (no album picker for playlists).

## Races, in one table

| Meanwhile | What happens |
|---|---|
| A step holds the row's lease and is mid-call | The pick clears the lease; the step's `SAVE_SQL` / `FINISH_TASK_SQL` match nothing. If it had just enqueued a transfer, the runner cancels that orphan (`DownloadTaskRunner.apply`, rows == 0 branch). |
| The album search is mid-wording | Its release touches only songs still at `SEARCH_INIT` with no files; the picked songs are `DOWNLOAD_INIT` with one file. The search then ends with fewer songs touched; cosmetic log noise. |
| Two picks for the same song | Both statements update the row; the later one wins, and each stops the transfer the other started through the returned old row. Harmless. |
| The sharer has gone offline since the search | slskd's enqueue fails; after two retries the song ends `SOURCES_EXHAUSTED`; the person picks another file. Expected. |
| Re-picking the exact current file | Its partial is deleted and it restarts from zero. The client greys the current row, so this is unreachable from the UI. |

## Size

100 files x about 350 bytes = 35 KB per song; a 500-song playlist writes about 17 MB once. 20 folders
x 15 files is about 75 KB per album. Both caps are constants (`DownloadStepExecutor.REMEMBERED_FILES`,
`AlbumSearchStep.REMEMBERED_FOLDERS`).

## Endpoints

`GET /downloads/{id}/tasks/{taskId}/candidates`, `GET /downloads/{id}/album-candidates`,
`POST /downloads/{id}/tasks/{taskId}/pick` `{username, filename}`, `POST /downloads/{id}/album-pick`
`{username, folder}`. Shapes in `AGENTS.md`; the contract file above has the client wording for every
status and reason.

## Change of 08-10-2026: every option, not only the judged ones

The owner saw the first lists and asked for everything Soulseek returned, not what Naviseerr judged relevant. So the
song list now keeps every audio file of the search (cover art, cue sheets and logs left out), the files the matcher
calls another song included and graded `NONE`, in the picker's order with those last, and no cap at all (it was 100; the
owner: "the results won't be long enough to worry"; slskd's own response and file limits bound a search anyway).
The album list keeps every folder holding at least one of the album's songs by the song rules, whatever its bit rate,
whether or not its path names the artist, stalling sharer or not: the folders the search itself would take come first
and carry `judged: true`, the rest `judged: false`; no cap either (it was 20). The automatic search is unchanged:
`selectBestFiles` and `AlbumFolderPicker.folders` still apply every rule. Rows written before this change keep their
shorter lists until the song or album is searched again.

## Addendum of 09-10-2026: the empty list says what really happened

The owner opened the pop-up on an album, read "This album was downloaded before folder lists were kept",
looked for the search in slskd's history by the current wording, found nothing, and asked whether the search
had ever been made. It had (05-10-2026, slskd search `10549190-…`, 250 answers, a whole folder, 15 songs done
in under two minutes), but nothing on screen or in the log said so; and in one case the pop-up was plainly
wrong: an album search slskd refused (`SEARCH_FAILED`) read as "Nobody shared enough of this album as one
folder", when nobody had been asked.

Decisions, server side (the client words them):

1. **The reason is read off the columns the rows already have**, no new table, no migration.
   `download_tasks.search_id` and `failure_reason`, `album_searches.search_id`, `outcome` and
   `finished_at` join the two cache reads. Song: `ALREADY_IN_LIBRARY`; then `NO_OWN_SEARCH` (a file the
   album folder or a person gave it, by the candidate's `source`, and no search id of its own: it was
   never searched for by itself); then `SEARCH_FAILED` / `SOULSEEK_OFFLINE` / `CANCELLED` (no list, no
   file, and a failure code that means the search never completed: `SEARCH_FAILED`, `TIMED_OUT`,
   `SOULSEEK_OFFLINE`, `CANCELLED`; the last two under their own name, since the client already words
   those codes and a song cancelled while searching must match the album view); then `NO_RESULTS`
   (completed, nothing relevant); else `BEFORE_CACHE`. Album: the outcome as it is (`NO_WHOLE_FOLDER`,
   `SEARCH_FAILED`, `NOTHING_TO_SEARCH`, `CANCELLED`), `BEFORE_CACHE` only for a folder found with nothing
   remembered. `SOULSEEK_OFFLINE` stays its own reason because the card already words that code;
   flip: fold it into `SEARCH_FAILED` in `DownloadService.reasonOf`. `TIMED_OUT` is reported as
   `SEARCH_FAILED` (a search that ran out of time never completed either); a `TIMED_OUT` row that has a
   file is a transfer timeout after a completed search and reads `BEFORE_CACHE`.
2. **Both views name slskd's search** (`searchId`) and the album view's `searchedAt` falls back to
   `finished_at`, so a pre-V15 row still says when it searched. The id is the one thing a person can
   paste into slskd's Searches page; what was actually sent is not stored (the id leads to it), and a
   self-hoster who sets `retention.search` short in slskd will find the id gone after that period: the
   Postgres lists (decision 1 above) are the durable copy. Flip: hide the id in the client, keep it in
   the API.
3. **The response shape is additive**: two new optional fields, new `reason` values; a client that does
   not know a reason falls through to its generic sentence, so the old client keeps working.
4. Not done (ponytail): storing the wording actually sent; re-reading slskd's stored search for pre-V15
   rows ("Try again" in the pop-up, ask 3 of this sweep, makes it moot); ordering the card's
   `MIN(failure_reason)` by severity.

The companion change makes the log name slskd's search id when an album search starts and warn when
one never starts (`AlbumSearchStep`), so the question is answerable from the log too.

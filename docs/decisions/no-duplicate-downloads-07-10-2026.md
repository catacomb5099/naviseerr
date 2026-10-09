# No duplicate downloads

**Date:** 07-10-2026
**Status:** Accepted, implemented
**Builds on:** `retry-and-cancel-28-09-2026.md` (the 409-with-the-current-card convention and "cancelled is
FAILED with reason CANCELLED") and `whole-album-downloads-04-10-2026.md` (#109: a song already filed in the
library is not fetched again, organiser on).

## Context

Every click on a download button was a new `downloads` row, a new Soulseek search and, without the
organiser, a new file. Nothing on the server or in the client knew that the same song, album or playlist
was already queued, running or finished. The owner asked for the server to refuse a request for something
it already has and for the client to grey out anything in progress so it cannot be queued again and again.

## Decisions

1. **Before inserting, `POST /download/song/{id}` and `POST /download/collection/{id}?type=` look for the
   newest existing download of the same `(download_type, youtube_id)` whose status is not `FAILED`.** If
   there is one, the answer is `409 Conflict` with that download's `ActiveDownloadView` card -- the same
   body cancel and retry use, which the client already applies as a row. Otherwise the insert and the 202
   happen as before. (`ActiveDownloadRepository.findCurrent`, `DownloadController.request`.)
2. **Queued, running, downloaded and partly downloaded all refuse.** A partly downloaded collection has a
   Retry button that fetches only the missing songs; a second request would re-download the finished ones
   on any install without the organiser.
3. **A FAILED download, cancelled included, can be asked for again** and gets a new row. Nothing was
   fetched, so nothing can duplicate, and sending someone to find a week-old failed row to retry is
   friction. Flip: to refuse everything and make Retry the only way to ask again, add `FAILED` to
   `ActiveDownloadRepository.CURRENT_STATUSES`.
4. **CURATED refuses only while live.** The curated id is the category key, reused by a new edition every
   week, so last week's finished edition must not refuse this week's download.
5. **Exact type + id only.** A song requested on its own (`SONG`, videoId) and the same song inside a
   playlist download are different rows and do not block each other; #109 already avoids fetching the file
   twice when the organiser is on. Two radios from one seed are two ids and both download.
6. **No index and no constraint now.** The lookup is a scan of `downloads` (one row per request) on a user
   click, well under a millisecond on any home install; `CREATE INDEX ON downloads (download_type,
   youtube_id)` is the upgrade if a history grows large. A partial unique index would close the
   two-clicks-in-one-millisecond race but cannot cover `SUCCEEDED` (existing histories already hold
   duplicates) or express the CURATED exception; the race costs one extra row, the posture the
   enqueue-crash decision already accepts, and the client greys the button after the first 202 anyway.

## Consequences

- A client that does not understand the 409 shows its generic "couldn't request" message -- no worse than
  the silent duplicate it used to get. The web client greys the button and announces "Already downloading"
  / "You already have" instead (client PRs of the same date). The refused item's button then reads
  "Downloaded" for the panel's usual 30 seconds and is green again until the next click, which the server
  refuses again; pre-greying something finished hours ago needs a lookup-by-id endpoint, not built.
- Existing histories with several rows per id are read newest-first; nothing is migrated or deleted.

## Addendum 09-10-2026: files gone from the library

**Context.** Decision 2 refused a request for a downloaded item from `downloads.status` alone; nothing
looked at the disk. People delete and move files (a Finder clean-up, a disk swap, a Navidrome purge), and
then "you already have it" was wrong and there was no way to get the song back short of editing the
database. Admission already knew how to skip the songs still on disk (#109, `stillFiled`), but the 409
never let a second row reach it.

**Decisions.**

1. **A finished download (SUCCEEDED or PARTIAL_SUCCESS) refuses only while its files are still in the
   library.** Before the 409, `DownloadController.request` asks `DownloadService.filesMissing(downloadId)`:
   the download's filed songs (`library_path IS NOT NULL`, the same rows the playlist file lists) go
   through `LibraryOrganiser.anyMissing`, one `stat` each on `boundedElastic`. If any is gone, the request
   is inserted as if nothing existed and one INFO line says so (`Download ... has files missing from the
   library; a new request is allowed`); admission then creates the songs still on disk `SUCCEEDED` pointing
   at their files and fetches only the missing ones. A SUCCEEDED task row is never reopened: the
   re-download is a new row, honest history. Both checks end in `LibraryOrganiser.inLibrary`, so they agree.
2. **A live duplicate (queued or working) always refuses**, as before. There is no file to look for yet.
3. **Unverifiable counts as present.** With the organiser off there is no library to look in; a song the
   organiser never filed (gave up, or filed before the organiser existed) has no path to test. In both
   cases the 409 stands, exactly the no-duplicates behaviour of 07-10 on the owner's laptop. Flip: in
   `DownloadService.filesMissing`, return `true` when `!organiser.isEnabled()` and/or count SUCCEEDED
   songs with a NULL `library_path` as missing; then finished items can always be re-requested where the
   server cannot see the disk (live ones still refuse).
4. **Any missing file lets the request through**, not all. Flip: `allMatch` in `anyMissing`; then a
   half-deleted album needs the deliberate override.
5. **No DDL, no new query.** The paths come from the existing `playlistEntries` query; the check is a stat
   over an existing column.
6. **A deliberate override for what the server cannot verify: `?force=true`.** Where the organiser is off
   (the owner's laptop, a remote slskd) or a song was never filed, decision 3 keeps the 409, so the web
   client's Downloads page offers "Download again" on a finished row. The request then carries
   `force=true`, and `findCurrent(type, id, liveOnly=true)` counts only a queued or running duplicate
   (the CURATED rule applied on demand): a finished one no longer stands in the way, a live one still
   does. The new row goes through admission as usual, so with the organiser on the songs still in the
   library are created SUCCEEDED and not fetched twice. Flip: hide the button (client) or ignore the
   parameter (server) to go back to "merge only decision 1".

**Consequences.**

- A file moved elsewhere inside the library is "gone" at its recorded path; the re-download files a
  second copy at the canonical path. Only a library scan would know better; not built.
- The old row keeps its stale `library_path`s. They hurt nothing: every use of filed copies is followed by
  an `inLibrary` test. For a playlist, the old row's playlist file keeps its stale lines until the new
  row's own playlist file is written, which has the same title and so replaces it.
- If the library volume is not mounted at all, every path is "gone" and every finished download becomes
  re-requestable; refusing to start in that state is the install's job (required-volumes work, same date).
- Cost: one stat per filed song of the existing download, per click. `anyMatch` stops at the first
  missing file.

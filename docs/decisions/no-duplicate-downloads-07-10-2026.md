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

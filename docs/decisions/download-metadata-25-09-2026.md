# Download metadata: a media table, lifecycle timestamps, and a per-song view

**Date:** 25-09-2026
**Status:** Accepted, implemented
**Builds on:** [collection-downloads-14-09-2026.md](collection-downloads-14-09-2026.md)

## Context

Collection downloads made a `downloads` row one request and `download_tasks` one row per song. Two
things were left over from when a download was one song:

- `downloads.song_name` held the display title. A download is a pointer to a song or a collection,
  not the thing itself, and a column named `song_name` on a row that might be a playlist says so.
- Nothing stored a picture, and nothing exposed the songs inside a collection. A half-succeeded
  album was one card saying `PARTIAL_SUCCESS`, and "which half?" needed `psql`.

Also a gap nobody had hit yet: a download whose id ytmusic-adapter could not resolve was failed
before any task row existed, and `failure_reason` lives on task rows, so the feed showed `FAILED`
with no reason.

## Decision

### One `media_items` table, keyed by YouTube id, for songs and collections alike

```sql
CREATE TABLE media_items (
    youtube_id       TEXT PRIMARY KEY,
    title            TEXT,
    artists          TEXT[] NOT NULL DEFAULT '{}',
    image_url        TEXT,
    duration_seconds INT,     -- songs
    track_count      INT,     -- collections
    fetched_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Written at admission, from the same adapter response that produces the task rows: one row for the
download's own id and, for a collection, one per track. Upserted, never blanked — a playlist
listing a track knows less about it than a direct lookup, so a later answer refreshes but a null
never overwrites a value.

**Keyed by the provider's id, not by download.** Two requests for the same album share one title
and one cover. A track of an album is a song in its own right and may be requested alone later; it
already has its row. The id spaces (videoId, `MPREb_` browse id, playlist id) do not overlap.

**One table, not `songs` and `collections`.** The shapes differ by one nullable column each way.
Two tables would be two joins in every feed query and a type switch at every read site, for that.

**`downloads.song_name` is dropped.** `downloads` shrinks back to the request and its lifecycle:
id, type, status, three timestamps, and a failure reason for the one failure that has no task row.
Pre-existing titles are backfilled into `media_items` first, so no history is lost; rows from before
V5 that have no YouTube id get a synthetic `legacy-<uuid>` one to hang their title on.

**`download_tasks.song_name` stays, and is the Soulseek query, not a title.** It holds
`"Title - Primary Artist"`, the exact string the client sent before requests became ids and the
shape `TrackMatchingService` still splits on. The collections branch had quietly changed this to the
bare title, which would have searched Soulseek for `"One"` — this restores the previous wording.
Rewording how a track is searched for is its own job with its own test; it is not this one.

### Artwork for tracks

The adapter's track shape carries no thumbnail. An album's tracks inherit the album's cover — they
are the album. A playlist's tracks come from anywhere, so the playlist cover would be the wrong
picture; they get YouTube's predictable per-video thumbnail
(`https://i.ytimg.com/vi/{videoId}/hqdefault.jpg`), which is the album art letterboxed into a 4:3
frame. Not pretty, but a picture rather than a blank, and a later direct request for the song
upserts the real artwork over it. The alternative — one adapter call per track — turns one metadata
request into hundreds for a large playlist.

### Lifecycle timestamps and the pre-admission failure reason

`downloads` gains `admitted_at` (written by `createTasks`), `finished_at` (written by
`concludeDownloads` as the last song's `finished_at`, and by `failUnadmitted`), and
`failure_reason` (written only by `failUnadmitted`). The feed reports `failureCode` as
`COALESCE(d.failure_reason, first song's reason)`.

`concludeDownloads` takes its timestamp from the task rows rather than a clock: a download finished
when its last song did, and the statement already has that value in hand.

### `GET /downloads/{id}`: the per-song view

The same card the feed returns, plus every task row as itself, in track order, with the pipeline's
bookkeeping on the wire: stage, progress, failure code, timestamps, how many candidates the search
found and which one this attempt is on, the peer and filename, slskd's last error. The audience is
whoever runs the instance; nothing here is a secret and all of it was already in the table.

Track order needs recording, so `download_tasks.position` is added and written from
`unnest(...) WITH ORDINALITY` in `createTasks`. The earlier plan deferred this "until collections
exist"; they do.

### The feed grows, and one field is renamed

`ActiveDownloadView` gains `youtubeId`, `downloadType`, `artists`, `imageUrl`, `songCount`,
`songsSucceeded`, `songsFailed`, `requestedAt`, `finishedAt`. `songName` becomes `title`, because it
is an album's title as often as a song's. **This is a breaking wire change for `naviseerr-client`.**
The alternative, keeping a misnamed field to avoid a client edit the new fields already force, was
judged not worth it on a branch whose name is a policy.

## Consequences

- Three tables. `downloads` = request and lifecycle; `download_tasks` = one song's pipeline;
  `media_items` = what an id is. Every read that shows a name or picture LEFT JOINs `media_items`;
  a QUEUED download has no media row yet and reports a null title, which is the honest state.
- Admission is three statements: upsert media, create tasks, and the status flip inside the second.
  A crash after the first leaves harmless extra metadata; the next pass redoes both and the upsert
  is idempotent.
- A playlist that lists one track twice produces two task rows and one media row. The repository
  folds duplicate ids before binding, because `ON CONFLICT DO UPDATE` refuses to touch a row twice
  in one statement.
- Backfill correctness on a populated pre-V6 database is asserted by no test: Testcontainers starts
  from empty. The migration is exercised for syntax on every integration test, and the backfill
  statements are three plain `INSERT ... SELECT`s.

## Rejected

- **Client sends the track list.** The client has it from search, but a 500-track playlist is a
  large request body carrying data the server fetches in one call anyway, and it puts a second copy
  of "what is this id" in a second codebase. The id-and-type request the collections branch chose
  stands.
- **`artists` as JSON text**, matching `download_tasks.candidates`. Artists are read on the polled
  path; `TEXT[]` maps to `String[]` with no parsing step, and `jsonb_to_recordset` builds the array
  on the way in for free.
- **Per-track artwork calls.** See above.

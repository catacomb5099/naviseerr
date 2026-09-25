# Collection downloads: one request, N songs

**Date:** 14-09-2026
**Status:** Accepted, implemented

## Context

A download was one song, and the request named it: `POST /download/Riptide%20-%20Vance%20Joy`. The
client glued a title and an artist together with a hyphen because that was the shape the matcher
expected, and the server split the string back apart to guess which half was the artist. The UI knew
a provider convention it should never have heard of, and the server worked from a guess.

Albums and playlists had no route at all, and no shape to fit into: `downloads.song_name` is a column
a playlist has nothing to put in.

## Decision

A `downloads` row is now one user **request**, identified by a YouTube id and a
`DownloadType` (`SONG`, `ALBUM`, `PLAYLIST`). `download_tasks` holds one row **per song**. The track
list is fetched from `ytmusic-adapter` when the loop admits the download, not when the request
arrives.

### One download to N tasks

`download_tasks.download_id` was its primary key, which *was* the 1:1 assumption. It becomes a plain
indexed foreign key and a new `task_id UUID` takes the primary key. Every statement that acts on one
song's state — `CLAIM_DUE_SQL`, `SAVE_SQL`, `FINISH_TASK_SQL` — keys on `task_id`. Keying any of
them on `download_id` would step every song of an album on one song's slskd response.

No third table. The sketch in AGENTS.md's Domain Model Direction anticipated a `CollectionDownload`
alongside `Download`; `download_tasks` going 1:N *is* that, with no new table, no new lifecycle, and
no second code path for the single-song case. A song is a collection of one, and falls out of the
same statements.

### Admission is two statements with an HTTP call between them

It was one atomic statement, and the atomicity was worth something. It cannot stay: what task rows a
download needs is a question only the provider can answer, so a network call has to happen between
finding the row and writing its tasks.

What makes that safe is that the *first* half writes nothing. `admitDownloads` selects and returns;
`createTasks` inserts every task row and flips the status in one statement. A crash anywhere in
between leaves the row exactly as it was, and the next pass picks it up. This is the level-triggered
rule the whole loop rests on, applied to a step that previously did not need it.

Admission now selects `PENDING` only. The old statement covered `IN_PROGRESS` too, purely to be
crash-safe across its own two halves; since the inserts and the flip are still one statement, an
`IN_PROGRESS` download always has task rows and there is nothing to recover.

### The download's status is derived, not written beside the task's

**This is the one place the original design sketch had to be reversed, and the reason is worth
keeping.**

`FINISH_DOWNLOAD_SQL` wrote `downloads.status` and the task's terminal phase in one data-modifying
CTE, so a crash could not split them. Correct, while a download had exactly one task.

With N tasks, the download's status is a function of all N rows, and the obvious extension — have
each finishing task check "am I the last?" — is broken in a way that is easy to miss. Two songs of
one download finishing concurrently each read a snapshot in which the other is still non-terminal.
Neither concludes. The download sits `IN_PROGRESS` forever with every one of its songs finished, and
nothing in the loop will ever revisit it. Making the statement bigger does not help: a
data-modifying CTE's writes are not visible to the rest of its own statement, so the CTE cannot see
the effect of the very write that would change the answer.

So no task decides. `DownloadService.finishTask` settles one song and touches `downloads` not at
all. `DownloadTaskRepository.concludeDownloads` is a separate statement that asks the question of
the whole table:

```sql
UPDATE downloads d SET status = agg.status
  FROM (SELECT t.download_id,
               CASE WHEN bool_or(phase = 'SUCCEEDED') AND bool_or(phase = 'FAILED')
                         THEN 'PARTIAL_SUCCESS'
                    WHEN bool_or(phase = 'SUCCEEDED') THEN 'SUCCEEDED'
                    ELSE 'FAILED' END AS status
          FROM download_tasks t
         GROUP BY t.download_id
        HAVING bool_and(phase IN ('SUCCEEDED', 'FAILED'))) agg
 WHERE d.download_id = agg.download_id
   AND d.status = 'IN_PROGRESS'
```

It runs at the end of every pass, unconditionally. `d.status = 'IN_PROGRESS'` makes it write each
download exactly once and then stop matching, so running it forever costs one indexed statement per
tick. Whichever song finishes last, the next conclude sees the settled aggregate. A missed
conclusion costs one interval, not a download — which is the trade this architecture makes
everywhere else already.

It runs *after* stepping rather than before, so a download whose last song finishes during a pass
reports its outcome on that same tick instead of waiting one interval in a state the feed would
render as `QUEUED`.

`PARTIAL_SUCCESS` needs one of each outcome, so it is unreachable for a single-song download.

### Metadata failures are classified, not retried blindly

`YtMusicService` already separates `YtMusicBadRequestException` (our fault, not retryable) from
`YtMusicUnavailableException` (transient), and that distinction turns out to be exactly what
admission needs. A bad request fails the download immediately with a new `METADATA_UNAVAILABLE`
code; an unavailable sidecar leaves the row `PENDING` for the next pass.

One line changes to make that true: **404 moves from unavailable to bad-request**. A 404 from this
adapter means YouTube Music has no such video, album, or playlist — retrying cannot make an id
exist. Left as "unavailable", one mistyped id would be re-requested every two seconds for the life
of the install. The mapping is right for search too, where a 404 was never a reason to back off and
try again.

A collection that resolves to an empty track list also fails, for the same reason: it is a real
answer, and asking again returns the same nothing.

### The feed still reports one row per download

`ActiveDownloadRepository`'s three queries joined `download_tasks` 1:1. Left alone, a ten-track album
would be emitted as ten cards sharing one `downloadId`, and `/downloads/all`'s `COUNT(*) OVER ()`
would page over songs while claiming to page over downloads.

They now join an aggregate subquery instead. Progress is the mean across songs; the reported stage
is the **least advanced** song's, because the honest summary of mixed progress is the part that is
not done — a collection is "searching" while any of its tracks is. `phase` is ranked to an integer
to take that minimum and mapped back, rather than relying on the alphabetical order of the phase
names, which is not the pipeline's order.

For a single-song download every aggregate is over one row and returns that row's own value, so the
response is byte-identical to before for songs. The aggregate is restricted to the download ids each
branch already selected rather than grouping the whole table, so both branches stay on the indexes
V2 and V4 added.

### `downloads.song_name` stays, nullable

The natural reading of "the request carries only an id" is that the download row holds no name. But
the feed selects `song_name` and the client renders it as the card title, and a download has to have
a title from the moment it appears — including while it is still `PENDING`.

So it survives as the download's **display title**: a song's title, or an album/playlist's title,
written by admission rather than by the request. It is null only between the 202 and the first loop
pass. The per-song titles that word each Soulseek query live on `download_tasks.song_name`; for a
single-song download the two agree, for a collection they do not.

### `type` is a required parameter, not inferred from the id

Albums and playlists are two different adapter endpoints. An album is an `MPREb_` browse id and a
playlist a `VL`-prefixed one, so a prefix check would work today and silently pick the wrong
endpoint the first time YouTube changes a prefix. The client already knows which kind of thing the
user clicked.

`type=SONG` on the collection route is rejected with 400 rather than delegated: a single track has
its own route, and accepting it here would create a download the collection branch cannot resolve.

### `POST /download/{songName}` is deleted, not deprecated

Normally a self-hosted service keeps a route for a release so an old client image keeps working. Not
here: the route inserts a row with no YouTube id, and admission cannot gather metadata for one, so
every download made through it would fail. A shim that reliably produces failures is worse than a
404 — the client cannot tell the difference between "this server is newer than me" and "Soulseek had
nothing", and one of those is actionable.

## Consequences

- The client must send ids. `naviseerr-client` needs the corresponding change; it is out of scope for
  this repository.
- Requesting a download now costs one adapter call before any Soulseek work starts. It is one GET
  per download, on the admission transition only, bounded by `batch-size` per pass.
- A track whose title contains a `/` is requestable for the first time — it broke the path variable
  however it was encoded.
- Per-song visibility inside a collection is **not** exposed. Which track of an album failed is in
  `download_tasks` but no endpoint returns it. That is a new endpoint and a client change, and
  deciding its shape before anyone has used a collection download is guesswork.
- `max-concurrent-transfers` is not scoped per collection, so one album can legitimately hold every
  transfer slot until it is done. That was already the documented intent for collections; it is now
  reachable.
- Rows written before V5 keep working: `download_type` defaults to `SONG`, `task_id` is backfilled by
  `gen_random_uuid()`, and `youtube_id` is null on history that admission will never look at again.

## Alternatives rejected

**A `songs` table, with `download_tasks` holding a `song_id` foreign key.** A fuller design for the
same problem had the *client* push name, artists and artwork into a third table at request time
(there is an unversioned local plan for it dated 31-08-2026). It solves more — artwork on the feed, a
query-builder seam, artist-aware matching — but it needs three migrations, a deprecation window, and a
client change landing in step. Fetching from the provider at admission needs one migration and no new
table, because the provider already has the metadata and the adapter already exposes it. The matching
improvements in that design are independent of this one and still worth doing; see gotchas #2.

**Keeping `download_tasks` 1:1 and making a collection N downloads.** Every `downloads` row would
then be a song again, and the aggregate status problem disappears. Rejected because
`max-concurrent-downloads` counts `downloads` rows: a 500-song playlist would become 500 in-flight
downloads and starve admission for everything else, which is the exact failure the
downloads-not-tasks counting rule exists to prevent.

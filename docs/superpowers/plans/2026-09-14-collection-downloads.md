# Collection downloads: filling in the patch

This implements the WIP patch against `ececa8c`. The strategy in that patch is taken as given: a
download is now *one user request* (a song, an album, or a playlist) identified by a YouTube id, and
`download_tasks` becomes one row **per song**, so a download is 1:N over tasks. Metadata is fetched
from `ytmusic-adapter` at admission time, not at request time.

What follows is only the gap-filling: what each `// TODO` becomes, and the three places where the
patch's sketch does not survive contact with the existing code.

## The one stale assumption in the patch

> `// TODO: each of the getInfo youtube service calls that need to be added, that respective call
> needs to be added to the ytmusicAPI project`

Not needed. `ytmusic-adapter` already serves all three:

| Call | Endpoint | Response |
|---|---|---|
| `getSongInfo` | `GET /v1/songs/{videoId}` | `SongMetadata` — `videoId`, `title`, `author` |
| `getAlbumInfo` | `GET /v1/albums/{browseId}` | `AlbumDetail` — `browseId`, `title`, `year`, `artists[]`, `tracks[]` |
| `getPlaylistInfo` | `GET /v1/playlists/{playlistId}` | `PlaylistDetail` — `id`, `title`, `author`, `tracks[]` |

So this change is confined to `naviseerr`. `PlaylistDetail` has no `year`, so
`YoutubeCollectionInfo.year` is null for playlists.

## Three places the sketch is changed, and why

**1. `DownloadTask` stays a record.** The patch converts it to `@Data @Builder @AllArgsConstructor
@Table("downloadTask")`. Two problems: `@Table("downloadTask")` names a table that does not exist
(it is `download_tasks`, and every statement touching it is hand-written SQL in
`DownloadTaskRepository` — nothing maps it as an entity), and `@Data` makes it mutable, which
`DownloadStateMachine` is documented as depending on not being ("a pure function... no fields, no
I/O"). The intuition behind the change — *stop hand-threading 14 positional arguments* — is right,
and `@Builder(toBuilder = true)` on the record delivers exactly that. It shrinks
`DownloadStateMachine` and `DownloadTask`'s own `with*` methods rather than growing them.

**2. `download_type` is an enum, not a `String`.** The patch's `getCollectionInfo` ends in
`return null`, which NPEs in `gatherMetadata` on any type that is not `ALBUM` or `PLAYLIST`. A
three-value `DownloadType` makes the switch exhaustive with no fallthrough branch, gets the 400 for
free (Spring rejects an unparseable request param), and is fewer lines than the string comparisons
it replaces.

**3. The aggregate download status is computed by the loop, not inside the task's terminal write.**
This is the risk the patch itself flags:

> *verify the logic here but I believe there is a risk of the last two processes both trying to set
> the final status, so they both check for all download tasks related to that download and they both
> see that there is one download task with status in progress, so they both don't conclude*

The risk is real, and it cannot be closed by making the statement bigger. Two tasks of the same
download finishing concurrently each read a snapshot in which the other is still non-terminal, so
neither concludes and the download stays `IN_PROGRESS` with every task finished — a permanently
stuck row. Folding it into one CTE does not help: a data-modifying CTE's writes are not visible to
the rest of its own statement.

What does close it is the architecture already in the repo. `AGENTS.md`: *"never act on a
notification that cannot be regenerated. Repeatedly ask the database what is due and act on the
answer."* So the terminal write stays per-task and atomic, and one extra idempotent statement runs at
the end of every pass:

```sql
UPDATE downloads d SET status = agg.status
  FROM (...GROUP BY download_id HAVING every task terminal...) agg
 WHERE d.download_id = agg.download_id AND d.status = 'IN_PROGRESS'
```

`d.status = 'IN_PROGRESS'` makes it write once and then never again. Whichever task finishes last,
the next statement after it sees the true aggregate. Two concurrent finishers are no longer a race
because neither of them is the one deciding. Worst case a download's terminal status lands one
statement later in the same pass; if even that is missed, the next pass fixes it.

This is a reversal of the patch's "one atomic statement" intuition, and it is worth being explicit
that the intuition was correct for the 1:1 world it was written in — `FINISH_DOWNLOAD_SQL`'s comment
block is about exactly that. It stops being achievable the moment a download has N tasks.

## Decisions made where the patch was silent

**`downloads.song_name` is kept, nullable.** The patch drops it from the entity, but the feed
(`ActiveDownloadRepository.PROJECTION`) selects it and the client renders it as the card title. With
per-song names now on `download_tasks`, the download-level name is the *requested thing's* name — a
song title, or an album/playlist title. It is written by `gatherMetadata`, so it is null only in the
window between the request being accepted and metadata arriving.

**`PARTIAL_SUCCESS`, not `PARTIAL`.** The patch says `PARTIAL`; `AGENTS.md` already commits to
`PartialSuccess` in Domain Model Direction and names widening the status `CHECK` for
`PARTIAL_SUCCESS` as a reason Flyway exists. Following the doc.

**Metadata fetch failures.** `YtMusicService` already separates
`YtMusicBadRequestException` (our fault, not retryable) from `YtMusicUnavailableException`
(transient). Reuse it: a bad request fails the download outright with a new
`METADATA_UNAVAILABLE` code, and an unavailable adapter leaves the row `PENDING` for the next pass.
One line changes in `buildException` — 404 moves from unavailable to bad-request, because a 404 from
this adapter means "no such video/album/playlist", never "come back later". Without that, a mistyped
id is retried against the adapter every two seconds forever.

**The feed keeps its one-row-per-download contract.** 1:N tasks would otherwise emit a 10-song album
as ten identical `downloadId`s. The three feed queries aggregate over the task rows: progress is the
mean, the reported stage is that of the *least advanced* task (a collection is "searching" until
every song is past searching), `updated_at` is the most recent write. For a single-song download —
one task — every aggregate returns that task's own value, so the response is byte-identical to
today's. Per-song visibility inside a collection is deliberately **not** added; that is a new
endpoint and a client change.

**Admission selects `PENDING` only.** The old `ADMIT_SQL` covered `PENDING` and `IN_PROGRESS`
because it had to be crash-safe across its own two halves. Task creation and the status flip are
still one statement, so an `IN_PROGRESS` download always has task rows, and `PENDING` alone is now
sufficient.

## Tasks

### Task 1: Migration `V5__collection_downloads.sql`

- [ ] `downloads`: add `download_type TEXT NOT NULL DEFAULT 'SONG'` with a `CHECK` over
      `SONG`/`ALBUM`/`PLAYLIST`, add `youtube_id TEXT`, drop `NOT NULL` from `song_name`, and widen
      the status `CHECK` to admit `PARTIAL_SUCCESS`.
- [ ] `download_tasks`: add `task_id UUID NOT NULL DEFAULT gen_random_uuid()`, move the primary key
      onto it, add `youtube_id TEXT`, and index `download_id` — it is no longer the primary key, and
      the feed's aggregate and the conclude statement both group by it.
- [ ] Existing rows keep working: `DEFAULT gen_random_uuid()` backfills `task_id` for every row
      already there, and `download_type` defaults to `SONG`, which is what they all are.

### Task 2: Types

- [ ] `DownloadType` enum: `SONG`, `ALBUM`, `PLAYLIST`, with `isCollection()`.
- [ ] `DownloadStatus`: add `PARTIAL_SUCCESS`. `DownloadStage`: add `PARTIAL_SUCCESS`.
- [ ] `DownloadFailureCode`: add `METADATA_UNAVAILABLE`.
- [ ] `Download`: add `downloadType`, `youtubeId`; keep `songName` nullable. One `@Id`, on
      `downloadId` — the patch adds a second on `youtubeId`, which would make R2DBC treat it as the
      identity.
- [ ] `DownloadTask`: `@Builder(toBuilder = true)`, plus `taskId` and `youtubeId` components.
      Rewrite `initial`/`withPhase`/`dueAt`/`withProgress`/`withProgressReset` and the three
      `new DownloadTask(...)` calls in `DownloadStateMachine` through `toBuilder()`. Drop the 13-arg
      legacy constructor; `@Builder` supersedes what it was for.

### Task 3: `YtMusicService` metadata calls

- [ ] `YoutubeSongInfo` and `YoutubeCollectionInfo` as their own files under
      `services/ytmusic/model/`, per the patch's TODO.
- [ ] Three methods, each one `GET`, mapping the adapter's shape to those records. Extract the
      existing `executeSearch` timeout/retry/typed-error pipeline into a generic `execute` so the new
      calls inherit it rather than re-implementing it — `AGENTS.md` names that pipeline the pattern
      new provider calls should follow.
- [ ] 404 → `YtMusicBadRequestException` in `buildException`, with a test.

### Task 4: Repository

- [ ] `admitDownloads(limit)` replaces `admitNewDownloads`: selects `PENDING` downloads with no task
      row, oldest first, `FOR UPDATE SKIP LOCKED`, returning `Download` rows. No writes.
- [ ] `createTasks(downloadId, collectionName, songs, now)`: one statement — `INSERT ... SELECT FROM
      unnest(:youtubeIds, :songNames)` in a CTE, then flip `downloads.status` to `IN_PROGRESS` and
      write `song_name`. Guarded on the download still being `PENDING` with no task rows, so a
      double gather is a no-op rather than duplicate songs.
- [ ] `CLAIM_DUE_SQL` and `SAVE_SQL` key on `task_id` and carry `youtube_id`.
- [ ] `concludeDownloads()`: the aggregate statement above.

### Task 5: `DownloadService`

- [ ] `requestDownload(youtubeId, type)` inserts a `PENDING` row with no name.
- [ ] Delete `claimPendingDownloads` and `markStatusIfInProgress` with their SQL. No production
      caller; the patch deletes their only test.
- [ ] `FINISH_DOWNLOAD_SQL` → `FINISH_TASK_SQL`: same idempotence and same `progress_percent = 100`
      normalisation, keyed on `task_id`, and no longer touching `downloads`.

### Task 6: `DownloadTaskRunner`

- [ ] `admit` → select admissible downloads, then `gatherMetadata` each, bounded by `batchSize`.
- [ ] `gatherMetadata`: song → one task; album/playlist → one per track. Debug log per download with
      the task count; warn and leave `PENDING` on a transient failure; fail the download on a bad
      request.
- [ ] `pass()` gains a third step: `admit → step → conclude`.

### Task 7: Controller

- [ ] `POST /download/song/{songId}` and `POST /download/collection/{collectionId}?type=`, rejecting
      a blank id and a non-collection `type` with 400.
- [ ] `POST /download/{songName}` goes. It inserts a row with no YouTube id, which admission cannot
      gather metadata for, so keeping it as a deprecated shim would only produce downloads that fail.

### Task 8: Feed

- [ ] Aggregate subquery in all three queries; `PARTIAL_SUCCESS` case in `toStage`.
- [ ] Both branches stay index-driven: the aggregate is restricted to the download ids the branch
      already selected, rather than grouping the whole table.

### Task 9: Tests and docs

- [ ] Delete `DownloadServiceClaimIT`.
- [ ] Update fixtures and the ITs for `task_id`, `youtube_id`, and the new admission split.
- [ ] New: metadata gathering for all three types, the conclude statement's three outcomes
      (`SUCCEEDED`/`FAILED`/`PARTIAL_SUCCESS`) and its idempotence, a collection's aggregate feed
      row, and the two new routes.
- [ ] `AGENTS.md` and the affected `docs/architecture/` guides.

## Not in this change

- Per-song progress on the wire. The feed reports a collection as one card.
- Cancellation, `CANCELLED`, `SKIPPED`.
- Album/playlist *search* results carrying ids the client can post — the ids already come back from
  search; wiring the client is `naviseerr-client` work.
- `V6` dropping `downloads.song_name`. It still carries the display title.

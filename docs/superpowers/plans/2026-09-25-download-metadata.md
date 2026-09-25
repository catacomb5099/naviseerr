# Download metadata, artwork, and the per-song view

> Written and executed in one session on the `move-fast-break-things` line. Decisions and their
> reasoning are in [the ADR](../../decisions/download-metadata-25-09-2026.md); this is the task list
> that was worked through, kept so the shape of the change is visible without reading the diff.

## Product asks this answers

1. Download a whole album or playlist as one request, by id. (Landed via the collections branch,
   cherry-picked as the base of this work.)
2. Stop storing a song's name on the download row; store what a download IS somewhere that can hold
   a picture and be shared.
3. Show a picture and timestamps for a download, whether song or collection.
4. Inside a collection, show each song's status and progress, with enough detail for a self-hoster
   who is a developer.

## Product decisions taken without asking

- **Client sends an id and a type, not a track list.** The server fetches everything it needs from
  ytmusic-adapter at admission. One request is one row however large the playlist.
- **A playlist track's picture is YouTube's per-video thumbnail**, not the playlist's cover and not
  a second call per track.
- **The feed's `songName` field is renamed `title`.** Breaking for the client; the new fields force
  a client change anyway.
- **What a self-hoster sees per song:** stage, progress, failure code, three timestamps, candidate
  count and index, retry index, peer, filename, last slskd error. Not exposed: the candidate list
  itself, the search id, the lease.

## Tasks

- [x] `V6__download_metadata.sql`: `media_items`; `downloads.admitted_at / finished_at /
      failure_reason`; backfill titles from both `song_name` columns; synthetic ids for pre-V5 rows;
      `youtube_id NOT NULL`; drop `downloads.song_name`; `download_tasks.position`.
- [x] Adapter models carry `thumbnailUrl` / `lengthSeconds` / `durationSeconds`;
      `YoutubeSongInfo` and `YoutubeCollectionInfo` gain `imageUrl` (+ duration); album tracks
      inherit the cover, playlist tracks get the fallback URL.
- [x] `MediaItem` record; `DownloadTaskRepository.upsertMedia` via `jsonb_to_recordset`, deduped by
      id; `createTasks` drops the title argument, writes `position` and `admitted_at`;
      `concludeDownloads` writes `finished_at`; `failUnadmitted` takes and writes the reason.
- [x] `DownloadTaskRunner.gatherMetadata` writes media rows before task rows; the Soulseek query is
      `"Title - Primary Artist"` again.
- [x] `Download` entity: no `songName`; `failureReason`, `admittedAt`, `finishedAt`.
- [x] `ActiveDownloadRepository`: LEFT JOIN `media_items` in every query; song counts in the
      aggregate; `findSongs`; `toSongStage`. `ActiveDownloadView` reshaped. `DownloadSongView`,
      `DownloadDetailView`.
- [x] `GET /downloads/{id}`.
- [x] Tests: repository IT (media upsert, position, timestamps, reason), feed IT (metadata, counts,
      per-song view, pre-admission failure), runner test (media rows, query wording, keyed by the
      requested id), controller test (detail 200/404), adapter test (artwork inheritance and
      fallback).
- [x] Docs: ADR, `AGENTS.md`, `persistence.md`.

## Not done, deliberately

- Rewording the Soulseek query (`"Artist Title"` instead of `"Title - Artist"`) and teaching the
  matcher about artist lists. Its own change, revertible alone, with a hit-rate test first.
- A migration test against a populated pre-V6 database.
- Client changes in `naviseerr-client`.

# Whole-album downloads

**Date:** 04-10-2026
**Status:** Accepted. A1, A2 and A3 implemented.
**Covers:** A1 (P9, many songs from one sharer), A2 (P5, whole album first), A3 (P6, the biggest part
from one sharer). The joining and tagging half of the same request (B1-B3) has its own ADR.
**Builds on:** `durable-download-state-machine-13-08-2026.md` (the loop, leases, one slskd call per
step), `collection-downloads-14-09-2026.md` (one task row per song), `skip-stalling-sharers-28-09-2026.md`.

## Context

The owner asked for albums to "get downloaded as a whole album if possible, else individual/partial with
metadata joining", with as little waste as possible. Until now an album was N unrelated song downloads:
each song searched Soulseek on its own and took whichever sharer ranked best for that one file, so an
11-track album typically came from 8-11 different people, in different formats and pressings, with a
search per song.

Measured on 04-10-2026 against a live slskd (eight album searches, research notes in the session
scratchpad, trimmed fixtures in `src/test/resources/slskd/`):

- A whole folder existed for every album tried, 72-230 clean ones each, including Talk Talk's
  *Laughing Stock*. Every album search filled slskd's 250-response cap in about 1.5 s.
- "Artist Album" finds far more of the right folders than the title alone when the title is a common
  word (*Discovery*: 86 artist folders by title, 245 with "Daft Punk").
- Deluxe editions sit next to plain ones (*Definitely Maybe*: 32 folders of 40+ files), multi-disc rips
  split into `CD1`/`Disc 2` subfolders, and a sharer sends one or two files at a time and queues the rest.

## Decisions

1. **A1 / P9: one sharer, many songs** (#103). A file waiting in a sharer's queue while that sharer is
   sending us another of our files is neither stalling nor timed out (its clock slides), and at most
   `max-transfers-per-sharer` (2) of our transfers are with one sharer. Without this, an album from one
   sharer would lose every song past the tenth minute. Details in AGENTS.md, "One sharer, many songs".
2. **A2 / P5: whole album first.** An album download first searches for one sharer's folder holding
   every YouTube track, and downloads just those files from it. Below.
3. **A3 / P6: part album next.** No whole folder: the folder with the most tracks (at least half, at
   least two) supplies those, the rest search on their own, and an album song searched on its own must
   match the album track's length. Below.

## How whole album first works (A2)

- **Admission.** `CREATE_TASKS_SQL` also inserts the download's `album_searches` row (same statement) and
  writes the songs *held*: due at now + two search budgets (4 minutes) instead of now, so they do not start
  their own searches. If the album step dies, the hold runs out and every song searches on its own, as
  before. The step re-extends the hold whenever it starts a wording.
- **Search slots.** Album searches use the same two slskd search slots as songs. The runner claims due
  album searches first (`CLAIM_DUE_ALBUM_SEARCHES_SQL`, starts limited to the free slots) and gives the
  songs only the slots left; `COUNT_ACTIVE_SEARCHES_SQL` counts both tables. Starting the album search
  runs inside the same one-at-a-time section as song searches (slskd answers overlapping starts with 429).
- **Wordings.** "Title - Artist" (the cleaned pair a song uses), then the title alone only when the first
  found nobody at all (Soulseek drops every search naming certain artists). A compilation ("Various
  Artists") searches its title only.
- **The step** (`AlbumSearchStep`): start the search; poll the batched search list; when the search is
  complete or has used `search-budget-ms`, fetch its responses once and judge them with
  `AlbumFolderPicker`.
- **The picker.** Reuses the song rules: `TrackMatchingService.grade` must call a file the requested
  version (EXACT) of the track, and the file must pass the same format rule (lossless, or at least
  `min-bit-rate`). New only for albums: files grouped by sharer and folder with disc subfolders merged;
  each file's length within max(10 s, 3%) of the YouTube row's (the last track may run longer: a hidden
  track), files under 30 s rejected; one file per track and one track per file (the title exactly as
  YouTube writes it beats the title without brackets, then the smaller length gap, then the file's own
  track number); a file naming another take ("Sawmills Outtake", "Monnow Valley Version") is not the
  plain track; and the folder must name the album artist in its path or file names, or, for a
  compilation, carry each song's own artist on at least 80% of its files. A folder is whole when every
  song still waiting matched. Whole folders are ranked: overloaded sharers last, then a free upload slot,
  the shortest queue, the fewest extra files, the upload speed; stalling sharers are left out.
- **Release, one statement** (`RELEASE_ALBUM_SONGS_SQL`). Marks the search DONE (only by its lease holder,
  only once) and only then touches the songs that are still untouched: still at the start, no search of
  their own, first wording, no kept files, no live lease. A song the best whole folder holds gets its
  file there plus the same track from the next two later folders of other sharers that have it (whole
  folders; since A3 part folders too), each marked `source: "ALBUM_FOLDER"`, and goes straight to
  downloading. Every other waiting song is due now and
  searches on its own. Both get a fresh clock and no lease.
- **Fallback.** A song whose album-folder files all fail (rejected, stalled, errored past retries) goes
  back to its own search at the first wording instead of ending "sources exhausted"; the runner removes
  the partial files of the folder attempts.
- **Cancel and retry.** Cancelling the whole download ends its album search (`CANCELLED`), so a search
  still running cannot hand files to songs a later retry reopens. Retry is unchanged: failed songs search
  on their own (P8).

## How part album next works (A3)

- **A part folder.** The picker (`AlbumFolderPicker.folders`) keeps a folder that holds every song still
  waiting, or at least half of them and at least two (11 songs: 6; 2 songs: both). Same matching rules as
  a whole folder. Folders are ranked by how many songs they hold first, so every whole folder comes before
  any part, then as before. On the real fixtures: raphyduck's *Definitely Maybe* (10 of 11, no *Live
  Forever*), soneo_app's *Discovery* (11 of 14); bugliker's six of 14 is too few.
- **Release.** When the best folder is a part, the songs it holds get its file plus the same track from
  the next two folders of other sharers that have it (outcome `PART_FOLDER`); every other song is due now
  and searches on its own, even when a later part folder has it, so the album comes from one sharer plus
  single-song searches, not from a patchwork of folders.
- **Album songs on their own search keep the album's length.** A song with a YouTube track number (only
  album rows have one) passes the album row's length to the song picker, which drops every file whose
  length slskd gives and that is outside max(10 s, 3%) of it, before ranking. The matcher reads names
  only, and a live album repeats the studio title and number: Counting Crows' *Live at Town Hall* has
  "03 - Mr. Jones.flac" at 379 s against the album's 270 s. A file with no length (3.5%) stays. This
  applies to every own search of an album song: no folder had it, its folder files failed, or a retry.
  Songs and playlist tracks are unchanged.

## How to flip each choice

| Choice | Where |
|---|---|
| Whole album first at all | `DownloadTaskRunner.gatherMetadata`: pass no hold for `ALBUM` and albums behave as before |
| How long songs wait (2 × `search-budget-ms`) | `AlbumSearchStep.holdUntil` |
| Wordings and their order | `AlbumSearch.wordings` |
| Candidates per song (3, other sharers) | `AlbumSearchStep.CANDIDATES_PER_SONG`, `AlbumFolderPicker.candidates` |
| Last-track allowance, 30 s floor | `AlbumFolderPicker.lengthGap` |
| Words that mark another take | `AlbumFolderPicker.OTHER_TAKE`, `PLAIN_VERSION` |
| Folder ranking (most songs first, then as listed) | the comparator in `AlbumFolderPicker.folders` |
| Part folders at all, and their size (half, at least two) | `fewest` in `AlbumFolderPicker.folders`: `tracks.size()` there means whole folders only |
| Part folder songs only from the best folder | `AlbumSearchStep.settle`: drop the `retainAll` line and a later part folder's songs come from it too |
| Album songs' own searches hold files to the album length | `DownloadTask.albumTrackSeconds`: return null and every song keeps any length |
| Length tolerance (max(10 s, 3%)), shared by both | `SlskdSearchResultProcessor.lengthTolerance` |
| Compilation rule (80%) | `AlbumFolderPicker.COMPILATION_ARTIST_SHARE` |
| File format | `slskd-service.min-bit-rate`, shared with song searches |

## Trade-offs

- **Only the files matched to YouTube's tracks are downloaded**, never the rest of the folder (bonus
  discs, other takes, cover images, cue sheets). That is the main waste lever: a deluxe folder costs no
  more than a plain one.
- **The picker is greedy** (best pairing first), not an optimal assignment (`ponytail:` in the code).
  Tracks of one album rarely compete for a file.
- **A folder must name the artist.** 3-15% of whole folders do not, and are skipped: from the folder
  alone they are indistinguishable from a cover album with the same track list (the fixture has one).
- **A failed start is not retried**; the songs search on their own, which is what they did before.
- **Mixed versions.** An older naviseerr on the same database that saves one of these songs writes its
  candidates without `source`; that song then ends "sources exhausted" as before instead of falling back
  to its own search. Never a loop. Its older search count also ignores album searches.
- **Edge left open.** If every song of an album is cancelled one by one while the album search runs and
  the download is retried within those seconds, the search can still hand the retried songs their folder
  files. Cancelling the whole download does not have this gap.
- **The card says "Starting"** while the album search runs (normally 5-15 s), because the songs have not
  started their own search yet.
- **Matching inherits the song rules.** A track called "Intro" or "Clean" is rejected by the DJ-pool
  filter until F4 lands, so such an album is never whole and those songs search on their own.

- **A part album is still several sharers.** The part comes from one sharer, each missing song from
  whoever its own search finds. Two part folders that together make the album are not combined.
- **The length check needs a length.** An album song whose file slskd sends without a length, or a live
  take within the tolerance (a 279 s bootleg *Mr. Jones* against 270 s), still passes. An album song that
  only exists in another length now fails "no candidates" instead of being filed as the wrong take.

## Not covered (next)

- Song and playlist tracks that B1 joins to an album are not length-checked against the album track
  (they keep whatever length their own search found).
- A folder word the request lacks ("Live at Wembley" in the path, not the file name) is not checked;
  only the length catches such a take.
- Asking a sharer for its whole folder listing (slskd's directory endpoint) when the search returned only
  part of it.

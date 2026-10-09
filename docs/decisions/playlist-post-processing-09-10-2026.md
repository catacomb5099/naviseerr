# Playlist post-processing after retries, and tags from stored YouTube details (09-10-2026)

> Status: accepted. Answers the owner's question of 09-10-2026: "How do retries affect playlist file
> generation? Is there a step in the state machine that post-processes the playlist and enriches the
> metadata from YouTube song details (stored in the payload or a separate table), or if unavailable
> just leaves it as is?" Written from the code at `move-fast-break-things` `0a4da1f` plus the one fix
> below; file references are the source of truth.

## The short answer

- **The step exists.** It is the last two sub-steps of the download loop's 2-second pass
  (`DownloadTaskRunner.pass()` -> `organise()`): **ORGANISE** tags each finished song and moves it into
  the library, recording `download_tasks.library_path`; **FINALISE** writes the collection's `.m3u8`
  whole and stamps `downloads.organised_at`. It is not a stage of its own and has no status word: the
  pass asks the database, every time, "which finished songs have no library path yet" and "which
  finished collections have no playlist stamp yet" (level-triggered, like the rest of the loop).
- **Retries do not break the playlist file.** A retry (whole or one song) and a manual pick reset the
  failed song and clear `organised_at` in the same statement (`RETRY_SQL`, `PICK_SQL`). When the song
  lands it is filed, the collection is concluded again, and the whole file is regenerated from every
  downloaded song that has a file, in track order. Songs already filed keep their files and tags. It
  works days later as well. Nothing has to remember that a retry happened.
- **The YouTube details are in separate tables, referenced by id**, not in the task row's payload:
  `media_items` (one row per YouTube id: title, artists, artwork, length; for albums also year, type,
  track count), the task row's own `track_title` / `track_number` / `duration_seconds` (written at
  admission, never changed), and `song_albums` (the song's trusted album, found by a separate lookup
  loop). Filing joins them in SQL (`TASKS_TO_ORGANISE_SQL`) and never calls YouTube; the only network
  call while filing is the cover image fetch. Chosen on 25-09-2026 (`download-metadata-25-09-2026.md`)
  and 04-10-2026 (`youtube-album-tags-04-10-2026.md`).
- **When details are missing, the file is left as it is.** No trusted album within two minutes of the
  song finishing: filed under its own name, title and artist filled only where the file had none, no
  album tag touched. Cover unreachable: filed without one. Untaggable or unreadable file: filed exactly
  as it came. A song is never failed for a tagging or filing problem, and a filed file is never
  re-tagged or moved later (a late album answer is ignored, by the 04-10 decision).

## What was missing, and the decision

Three gaps, none of them a missing step:

1. **A millisecond hole** (recorded in the retry ADR on 07-10-2026, not closed then). `CONCLUDE_SQL`
   takes one snapshot of the song rows and then writes the collection's status. A one-song Retry that
   committed between the two left the collection "finished" with a live song; FINALISE then wrote the
   playlist **without** that song and stamped it, and when the song landed nothing cleared the stamp.
   **Decision: close it with one level-triggered statement, `REOPEN_SQL`, run by `concludeDownloads()`
   before `CONCLUDE_SQL` on every pass** (naviseerr PR #132, `fix/ai-playlist-reconclude`): a
   `SUCCEEDED` / `PARTIAL_SUCCESS` / `FAILED` collection with a song that is not terminal goes back to
   `IN_PROGRESS` with `finished_at`, `failure_reason` and `organised_at` cleared, the columns
   `RETRY_SQL`'s reopen clears. Idempotent (a reopened collection has a live song, so it stays open until
   the song settles), organiser-independent (with the organiser off it simply concludes again), and it
   also covers any future path that revives a song without going through `RETRY_SQL` / `PICK_SQL`. The
   same PR makes `PLAYLIST_ENTRIES_SQL` list `SUCCEEDED` rows only, so a song reset in place while still
   carrying an old path is not listed until it lands again.
2. **No test pinned the whole chain.** This record's PR adds
   `afterARetriedSongIsFiled_theDownloadIsFinalisedAgain_andThePlaylistEntriesIncludeIt`
   (`DownloadTaskRepositoryIT`) and a stage in the sealed end-to-end check (`scripts/e2e/playlist-file.sh`,
   step 4b) that retries a failed song through the real API, simulates its landing, and checks the
   `.m3u8` is rewritten byte-exact with four lines and that Navidrome imports four songs.
3. **The answer was written down nowhere.** This file; `download-manager.md` has the paragraph
   "Post-processing after a retry or a pick".

### Rejected

- **A POST_PROCESSING stage or status.** The stage words are the client's whole contract and a new
  `downloads.status` value is a CHECK-constraint change (DDL). No user benefit: the card would say
  "Post-processing" for the two seconds a file move takes.
- **A scheduled post-processing pass of its own.** `pass()` already is the 2-second, level-triggered
  scheduler; running the step inside it, after conclusion and serialised with the steps, is what makes
  "same tick" and "no race with a step mid-flight" true.
- **A payload column with the YouTube details on the task row.** `media_items` keyed by YouTube id is
  shared by every download of that id and refreshed on every later answer; a per-row copy would go stale.
- **Re-tagging after a late album answer.** Needs a marker column (DDL) and re-opens files inside the
  library while a scanner may read them. Kept as decided on 04-10-2026.

## Product choices (defaults in bold)

1. **A finished collection with a song live again reopens on the next pass** (PR #132). Flip: drop
   `REOPEN_SQL`; the retry and pick statements keep reopening on their own and the race stays open.
2. **A rewritten playlist lists every filed song, even one whose file was moved or deleted since.**
   Navidrome and Jellyfin skip such lines. Flip: a file-exists check in
   `LibraryOrganiser.writePlaylistBlocking` (three lines, one unit test).
3. **Tags are written once, at filing; a late album answer is never applied.**
4. **A succeeded song whose file has not appeared within 10 minutes is left out for good**
   (`LibraryOrganiser.GIVE_UP_AFTER`).
5. **A fully downloaded playlist cannot be re-processed from the app** (Retry matches failed songs only).
   Flip: a `POST /downloads/{id}/organise` that clears `organised_at`; the next pass rewrites the file.
6. **Post-processing is invisible in the app** (the card says "Downloaded" while the file is being
   filed). Flip: the client reads `DownloadSongView.libraryPath`, already on the wire.

## Evidence

- Retry and pick clear the stamp: `retry_resetsOnlyFailedSongs_reopensTheDownload_andClearsOrganisedAt`,
  `retry_ofOneSong_ofAConcludedDownload_resetsThatSongOnly_andReopensIt`,
  `pick_reopensAPartlyDownloadedDownload_andClearsItsPlaylistStamp_likeRetry`.
- The stamp never lands on a reopened download: `setOrganisedAt_afterARetry_updatesNothing`.
- Finalise picks a finished, unstamped collection with filed songs: `downloadsToFinalise_*`;
  the file is regenerated whole: `LibraryOrganiserTest.writePlaylist_*`,
  `DownloadTaskRunnerTest.aFinishedPlaylist_getsItsPlaylistFileWritten_thenIsStampedOrganised`.
- The reopen: `concludeDownloads_reopensAFinishedDownloadWhoseSongIsLiveAgain_andClearsItsPlaylistStamp`,
  `concludeDownloads_leavesAFinishedDownloadWithNoLiveSongAlone`, `playlistEntries_listsOnlySucceededSongs` (PR #132).
- The chain: the test and the e2e stage named above.
- Seen running (demo of 09-10-2026, `sweep-1009-notes/demos/playlist-postprocess/`): a seeded playlist
  "2 of 3 downloaded, 1 failed" with a 2-line `.m3u8`; Retry clicked in the app; the landing simulated;
  three seconds later `Concluded 1 download(s)`, `Filed song ...`, `Wrote playlist ... with 3 track(s)`;
  `ffprobe` shows the first song's album, year and track number written from the stored YouTube album
  and the no-album songs left with title and artist only. On `move-fast-break-things` a collection put
  in the race's state stayed `SUCCEEDED` and stamped through four passes; on the branch the next pass
  logged `Reopened 1 finished download(s) whose song is live again`.

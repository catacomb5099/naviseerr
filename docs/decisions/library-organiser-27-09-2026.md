# Library organiser: filing finished downloads where Navidrome and Jellyfin can find them

**Date:** 27-09-2026
**Status:** Accepted, implemented in two PRs (per-song filing; then the playlist file)
**Builds on:** [download-metadata-25-09-2026.md](download-metadata-25-09-2026.md)

## Context

slskd drops every finished file into `/downloads/<last folder of the peer's path>/<file>`. A single
song lands in a folder named after whatever the peer called its folder; ten songs of one playlist land
in ten unrelated folders; a failed transfer leaves a partial file in `/incomplete/<peer>/...` forever.
None of that is a library. Navidrome and Jellyfin can be pointed at `/downloads`, but Jellyfin makes an
"album" out of every folder it finds and nothing tells either server that ten scattered files were one
playlist.

The owner's need: a finished download should show up in Navidrome or Jellyfin with no manual work.

## Decision

### A fourth step in the download loop, not a callback

Filing is a step in `DownloadTaskRunner.pass()` after `concludeDownloads`, driven by one column added
in V8: `download_tasks.library_path` (where the file ended up; null until then). Every pass asks the
table "which finished songs have no `library_path` yet?" and moves those. A crash before the move costs
one pass. A crash between the move and the write leaves the file correctly in the library and the row
without a `library_path`: the next pass no longer finds slskd's copy (it *is* the library file) and
gives the row up after ten minutes, so nothing is duplicated but the row never records where its file
went. A crash between the copy and the final rename leaves `<name>.<task id>.partial` in the library,
which is not retried -- rename it by hand. Nothing is held in memory, nothing reacts to an event that
cannot be regenerated -- the same level-triggered rule the rest of the loop already follows.

One column rather than a `library_files` table: it is one fact about a row that already exists,
written once by the loop that owns those rows. A table would be one more join for one column. The
playlist PR adds whatever it needs for itself.

### Off by default; on only when both folders are configured

`library.slskd-downloads-dir` (env `SLSKD_DOWNLOADS_DIR`) is where slskd's downloads folder appears on
naviseerr's machine; `library.root` (env `LIBRARY_ROOT`) is where the library goes. With either unset
the organiser logs one line at startup and the loop is exactly what it was before. **This requires
slskd's downloads folder to be visible to naviseerr** -- same host, or the same volume mounted into
both containers. naviseerr moves files itself rather than asking slskd's files API to, because that API
only deletes and only with `RemoteFileManagement` enabled, and because a move across a shared volume is
the same operation whether the two are one container or two.

The optional third folder, `library.slskd-incomplete-dir` (`SLSKD_INCOMPLETE_DIR`), turns on removal of
the partial files a song that failed for good left in slskd's incomplete folder.

### Where slskd put the file

slskd 0.26's rule (`DownloadService.DeriveDestination`, `${SOURCE_DIRECTORY}` pattern): the file lands
at `/downloads/<last segment of the remote directory>/<remote file name>`, where the remote path is
split on both `\` and `/`, `@@xxxxx` share roots, drive letters and `..` are dropped, and on Linux no
character other than NUL is altered. A file shared at the peer's root has no folder and lands directly
in `/downloads`. `LibraryOrganiser.locate` computes exactly that path first. If it is missing, the one
other name slskd's default `exists: rename` can produce is looked for **in that same folder only**:
`<stem>_<ticks><ext>`, newest first. Nothing wider: a same-named file in another folder is another
song's (two albums both with `01 - Intro.flac` is routine), and the first version of this ADR searched
the whole downloads folder for the name, which would have swapped such files between albums. If the
operator changed slskd's subdirectory pattern the file is simply given up on, below.

A finished song's file may not be there yet: slskd reports `Completed, Succeeded` from the transfer
callback and moves the file out of `incomplete` afterwards. So a missing file is not an error; the row
is left for the next pass. After ten minutes (`GIVE_UP_AFTER`) it is warned about once and never looked
for again: the query's `finished_at > cutoff` filter is both the give-up rule and what stops the
organiser from trawling every success in the install's history the day it is switched on. The same
window is a hard deadline on naviseerr itself: songs that finish while naviseerr is stopped, or cannot
see the downloads folder, for longer than ten minutes are left where slskd put them and never filed.

### Folder scheme

| Download | Target | Named from |
|---|---|---|
| Song | `<root>/<primary artist>/<song title>/<file as downloaded>` | the song's `media_items` row |
| Album track | `<root>/<album artist>/<album title>/<file as downloaded>` | the album's `media_items` row |
| Playlist track | `<root>/<track's primary artist>/<track title>/<file as downloaded>` | the track's `media_items` row |
| Playlist itself | `<root>/Playlists/<playlist title>.m3u8` (PR 2) | the playlist's `media_items` row |
| Album itself | nothing extra | -- |

The file keeps the name the peer gave it (it usually carries the track number and is unique within
its folder), sanitised. Every component naviseerr creates goes through `sanitise`: Windows-illegal
characters and control characters become `_`, whitespace collapses, leading and trailing dots go,
Windows device names get a `_` prefix, at most 200 bytes of UTF-8 cut on a character boundary, NFC.
The strictest rule set, so the library is safe to export over SMB. Empty or unknown names fall back
to `Unknown Artist` / `Unknown Album` / the file's own stem.

**Why one folder per song, not a `Singles` dump.** Jellyfin turns any folder that directly contains
audio into an album named after the first track's Album tag. A shared `Singles` folder would be one
"album" named after whichever song arrived first. Navidrome ignores folders entirely (tags only), so
the per-song folder costs it nothing. Songs are never left directly in `<root>`: Jellyfin builds no
album for root-level files.

**Why an album's folder is `<album artist>/<album title>`.** It is exactly Jellyfin's documented "one
folder, one album" rule, and gives Navidrome's folder browser something sensible. Multi-disc sets are
flattened into the one folder (disc-number tags carry the ordering; Jellyfin only recognises disc
subfolders that match its naming patterns).

**Why playlist tracks are filed like songs and NOT kept in one `Playlists/<name>/` folder.** This
deviates from the "one folder per collection" starting point, on the research's strong recommendation:
Jellyfin would show `Playlists/<name>/` as a fake album named after the first track's Album tag, and
Navidrome would scatter the tracks into their tagged albums anyway, so the folder would buy nothing
there either. Instead the tracks go where a single download of each would have gone, and the playlist
is one `.m3u8` in `<root>/Playlists/` pointing at them with `../<artist>/<title>/<file>` entries. Both
servers resolve relative entries against the playlist file's own folder, both name the playlist after
the file, both re-import it on every scan. A `Playlists` folder holding only `.m3u8` files is not an
album to Jellyfin. The alternative not chosen -- one folder, `.m3u8` inside it -- works in Navidrome
and produces a wrong album in Jellyfin.

**The playlist file.** Written by the finalise step once every succeeded song of the download has a
`library_path` or has been given up on, and at least one was filed (`DOWNLOADS_TO_FINALISE_SQL`); then
`downloads.organised_at` is stamped. The file is naviseerr's and is regenerated whole each time, which
is what makes rewriting it idempotent -- Jellyfin may rewrite it if a user edits the playlist in its UI,
and the next regeneration simply wins.

```
#EXTM3U
#PLAYLIST:Alt Nation 1989
#EXTINF:210,Pixies - Here Comes Your Man
../Pixies/Here Comes Your Man/05 Pixies - Here Comes Your Man.flac
#EXTINF:-1,Devo - Whip It
../Devo/Whip It/02-devo-whip_it.mp3
```

UTF-8 without BOM, LF, forward slashes, NFC, one path per line relative to the folder holding the
file (both servers join against that folder; absolute container paths break when a mount moves).
`#PLAYLIST` names it in Navidrome; Jellyfin ignores it and uses the file name, which is the same
sanitised title. `#EXTINF` is a comment both servers ignore in favour of the tracks' own tags;
duration is `-1` when unknown. Staged as `.partial` and renamed into place. Entries whose path is not
under `library.root` (a reconfigured root) are skipped with a warning rather than written as dangling
lines. A song or an album has no collection-level file and is just stamped `organised_at`.

**Why no `album.nfo`.** Navidrome never reads nfo files. Jellyfin reads `album.nfo` only when its Nfo
reader is enabled and then overwrites the album name from the first track's tag anyway, so it cannot
fix an untagged album. Nothing to write. A `cover.jpg` would help both servers and is a follow-up
(copy the peer's `cover.*`/`folder.*`/`front.*`, or fetch `media_items.image_url`).

### Never overwrite, never follow, never leave the fence

- A target that already exists gets a ` (2)`, ` (3)`... suffix before the extension. Two downloads
  of the same song from different peers are two files; deciding which is better is not this step's job.
- Across filesystems `Files.move` is a copy then a delete, so the file is staged as
  `<name>.<task id>.partial` and renamed into place atomically. A scanner never indexes a half-copied
  audio file, and two songs filing into one folder (the same song twice in a batch) never share a
  staging file. The free ` (2)` name is chosen right before the rename, not before the copy.
- Symlinks are never followed (`NOFOLLOW_LINKS` everywhere).
- Every constructed path is normalised and checked with `startsWith` against the folder it must stay
  inside; `..`, `.`, `@@` roots and drive letters are dropped before a path is even built.
- The organiser refuses to start (ERROR, disabled) if `library.root` is inside either slskd folder or
  contains one -- it would move files onto themselves.
- After a move the emptied `/downloads/<folder>` is removed; `/downloads` itself never is.

### Failed leftovers

slskd keeps a failed transfer's partial file at `/incomplete/<peer>/<full remote folder path>/<file>`
so a retry can resume it (`transfers.download.retry.partial`). Its `DELETE .../transfers` removes the
record only. naviseerr's own failover means every peer tried for a song may have left one. When a song
finishes `FAILED` for good -- `finishTask` updated a row, so this is the first finish, not a duplicate
-- the partial file of every candidate up to the current one is deleted, and the emptied folder chain
above it up to (never including) the incomplete folder. Best effort: a missing file or a permissions
error is a log line. This one is inline on the terminal write rather than a level-triggered step
because there is no column to mark it done and a missed cleanup costs one partial file, which is
exactly today's behaviour.

## What still needs tags

Navidrome groups entirely by embedded tags and ignores every folder and file name above. A file with
no `ALBUM` tag is `[Unknown Album]`; no `ARTIST` is `[Unknown Artist]`; tracks of one album with
inconsistent tags split into several. Jellyfin is folder-anchored, so the layout above always yields an
album per folder, but its artist views come from tags too. Playlists in both servers show tag-derived
titles per track. Soulseek files are overwhelmingly tagged already; this ADR ships the folder scheme
and leaves tag-writing (fill only empty `TITLE`/`ARTIST`/`ALBUM`/`ALBUMARTIST`/`TRACKNUMBER` from
`media_items`, never overwrite, before the move) as a separate decision once a count of untagged
downloads shows it is needed.

## Not decided / follow-ups

- Duplicate detection: a playlist track already in the library is downloaded again and filed as ` (2)`.
- Two playlists with the same title share one `.m3u8`; the later one wins.
- `cover.jpg` for albums (both servers read it).
- A crash between the move and the `library_path` write leaves the file correctly filed and the row
  with no `library_path` (abandoned after ten minutes). No duplicate; the row just does not know.
- slskd hosted on Windows replaces `? : *` and friends with `_` in local names; `locate` uses the raw
  remote name, so those files would never be found. Windows-hosted slskd is unsupported here.
- Case-insensitive collision folding (`Pixies` vs `pixies` become two folders on Linux, one on SMB).
- Abandoned partials from before the organiser was switched on are not swept.

## Evidence

- slskd 0.26.0 release notes: `${SOURCE_DIRECTORY}` = the immediate parent directory on the peer;
  `exists: rename` appends a number. https://github.com/slskd/slskd/releases/tag/0.26.0
- `DownloadService.cs` (0.26.0): destination derivation, incomplete path
  `incomplete/<username>/<full remote dir>/<file>`, `MoveFile` on success, catch blocks only
  `TryFail()` -- nothing deletes a partial.
  https://raw.githubusercontent.com/slskd/slskd/0.26.0/src/slskd/Transfers/Downloads/DownloadService.cs
- `FileService.cs` (0.26.0): collision name `{stem}_{DateTime.UtcNow.Ticks}{ext}`; deletes the
  emptied source parent only. https://raw.githubusercontent.com/slskd/slskd/0.26.0/src/slskd/Files/FileService.cs
- `FileSafety.cs`: both separators, `@@` roots, drive letters, `..` dropped; on Unix only NUL and `/`
  are invalid. https://raw.githubusercontent.com/slskd/slskd/master/src/slskd/Common/FileSafety.cs
- `TransfersController.cs`: `DELETE ...?remove=true` removes the record, not the file.
  https://raw.githubusercontent.com/slskd/slskd/master/src/slskd/Transfers/API/Controllers/TransfersController.cs
- `FilesController.cs`: deletes need `RemoteFileManagement`, 403 otherwise.
  https://raw.githubusercontent.com/slskd/slskd/master/src/slskd/Files/API/FilesController.cs
- Navidrome tagging: organised entirely from tags; folders ignored.
  https://www.navidrome.org/docs/usage/library/tagging/
- Navidrome options: `PlaylistsPath` default empty = import from anywhere in the library.
  https://www.navidrome.org/docs/usage/configuration/options/
- Navidrome `parse_m3u.go`: `#PLAYLIST:` sets the name, other `#` lines skipped, relative paths joined
  to the playlist's folder, non-audio and missing entries dropped with a warning.
  https://raw.githubusercontent.com/navidrome/navidrome/master/core/playlists/parse_m3u.go
- Navidrome `import.go`: an existing playlist found by path is updated in place.
  https://raw.githubusercontent.com/navidrome/navidrome/master/core/playlists/import.go
- Navidrome fallbacks `[Unknown Album]`, `[Unknown Artist]`:
  https://raw.githubusercontent.com/navidrome/navidrome/master/model/metadata/persistent_ids.go,
  https://raw.githubusercontent.com/navidrome/navidrome/master/consts/consts.go
- Navidrome artwork: `cover.*, folder.*, front.*` read; no nfo. https://www.navidrome.org/docs/usage/library/artwork/
- Jellyfin music: "one folder containing one and only one album"; metadata from embedded tags.
  https://jellyfin.org/docs/general/server/media/music/
- Jellyfin `MusicAlbumResolver.cs`: a directory with audio directly inside is an album; the library
  root is excluded.
  https://raw.githubusercontent.com/jellyfin/jellyfin/master/Emby.Server.Implementations/Library/Resolvers/Audio/MusicAlbumResolver.cs
- Jellyfin `AlbumMetadataService.cs`: album name from the first child's Album tag.
  https://raw.githubusercontent.com/jellyfin/jellyfin/master/MediaBrowser.Providers/Music/AlbumMetadataService.cs
- Jellyfin `PlaylistResolver.cs` / `PlaylistItemsProvider.cs`: `.m3u8` in a music library becomes a
  playlist named after the file; entries resolved against the playlist's folder; missing entries dropped.
  https://raw.githubusercontent.com/jellyfin/jellyfin/master/Emby.Server.Implementations/Library/Resolvers/PlaylistResolver.cs,
  https://raw.githubusercontent.com/jellyfin/jellyfin/master/MediaBrowser.Providers/Playlists/PlaylistItemsProvider.cs
- Jellyfin nfo docs: genre ignored for music; `AlbumNfoProvider` is generic.
  https://jellyfin.org/docs/general/server/metadata/nfo/
- Jellyfin does not read `#PLAYLIST`: https://features.jellyfin.org/posts/3104/support-playlist-field-in-m3u-playlists
- Jellyfin rewrites m3u8 paths when edited in its UI: https://github.com/jellyfin/jellyfin/issues/1874
- Windows naming rules (reserved characters, device names, trailing dots, MAX_PATH):
  https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file
- Per-component length limits by filesystem: https://ss64.com/nt/syntax-filenames.html

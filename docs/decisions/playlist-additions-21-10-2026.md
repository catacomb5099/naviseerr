# Add a song to your own Navidrome/Jellyfin playlist once it is downloaded (21-10-2026)

> Status: accepted. Owner's ask of 21-10-2026 (ask 2 redone, Feishin): "Add to playlist" on your existing Feishin
> (Navidrome/Jellyfin) playlists works for songs you do not have: it downloads them, and a post-processing step adds
> them to that playlist. Written from `move-fast-break-things` `2bc4f6a` + the S3 branch, Navidrome 0.64.2 run live.
> Plan: `sweep-1020-notes/DECISIONS-FEISHIN.md` sections 2.3 (S4) and 3.

## The short answer

- **Two calls from the player.** Feishin asks for the download as it always did (`POST /download/song/{id}` or
  `/download/collection/{id}`), takes `downloadId` from the 202 or the 409, then posts
  `POST /playlist-additions {server, playlistId, userId, items:[{downloadId, youtubeId}]}`. The download routes are
  untouched; a player that dies between the two calls leaves a download without an addition, which does no harm.
- **One row per song and playlist** (`playlist_additions`, V23). Asked again = the same row, re-armed (whatever it
  had become: the "already in the playlist" check below makes that safe).
- **Its own loop, `mediaserver.PlaylistAdder`, every 15 s** (`media-servers.playlist-additions-interval-ms`): rows
  whose song is filed (or whose download ended without that song) are handled oldest request first, then album order.
  For each: find the song in the media server with S3's rule (`SongFinder`: Jellyfin by path, Navidrome by path or
  artist + length), read the playlist, add the song unless it is already there, stamp `added_at`.
- **Waiting states are derived on every read** from the song's task, never stored: `WAITING_FOR_DOWNLOAD` (live, or
  done and not filed yet), `DOWNLOAD_FAILED` (failed or cancelled; a retry resets the task in place, so the addition
  simply waits again), `WAITING_FOR_MEDIA_SERVER` (filed, not seen yet), then `ADDED` or `GAVE_UP` with a reason.

## Failure

| What happened | Row becomes | Retried? |
|---|---|---|
| media server down, timeout, HTTP error | same row, `last_error` | yes, back-off |
| media server does not list the song yet | same row | 15 s, 30 s, 1 min, 2 min, 5 min, then every 10 min; the 3rd miss also asks for a rescan; `GAVE_UP NOT_FOUND_IN_MEDIA_SERVER` 24 h after filing |
| playlist deleted (Navidrome Subsonic 70, Jellyfin 404) | `GAVE_UP PLAYLIST_GONE` | no |
| playlist refuses songs (Navidrome Subsonic 50, Jellyfin 403) | `GAVE_UP NOT_EDITABLE` | no |
| that server was removed from naviseerr's Settings | `GAVE_UP MEDIA_SERVER_NOT_CONNECTED` | no |
| the download finished without that song | `GAVE_UP NOT_IN_DOWNLOAD` | no |

Each give-up is one WARN. The request itself is refused, with nothing stored, when the download does not exist
(404 `DOWNLOAD_NOT_FOUND`), the server is not set up (409 `MEDIA_SERVER_NOT_CONNECTED`), naviseerr's server has no
such playlist (409 `PLAYLIST_NOT_FOUND`: also what a Feishin on another server gets), the playlist already says it
cannot change (409 `NOT_EDITABLE`), or the server does not answer (502 `MEDIA_SERVER_UNREACHABLE`).

## Measured on Navidrome 0.64.2 (sweep20-navidrome-2, 21-10-2026), with an admin login

- `updatePlaylist?songIdToAdd=` on the admin's own playlist: ok.
- On another user's playlist: ok (Navidrome lets an admin change any playlist). `getPlaylist` shows such a playlist as
  `readonly: true` to the admin even so, which is why "not editable" at request time is only trusted for a playlist
  naviseerr's own account owns.
- On an imported `.m3u8` playlist (`readonly: true`, owner the admin): Subsonic error 50 "User is not authorized for
  the given operation" = `NOT_EDITABLE`.
- `getPlaylist` of an unknown id: error 70 "playlist not found"; `updatePlaylist` of one: error 70 "data not found".

Jellyfin (`GET/POST /Playlists/{id}/Items?userId=`, the API key acting as the playlist owner, whose id comes from the
player because a key has no user of its own) is covered by tests against recorded answers, not run live this round.

## Choices (flip in one place)

- **Order:** each song goes in as soon as it is ready (an album is filed together, so it lands in album order). Flip:
  make a row wait while an older unfinished row for the same playlist exists (`PlaylistAdditionRepository.DUE_SQL`);
  one stuck song then holds the rest.
- **A whole YouTube playlist into your playlist:** the player posts one item per song. Flip: expand a playlist id on
  the server (ask 1's `TrackLists`) if a player ever wants to send one id.
- **The music folder (organiser) must be on:** without it no song is ever filed, so nothing is added.

## Not done

- No push from the organiser to the adder: a fixed 15 s poll (one indexed SELECT while idle).
- Album twins: the song's own filed file is the key; a copy under another album is not looked for.

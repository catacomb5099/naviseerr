# An artist's top songs: five, then ten, then the whole list (20-10-2026)

## The problem

The owner asked for the artist page's top songs to show 5, expand to 10, and offer a "Show all". The web app already had
"See more" past five, but it never appeared: the adapter sent only YouTube's artist-page shelf, which lists five songs.

## The decision

- YouTube keeps a "Top songs" playlist for every artist (the songs shelf's `browseId`, `VLOLAK5uy_…`, about 150 songs).
  Its first five rows are the page's five, in the same order (checked on four artists on 20-10-2026). The adapter
  reads that playlist next to the discography and sends its first ten rows as `topSongs`; when the call fails it sends
  the page's five as before.
- The playlist's rows carry YouTube's combined play count ("1.7B plays"), which ytmusicapi throws away on playlists; the
  adapter keeps it (ytmusicapi repo, `app/ytmusic_client.py`). So the ten songs need no album lookups, and the full
  list opened from "Show all" shows plays on every row with no per-song `/songs/views` calls.
- `GET /artists/{id}` passes the playlist's bare id through as `topSongsPlaylistId`. "Show all" in the web app opens
  the normal playlist page (`/playlist/{id}`), with its "Download playlist" button. That is what YouTube Music's own
  "Show all" opens (Apple Music's "Top Songs >" does the same job). Spotify stops at ten.
- YouTube titles every artist's list just "Top songs", so two downloaded ones would write the same `Top songs.m3u8`.
  The adapter names it "<artist>: Top songs" ("Oasis: Top songs"), which the playlist page header and the downloaded
  file then share.

## How to flip

- Rows before "See more": `COLLAPSED_SONGS` in the web app's `ArtistPage.tsx` (5).
- Rows after "See more": `TOP_SONGS_LIMIT` in the adapter's `app/mappers.py` and `ArtistView.MAX` here (10).
- No "Show all": drop the link in `ArtistPage.tsx`, or send no `topSongsPlaylistId`.
- Keep YouTube's bare "Top songs" title: drop the rename in the adapter's `map_playlist_detail`.

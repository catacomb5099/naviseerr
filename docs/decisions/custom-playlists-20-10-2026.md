# Your own playlists (20-10-2026)

> Status: accepted. Owner's ask 1 of 20-10-2026: make your own playlists in the app from any song, album or playlist,
> put them in your order, give them a picture, and download them with "Create". Research in the sweep notes
> (`research/custom-playlists.md`); the owner questions it raised were answered with their defaults (below).

## The problem

Naviseerr downloaded only lists someone else made: a YouTube Music playlist, a suggested playlist, a radio. There was no
list you own and edit, no way to collect songs from several albums into one playlist, and no picture of your own.

## The decision

- **A draft, then "Create".** A playlist starts as a draft you edit freely (`playlists` row, V20, `downloaded_at` null).
  "Create" downloads it as a `CUSTOM` download whose id is the playlist's UUID; from there the existing pipeline does
  everything a YouTube playlist gets: one task row per song in your order (`position`), songs you already have are not
  downloaded again, filing, the `.m3u8` in that order, and the picture for Navidrome and Jellyfin.
- **Created = frozen** (owner question 2, default a). Every edit answers 409 once created; Duplicate makes a new draft
  "Copy of <title>" to change. Follows the radio rule: what you saw is what downloads. Flip: drop `AND downloaded_at IS
  NULL` from the edit statements in `PlaylistRepository` and add an "Update" that requests a new CUSTOM download.
- **No song twice** (owner question 3, default a). Adding skips songs already in the playlist and says how many
  (`alreadyIn`); Spotify asks "Add anyway?" instead. Flip: append without the `NOT EXISTS` in `APPEND_SQL`, and key the
  order/remove routes by position instead of video id.
- **Songs are added by id** (a song, an album, a YouTube playlist, a suggested playlist, a radio, another of your
  playlists, or only some songs of one) and resolved on the server by `download.TrackLists`, the same switch admission
  uses, so "add this album" adds exactly the songs "download this album" would.
- **Names**: a blank title is "My playlist #N" (Spotify), 1-100 characters; description 0-300 (Spotify's limits);
  Duplicate is "Copy of <title>". In the app only: the `.m3u8` carries the title alone, and neither Navidrome nor Jellyfin
  reads a description from a file. Constants in `PlaylistController`.
- **At most 5,000 songs** (YouTube Music's own limit); an add that would pass it adds nothing.
- **Order** is the list order. Reordering sends the ids in the new order; songs not listed keep their place after
  them, so a tab that missed a song added elsewhere never drops it.
- **Last edited first** for the list (Spotify's "Recents").
- **Delete** only a draft: a created playlist is in your music app and your downloads, which are never deleted either.
- **No user names** on a download of your playlist (auth threat model): its author line is empty.

- **Your own picture** (owner question 4, default a). The web app takes a JPEG, PNG or WebP, crops the centre square,
  shows a preview and sends a 1200 px JPEG (the size album covers use); the server keeps it in the playlist's row (V20
  `cover`) and only checks it is a JPEG of at most 4 MB (Spotify's apps take 4 MB too). In the row rather than a file:
  the naviseerr container's config is read-only, Duplicate copies it in one statement, and it is backed up with the
  database. No picture = the 2x2 mosaic of the first four different song pictures (Spotify, YouTube Music; Navidrome and
  Jellyfin draw the same). A created playlist keeps its picture (409). Flip: `MAX_COVER_BYTES` in `PlaylistController`.

## Not decided here

- Two playlists with one title (two of yours, or yours and a YouTube one) write the same `Playlists/<title>.m3u8`, the
  last one finalised wins (known since library-organiser-27-09-2026). The web app can warn on your own titles; a real
  fix (a suffix on a clash) touches every playlist type and is not built.

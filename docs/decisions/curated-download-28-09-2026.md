# Download a suggested playlist as one download: the CURATED type

**Date:** 28-09-2026
**Status:** Accepted, implemented
**Builds on:** `suggested-playlists-api-28-09-2026.md` (reading the curator's editions) and
`collection-downloads-14-09-2026.md` (one download, many songs)

## Context

A suggested playlist is 40 YouTube ids with no YouTube playlist behind them: the curator picked them from
Discogs and YouTube Music play counts. The client could download them one at a time, which meant 40
requests, 40 cards in the downloads panel and no playlist file in the library. Every other kind of
collection is one request and one card.

## Decision

### A fourth download type, `CURATED`, whose id is the curator's category key

`POST /download/collection/80s-indie-pop?type=CURATED` inserts one `downloads` row like any other
collection request. At admission the loop asks the curator for the category's latest edition
(`GET /v1/editions/{category}`, the same read `GET /suggested-playlists/{category}` makes) instead of asking
ytmusic-adapter, and turns it into the shape every other collection arrives in: the edition's title as the
download's name, "Naviseerr" as the author, the first song's thumbnail as its picture, one task row per song
with the usual "Title - Artist" Soulseek wording. Nothing after admission knows or cares where the list
came from.

The curator stores no artwork, so every song gets YouTube's predictable thumbnail, the same fallback a
playlist's tracks already use.

### It is filed like a playlist

The library organiser files each song under its own artist (not in one folder, for the same reason playlist
tracks are not) and writes `<root>/Playlists/<edition title>.m3u8` once every song is filed, exactly as for a
`PLAYLIST`. `DownloadType.isPlaylist()` is the one place that says which types get a playlist file.

### Failure rules follow the adapter's

A curator answer that cannot change by asking again (404 no edition for that category, 400 unknown key,
401 wrong token) fails the download before any task row exists, with `METADATA_UNAVAILABLE`, exactly as an
id ytmusic-adapter cannot resolve does. A curator that is merely down (timeout, connection refused, 5xx)
leaves the row `PENDING` for the next pass, exactly as an adapter outage does.

### The schema change

`V10__curated_downloads.sql` widens the `downloads.download_type` CHECK constraint to admit `CURATED`. Same
operation V5 performed for `PARTIAL_SUCCESS`; nothing else changes.

## Trade-offs

- **The edition is read at admission, not at request.** A request placed on Sunday night for an edition
  that is replaced on Monday at 03:00 before the loop admits it gets Monday's songs. Admission is normally
  seconds after the request, so this is theoretical; pinning a date (`?date=`) is a query parameter away if
  it ever matters.
- **No request-time check that a curator is configured.** With no curator, admission gets a 401 from an
  empty token and fails the download with `METADATA_UNAVAILABLE`. Honest, if a little late; the client only
  offers "Download all" on a page that exists only when the curator is on.
- **`GET /collections/{id}?type=CURATED` is 400.** A suggested playlist is read on
  `GET /suggested-playlists/{category}`, which carries the curator's tiers and reasons; the collection view
  has nowhere to put them.
- **`youtube_id` holds a category key for these rows.** The column was already "whatever id the request
  carried" (a `videoId`, a `browseId`, a playlist id); one more id space, and `media_items` is keyed the
  same way, so the feed's join works unchanged.

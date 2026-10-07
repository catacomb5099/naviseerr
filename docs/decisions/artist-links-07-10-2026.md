# Artist names link to artist pages: which names get an id

**Date:** 07-10-2026
**Status:** Accepted. naviseerr #119 (this), naviseerr-client #70 and #73.
**Builds on:** [ytmusic-search-provider-10-08-2026.md](ytmusic-search-provider-10-08-2026.md)

## Context

The owner asked for every artist name on a song or album card, on an album page and on the Downloads
page to open that artist's page. The YouTube Music adapter already returned each artist's `channelId`;
the server kept only the names on the search, artist and collection routes (the Downloads answers had
carried `artistIds` since the Downloads page got its links). The client cannot link a name it has no id
for, so the question was which answers carry ids and which names deliberately do not.

## Decision

1. `Track`, `Album` and `Playlist` answers carry `artistIds`, index-aligned with `artists`, `null`
   where YouTube gave no channel. Same shape as the Downloads answers, so the client has one piece
   (`ArtistNames`) that links a name with an id and leaves the rest as text; a shorter or missing list
   (an older server, mock data) degrades to plain text, never a dead link.
2. Playlist authors are never linked by the client even when an id comes through. A playlist's author
   is a YouTube channel, not an artist page: the adapter answers 502 on
   `/v1/artists/{authorChannelId}` for such ids, which the client would show as "We couldn't find this
   artist". The server still sends the id (it is what YouTube has), the client decides not to use it.
3. On an artist's page, the album and single shelves carry that artist's own id (the one the page was
   requested with): the adapter's album rows there have no artist list of their own, and the name shown
   is the page's artist.

## Consequences

Changing who gets linked is a client-only change for 2 (pass the ids for `PLAYLIST` in `AlbumCard`),
and a one-line change in `ArtistView` for 3 (send none). Suggested-playlist rows stay text until the
curator keeps the ids it already receives (three-repo follow-up).

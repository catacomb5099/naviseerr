# LastFM Integration

> Status: current as of 2026-06-29, branch `event-driven-download-queue`. **Superseded 10-08-2026: retained on disk, unused.** Agent-oriented guide - the cited source files are the source of truth; verify before relying.

> [!IMPORTANT]
> As of 10-08-2026, `SearchService` no longer calls any of the code described below — search now
> runs through [YtMusicService](../../src/main/java/com/catacomb5099/naviseerr/services/ytmusic/YtMusicService.java);
> see [ytmusic-integration.md](ytmusic-integration.md) and the
> [ADR](../decisions/ytmusic-search-provider-10-08-2026.md). `LastFMConfig` and `LastFMService` are
> marked `@Deprecated(forRemoval = true)` but not yet deleted — this doc describes code that still
> exists and still compiles, just isn't reachable. Do not extend it; it is pending removal.

LastFM was the metadata source for search (tracks, albums, artists). It was read-only and reactive (`Mono`), exposed through [SearchService](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java).

## Client

[LastFMConfig.java](../../src/main/java/com/catacomb5099/naviseerr/services/lastfm/LastFMConfig.java) builds the `lastFmWebClient` bean with base URL `last-fm-service.url` (e.g. `http://ws.audioscrobbler.com/2.0`). There is no API-key header - LastFM takes the key as a query parameter.

## Service

[LastFMService.java](../../src/main/java/com/catacomb5099/naviseerr/services/lastfm/LastFMService.java):

- `getResults(query, apiMethod)` - issues a `GET` with query params `api_key`, `format=json`, `limit` (`last-fm-service.search-result-limit`), `method`, and a method-specific param, then maps the raw response via `SearchResponseMapper`.
- `getResults(query)` - runs track, album, and artist searches concurrently and combines them with `Mono.zip(...)` into one `SearchResponse`.

## Method mapping

- [LastFMAPIMethod.java](../../src/main/java/com/catacomb5099/naviseerr/util/LastFMAPIMethod.java) - enum: `TRACK_SEARCH`, `ALBUM_SEARCH`, `ARTIST_SEARCH`.
- [LastFMAPIMethodHelper.java](../../src/main/java/com/catacomb5099/naviseerr/util/LastFMAPIMethodHelper.java) - maps the enum to the LastFM `method` value (`track.search` / `album.search` / `artist.search`) and the query param name (`track` / `album` / `artist`).

## Response mapping

[SearchResponseMapper.java](../../src/main/java/com/catacomb5099/naviseerr/util/SearchResponseMapper.java) converts the raw [LastFmSearchResponse](../../src/main/java/com/catacomb5099/naviseerr/services/lastfm/model/LastFmSearchResponse.java) into the app DTOs in `schema/response/` (`Track`, `Album`, `Artist`, `SearchResponse`).

Be aware (see [gotchas.md](gotchas.md)):

- Artist/album image selection uses `images.get(2)` guarded only by an `isEmpty()` check - an `IndexOutOfBoundsException` is possible if there are fewer than 3 images.
- `mapFromLastFMTrack` uses placeholder values (`"lol"` for album id, `0` for year); album mapping uses `0` for year. These are not real metadata yet.

## Endpoints

These paths are now served by `YtMusicService`, not the classes on this page:

- `GET /search/{query}` - combined (track + album + artist)
- `GET /search/{query}/tracks` | `/albums` | `/artists`

## Configuration (`last-fm-service.*`)

- `url` - base URL.
- `api_key` - LastFM API key (currently committed - see [gotchas.md](gotchas.md)).
- `search-result-limit` - per-type result cap.

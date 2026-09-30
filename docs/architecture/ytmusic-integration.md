# YouTube Music Integration

> Status: current as of 27-09-2026. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

YouTube Music, via a sidecar adapter service, is the metadata source for search (tracks, albums,
artists, playlists) and for browsing an album, playlist or artist by id. It replaces LastFM as the active search backend as of this doc; see
[lastfm-integration.md](lastfm-integration.md) for the retained-but-unused Last.fm path and the ADR
below for why. It is read-only and reactive (`Mono`), exposed through
[SearchService](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java),
[CollectionController](../../src/main/java/com/catacomb5099/naviseerr/services/CollectionController.java) and
[ArtistController](../../src/main/java/com/catacomb5099/naviseerr/services/ArtistController.java).

## The adapter is a separate service, not a library

Naviseerr talks to YouTube Music through **ytmusic-adapter**, a standalone stateless FastAPI/Python
service in the sibling repo `~/IdeaProjects/ytmusic-adapter`, wrapping the Python-only, synchronous
`ytmusicapi` library (pinned `1.12.2`). It is not vendored into this repo. It runs anonymously — no
YouTube credentials, no OAuth — and exposes a stable, versioned (`/v1/...`) JSON contract:
`GET /v1/search`, `/v1/search/{songs|albums|artists|playlists}`, plus detail lookups by id. As of
27-09-2026 naviseerr consumes five of those detail routes: `GET /v1/songs/{videoId}`,
`GET /v1/albums/{browseId}` and `GET /v1/playlists/{playlistId}` — which is how a download request
carrying only a YouTube id learns what to search Soulseek for — plus `GET /v1/artists/{channelId}`,
behind the read-only artist page, and `GET /v1/songs/{videoId}/details`, behind the song page.

Wired into [compose.yaml](../../compose.yaml) as `ytmusic-adapter`, built from
`build: ../ytmusic-adapter` — a path outside this repo. `./gradlew bootRun` (which auto-starts
compose via `spring-boot-docker-compose`) therefore only works with both repos checked out side by
side. The container's own `HEALTHCHECK` (in the adapter's Dockerfile) hits its liveness endpoint;
`docker compose ps` reports `healthy` once it responds.

## Client

[YtMusicConfig.java](../../src/main/java/com/catacomb5099/naviseerr/services/ytmusic/YtMusicConfig.java)
builds the `ytMusicWebClient` bean with base URL `yt-music-service.url`
(`${YT_MUSIC_SERVICE_URL:http://localhost:8000}`). No API key — the adapter is anonymous.

As with `lastFmWebClient`/`slskdWebClient`, the bean is selected purely by matching the constructor
parameter name (`ytMusicWebClient`) to the `@Bean` method name; there is no `@Qualifier` anywhere in
this codebase.

## Service

[YtMusicService.java](../../src/main/java/com/catacomb5099/naviseerr/services/ytmusic/YtMusicService.java):

- `getResults(query, type)` - `GET /v1/search/{songs|albums|artists|playlists|featured_playlists}`
  (the adapter's typed sugar routes; `type` is [YtMusicSearchType](../../src/main/java/com/catacomb5099/naviseerr/services/ytmusic/YtMusicSearchType.java),
  the LastFM-era `LastFMAPIMethod`'s replacement), passing `yt-music-service.search-result-limit`
  (`10`) as `limit`. `getResults(query, type, limit)` is the same call with a caller-chosen page
  size: the category search routes pass the client's `?limit=` through it, and the artist page's
  featured-playlist search uses it because it filters the answer afterwards. The
  response is mapped through `YtMusicSearchResponseMapper`, which yields a `SearchResponse` with
  only that one list populated (the adapter already filtered server-side).
- `getResults(query)` - **one** unfiltered `GET /v1/search` call, passing
  `yt-music-service.mixed-search-limit` (`100`) as `limit`. `YtMusicSearchResponseMapper` partitions
  the mixed response into all four lists in a single pass. This used to fan out the three typed
  searches concurrently and fuse them with `Mono.zip(...)`, same shape as
  `LastFMService.getResults(String)` did; that was changed to cut the provider calls a general
  search makes from three to one — see the "Mixed (general) search" section below for the accepted
  tradeoff.

Both overloads share one private `executeSearch(uriFunction, label, query)` helper for the
request/response pipeline below (error translation, debug logging, timeout, retry) — they differ
only in the URI they build and the label used in logs.

This is the first provider client in the codebase with **timeouts and typed error handling** — see
below. It uses Lombok `@Slf4j`, not the `import static reactor.netty.http.HttpConnectionLiveness.log`
idiom present on three of the four other `services/` classes (`SearchService` itself, `LastFMService`,
the slskd processors) — that import borrows an internal Reactor Netty logger and should not be
copied into new code.

## Error handling

The adapter's error contract is genuinely non-uniform: most errors return
`{"error":{"code","message"}}`, but an unsupported `type` on `/v1/search` (400), a 422 validation
failure, and its own `/health/ready` 503 all return `{"detail": ...}` instead — a plain string in two
cases, a JSON array in the 422 case. `YtMusicService.extractMessage` reads the body as a `JsonNode`
and tries both shapes defensively; it never assumes a fixed record structure.

Two-exception hierarchy, both extending `YtMusicException`
(package `com.catacomb5099.naviseerr.services.ytmusic`):

| Adapter response | Exception | Retried? |
|---|---|---|
| `200` with `count: 0` | *(none — empty lists)* | n/a; this is "no good match", not a failure |
| `400`, `422`, `500` | `YtMusicBadRequestException` | No |
| `404` **with** the adapter's `{"error":{"code":"not_found",…}}` envelope — it looked, the id does not exist | `YtMusicBadRequestException` | No |
| `404` **without** it — FastAPI's `{"detail":"Not Found"}` or an empty body: the running image has no such route | `YtMusicUnavailableException` ("…is the sidecar image up to date?") | Yes |
| `429`, `502`, `504`, any other status | `YtMusicUnavailableException` | Yes |
| client-side timeout / connection refused / decode failure | `YtMusicUnavailableException` | Yes |

Retries reuse [ReactivePoller.defaultBackoff](../../src/main/java/com/catacomb5099/naviseerr/util/networkcalls/ReactivePoller.java)
(`Retry.backoff(...).jitter(0.2).transientErrors(true)`), filtered to `YtMusicUnavailableException`
only. One subtlety worth knowing if you touch this: Reactor's default retry-exhaustion behavior wraps
the last failure in an `IllegalStateException` (`Exceptions.retryExhausted`), which would have hidden
the real exception type from every caller. `getResults(query, type)` overrides that with
`.onRetryExhaustedThrow((spec, signal) -> signal.failure())` so callers only ever see
`YtMusicException` subtypes, retried or not — see `YtMusicServiceTest`'s
`getResults_connectionRefused_mapsToUnavailable` for the regression test.

Configured via `yt-music-service.timeout-ms` (default `15000` — deliberately above the adapter's own
`YTM_TIMEOUT_SECONDS=10`, since every adapter call also queues behind a `threading.Semaphore(8)` per
worker process), `retry-count`, and `first-back-off-duration-ms`.

## Response mapping

[YtMusicSearchResponseMapper.java](../../src/main/java/com/catacomb5099/naviseerr/util/YtMusicSearchResponseMapper.java)
converts the raw [YtMusicSearchResponse](../../src/main/java/com/catacomb5099/naviseerr/services/ytmusic/model/YtMusicSearchResponse.java)
into the same app DTOs LastFM used (`Track`, `Album`, `Artist`, `SearchResponse` in `schema/response/`)
— the frontend contract did not change.

Field mapping, and why each choice was made (naviseerr-client has no normalization layer, so these
are load-bearing on the UI, not stylistic):

| Target | Source | Note |
|---|---|---|
| `Track.id` | `videoId` | a real, stable YouTube id — replaces LastFM's frequently-empty `mbid` |
| `Track.iconURL` / `Album.iconURL` | `thumbnailUrl` | capital `URL` — the client mirrors this casing |
| `Artist.iconUrl` | `thumbnailUrl` | lowercase `Url` — a **different** casing, for `Artist` only |
| `Track.artists` / `Album.artists` | `artists[].name` | display **names**, never `channelId` — the client `join(', ')`s this directly |
| `Track.streamURL` | `""` | the adapter exposes no streaming path by design; unread by the client either way |
| `Track.albumId` | `album.browseId` | a real `MPREb_…` id — replaces the old hardcoded `"lol"` (see [gotchas.md](gotchas.md) #5) |
| `Track.plays` | `views` + `" plays"`, else null | "7.2M" becomes "7.2M plays", the wording album tracks carry; never parsed (YouTube abbreviates it). Null on the Top result card |
| `Album.year` | `year`, else `0` | song search items carry no year; only album items do |
| `Playlist.id` | `playlistId`, else `browseId`, else `""` | bare `PL...` preferred over the `VL`-prefixed `browseId` -- see Endpoints below |
| `Playlist.artists` | `artists[].name` | the adapter folds ytmusicapi's `author` string into `artists[0]` |
| `Playlist.trackCount` | `trackCount`, else `0` | the adapter's `itemCount`, already an int |

`YtMusicSearchResponseMapper.mapToSearchResponse(response)` classifies every item by `resultType`
in a single pass and routes it into `tracks`/`albums`/`artists`/`playlists`; anything else (`video`, `episode`,
`podcast`, `station`, `profile`, `null`) is dropped. This is what makes the mapper safe
for both callers: a typed response (already filtered server-side) yields items of one kind, so three
of the four lists come back empty; a mixed response yields all four at once. It also replaces the
old per-type defensive filter (three separate `"song".equals(...)` style checks) with one
structural pass — defense against upstream shape drift leaking, e.g., a podcast into the artists
list, is now inherent rather than duplicated three times.

Do not filter artists on `browseId != null` — a bare-name query's "Top result" artist card omits
`browseId` entirely, and the adapter's own `map_search_item` falls back to `artists[0].id` for
exactly this case (`ytmusic-adapter/AGENTS.md`).

## Mixed (general) search: one request, fewer results

`GET /v1/search` with no `type` returns YouTube Music's own mixed results page — the same page you
get typing into the YTM search box — instead of a category-filtered page. Two constraints, both
confirmed against the pinned `ytmusicapi` 1.12.2 source, shape what naviseerr can do with it:

- **`limit` is dead when unfiltered.** In `ytmusicapi/mixins/search.py`, the continuation loop that
  paginates until `limit` results are collected only runs `if internal_filter:`. With no filter,
  that block never executes, so the call returns exactly one page of shelves regardless of the
  `limit` requested — `limit=100` and `limit=1` are byte-identical.
- **Categories cannot be excluded.** The adapter's `filter` query param maps to a single-valued
  `Literal` in `ytmusicapi`; there is no way to ask for "songs, albums, artists but not videos".

The adapter still truncates its own output with `items[:limit]` *before* grouping, and YouTube
interleaves the mixed page (videos first, albums starting around index 12 for a typical query). So
`mixed-search-limit` is set to the adapter's ceiling (`100`) purely to avoid that truncation
starving the categories that appear late — at `limit=10` a mixed page can return zero albums.

Even at `limit=100`, a mixed page has noticeably fewer results per category than three typed calls
at `search-result-limit=10` would: recorded against the `Oasis Wonderwall` fixture, mixed search
returns roughly 6 songs / 3 albums / 6 artists versus ~10 / 6 / 10 from the typed routes. It also
returns songs with `album: null` and `durationSeconds: null` — YouTube only populates those on
category-filtered searches — so `Track.albumId` on the general endpoint is always `""`, unlike the
typed `/search/{query}/tracks` route, which still returns a real `MPREb_…` id. This was accepted
deliberately in exchange for cutting the general endpoint from three provider calls to one; see
[docs/decisions/ytmusic-mixed-search-20-08-2026.md](../decisions/ytmusic-mixed-search-20-08-2026.md).

The three typed calls this replaced already ran concurrently via `Mono.zip`, so this change reduces
provider load and adapter semaphore occupancy — it is not a user-visible latency improvement.

## Metadata lookups (the download pipeline's use of this provider)

Search is not the only caller any more. `DownloadTaskRunner.gatherMetadata` resolves a download's
YouTube id at admission time, via the first three of these methods on
[YtMusicService](../../src/main/java/com/catacomb5099/naviseerr/services/ytmusic/YtMusicService.java)
(the last three belong to the artist page, the song page and the view-count lookup; admission never calls them):

| Method | Route | Returns |
|---|---|---|
| `getSongInfo(videoId)` | `GET /v1/songs/{videoId}` | `YoutubeSongInfo` |
| `getAlbumInfo(browseId)` | `GET /v1/albums/{browseId}` | `YoutubeCollectionInfo` |
| `getPlaylistInfo(playlistId)` | `GET /v1/playlists/{playlistId}` | `YoutubeCollectionInfo` |
| `getArtistInfo(channelId)` | `GET /v1/artists/{channelId}` | `YtMusicDetailResponse.Artist` |
| `getSongDetails(videoId)` | `GET /v1/songs/{videoId}/details` | `YtMusicDetailResponse.SongDetails` (adapter shape; only `SongInfoView.from` reads it) |
| `getSongViewCount(videoId)` | `GET /v1/songs/{videoId}` | `Long`, that one upload's exact `viewCount`; empty when none (only `GET /songs/views` reads it) |

Three things about the mapping are easy to get wrong, because the adapter's three responses are not
the same shape:

- **A song's `author` is a single string; a collection track's `artists` is a list.**
  `YoutubeSongInfo.authorNames()` is a list either way, so callers never have to know which shape a
  song came from.
- **An album reports `browseId` and an `artists[]`; a playlist reports `id` and a single `author`.**
  One `YoutubeCollectionInfo` covers both — they are one title plus one track list as far as the
  download pipeline is concerned, and splitting them would mean two types and a switch at every use
  site for one nullable field's worth of difference (`year`, which only albums have).
- **A track with `isAvailable: false` is dropped.** It is region-blocked or deleted, so a task row
  for it would spend a whole search budget to fail. Only an explicit `false` counts — album
  responses omit the field entirely, and treating null as unavailable would drop every album track.

All five go through the same `execute` pipeline as search — one timeout, typed error translation,
retry on availability failures only — rather than reimplementing it. That pipeline was extracted from
`executeSearch` for exactly this reason.

**404 maps to `YtMusicBadRequestException`, not `YtMusicUnavailableException`**, and that is
load-bearing rather than cosmetic. A 404 here means YouTube Music has no such video, album or
playlist; retrying cannot make an id exist. Admission leans on the distinction to tell "fail this
download now" from "try again next pass", so classifying it as unavailable would have one mistyped id
re-requested every loop interval for the life of the install. See
[the ADR](../decisions/collection-downloads-14-09-2026.md).

The one 404 that is *not* a bad request is the one the adapter never produced. FastAPI answers
`{"detail":"Not Found"}` (no `error` envelope) when the path has no route at all, which happens when
the running `ytmusic-adapter` image predates an endpoint naviseerr now calls — on 28-09-2026 a stale
image made `/v1/songs/{id}/details` 404 for every song, and every controller dutifully reported
"unknown song". `buildException` tells the two apart by the envelope: no envelope maps to
`YtMusicUnavailableException` with a message naming the likely cause, so the client shows "Couldn't
load … Try again" and the log says to rebuild the image.

## Endpoints

Exposed by [SearchService.java](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java),
`@GetMapping` (was `@RequestMapping`, which accepted every HTTP verb):

- `GET /search/{query}` - mixed (one unfiltered adapter call; see above). Fills all four lists,
  `playlists` included -- the mixed page always carried `playlist` items, the mapper used to drop them.
- `GET /search/{query}/tracks` | `/albums` | `/artists` `?limit=` - typed (one filtered adapter call
  each). `limit` defaults to `20` and is pulled into `1..100` (the adapter refuses more than 100).
  This is how the client's "Show more" works: YouTube Music has no "next page" we can ask for, so it
  asks again with a bigger `limit` and keeps what it has not shown yet. Measured 30-09-2026: 20
  results take ~0.5 s, 100 take ~3 s (ytmusicapi fetches 20 at a time, one after another); YouTube
  had hundreds of songs, albums and fan-made playlists for "oasis" but only 57 artists, and the
  same song can appear twice across its pages. So the client merges what it gets rather than
  trusting positions: artists and playlists by id, songs and albums by title and artists (YouTube
  also carries one recording under several ids, which by id alone would show twice).
- `GET /search/{query}/playlists?limit=` - two filtered adapter calls at once (`playlists` = fan-made,
  `featured_playlists` = YouTube Music's own), merged by `SearchService.mix`: top two of each pinned
  in YouTube's order (featured first), the rest of both shuffled. A failing featured call degrades
  to fan-made only; the fan-made call keeps the usual error handling. `limit` grows the fan-made
  call only; the featured one stays at `search-result-limit`, because past its first one or two
  relevant rows it is unrelated filler.

And by [SongInfoController.java](../../src/main/java/com/catacomb5099/naviseerr/services/SongInfoController.java):

- `GET /songs/{videoId}` - one song as a
  [SongInfoView](../../src/main/java/com/catacomb5099/naviseerr/services/SongInfoView.java): artists
  with channel ids, album, year, exact duration and view count (this one upload's plays), YouTube
  Music's combined `plays` wording ("1.7B plays", null for an official video), the per-track
  `explicit` flag and the credits panel (`role` is YouTube's own heading, rendered verbatim). The adapter stitches it from up
  to four YouTube Music calls, so expect 1-2.5 s. An official-video id (`(Official Video)` titles)
  has no album, year, explicit flag or credits anywhere on YouTube Music; they come back null / `[]`,
  not as an error, and the adapter deliberately does not guess the album twin by title search.
  Same 404/502 handlers as `/collections/{id}`.
- `GET /songs/views?ids=` - `{id: viewCount}` for up to 50 ids, one `getSongViewCount` each, 8 at a
  time. The count is that one upload's exact plays, smaller than the combined "plays" wording other
  song rows carry; the client uses it for playlist songs, which YouTube gives no count. A lookup that
  fails, or has no count, is left out of the object instead of failing the request.

And by [CollectionController.java](../../src/main/java/com/catacomb5099/naviseerr/services/CollectionController.java):

- `GET /collections/{id}?type=ALBUM|PLAYLIST` - one album or playlist as a
  [CollectionView](../../src/main/java/com/catacomb5099/naviseerr/services/CollectionView.java):
  header plus every available track with a 1-based `position` and, on an album's tracks only,
  YouTube's own `plays` wording ("28M plays", passed through unparsed; null on a playlist's, where
  YouTube gives none). Calls the same `getAlbumInfo` /
  `getPlaylistInfo` as admission, so what the client sees is what a download of the same id would
  create task rows for. `type=SONG` is 400 (a track has no collection view); the adapter's 404 --
  which the client maps to `YtMusicBadRequestException`, see above -- becomes a 404 here, *not*
  `SearchService`'s 400, because a lookup by id that finds nothing is "not found", not "bad query";
  `YtMusicUnavailableException` is 502 as everywhere else.

And by [ArtistController.java](../../src/main/java/com/catacomb5099/naviseerr/services/ArtistController.java):

- `GET /artists/{channelId}` - one artist page as an
  [ArtistView](../../src/main/java/com/catacomb5099/naviseerr/services/ArtistView.java): header
  (name, picture, description, subscribers) plus top songs, albums, singles, playlists and similar
  artists, each capped at 10 and expressed in the search DTOs so the client reuses its cards. Up to
  fourteen adapter calls: `getArtistInfo`, then a `featured_playlists` search for the artist's
  *name* with `limit=20` (there is no "playlists featuring this artist" route; this is YouTube
  Music's own editorial playlists its search links to the name), then up to 12 `getPlaylistInfo`
  lookups. Playlists whose title contains the artist's name or a related artist's name ("Presenting
  Oasis", "Presenting The Kooks") are dropped, comparing lowercased letters and digits with accents
  stripped; the first 12 survivors are then opened (four at a time, YouTube's order kept) and only
  those whose track list credits the artist - whole folded name, so "Pixies Tribute Band" is not
  Pixies - stay, because the search links a playlist to a name for reasons other than membership
  (for Oasis: "Summer House", 131 tracks, no Oasis). The lookup reads the adapter's default
  `/v1/playlists/{id}` page of 100 tracks, so an artist buried deeper in a very long playlist is
  missed; a playlist that fails to open is dropped. `ArtistView` caps the result at 10. The whole
  step is best-effort - if it fails (including an adapter that does not know the
  `featured_playlists` type yet) the page still loads with an empty `playlists` shelf. Errors map
  exactly as `/collections/{id}`: the adapter's 404 (and the 400/422/500 folded into the same
  exception) is 404, `YtMusicUnavailableException` is 502.

`Playlist.id` on the search side is the adapter's bare `playlistId` (`PL...`, or `RDCLAK5uy_...` for a
featured playlist; `/v1/playlists/{id}` accepts both), falling back to the
`VL`-prefixed `browseId` only when the bare id is absent. The adapter's detail route accepts either,
but the backend keys `media_items` by whatever id the client posts, so the client must pass the id
it was given through to both `/collections/{id}` and `/download/collection/{id}` unchanged.

## Configuration (`yt-music-service.*`)

- `url` - adapter base URL, env-var-backed (`${YT_MUSIC_SERVICE_URL:http://localhost:8000}`) — no
  secret is needed since the adapter is anonymous.
- `search-result-limit` - the `limit` for a typed search whose caller names none: today only the
  featured-playlists half of `/search/{query}/playlists`. The category routes take theirs from the
  request (`SearchService.FIRST_PAGE`, `20`, when absent).
- `mixed-search-limit` - the `limit` sent on the general/unfiltered route. Kept at the adapter's
  ceiling (`100`) even though `ytmusicapi` ignores `limit` unfiltered — see "Mixed (general) search"
  above for why lowering it is a silent regression, not a simple tuning knob.
- `timeout-ms`, `retry-count`, `first-back-off-duration-ms` - see Error handling above.

## Known gaps

- Playlist search (`Playlist.trackCount`) reports the adapter's `trackCount`, which is YouTube's
  advertised `itemCount`, or `0` when YouTube gives none; `CollectionView.trackCount` is the count
  of *available* tracks after the `isAvailable: false` filter. The two can legitimately differ.
- No caching — every search hits the adapter (and, behind it, YouTube) fresh.
- Similar artists' pictures (`Artist.iconUrl`) come from the adapter's `related[].thumbnailUrl`;
  an adapter image older than that field leaves them `""`, no error. Rebuild the adapter image.
- Top songs on the artist page use YouTube's predictable per-video thumbnail because the adapter's
  `TrackDto` carries no artwork; same picture the collection view uses for playlist tracks.
- General search returns fewer results per category than the typed routes, and blanks
  `Track.albumId` — see "Mixed (general) search" above.

# YouTube Music Integration

> Status: current as of 10-08-2026. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

YouTube Music, via a sidecar adapter service, is the metadata source for search (tracks, albums,
artists). It replaces LastFM as the active search backend as of this doc; see
[lastfm-integration.md](lastfm-integration.md) for the retained-but-unused Last.fm path and the ADR
below for why. It is read-only and reactive (`Mono`), exposed through
[SearchService](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java).

## The adapter is a separate service, not a library

Naviseerr talks to YouTube Music through **ytmusic-adapter**, a standalone stateless FastAPI/Python
service in the sibling repo `~/IdeaProjects/ytmusic-adapter`, wrapping the Python-only, synchronous
`ytmusicapi` library (pinned `1.12.2`). It is not vendored into this repo. It runs anonymously — no
YouTube credentials, no OAuth — and exposes a stable, versioned (`/v1/...`) JSON contract:
`GET /v1/search`, `/v1/search/{songs|albums|artists|playlists}`, plus album/artist/playlist detail
lookups that naviseerr does not currently consume.

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

- `getResults(query, type)` - `GET /v1/search/{songs|albums|artists}` (the adapter's typed sugar
  routes; `type` is [YtMusicSearchType](../../src/main/java/com/catacomb5099/naviseerr/services/ytmusic/YtMusicSearchType.java),
  the LastFM-era `LastFMAPIMethod`'s replacement), passing `yt-music-service.search-result-limit`
  (`10`) as `limit`. The response is mapped through `YtMusicSearchResponseMapper`, which yields a
  `SearchResponse` with only that one list populated (the adapter already filtered server-side).
- `getResults(query)` - **one** unfiltered `GET /v1/search` call, passing
  `yt-music-service.mixed-search-limit` (`100`) as `limit`. `YtMusicSearchResponseMapper` partitions
  the mixed response into all three lists in a single pass. This used to fan out the three typed
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
| `429`, `502`, `504`, `404`, any other status | `YtMusicUnavailableException` | Yes |
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
| `Album.year` | `year`, else `0` | song search items carry no year; only album items do |

`YtMusicSearchResponseMapper.mapToSearchResponse(response)` classifies every item by `resultType`
in a single pass and routes it into `tracks`/`albums`/`artists`; anything else (`video`, `episode`,
`podcast`, `playlist`, `station`, `profile`, `null`) is dropped. This is what makes the mapper safe
for both callers: a typed response (already filtered server-side) yields items of one kind, so two
of the three lists come back empty; a mixed response yields all three at once. It also replaces the
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

## Endpoints (unchanged)

Still exposed by [SearchService.java](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java),
now `@GetMapping` (was `@RequestMapping`, which accepted every HTTP verb):

- `GET /search/{query}` - mixed (one unfiltered adapter call; see above)
- `GET /search/{query}/tracks` | `/albums` | `/artists` - typed (one filtered adapter call each)

## Configuration (`yt-music-service.*`)

- `url` - adapter base URL, env-var-backed (`${YT_MUSIC_SERVICE_URL:http://localhost:8000}`) — no
  secret is needed since the adapter is anonymous.
- `search-result-limit` - per-type result cap for the typed routes, passed through as the adapter's
  `limit` query param.
- `mixed-search-limit` - the `limit` sent on the general/unfiltered route. Kept at the adapter's
  ceiling (`100`) even though `ytmusicapi` ignores `limit` unfiltered — see "Mixed (general) search"
  above for why lowering it is a silent regression, not a simple tuning knob.
- `timeout-ms`, `retry-count`, `first-back-off-duration-ms` - see Error handling above.

## Known gaps

- No playlist search — the adapter supports it (`/v1/search/playlists`), but `SearchResponse` has no
  `Playlist` field yet, and the general/type-filtered search on the naviseerr side never requests it.
  Deferred deliberately; see the ADR.
- No caching — every search hits the adapter (and, behind it, YouTube) fresh.
- The adapter's album/artist/playlist *detail* endpoints (`/v1/albums/{browseId}` etc.) are
  unconsumed; there is no `services/ytmusic` code path that calls them.
- General search returns fewer results per category than the typed routes, and blanks
  `Track.albumId` — see "Mixed (general) search" above.

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
  the LastFM-era `LastFMAPIMethod`'s replacement), maps the raw response into a `SearchResponse` with
  only that one list populated.
- `getResults(query)` - runs the three typed searches concurrently and fuses them with
  `Mono.zip(...)`, same shape as `LastFMService.getResults(String)` did. Unlike that method, it does
  **not** re-apply request-level `doOnSubscribe`/`doOnSuccess`/`doOnError` logging per sub-call —
  that duplicated the controller's own logging and made every combined search log each line twice;
  the triplet now lives once, in `SearchService`.

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

Each typed response is re-filtered by `resultType` inside the mapper (`"song"`/`"album"`/`"artist"`)
even though the adapter's sugar routes already filter server-side — defense against upstream shape
drift leaking, e.g., a podcast into the artists list.

## Endpoints (unchanged)

Still exposed by [SearchService.java](../../src/main/java/com/catacomb5099/naviseerr/services/SearchService.java),
now `@GetMapping` (was `@RequestMapping`, which accepted every HTTP verb):

- `GET /search/{query}` - combined (track + album + artist)
- `GET /search/{query}/tracks` | `/albums` | `/artists`

## Configuration (`yt-music-service.*`)

- `url` - adapter base URL, env-var-backed (`${YT_MUSIC_SERVICE_URL:http://localhost:8000}`) — no
  secret is needed since the adapter is anonymous.
- `search-result-limit` - per-type result cap, passed through as the adapter's `limit` query param.
- `timeout-ms`, `retry-count`, `first-back-off-duration-ms` - see Error handling above.

## Known gaps

- No playlist search — the adapter supports it (`/v1/search/playlists`), but `SearchResponse` has no
  `Playlist` field yet, and the general/type-filtered search on the naviseerr side never requests it.
  Deferred deliberately; see the ADR.
- No caching — every search hits the adapter (and, behind it, YouTube) fresh.
- The adapter's album/artist/playlist *detail* endpoints (`/v1/albums/{browseId}` etc.) are
  unconsumed; there is no `services/ytmusic` code path that calls them.

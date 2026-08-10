# Decisions: ytmusic-search-provider

- **Date:** 10-08-2026
- **Topic:** ytmusic-search-provider

---

## Decision: Replace LastFM with YouTube Music as the active search backend

**Context:** LastFM was the only search metadata source. It has no playlist concept at all, and its
response mapping ([SearchResponseMapper](../../src/main/java/com/catacomb5099/naviseerr/util/SearchResponseMapper.java))
carries known defects already on file as [gotchas.md](../architecture/gotchas.md) #5: `Track.albumId`
is the hardcoded literal `"lol"`, both `year` fields are hardcoded `0`, ids come from LastFM's `mbid`
(frequently an empty string), and artist/album image selection (`images.get(2)`) throws
`IndexOutOfBoundsException` on fewer than three images. Meanwhile `AGENTS.md` lists "playlist search
and playlist downloads" and "artist/song/album pages" as milestones LastFM cannot support.

`ytmusicapi` (wrapping YouTube Music's internal API) covers all four search types anonymously —
songs, albums, artists, and playlists — with real detail lookups behind each result. It is
Python-only and synchronous, so it cannot be called directly from this reactive JVM service; it is
wrapped in a separate FastAPI adapter (`~/IdeaProjects/ytmusic-adapter`, its own repo) that exposes it
as a stable JSON API, built and tested independently of this change.

**Options Considered:**
1. *Provider interface + config flag/param, both providers live* — introduce
   `MusicSearchProvider`, keep LastFM reachable behind a flag or `?provider=` param.
2. *Repoint entirely, retain LastFM code unused on disk* — swap `SearchService`'s dependency from
   `LastFMService` to a new `YtMusicService`; mark the LastFM classes `@Deprecated`; delete nothing.
3. *Repoint entirely, delete LastFM code* — same as 2, plus remove `LastFMService`, `LastFMConfig`,
   `LastFMAPIMethod(Helper)`, and their model/mapper code in the same change.

**Final Choice:** Option 2.

**Rationale:** LastFM is being deprecated outright, not kept as an alternative — there is no product
reason to run both providers, and no seam existed to select between them (no interface, no
`@Qualifier`, no profile files), so building one purely to keep a dying provider reachable would be
throwaway abstraction. But `SearchResponseMapper` and the `LastFmSearchResponse` model are read by
nothing else in the codebase (verified: zero references outside `LastFMService` and
`SearchResponseMapper` itself), and there are currently zero tests exercising any of it — so deleting
it in the same change as the repoint would conflate two different kinds of risk (behavior change vs.
code removal) in one diff. Marking it `@Deprecated(forRemoval = true)` documents the intent precisely
without taking on that risk now; removal is a clean, low-risk follow-up.

**Trade-off to be aware of:** the LastFM beans (`LastFMConfig`, `LastFMService`,
`LastFMAPIMethodHelper`) still get instantiated by Spring at startup — they are simply never
autowired by anything after this change. `last-fm-service.*` config and its committed API key
(see [gotchas.md](../architecture/gotchas.md) #2) remain in `application.yaml` until the follow-up
deletion.

---

## Decision: No playlist support in this change

**Context:** YouTube Music search returns playlists, which is the single largest capability gap
LastFM had. `SearchResponse` has no `Playlist` field.

**Options Considered:**
1. *Add `Playlist` to `SearchResponse` now, wire a `/search/{query}/playlists` endpoint.*
2. *Defer playlists entirely — map only tracks/albums/artists, matching today's `SearchResponse`.*

**Final Choice:** Option 2.

**Rationale:** Scoped explicitly by the requester: the goal of this change is repointing existing
search behavior onto a better provider, not adding new product surface in the same diff. Playlist
search remains available in `ytmusic-adapter` (`GET /v1/search/playlists`) whenever it's picked up.

---

## Decision: Search only — adapter detail endpoints stay unconsumed

**Context:** `ytmusic-adapter` also exposes album/artist/playlist *detail* lookups
(`/v1/albums/{browseId}`, `/v1/artists/{channelId}`, `/v1/playlists/{playlistId}`) — real metadata
that could back "artist/song/album pages" from the `AGENTS.md` milestone list.

**Final Choice:** Not consumed in this change. `services/ytmusic/YtMusicService` only calls the
adapter's search routes. Wiring the detail routes is straightforward (same client, same error
taxonomy) but has no controller/client demand yet — deferred until there's a UI need driving it.

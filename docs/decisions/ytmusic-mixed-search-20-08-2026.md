# Decisions: ytmusic-mixed-search

- **Date:** 20-08-2026
- **Topic:** ytmusic-mixed-search

---

## Decision: General search issues one unfiltered request instead of three typed requests

**Context:** `SearchService.search(query)` (general search) called
`YtMusicService.getResults(query)`, which ran three typed searches concurrently
(`/v1/search/songs`, `/v1/search/albums`, `/v1/search/artists`) via `Mono.zip` and fused the
results. Each typed call is a separate `ytmusicapi.search(filter=...)` invocation on the adapter
side, so one user search cost three upstream YouTube Music requests and three slots of the
adapter's `threading.Semaphore(max_concurrency=8)`. `YtMusicSearchResponseMapper` also switched on
a `YtMusicSearchType` parameter to decide which one of its three lists to populate, duplicating the
same `resultType`-based filtering logic three times (once per type) across the two call sites.

The adapter already exposes `GET /v1/search` with `type` omitted, which performs YouTube Music's
own mixed/default search — the same result page the YTM web UI shows — and is a documented,
supported surface (`ytmusic-adapter/README.md`). Repointing the general search at that one
endpoint, then classifying the returned items client-side, collapses three provider calls into one
and lets the mapper's classification logic serve both the general and typed routes with no
per-caller branching.

**Options Considered:**
1. *Keep three typed requests, only share the mapper's filtering logic* — refactor
   `YtMusicSearchResponseMapper` to partition items in one pass regardless of caller, but leave
   `YtMusicService.getResults(query)` as the `Mono.zip` of three typed calls.
2. *One unfiltered request, raise `limit` to compensate* — call `/v1/search?limit=<high>` and rely
   on a high `limit` to recover typed-search result volume.
3. *One unfiltered request, accept reduced results* — call `/v1/search` once, partition the mixed
   response, and accept that a mixed page has fewer results per category and blanks
   `Track.albumId`, in exchange for a 3x reduction in provider calls.

**Final Choice:** Option 3.

**Rationale:** Option 2 does not work: verified against the pinned `ytmusicapi` 1.12.2 source
(`mixins/search.py`), the continuation loop that consumes `limit` is gated on `if internal_filter:`
— with no filter set, that block never runs, so an unfiltered search returns exactly one page of
shelves and `limit` is inert (`limit=100` and `limit=1` are byte-identical). The adapter's own
`items[:limit]` truncation still applies before grouping, though, and YouTube interleaves the mixed
page (videos first, albums appearing around index 12 for a typical query) — so `limit` is set to
the adapter's ceiling (`100`, see `yt-music-service.mixed-search-limit`) purely to stop that
truncation from starving late-appearing categories, not to recover volume: a mixed page at
`limit=10` can return zero albums, and even at `limit=100` a mixed page yields roughly
6 songs / 3 albums / 6 artists against ~10 / 6 / 10 from three typed calls (measured against the
recorded `Oasis Wonderwall` fixture).

Type exclusion (asking for "everything but videos/episodes/podcasts") is also not available:
`ytmusicapi`'s `filter` parameter is a single-valued `Literal`, so the choice is one category or
the full mixed page; naviseerr discards the unwanted types after the fact instead.

Option 1 (share the mapper only, keep three requests) was rejected because the three typed calls
already run concurrently via `Mono.zip`, so keeping them would forgo the actual benefit — reduced
provider load — while still doing the mapper refactor. Option 3 was chosen deliberately accepting
two known regressions on the general endpoint specifically:

- Fewer results per category (roughly half, per the counts above) than the typed routes return.
- `Track.albumId` is always `""` on general search, because mixed-page song items carry no
  `album` field (only category-filtered searches populate it). The typed `/search/{query}/tracks`
  route is unaffected and still returns a real `MPREb_…` id.

Both are scoped to the general endpoint; `/search/{query}/tracks|albums|artists` keep their
existing filtered calls, full result counts, and populated `albumId` unchanged. This also means a
general search no longer fails as a whole if a single leg of a three-way zip fails — there is only
one call now, so that failure mode no longer exists.

Not addressed here, deferred: the adapter's `tests/test_contract_live.py` guards its per-type
mixed-search assertions with `if "<type>" in by_type:`, so a mixed response that stopped returning
albums entirely would not fail that suite. Naviseerr now depends on mixed mode in a way it didn't
before; tightening that guard is reasonable follow-up work in the adapter repo, not in scope here.

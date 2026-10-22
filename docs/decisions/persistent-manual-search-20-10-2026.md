# Persistent manual search (20-10-2026)

The owner: "manual override is not captured after the dialog is closed, the shown query doesn't match the search
results and it seems like closing a dialog mid search cancels it". The pop-up is the manual import pop-up on the
Downloads page ([manual-pick-07-10-2026.md](manual-pick-07-10-2026.md), addendum 19-10-2026). Research with a
reproduction: `sweep-1020-notes/research/persistent-manual-search.md`.

## 1. Every list names the search it came from (V21)

**Decision.** The words and slskd id of the search that found a remembered list are stored WITH the list, in the same
statement (`download_tasks.search_results_query` / `search_results_search_id`, `album_searches.folders_query` /
`folders_search_id`). The pop-up's "Soulseek was asked for" line, its search box and its grey slskd line read them.

**Why.** Before, the pop-up named the loop's newest wording and the row's newest search id. The loop advances its
wording when a search finds nothing (the blocked-artist phrases Soulseek drops), but keeps the earlier wording's list
on purpose, so the header named a search that found nothing over files another search found. A person's own search was
worse: its words were never stored, so a reopened pop-up named Naviseerr's wording over the person's results.

**Follows** slskd's own Searches page: each result list sits under the text that produced it.

**Flip.** `DownloadService.view` / `albumQuery` / `albumSearchId`: prefer the derived wording again.

- Older rows (null columns) fall back to today's derived wording and `search_id`; no backfill.
- An album's song with no list of its own shows the album's folders under the album search's words, id and time
  (before: the song's tier-0 wording, which was never searched).
- The manual search still also writes `search_id` (unchanged), so `reasonOf`'s "never searched on its own" check keeps
  working for a person's empty search.

## 2. A person's search runs to the end whoever is watching

**Decision.** `ManualSearch` subscribes the search itself and hands the request a replay (`Mono.cache()`), so a request
that goes away (closed pop-up, reloaded page, the client's 60 s timeout) only stops waiting. While it runs, the song
(by task id) or album (by download id) sits in an in-memory registry: the candidate GETs answer `SEARCHING` with the
typed text (`ManualSearch.overlay`), and a second search for it is refused with 409 `MANUAL_SEARCH_RUNNING`. A search
that fails while nobody waits is kept as `searchError` (one sentence, with the typed words) until the next one starts.
`inFlight()` is the registry's size, so the loop still leaves the person their search slot.

**Why.** The search was the HTTP request: WebFlux cancelled it when the browser went away, and nothing on the server
knew it was running, so a reopened pop-up showed the old list, an enabled Search button and no progress, a second
search ran beside the first, and whichever finished last owned the list.

**Follows** slskd's own Searches page: a search is a server-side object you can leave and come back to. Spotify,
YouTube Music and Apple Music have no long-running search to compare with.

**Flip.**
- A new search replaces the running one instead of being refused (owner question Q19 b): in `ManualSearch.tracked`,
  dispose the running search and register the new one (about five lines).
- Say nothing about a failed background search (Q20 b): drop the `failed.put` in `tracked`.

**Limits.** The registry lives in memory: a server restart mid-search loses that search (at most about 45 s of work;
the old list shows). Several songs can be searched back to back now that closing frees nothing; past two at once
slskd answers 429 (`SOULSEEK_BUSY`), as with several tabs before.

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

# Suggested playlists: naviseerr hands the curator's weekly editions to the client

**Date:** 28-09-2026
**Status:** Accepted, implemented (two read endpoints; the "make this week's playlists now" action is a separate PR)
**Builds on:** `curator-weekly-trigger-27-09-2026.md` (the weekly refresh) and the playlist-curator's HTTP API

## Context

Since 27-09-2026 naviseerr asks the playlist curator once a week to build a fresh "suggested playlist" per
category (80s indie pop, current pop, and so on). The curator writes each edition as a JSON file and serves
it on `GET /v1/editions` and `GET /v1/editions/{category}`. Nothing showed them to a user yet.

The client cannot read them from the curator directly: every curator call needs the shared `CURATOR_TOKEN`,
and a secret does not belong in a browser. It also should not have to learn a second server's address and
vocabulary when it already talks to naviseerr for everything else.

## Decision

### naviseerr proxies the curator's editions, in the client's own vocabulary

- `GET /suggested-playlists` answers `{enabled, playlists[{category, title, editionDate, trackCount}]}`: the
  latest edition per category, from the curator's `GET /v1/editions`.
- `GET /suggested-playlists/{category}` answers the edition itself: `{category, title, filters, editionDate,
  trackCount, tracks[{id, name, artists, album, albumYear, popularity, tier, reason, iconURL, position}]}`.
  `id`, `name`, `artists` and `iconURL` are named as on the search contract, so the client's song rows,
  download buttons and info pop-up work on these songs unchanged. `tier` (`top`, `mid`, `random`) and
  `reason` are the curator's own explanation of why each song is in; `filters` are the Discogs filters
  behind the category (`year`, `style`, `genre`) for a one-line description.
- Every request is read through to the curator. naviseerr keeps no copy: the curator owns the files, the
  list is a few hundred bytes, an edition is a few kilobytes, and a copy would be one more thing to keep
  in step once a week.

### Three states the client can tell apart

| Situation | List route | Detail route |
|---|---|---|
| No curator configured (no `CURATOR_TOKEN`) | 200, `enabled: false`, empty list | 503 |
| Curator configured, no edition for that category yet | 200, `enabled: true` (category absent) | 404 |
| Curator down, wrong token, curator error | 502 | 502 |

`enabled: false` lets the client hide the whole section on an install that never set the curator up, instead of
showing an empty shelf with a button that cannot work. The 404 is what the client's "this week's playlists
are not ready yet" state hangs off. A 502 means the problem is behind naviseerr and the log line says which.

### Artwork

The curator stores no pictures. Each song gets YouTube's predictable thumbnail URL for its id, the same
fallback playlist tracks already use, so the rows show album art rather than blanks. The playlist itself has
no cover; the client draws one.

## Trade-offs

- **A read costs a curator round trip.** Fine at this size and for one user's browser; if it ever matters,
  cache the list for a minute in naviseerr. Not done now.
- **The enabled flag reuses the scheduler's rule.** "Configured" means the same thing for reading editions as
  for the weekly refresh: both `CURATOR_URL` and `CURATOR_TOKEN` are set. One rule, one place.
- **No paging.** An edition is 30-50 songs by design.

## Not in this PR

- A "make this week's playlists now" action (`POST /suggested-playlists/refresh`) and a way for the client to
  follow the run. `CuratorScheduler.refresh()` is public for exactly that; next PR.
- Downloading a suggested playlist as one download. Today the client can download its songs one by one
  (they are ordinary YouTube ids). A `CURATED` download type that admits an edition as one collection is
  the follow-up the curator's discovery notes already name.
- The client UI itself (naviseerr-client).

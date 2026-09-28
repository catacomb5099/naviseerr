# Suggested playlists: naviseerr hands the curator's weekly editions to the client

**Date:** 28-09-2026
**Status:** Accepted, implemented in two PRs (the two read endpoints; then the "make this week's playlists now" action)
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

- `GET /suggested-playlists` answers `{enabled, refreshDay, playlists[{category, title, year, editionDate,
  trackCount}]}`: the latest edition per category, from the curator's `GET /v1/editions`. `year` is the
  category's Discogs range as the curator has it ("1980-1989"; "1950-2026" for an all-time list; null from an
  older curator), passed through so the client can shelve the playlists by decade without naviseerr knowing
  the categories. `refreshDay` ("MONDAY") is read off
  `curator.cron` when it names one plain weekday, so the client can say "New edition every Monday" the way
  every streaming service names its day; null otherwise.
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

### Refresh on demand: "make this week's playlists now"

The weekly clock is the normal way editions appear. When none are ready (a fresh install, a curator that
was down on Monday, a category added mid-week) the client can ask for them:

- `POST /suggested-playlists/refresh` tells the curator to start and answers 202 at once with the run
  (`{runId, status, requestedAt, startedAt, finishedAt, categories[{key, status, editionDate, trackCount,
  message}], final}`, the curator's own run record; `final` is true once the run is over). Pressing during a run returns that run: the curator's trigger
  is idempotent. Unlike the cron tick, the trigger is not retried; a person is waiting and can press again.
- `GET /suggested-playlists/refresh` is the most recent run, whoever started it, so the client can follow
  it: `queued`, `running`, then `succeeded`, `partial` or `failed` with one plain-language line per
  category. 404 when the curator has never run. The literal path wins over `/{category}`, so a category
  cannot be called `refresh`.
- After the 202, naviseerr keeps following the run in the background exactly as after a cron tick
  (`CuratorScheduler.refreshNow()`), so the log tells the same story either way. If the weekly refresh is
  already following a run, the manual one is not followed twice.

Both answer 503 when no curator is configured.

## Trade-offs

- **A read costs a curator round trip.** Fine at this size and for one user's browser; if it ever matters,
  cache the list for a minute in naviseerr. Not done now.
- **The enabled flag reuses the scheduler's rule.** "Configured" means the same thing for reading editions as
  for the weekly refresh: both `CURATOR_URL` and `CURATOR_TOKEN` are set. One rule, one place.
- **No paging.** An edition is 30-50 songs by design.

## Not in these PRs

- Downloading a suggested playlist as one download. Today the client can download its songs one by one
  (they are ordinary YouTube ids). A `CURATED` download type that admits an edition as one collection is
  the follow-up the curator's discovery notes already name.
- The client UI itself (naviseerr-client).

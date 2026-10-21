# Search learning: record what worked, send it only when asked (20-10-2026)

## What

Every completed search of a song now leaves one entry on `download_tasks.search_log` (V22): the words searched,
the wording number (null for a person's typed text), how many sharers answered and how many audio files they
offered by grade. Each kept file carries the `wording` that found it. From those rows naviseerr builds, on demand,
one **report** per successful song (`GET /search-learning/reports`, `SearchLearningRepository`). When the person
switches **Settings → Help improve search** on and the install file sets `SEARCH_LEARNING_URL`, the
`SearchLearningSender` POSTs new reports in batches to that address.

## Decisions

| choice | default | how to flip |
|---|---|---|
| The switch | off on every install | `PUT /settings/search-learning {on: true}` (Settings) |
| What counts as success | downloaded from a search **and** filed in the library | drop `t.library_path IS NOT NULL` from `REPORT_SQL` |
| History | only songs that finish after the switch went on (`install_settings.search_learning_since`) | let `SET_SEARCH_LEARNING_SQL` write `'-infinity'` once |
| Receiver | the owner's croissant, `POST /v1/search-reports` (any URL works) | `SEARCH_LEARNING_URL` / `SEARCH_LEARNING_TOKEN` |
| File path in a report | last two folders + file name, share root and sharer-named folders dropped | `SearchReport.shortPath`: keep only the last part |
| YouTube id in a report | included (re-query YouTube later) | drop `Song.youtubeId` |
| Rhythm | every 10 minutes, 100 reports per batch | `SEARCH_LEARNING_INTERVAL_MS`, `SEARCH_LEARNING_BATCH_SIZE` |

Never in a report: any Soulseek user name, the sharer's stats, the library folder, download ids, an address or a
token. The receiver sees the sender's IP address on the request itself (every HTTP request carries it); croissant's
route does not log it.

The switch follows Apple's "Share iPhone Analytics" and Home Assistant's analytics opt-in (one switch, off until you
say yes, a way to see exactly what is sent). Spotify and YouTube Music have no user-facing equivalent.

## Why this shape

- **No outbox table.** Reports are rebuilt from the rows that already exist; the only sent state is
  `learning_sent_at` per song. Per row rather than a "sent up to" time, because a song is filed after it finishes.
- **Retry = the next tick.** Any 2xx marks exactly those songs sent; anything else marks nothing. The last problem
  is kept in memory for the Settings page and logged once when it changes.
- **Generic contract.** naviseerr only knows "POST `{schema: 1, reports: [...]}` with an optional bearer token", so a
  public receiver for other people's installs can come later without touching naviseerr (owner question 16 b).
- Album-folder files carry no wording of their own; their report names the album search's words (V21's
  `folders_query`). Searches that never complete (timed out) are not logged.

# Weekly curator trigger: naviseerr asks the playlist curator for fresh suggested playlists

**Date:** 27-09-2026
**Status:** Accepted, implemented in one PR (trigger + poll + logging; nothing user-facing yet)
**Companion:** the croissant repo's HTTP API (the repo was called playlist-curator until 28-09-2026) (agreed contract of the same date)

## Context

The playlist curator is a separate service that builds a "suggested playlists" edition per category
(80s indie pop, and so on). One run takes minutes: it talks to Discogs and makes a couple of hundred
YouTube Music requests per category, one after another so neither provider rate-limits it. Somebody has
to tell it when to run, and somebody has to notice whether it worked. Nothing in naviseerr did either.

## Decision

### naviseerr owns the schedule; the curator answers instantly and works in the background

Once a week (`curator.cron`, default Mondays 03:00 server time) naviseerr sends `POST /v1/runs` with an
empty body, which means "every category". The curator replies straight away with HTTP 202 and a run id,
then does the slow part on its own. naviseerr then asks `GET /v1/runs/{id}` every 30 seconds until the
run says `succeeded`, `partial` (some playlists were written, some categories found nothing) or `failed`, or until 2 hours have passed (30 minutes until 09-10-2026, see the addendum). At the end it writes one log line per
category (what happened, which edition date, how many tracks) and one summary line. That is the whole
feature: no database change, no new naviseerr endpoint.

Both sides share one secret, `CURATOR_TOKEN`. naviseerr sends it as a bearer token on every call; the
curator refuses to start without one and answers 401 when it does not match. A 401 is logged as
"curator rejected the token" so the fix is obvious.

The scheduler is off unless both `CURATOR_URL` and `CURATOR_TOKEN` are set -- the same rule the library
organiser follows -- and says so in one INFO line at startup.

### Why not one synchronous call

A run takes minutes; an HTTP request that waits minutes for its answer is a request that will be cut
off by the first timeout in the way (naviseerr's own is 15 s, and proxies are usually similar). Cut
off, naviseerr would not know whether the run happened at all. Trigger-then-poll costs a few more lines
and never has that problem.

### Why not let the curator schedule itself

It could: a cron inside the curator would be less code overall. The owner decided the schedule belongs
to naviseerr, because naviseerr is the product the user configures and reads logs from. One place to
look for "when does this run, and did it work?" beats two, and a manual "refresh now" button later is a
naviseerr button, not a curator one.

## Trade-offs

- **A curator restart mid-run.** The curator marks the interrupted run `failed` ("interrupted by
  restart") when it comes back, and naviseerr's next poll sees that. Nothing retries it until the
  following week; run it by hand with `POST /v1/runs` if it matters.
- **The poll budget.** After 2 hours (30 minutes until 09-10-2026, see the addendum) naviseerr stops asking and logs a warning pointing at the
  curator's `/v1/runs/latest`. The curator keeps going and finishes on its own; only naviseerr's view is
  cut short. Raise `curator.run-budget-ms` if runs regularly take longer.
- **A poll that fails is not a failed run.** A timeout or a 5xx while polling is logged and polling
  continues; only the trigger itself is retried (three times, backing off from 10 s), and never on a
  4xx. The curator ignores a second trigger while one is running, so a retried trigger is harmless.
- **Token rotation needs both sides restarted.** The token is read once at startup on each side.
- **One refresh at a time.** If a tick fires while the previous refresh is still polling, it is skipped
  with a warning rather than queued. With a weekly cron and a 2-hour budget this cannot happen
  unless the budget is set above a week.

## Not in this PR

- The Suggested playlists UI, and the naviseerr endpoint it would read (`GET /v1/editions` on the
  curator side already exists for it).
- A manual "refresh now" button. `CuratorScheduler.refresh()` is public so wiring one is a controller
  method away.
- Storing run history in naviseerr's database. The curator keeps its own history in `runs/`; the logs
  are enough for now.

## Addendum 09-10-2026: a first run on start, and a 2-hour budget

A fresh install had no suggested playlists until the first Monday 03:00 unless somebody pressed "Make
this week's playlists now". Now, when the scheduler is on, naviseerr waits two minutes after start (the
curator is up by then and the first `docker compose up` is over), asks the curator for its editions and,
if there are none at all, runs one refresh at once: the same trigger, polling and per-category log lines
as a cron tick. Editions present means nothing happens; a curator that cannot be reached means one WARN
line and the weekly cron still stands (a 90-minute job must not start on a guess). `curator.first-run-on-start`
(env `CURATOR_FIRST_RUN_ON_START`) is on only where compose.yaml passes it, in the Docker install;
`CURATOR_FIRST_RUN_ON_START=false` in `.env` turns it off there. The jar's own default is off, so a developer's
IntelliJ start or `./gradlew test` (both read the dev `.env`, which may hold a `CURATOR_TOKEN` pointing at a
local curator) never starts a run on its own (found in review, 09-10-2026). A restart loop cannot start two
runs: the curator hands the active run back to a second trigger.

The poll budget went from 30 minutes to 2 hours (`curator.run-budget-ms`): a full run over 49 categories
takes about 80-90 minutes, so every real run used to end with a misleading "still running after PT30M"
warning while the curator was still working.

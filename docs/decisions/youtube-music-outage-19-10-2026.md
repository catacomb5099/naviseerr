# Say it is YouTube Music when YouTube Music is the problem (19-10-2026)

## The problem

On 12-10-2026 the owner's all-in-one install logged `SearchService : ytmusic-adapter unavailable: Upstream request timed
out` after a Netty stack trace, and the web app said "Search is not available right now". The owner read it as a YouTube
rate limit badly communicated. The line itself was the adapter container failing to reach YouTube at all (a DNS or
connection failure, which the adapter worded "timed out"; see the ytmusicapi repo's `app/errors.py`), not a rate limit.
Either way nothing the user saw named YouTube Music: the search page guessed from the URL, the artist, album and song
pages said only "Couldn't load…", a download requested meanwhile sat at "Waiting" with no reason, and there was no
strip like the Soulseek one.

The server collapsed every provider failure into one untyped `YtMusicUnavailableException` and answered 502 with an
empty body, from five identical controller-local `@ExceptionHandler`s, logging each at ERROR twice (once with the
stack trace).

## The decision

- One `@RestControllerAdvice`, `YtMusicUnavailableAdvice`, answers the exception for every route: 502 with
  `{reason, message}`. `reason` is `YOUTUBE_MUSIC_UNAVAILABLE` or `YOUTUBE_MUSIC_RATE_LIMITED`; `message` is the sentence
  the web app prints, in the owner's words from the ask: "YouTube Music is not answering right now. Try again in a
  minute." / "YouTube Music is rate-limiting this server. Wait a few minutes, then try again."
- `YtMusicUnavailableException` carries `rateLimited`, set by `YtMusicService.buildException` when the adapter answered
  429. Nothing else about the cause is typed: the probe and the adapter's log tell "adapter down" from "YouTube
  unreachable", the user's screen does not need to.
- The five controller-local handlers are deleted, not left: Spring resolves a controller's own `@ExceptionHandler`
  before any advice, so a leftover copy would answer an empty 502 again.
- An outage is logged once, at WARN, without a stack trace (`SearchService.logUnexpected` keeps the stack for anything
  that is not this exception). A dependency being down is expected weather; the trace told a self-hoster nothing.

## Why this shape

- The web app already turns a 409's body into a card and a 404's into "not found"; a 502 with a body is the same
  mechanism, and an older web app ignores the body and shows what it showed before (no coordinated deploy).
- Two sentences, not a cause taxonomy: Spotify, YouTube Music and Apple Music show "Something went wrong — Retry" /
  "Couldn't load, check your connection" in the content area, naming what the user can do, not the backend. Sonarr /
  Radarr / Lidarr add a persistent health warning for a failing dependency ("Indexers are unavailable due to
  failures"), which is what the `/status` strip (addendum below) follows.
- The sentence lives on the server so the web app and any other client (the Chrome extension) say the same thing.

## Known ceilings (stated, not fixed)

- A "Waiting" download card does not itself say it waits for YouTube Music; the strip above every page covers it.
- `GET /songs/views` keeps dropping counts silently (a playlist page simply shows none).
- croissant (suggested playlists) calls ytmusicapi directly; its YouTube failures are its own.
- Rate-limit detection is as good as the adapter's: a YouTube 429 with an HTML body reaches it as an unreadable
  answer, not a 429, and reads "not answering".

## Addendum, same day: no retry on a rate limit

The adapter client retried every `YtMusicUnavailableException` up to `retry-count` times (two, 50 ms apart): three calls
in about 150 ms. For a 502 or a connection failure that is cheap and often enough (YouTube's stalls answer at once on the
second try). For a 429 it is the opposite: the adapter has just said "too many", and three more requests per page view
is how a short limit becomes a long one. The retry filter now skips `rateLimited`; a 429 fails at once and reaches the
advice as `YOUTUBE_MUSIC_RATE_LIMITED`. `Retry-After` is still not read: nothing on the server waits on behalf of a
browser request, and the sentence already tells the person to wait a few minutes.

Known ceiling: only the in-call retry is gone. The download loop still asks the adapter once per pending download per
pass (up to `download-task.batch-size` calls every pass; the rows stay PENDING), and the `/status` probe below keeps
about one tiny search a minute going; both carry on through a rate limit. Skipping a pass while the last admission
failure was a 429 is the next step, if a real one is ever observed.

## Addendum, same day: `/status` says whether YouTube Music can be reached

The web app already shows a persistent amber strip for the two things a download waits on that the user cannot see
from a page (Soulseek login, the library folder), fed by `GET /status`. YouTube Music is the third such thing: search
and the admission of every new download wait on it, and a download requested during an outage sits at "Waiting" with
nothing to say why. `/status` now carries `youtubeMusic: {reachable, detail}` from the adapter's own cached
`GET /health/ready` (one tiny search per minute per adapter worker), with the same 3 s budget as the slskd calls and
always 200. The strip is one wording for every cause ("YouTube Music isn't answering — search and new downloads will
wait until it is back"): the probe cannot tell a rate limit from an outage, and a strip that guesses is worse than one
that is plain; `detail` keeps the distinction for the person reading the status by hand. This is the Sonarr / Radarr /
Lidarr shape (System → Health: "Indexers are unavailable due to failures", cleared by the next periodic check).

Rejected: a passive status (remember the last real adapter outcome in `YtMusicService`, no probe traffic). It cannot
clear itself until somebody searches again, so it needs a "probe when stale" branch anyway: more code than the probe,
not less. Known ceiling: the adapter caches a failed probe for 60 s, so the strip can linger up to about a minute after
YouTube Music is back (Sonarr's health warnings "will not always disappear immediately" either).

Same day, after review: the adapter client's connection pool drops any connection idle for over a second
(`YtMusicConfig`). uvicorn closes an idle keep-alive connection after 5 s (gunicorn's `UvicornWorker` after 2 s), and
with the web app polling `/status` every 5 s a pooled connection was reused just as the adapter closed it: a
`Connection reset`, which the probe (no retry, by design) worded as a one-poll outage on a healthy adapter during the
first demo recording. `execute()` had been hiding the same race behind its retries.

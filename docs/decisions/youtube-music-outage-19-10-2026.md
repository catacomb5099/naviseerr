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

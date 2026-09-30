# "All" search asks each category separately, keeps YouTube's own picks on top, and every shelf can show more

Date: 2026-09-30. Status: decided by the owner ("do a song query, an album query, an artist query and
the playlist queries individually, then put them all together"). Supersedes
[ytmusic-mixed-search-20-08-2026.md](ytmusic-mixed-search-20-08-2026.md).

## What the owner asked for

A search for anything should return enough to browse: a large set of songs, albums, artists and
playlists, of which the app shows part, with "Show more" bringing more.

## What it was

`GET /search/{query}` made one unfiltered YouTube Music search. ytmusicapi answers that with one
page whatever `limit` says: about 6 songs, 3 albums, 6 artists and 6 playlists. The category routes
(`/tracks`, `/albums`, `/artists`, `/playlists`) returned 10 each, fixed.

## Measured on 30-09-2026 (ytmusicapi 1.12.2, queries "oasis" and "wonderwall")

| ask | songs | albums | artists | fan-made playlists | featured playlists |
|---|---|---|---|---|---|
| 20 | 20 in 0.5-1.3 s | 20 in 0.5 s | 20 / 10 in 0.5 s | 20 in 0.4 s | 16 / 20 in 0.3 s |
| 100 | 100 in 3.1-3.3 s | 100 / 39 in 1.5-4.1 s | 57 / 10 in 0.6-2.4 s | 100 in 2.9 s | 15 / 30 (all there is) |
| 300 | 300 in 11-13 s | 300 / 39 | 57 / 10 | 300 in 9-10 s | 16 / 30 |

- ytmusicapi fetches 20 at a time, one after another, so the time grows with the ask.
- Pages overlap: 100 songs held 93 unique ones (the same song at positions 21-25 of 40).
- Order is not stable between two identical calls: "oasis" songs and albums came back reordered, same
  set. So positions cannot be used to fetch "the next 20".
- YouTube Music exposes no "next page" handle through ytmusicapi's public API.

## Decision

1. **All = YouTube's mixed page on top, one search per category below.** Six adapter calls at once:
   the unfiltered mixed search, and songs, albums, artists (20 each) and the playlists route's own
   two calls. Each shelf is the mixed page's items of that kind first, then the category search's
   items it does not already hold, matched by YouTube id (the client additionally folds songs and
   albums with the same title and artists, since YouTube carries one recording under several ids).
   When YouTube is healthy the wall-clock is about the same as the one mixed call (0.5-1.1 s for
   six queries), since they run together. See point 4 for when it is not.
   Why keep the mixed page: the owner's plan was category searches alone, and those rank worse at
   the top. For "oasis" the mixed page's songs are Champagne Supernova, She's Electric, Morning
   Glory; the songs search leads with Liam Gallagher's Sad Song, Roberta Flack's "Oasis" and J Balvin.
   For "daft punk" the mixed albums are Discovery and Human After All; the albums search puts
   tribute albums second. For a song title ("wonderwall") the two agree. So the mixed page supplies
   the first few, which are what most people are looking for, and the category searches the depth.
   The songs search is the canary: if it fails, the answer is an error, so a down adapter never reads
   as "no results"; the mixed page and the other three drop out on their own.
2. **Category routes take `?limit=`**, default 20, pulled into 1..100 (the adapter's ceiling).
   "Show more" asks again with a bigger limit and keeps the ones it has not shown yet (songs and
   albums matched by title and artists, artists and playlists by id).
   That survives both the duplicates and the reordering above. Going past 100 would need an adapter
   change and costs 4 s per extra 100.
3. **Featured playlists do not grow.** Past its first one or two relevant rows, YouTube's featured
   search is unrelated filler (Kidz Bop, classical: see the 28-09-2026 measurement in
   `ytmusic-integration.md`), so the playlists route grows the fan-made half only.
4. **A part that drops out is named.** All's answer carries `unavailable`: which of the mixed page,
   albums, artists and playlists failed and came back empty. The client shows "couldn't load
   albums" with a button that asks the albums search itself, rather than no albums shelf at all
   (AGENTS.md: report "no good match" distinctly from provider errors).
5. **Every search caps each try and asks again.** Found by the review before merge: that evening
   YouTube started holding some calls 5-10 s and then answering with an empty body (16 of 72 sent
   six at a time, 6 of 30 one at a time, 3 of 12 straight through ytmusicapi). The adapter client
   retries only after the stall has run, up to three times, and All waits for its slowest call, so
   searches took 6-20 s. A second try usually answers at once. All's calls get 3 s per try (songs
   3 tries, then the search fails; the rest 2, then `unavailable`); the category routes get 2 s plus
   1 s per 20 asked for, then one more try, then a 502; the featured half of playlists gets one try,
   then fan-made only. Measured afterwards, still stalling: All median 3.5 s, worst 6.6 s.

Counts on All, before and after (songs / albums / artists / playlists):

| query | before (mixed page only) | after |
|---|---|---|
| oasis | 8 / 3 / 7 / 6 | 27 / 20 / 26 / 30 |
| wonderwall | 6 / 3 / 6 / 6 | 25 / 23 / 11 / 33 |
| taylor swift | 7 / 4 / 7 / 6 | 25 / 23 / 19 / 33 |
| daft punk | 8 / 3 / 7 / 6 | 28 / 23 / 9 / 28 |

(Measured on the live stack; "after" is the category search's 20 plus the mixed page's own items it
did not also return. Artists stay low where YouTube knows few: "daft punk" has 9.)

## Options considered

- *Keep the mixed search, raise its limit.* Does nothing: unfiltered search ignores `limit`.
- *Category searches only (the owner's first idea).* Built first, then measured: the first rows got
  worse for artist names (above). Keeping the mixed page as the head costs one more call in parallel.
- *Fan out in the client (four requests from the browser).* Same YouTube cost, but four requests in
  the Network tab instead of one, and every client would have to repeat the canary rule.
- *Real paging through the adapter (continuation tokens).* ytmusicapi keeps them internal; the
  adapter would have to reach into private helpers that change between ytmusicapi releases.
  Re-asking with a bigger limit costs up to 3 s at 100 and needs no adapter change.
- *Server-side cache of the long list.* Would make each "Show more" instant after the first, but
  adds state for a single-user app; the client already keeps answers for five minutes.

## Cost

Six YouTube Music calls per All search instead of one, and up to 100-result calls on "Show more".
For one person browsing this is fine; if the adapter starts seeing 429s, the first knob is
`FIRST_PAGE` in `SearchService`. All waits for its slowest call, so a call YouTube is slow to
answer would hold six shelves rather than one page; point 5 caps that. When YouTube stalls, one All
search can cost up to 12 calls.

## How to flip

- Fewer or more per shelf on All: `SearchService.FIRST_PAGE`. The client asks the category routes
  for its own page size (`PAGE` in naviseerr-client `src/lib/showMore.ts`), but reads an All shelf
  shorter than that page (and not `unavailable`) as "that is all there is", so keep `FIRST_PAGE` at
  least the client's `PAGE`.
- Deeper "Show more": `SearchService.MAX_LIMIT`, together with the adapter's `le=100` on `limit` and
  the client's `MAX_RESULTS` (`src/lib/showMore.ts`), which stops asking at 100.
- Category searches only, no mixed page on top: drop the `top` leg in `SearchService.search`.
- Slower or faster give-up: `SearchService.ALL_TRY` (All) and `SearchService.tryFor` (category routes).
- Back to one mixed call: revert this PR; the mixed-search decision explains what that costs.

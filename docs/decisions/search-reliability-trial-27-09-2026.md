# Search reliability trial across five playlists

Date: 2026-09-27. Status: findings, with a recommendation at the end.

## Why we did this

Earlier today the owner's playlist "'80s Indie + Alternative" (50 songs) finished 18 songs found, 26 "timed out"
and 6 with no candidates. slskd's own search list told a different story: all 50 searches had been submitted
within a minute, completed strictly one after another over 4.5 minutes, and every one of them ended normally
("Completed, TimedOut" or "Completed, ResponseLimitReached"). 26 of them completed *after* naviseerr had already
given up on them, and many of those held 250 results. The searches were fine; naviseerr had flooded slskd, slskd
queued them (it only ever runs two searches at a time, a hard-coded limit since slskd 0.24), and naviseerr's
two-minute search clock, which starts at submission, ran out on the back of the queue.

Two fixes were written in response — cap running searches at slskd's own two slots (PR #38), and give up on a
Soulseek peer that keeps a transfer queued for ten minutes (PR #39) — alongside the already-open change to the
search wording (PR #35: bare "title - artist" first, title alone as the one fallback). This trial measures the
search half of that combination on real playlists, without downloading anything.

## Setup

- **Code under test.** A throwaway local branch merging PR #38, PR #39 and PR #35 on top of
  `move-fast-break-things`. Deleted after the run; nothing from it is in this PR except this document.
- **Search only, no transfers.** The backend ran with `download-task.max-concurrent-transfers=0`, which parks
  every song at `DOWNLOAD_INIT` the moment its search finds candidates, so no transfer was ever started
  (confirmed: no task row reached `DOWNLOAD_POLL` at any point). Everything else was the default configuration,
  including `max-concurrent-searches: 2` and the unchanged 120 s search budget.
- **Environment.** Backend on port 8081 against a throwaway Postgres 16 container on port 5433 (removed
  afterwards); the shared `ytmusic-adapter` on port 8000 for playlist metadata; the owner's real slskd
  (0.26.0) for Soulseek. One playlist at a time, requested with `POST /download/collection/<id>?type=PLAYLIST`,
  the database polled every 5 s until every song of that download had left `SEARCH_INIT`/`SEARCH_POLL`, then
  the next playlist.
- **What was recorded per song.** Outcome (`FOUND` = reached `DOWNLOAD_INIT` with candidates; else the failure
  code: `NO_CANDIDATES`, `TIMED_OUT`, `SEARCH_FAILED`); the search tier the outcome was reached on
  (`download_tasks.search_tier` from PR #35: 0 = bare "title - artist", 1 = title only); seconds spent in the
  search phase as seen in the database (5 s resolution, spanning both tiers where two were needed); and, for
  every slskd search the song ran, slskd's own `state`, `responseCount`, `fileCount` and `startedAt`-to-`endedAt`
  duration, read from `GET /api/v0/searches`.
- **Safety stops.** The runner would have stopped on any HTTP 429 from slskd, on slskd failing to answer twice in
  a row, on slskd reporting itself logged out of the Soulseek server, or on any transfer starting. None fired.
- **Playlists.** One per genre, chosen from `GET /search/<genre>/playlists` for being 30-60 songs and, where one
  existed, an official or label list rather than a chart of film songs or K-pop video titles.

| Genre | Playlist | Curator | Songs |
|---|---|---|---|
| indie rock | Indie Essentials \| Vevo Playlist | Vevo Playlists | 56 |
| grime | GRIME CLASSICS | (user list; no label grime list exists on YouTube Music) | 41 |
| electronic | Anthems - Electronic 90s \| Ministry of Sound | Ministry of Sound | 54 |
| rock | 90s rock hits | (user list) | 42 |
| pop | Pop Essentials | (user list) | 59 |

These searches remain in the owner's slskd search history (slskd keeps them until it ages them out).

## Results

| Genre | Playlist | Songs | Found | of which via the title-only fallback | No candidates | Timed out | Search failed | Wall time | slskd searches run | first searches with 0 responses |
|---|---|---|---|---|---|---|---|---|---|---|
| indie-rock | Indie Essentials (Vevo) | 56 | 53 (94%) | 6 | 3 | 0 | 0 | 11 min 06 s | 65 | 7 |
| grime | GRIME CLASSICS | 41 | 39 (95%) | 2 | 2 | 0 | 0 | 7 min 48 s | 45 | 1 |
| electronic | Anthems - Electronic 90s (Ministry of Sound) | 54 | 47 (87%) | 20 | 6 | 1 | 0 | 9 min 32 s | 80 | 22 |
| rock | 90s rock hits | 42 | 41 (97%) | 0 | 0 | 1 | 0 | 3 min 38 s | 42 | 0 |
| pop | Pop Essentials | 59 | 57 (96%) | 5 | 2 | 0 | 0 | 4 min 31 s | 66 | 6 |
| **all five** | | **252** | **237 (94.0%)** | **33** | **13** | **2** | **0** | **36 min 35 s** | **298** | **36** |

| Genre | median slskd search duration | median time in the search phase, songs found on the first wording | slowest such song | median time in the search phase, all songs |
|---|---|---|---|---|
| indie-rock | 14 s | 25 s | 41 s | 26 s |
| grime | 17 s | 20 s | 36 s | 20 s |
| electronic | 10 s | 5 s | 36 s | 260 s |
| rock | 2 s | 5 s | 31 s | 5 s |
| pop | 2 s | 5 s | 31 s | 5 s |

## What we found

### 1. The false timeouts are gone

This morning's run failed 26 of 50 songs as "timed out" (52%). Across the five playlists here, 2 of 252 songs
timed out (0.8%), and the two that did are a different problem (see finding 4), not the queue problem the cap
fixes. slskd's own list showed exactly two searches in progress at every check, and no HTTP 429 was ever
returned. The median search took 5 s inside slskd and a first-tier song spent a median of 10 s in
the search phase end to end; the slowest first-tier song that was found took 41 s, about a third of the 120 s budget.

Total wall time for 252 songs was 36 min 35 s, about 8.7 s per song, which is the pace slskd's two slots
allow. It is the same pace the flooded run achieved (50 searches in 4.5 minutes); the cap does not slow anything
down, it only stops naviseerr giving up on searches that were still waiting their turn.

### 2. The title-only fallback carries a lot of weight, for two different reasons

33 of the 237 found songs (13.9%) were found only after the "title - artist" search returned nothing and the
title alone was tried. Two very different things hide behind that number:

- **The Soulseek server silently drops searches naming certain artists.** Indie rock showed the lab's pattern
  clearly: `Kids - MGMT`, `On Melancholy Hill - Gorillaz`, `The Wilhelm Scream - James Blake`, `PDA - Master Peace`,
  `He Said She Said - CHVRCHES` and `we fell in love in october - Marie Ulven` each returned **zero** responses
  after the full search, while the same title without the artist returned 150 to 250 responses and thousands of
  files. Seven of 56 indie rock songs (12.5%), against 5.6% in the 809-song lab; the affected names are not rare
  acts.
- **The "artist" is a YouTube uploader.** The Ministry of Sound playlist is mostly plain uploads named
  `Artist - Title - uploader` ("Underworld - Born Slippy - sean7000", "Leftfield- Phat Planet - Dakota Martin").
  YouTube Music reports the uploader as the artist, so the first search asks Soulseek for `Underworld Born Slippy -
  sean7000`, which nothing on earth matches. 20 of that playlist's 47 finds came from the fallback,
  every one of them a wasted first search of 10-12 s plus a trip to the back of the search queue.

The fallback works, but it is expensive in exactly the way the cap makes visible: a song that needs it re-enters the
queue behind every first-tier search of its playlist (work is taken oldest-first), so on the electronic playlist the
median song spent 260 s in the search phase against 5-26 s on the others. Nothing timed out because the
budget only runs while a search is actually in progress, but the user waits.

### 3. Where "no candidates" really comes from

13 songs (5.2%) ended with no candidates. slskd's stored responses for each were pulled and checked for
files that carry the title, the artist somewhere in the path, and pass the quality filter (FLAC or 320 kbps). The
picture is the opposite of what the failure code suggests: for most of them Soulseek had the song and **the picker
refused every file**.

| Genre | # | Song as YouTube names it | What was searched, and what came back | Why |
|---|---|---|---|---|
| indie-rock | 8 | Goodnight and Go (Immi's Radio Version) [Official Video] - Imogen Heap | tier 0: `Goodnight and Go - Imogen Heap` -> 250 responses, 396 files<br>tier 1: `Goodnight and Go` -> 250 responses, 835 files | **Picker rejected everything Soulseek offered.** 395 files came back, 242 of them the right song by the right artist in FLAC or 320 kbps. The request asks for "Immi's Radio Version": the plain files carry neither "Immi" nor "radio", so they fail the requested-version rule, and the one file that does ("Immi's radio mix") says "mix", which the unrequested-version rule reads as a remix nobody asked for. |
| indie-rock | 22 | So Good at Being in Trouble - Unknown Mortal Orchestra | tier 0: `So Good at Being in Trouble - Unknown Mortal Orchestra` -> 0 responses, 0 files<br>tier 1: `So Good at Being in Trouble` -> 0 responses, 0 files | **The Soulseek server drops the phrase.** Zero responses with the artist, zero for the title alone, and zero again when re-tested by hand two hours later. The same words rearranged as "Mortal Orchestra Trouble" returned 250 responses. The Soulseek server discards that exact phrase. |
| indie-rock | 24 | beabadoobee - If You Want To (Official Video) - beabadoobee | tier 0: `If You Want To - beabadoobee` -> 93 responses, 120 files<br>tier 1: `If You Want To` -> 250 responses, 2993 files | **Picker rejected everything Soulseek offered.** 120 files, 78 of them the right song, right artist, good quality. YouTube names it "beabadoobee - If You Want To (Official Video)" and the artist is appended again, so the picker takes "beabadoobee" as the title and demands it in the last dash-segment of every filename, where the real title sits. |
| grime | 23 | JME - Don't Get Rude Ft. Skepta - UkGrimeChannel | tier 0: `JME Don't Get Rude Ft. Skepta - UkGrimeChannel` -> 0 responses, 0 files<br>tier 1: `JME Don't Get Rude Ft. Skepta` -> 0 responses, 0 files | **Leftover noise in the search wording.** The uploader ("UkGrimeChannel") is the artist YouTube reports, and the credit "Ft. Skepta" stayed in both wordings; both returned nothing. Re-tested by hand as "JME Don't Get Rude": 9 responses, 12 files. |
| grime | 39 | Head, Shoulders, Kneez & Toez (Babycham Mix) - K.I.G | tier 0: `Head, Shoulders, Kneez & Toez - K.I.G` -> 10 responses, 12 files<br>tier 1: `Head, Shoulders, Kneez & Toez` -> 15 responses, 20 files | **Picker rejected everything Soulseek offered.** Twelve files, three of them the right song, right artist, 320 kbps, named "(Radio Edit)". The request says "(Babycham Mix)": the picker accepts "edit" as a remix-family word for the requested-version check, then rejects the same "edit" as an unrequested version word. |
| electronic | 1 | Anthems – Electronic 90s \| Ministry of Sound - Ministry of Sound | tier 0: `Anthems Electronic 90s - Ministry of Sound` -> 22 responses, 1187 files<br>tier 1: `Anthems Electronic 90s` -> 30 responses, 1227 files | **Not a song.** The playlist's own trailer video, listed as a track. 60 matching files came back (the compilation's album folders) but no file is called that. |
| electronic | 4 | EMF - Unbelievable (Official Music Video) HD - EMF | tier 0: `Unbelievable HD - EMF` -> 3 responses, 4 files<br>tier 1: `Unbelievable HD` -> 28 responses, 92 files | **Leftover noise in the search wording.** The bare "HD" outside the brackets survived cleaning, so Soulseek was asked for "Unbelievable HD - EMF" (4 files, all YouTube rips). Re-tested by hand as "Unbelievable - EMF": 250 responses, 730 files. |
| electronic | 22 | THE SOURCE FT CANDI STATON - You've Got the Love - blasreeder | tier 0: `THE SOURCE FT CANDI STATON You've Got the Love - blasreeder` -> 0 responses, 0 files<br>tier 1: `THE SOURCE FT CANDI STATON You've Got the Love` -> 5 responses, 5 files | **Leftover noise in the search wording.** Uploader ("blasreeder") as artist and the "FT CANDI STATON" credit kept; the title-only wording found 5 files, all 192 kbps, below the 320 kbps floor. |
| electronic | 34 | Hardrive ‎– Deep Inside [1993] - Old Skool Rewind Official | tier 0: `Hardrive ‎– Deep Inside - Old Skool Rewind` -> 0 responses, 0 files<br>tier 1: `Hardrive ‎– Deep Inside` -> 6 responses, 7 files | **Leftover noise in the search wording.** An invisible left-to-right mark glued to an en dash ("Hardrive ‎– Deep Inside") stopped the dash being read as a separator, so the odd characters went into the search and only 7 YouTube rips carry them. Re-tested by hand as "Hardrive Deep Inside": 250 responses, 1,137 files. |
| electronic | 45 | Erase / Rewind “Director's Cut” - The Cardigans | tier 0: `Erase / Rewind “Director's Cut” - The Cardigans` -> 0 responses, 0 files<br>tier 1: `Erase / Rewind “Director's Cut”` -> 0 responses, 0 files | **Leftover noise in the search wording.** Curly quotes and a slash ("Erase / Rewind “Director's Cut”") kill the search outright: zero responses for both wordings. Re-tested by hand as "Erase Rewind - The Cardigans": 250 responses, 426 files. |
| electronic | 51 | The Future Sound Of London - Papua New Guinea HD (Offical Video) - BapsyesBangerz | tier 0: `The Future Sound Of London Papua New Guinea HD - BapsyesBangerz` -> 0 responses, 0 files<br>tier 1: `The Future Sound Of London Papua New Guinea HD` -> 10 responses, 12 files | **Leftover noise in the search wording.** Bare "HD" again, plus the uploader ("BapsyesBangerz") as artist; the title-only wording found 12 files, two of them 320 kbps YouTube rips named "(Offical Video)", the rest 128-188 kbps. Re-tested by hand as "Papua New Guinea - The Future Sound Of London": 250 responses, 1,746 files. |
| pop | 20 | Total Eclipse of the Heart - Bonnie Tyler | tier 0: `Total Eclipse of the Heart - Bonnie Tyler` -> 0 responses, 0 files<br>tier 1: `Total Eclipse of the Heart` -> 0 responses, 0 files | **The Soulseek server drops the phrase.** Zero responses with the artist, zero for the title alone, zero again on re-test. "Bonnie Tyler" alone returns 250 responses and 9,399 files; "Tyler Eclipse" returns zero. The server discards anything containing that title. |
| pop | 53 | YMCA (Original Version 1978) - Village People | tier 0: `YMCA - Village People` -> 0 responses, 0 files<br>tier 1: `YMCA` -> 0 responses, 0 files | **The Soulseek server drops the phrase.** "YMCA" alone and "Village People" alone both return zero responses, on re-test too. |

By class:

- **Leftover noise in the search wording: 6 songs (2.4% of all songs).**
- **Picker rejected everything Soulseek offered: 3 songs (1.2% of all songs).**
- **The Soulseek server drops the phrase: 3 songs (1.2% of all songs).**
- **Not a song: 1 song (0.4% of all songs).**

### 4. Two searches never finish inside slskd

`Go - Moby` collected 236 responses (2,409 files) and `Slide - Goo Goo Dolls` 229 responses (461 files); both then
sat in slskd as `InProgress` with no end time, and both were still there an hour later. They are not alone: a
`Tell It to My Heart - Taylor Dayne` search from 25-09-2026 sits in the same state in the same list. This is
slskd's known stuck-search bug (slskd #1306, folded into #898). naviseerr reads "not complete" as "still running",
waits out the 120 s budget, and fails the song as `TIMED_OUT`. The responses are not recoverable: asking slskd for
them returns an empty list (it only stores a search's responses when the search completes), and asking slskd to
stop the search (`PUT /searches/{id}`) answers 200 but changes nothing, because the live search is already gone and
only its record remains. Neither stuck record held one of the two slots (two other searches ran alongside them
throughout), so the cost is one song each, not throughput.

## Comparison with this morning

| | This morning ('80s Indie + Alternative, 50 songs, no cap) | This trial (252 songs, cap of 2) |
|---|---|---|
| found | 18 (36%) | 237 (94.0%) |
| timed out | 26 (52%) | 2 (0.8%) |
| no candidates | 6 (12%) | 13 (5.2%) |
| searches slskd ran concurrently | 2 (the rest queued inside slskd) | 2 |
| HTTP 429 from slskd | not checked | none |

The playlists are different, so the "found" rates are not directly comparable, but the timeout column is: the
flooding explained essentially all of the morning's timeouts, and removing the flood removed them.

## Do we still need retries?

**No, not for the problem retries were being considered for.** Search timeouts went from 26 of 50 this morning to
2 of 252 here, and the two that remain are not slow searches: they are searches slskd itself lost track
of (finding 4). A blanket "retry timed-out searches" rule would have re-run 26 searches this morning to work
around a queueing bug that is now fixed, and today it would re-run two to paper over an slskd defect. The next
root-cause fixes, in the order of how many songs they recover:

1. **Clean the search wording further (6 songs, 2.4%; 4 of them confirmed by hand re-test to jump from 0-7
   files to 250 responses).** Strip bare `HD`/`HQ`/`4K` tokens wherever they sit, not only when a whole segment is
   noise; strip every quote character (straight and curly) and slashes; strip invisible Unicode marks and treat an
   en or em dash glued to one as a separator; drop `ft.`/`feat.` credits from the search (the picker still sees the
   full name). One place to change: `SearchQueryTiers`.
2. **Stop searching for the uploader (20 songs slowed, 3 of the 6 above lost).** When the artist YouTube Music
   reports is a channel or uploader — it is not contained in the title, and the title already has the
   `Artist - Title` shape — search the title as it stands first and never search the uploader wording. On the
   Ministry of Sound playlist that would have saved 20 wasted first searches of 10-12 s each and the trip to the
   back of the queue that followed each one.
3. **Two picker rules (3 songs, 1.2%; in every case the right file was in the results).** (a) Make the
   unrequested-version check family-aware: a file saying `edit` or `radio mix` is not an unrequested version when the
   request asked for a `mix`. (b) Judge files against the same cleaned `title - artist` the first search is built
   from (or against the adapter's structured title and artist, gotcha #2), so YouTube's `Artist - Title (Official
   Video)` wording cannot be mistaken for the title.
4. **Phrases the Soulseek server drops (3 songs, 1.2%).** Nothing on our side makes `YMCA` or `Total Eclipse
   of the Heart` come back; the server discards them, stably, hours apart. A third wording built from the artist's
   distinctive word and the title's rarest word dodges the drop sometimes (`Mortal Orchestra Trouble`: 250
   responses) and not always (`Tyler Eclipse`: 0). Worth adding as a last wording only after the two above, and the
   doc should say plainly that some songs cannot be found on Soulseek by name at all.
5. **Searches slskd loses (2 songs, 0.8%).** Report upstream with the two search ids (slskd #1306 / #898).
   A cheap experiment on our side: two of the three stuck records in slskd's list sit just under the 250-response
   limit (229 and 236; the third, from 25-09, at 156), so lowering the per-search `responseLimit` to 100 — far
   more than the picker's ten candidates need — might shorten the window in which a search can get stuck, and
   would make every search finish sooner besides. If, after that, the owner still wants a safety net, the
   narrowest one is not "retry timeouts" but "a search whose response count has not moved for 30 s while slskd
   still calls it in progress is lost; start a replacement". That is the one retry-shaped item in this list, and
   it is last.

## One thing to keep an eye on

A song that needs the title-only fallback goes back into the search queue behind every first-wording search of its
playlist, because work is taken oldest-first and the fallback is a new `SEARCH_INIT` row with a fresh due time. It
costs no budget (the clock only runs while a search is in progress) and nothing timed out because of it, but on the
electronic playlist the median song spent 260 s in the search phase against 5-25 s elsewhere.
If that ever matters, the fallback could keep the song's original due time so it re-enters near the front; fix 2
above removes most of the cases anyway.

## Housekeeping

- The five playlist runs, the 13 hand re-tests and the two cancel attempts all remain in the owner's slskd search
  history; slskd ages searches out on its own.
- The throwaway trial branch, the Postgres container on 5433 and the backend on 8081 were removed after the run.
  Raw per-song data (outcomes, tiers, slskd states and response counts) was kept in the session scratchpad only;
  this document is the record.

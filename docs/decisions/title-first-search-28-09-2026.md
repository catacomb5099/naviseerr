# Search for the title first, and make the picker insist on the artist

Date: 2026-09-28. Status: decided by the owner; shipped as four stacked PRs (steps 1-3 below, then the
search-start retry at the end). Reviewed by six independent adversarial readers before pushing; their
confirmed findings are folded into the steps below.

## What the owner asked for

> Keep it simple. Just search for the song name. If amongst all of the results you get you don't get enough
> songs that meet the criteria, then move on to what we currently do. Just the song name, removing all the
> fluff like versioning. Do that as the first try.

The example given was "My Curse - Killswitch Engage", reported as failed, while a hand search for "my curse"
in slskd returned over a thousand files with at least twenty by the right band.

## What the data said before anything was changed

- **The example did not reproduce.** Both downloads of "My Curse - Killswitch Engage" today (playlists
  "Metalcore Essentials" 10:32 and "Metalcore Meltdown" 11:07) succeeded on the first wording: slskd returned
  250 responses / 558 and 575 files, the picker accepted ten exact files each time, and the transfers completed.
  The hand search "My Curse" (15:45) returned 250 responses / 1,760 files, most of them other songs of that name.
- **Today's real failures were 10 of 584 songs (98.3% found):**

  | count | failure | why |
  |---|---|---|
  | 4 | NO_CANDIDATES | Soulseek returned zero peers for both "title - artist" and the title alone: "Bad Romance - Lady Gaga", "Born This Way - Lady Gaga", "18 and Life - Skid Row", "Happy Song - Bring Me The Horizon" |
  | 1 | NO_CANDIDATES | Japanese title ("月下の夜想曲"): 59 responses, but the picker only reads Latin letters and digits, so no file can pass while the request title is in Japanese. Needs the English title from `media_items`, not a search change |
  | 4 | SEARCH_FAILED | the first `POST /searches` failed and was not retried, because the 120 s search budget had started when the row was created and the song had waited 7-21 minutes for a search slot |
  | 1 | TIMED_OUT | slskd's stuck-search bug ("Regulate - Warren G" is still `InProgress` hours later) |

- **Soulseek blocks whole phrases, not words.** Probed live on the owner's slskd, one search at a time
  (12 s timeout). The last four rows are the exact wordings the code now sends:

  | search | peers | files | first files |
  |---|---|---|---|
  | Lady Gaga | 0 | 0 | |
  | Gaga | 250 | 6,536 | Lady Gaga tracks |
  | Bad Romance | 0 | 0 | |
  | Bad Romance Gaga | 0 | 0 | (still contains the whole blocked title) |
  | Skid Row / 18 and Life | 0 | 0 | |
  | Happy Song | 0 | 0 | |
  | Linkin Park / Michael Jackson / Depeche Mode | 0 | 0 | |
  | Linkin / Jackson Thriller / Depeche | 250 | 18,217 / 4,097 / 22,037 | the right artists |
  | Rihanna / Gorillaz | 0 | 0 | one-word names cannot be dodged |
  | Romance Gaga | 250 | 723 | `03. Bad Romance.mp3`, `01 - Lady Gaga - Bad Romance.mp3` |
  | Born Way Gaga | 250 | 3,090 | `105 - Lady Gaga - Born This Way (radio edit version).flac` |
  | 18 Life Skid | 250 | 465 | `Skid Row - 18 and Life.mp3` |
  | Happy Bring The Horizon | 250 | 429 | `Bring Me The Horizon - 02 - Happy Song.mp3` |

  A search is dropped when it contains every word of a blocked phrase (an artist or a hit title). Leave any
  one word out and it goes through. This is upstream of slskd (its request filter is empty).

- **The title alone is not safe as a first search with today's picker.** The picker judged the filename
  only, so for "Believe - Cher" a title-only search accepts every artist's `Believe.flac`. On the lab's
  3,663 labelled title-only results it accepted 359 wrong files.

## The three steps

1. **Picker: a file found without naming the artist must carry the artist in its path** to keep its grade
   (`TrackMatchingService.grade` with the wording). When the wording named the artist, Soulseek already
   matched it against the path, so nothing changes for those results. "The" is optional, accents are
   ignored, "LadyGaga" counts, and a name with no Latin letters or digits ("米津玄師") cannot be checked, so
   it is taken as named. A file that fails the check is not thrown away: it is graded **unverified** (the
   song by name, artist unknown) and `SlskdSearchResultProcessor` keeps such files only when no file in the
   search names the artist. That preserves today's behaviour for the two cases the 26-09 lab flagged as
   reachable only through a title-only search: a YouTube "artist" that is really a channel ("This Charming
   Man - Lo Mejor del Rock de los 80", "Just Like Honey - UPROXX Indie Mixtape") and a song shared only
   under a collaborator's folder. On the lab's title-only results: wrong files graded exact 359 -> 83,
   precision 0.874, recall on the fixture 0.689 (the fixture keeps only the last two folders of each path,
   so real Soulseek paths do better).
2. **Title first.** `SearchQueryTiers.of` now puts the bare title first (brackets, quotes, version words,
   guest credits gone), then the wordings used until now ("title - artist", the short-title form). The
   first wording is "enough" when at least `download-task.first-wording-min-candidates` (3) files are in
   the **requested version** with the artist confirmed; a request for a live take does not settle for three
   studio copies before the artist wording has run. Anything the first wording did find travels along on
   the row and is used when the artist wordings find nothing, or their search fails, times out or errors:
   files with the artist confirmed first, unverified files only when nothing better was ever seen. The
   later wordings are enough with one confirmed file, as before. `download_tasks.search_tier` now means
   0 = title alone, 1 = "title - artist", 2 = the blocked-phrase wording.
3. **A wording that gets past a blocked phrase.** After the title and "title - artist", the title minus one
   word plus the artist minus one word ("Romance Gaga", "18 Life Skid", "Happy Bring The Horizon", "Born
   Way Gaga", all four probed live above). The first stop word goes, else the shortest word; a one-word
   name stays whole. Rescues 4 of today's 5 NO_CANDIDATES. For titles longer than five words it takes the
   place of the bare short-title wording (the cap stays at four searches per song); the short title with
   the artist remains.

## What to watch

- Common one-word titles ("Believe", "Breathe", "Hello"): the 250-response cap can fill with other artists'
  songs, the picker then confirms fewer than three of the right one, and the song pays one extra search
  (about 12 s) before the artist wording runs. Checked live below: all three cleared the threshold today.
  The lab harness (`tools/search-lab/`) can measure how often they would not.
- A wrong-artist download remains possible exactly where it was possible before: a song whose YouTube
  "artist" is a channel name, or whose only copies sit in compilations or under a collaborator's name. Those
  are the unverified files, and they are used only when no wording found a file that names the artist.
- The tier-advance log line now says how many files the wording found and that the next wording names the
  artist; `GET /downloads/{id}` may report a `candidateCount` of 1-2 while a song is still searching (the
  files kept from the title-only search).

## Checked against live results (28-09-2026, after the code was written)

The real picker (the committed classes) run over the actual files slskd returned for these searches on the
owner's instance, counting only flac or 320 kbps files as the app does:

| request | wording searched | files | confirmed (exact / other version) | graded exact by the old filename-only rule |
|---|---|---|---|---|
| My Curse - Killswitch Engage | My Curse | 758 | 10 / 6, all Killswitch Engage | 30, including H2O's and The Afghan Whigs' "My Curse" |
| Believe - Cher | Believe | 4,726 | 13 / 6 | 442, mostly other songs ("I Believe", "Best Believe") |
| Breathe - The Prodigy | Breathe | 2,379 | 33 / 15 | 339, including Pink Floyd and Prefuse 73 |
| Judas - Lady Gaga | Judas Gaga | 529 | 191 / 261 | 452 |
| Bad Romance - Lady Gaga | Romance Gaga | 381 | 166 / 110 | 276 |
| 18 and Life - Skid Row | 18 Life Skid | 168 | 89 / 3 | 92 |
| Happy Song - Bring Me The Horizon | Happy Horizon | 179 | 98 / 10 | 108 |

So the owner's example downloads from the title alone with no second search (10 exact files is above the
threshold of 3), and the three common one-word titles clear the threshold too while the wrong songs are
kept out. One pre-existing gap showed up and is not fixed here: "Cher x LU2VYK - Believe (Dj Allan House
Mash-Up).mp3" is graded exact because the version words list knows "mashup" but not "mash-up".

## The independent fix, stacked last

A search that fails to start (`SEARCH_INIT`) is retried while the search budget lasts, as before, and at
least `slskd-service.retry-count` times whatever the wall clock says: the budget started when the row was
created and may have been spent waiting for a search slot, but no search is running yet. The error is kept
in `download_tasks.last_error` (the exception's class name when it carries no message, as a Netty read
timeout does), which was empty for all four SEARCH_FAILED rows today. If the row already holds files kept
from the title-only search, a search that cannot be started, errors or times out downloads those instead of
failing.

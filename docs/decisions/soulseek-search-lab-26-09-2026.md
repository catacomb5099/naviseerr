# Soulseek search lab: what to ask for, and how to pick the right file

Date: 2026-09-26. Status: findings, with recommended changes listed at the end.

## Why we did this

Naviseerr takes a song from YouTube Music and tries to fetch it from Soulseek. Two things go wrong: we ask
Soulseek the wrong question and get nothing back, or we get plenty back and download the wrong file (a live
recording, a DJ edit, a different song that shares a word). Until now both were tuned by hand on about 20 songs.
This lab ran the real thing at scale, with no downloads, and labelled what came back.

## What we did

- **Songs.** 809 real songs from nine YouTube Music playlists: 100 current chart songs (kept as a comparison
  group) and 709 catalogue songs from decade and genre playlists (90s rock, RHINO soul, RHINO 80s alternative,
  80s and 70s hits, 90s/2000s alt rock, early 2000s throwbacks). Tracks that were plain user uploads were dropped.
- **Searches.** 1,121 searches against the real slskd, one at a time, five seconds apart, never downloading.
  Every song got the bare query `title - artist`. Where the YouTube name carried a qualifier (remix, live,
  acoustic) it also got that query. Songs that came back nearly empty got a title-only query.
- **Labels.** For each song, up to 60 of the returned files (44,008 in total) were read by Claude Sonnet 5 and
  marked: is this the requested song by the requested artist, and which version is it (studio, live, remix,
  acoustic, instrumental, karaoke, cover, DJ edit). A cheaper model was tried first and audited against a blind
  second pass: it disagreed on 18.5% of files, so its labels were thrown away. Sonnet against a second Sonnet
  pass disagreed on 0.4%.
- **Analysis.** Every number below comes from joining those labels back to the search that produced each file.
  The raw data and scripts live in `tools/search-lab/`; the labelled sample is checked in as a test fixture.

## What we found

### 1. The bare `title - artist` query is the right first question

| query shape | songs searched | median files back | share of files that are the right song and version |
|---|---|---|---|
| bare `title - artist` | 809 | 603 | 64% |
| noise-stripped, qualifier kept (only where different) | 180 | 18 | 81% |
| title only (only where bare came back starved) | 119 | 341 | 23% |

The bare query found at least one correct file for 708 of the 718 songs where it returned anything. The
qualifier-keeping query is more precise when it answers, but it answers with almost nothing: 48 of its 180
searches returned zero files.

**The loose-search idea holds.** For the 52 songs whose YouTube title asks for a specific version (25 live,
19 remix, 2 acoustic, 6 other), the bare query's results already contained the requested version for 42 of
them. The qualifier-keeping query managed only 32. Ask loosely once, then choose locally.

### 2. Soulseek silently drops searches for some artists

45 of 809 songs (5.6%) returned zero files after the full timeout on the bare query while the title-only query
returned hundreds or thousands. Live re-tests on "Michael Jackson", "Depeche Mode" and "Linkin Park" confirmed
it is the artist name, not load or luck: any query containing the name comes back with zero peers, every time,
in any word order, while "Jackson Thriller" returns 5,408 files and "Thriller - Michael Jackson" returns none.
The same zero-then-thousands pattern hit Lenny Kravitz, Gorillaz, Rihanna, Beyoncé, Lady Gaga, Kanye West,
Kelly Clarkson, Franz Ferdinand, Bob Dylan and Ariana Grande in the run; those were not re-tested by hand. Our slskd has no request filter configured, so this happens upstream on the Soulseek network.

The signature is unmistakable: zero peers after the full 10-second timeout for a well-known song. The only
recovery is a query without the artist, and that query is 23% precise, so the picker has to carry the load:
it must insist on the artist appearing somewhere in the file's folder path. Because these are major artists,
the user-visible failure rate today is well above 5.6% of requests.

### 3. What to remove from the YouTube name

Sonnet tagged every bracketed fragment and extra segment in the 809 titles. The noise it found that the current
list does not yet strip:

- `Official HD Video`, `Official HD Music Video`, `Official 4K Video`, `Original Video`, `Video Oficial`
- channel or series tags: `Soul Sunday` (14 titles), `A COLORS SHOW`, `Closed-Captioned`, `Lyrical`,
  `Full Video Song`, `Video Song`, `Song`, `Tik Tok Hit`, `Dance Performance Video`
- film credits: `From "Zid"`, `From "The Paradise"`, `Telugu`, `Tamil`
- straight double quotes around a title (`KATSEYE "Animal"`) kill the search outright
- channel names used as the artist: `BlondieVEVO`, `StevieWonderVEVO`, `SublimeVEVO`, `RHINO`, `Stax Records`,
  `7clouds Rock`, `UPROXX Indie Mixtape`, `Lo Mejor del Rock de los 80`. These match no filename, so the song
  is lost unless the picker falls back to title-only.

Since the bare query drops every bracket anyway, the noise list only matters for deciding what is a
*meaningful* qualifier. Sonnet's meaningful list: remix, live, MTV Unplugged, acoustic, stripped, mono,
single version, radio edit, remaster years. Those should be kept as *hints for the picker*, not as search words.

### 4. The picker is where the damage is

Today's matcher (`TrackMatchingService.isMatch`) measured on the labelled files:

| rule | files accepted | precision (right song, right version) | recall |
|---|---|---|---|
| today's matcher | 37,226 | 70% | 97% |
| today's matcher, reject files whose name carries a version word we did not ask for | 32,585 | 79% | 96% |
| the above, also reject DJ-pool edits (`Clean`/`Dirty`, key+BPM tags like `12A 125`, `Intro`/`Outro`, `dj-promo`) | 31,691 | 81% | 95% |
| the above, and the artist must appear in the folder path | 29,848 | 82% | 91% |
| the above, and file length within 10 s of the YouTube duration | 17,713 | 91% | 60% |

Today's matcher accepts 11,167 files that are not the requested version. What they are:

| label | count |
|---|---|
| live recording | 2,628 |
| a different song entirely | 2,342 |
| DJ-pool edit, acapella, transition | 2,239 |
| remix | 1,867 |
| studio version when a remix or live was asked for | 1,228 |
| acoustic | 482 |
| karaoke | 171 |
| instrumental | 143 |
| cover by another artist | 67 |

The words that give them away, in order: live (1,086), remix (888), mix (649), edit (575), karaoke (445),
acoustic (285), demo (180), instrumental (134), mashup (115), bootleg (61), cover (52), unplugged (40),
slowed (38). 6,760 wrong files carry no version word at all; most are DJ-pool tags, other tracks from the same
album folder, or different songs sharing a word.

The matcher also rejects 741 correct files. They are mostly scene-style names with underscores
(`11-karol_g-bby_wow_(feat_judeline_and_rusowsky)-marr.mp3`) and files whose artist appears only in the folder
name. Feeding the matcher the folder path as well as the filename fixes part of this.

### 5. Duration is a strong tiebreaker, not a filter

slskd returns each file's length in seconds; naviseerr currently throws it away. Among correct files, the
median gap to the YouTube duration is 5 s, but only 65% are within 10 s, because YouTube's duration is the
*video* length. For songs whose YouTube entry is an audio track the gap is within 10 s 87% of the time; for
music videos only 58%. So: use length to rank candidates (closest first), never to reject them.

### 6. Where the songs come from matters

| playlist | precision of the bare query |
|---|---|
| Classic Soul Music (RHINO) | 74% |
| Best Rock & Alternative Songs 1990-1999 | 69% |
| Alt Rock Mix 90s 2000s (Redlist) | 67% |
| RHINO 80s Alternative | 61% |
| Early 2000s Throwbacks | 61% |
| Hits of 80s | 60% |
| 70s Music Hits (Redlist) | 56% |
| Top 100 Songs Global (charts) | 52% |

Current charts are full of regional film songs and K-pop video titles that Soulseek does not carry. Even the
decade playlists carry filler: the Redlist 70s list contains a dozen "MIRA" tracks that are not 70s songs at
all. Label-curated lists (RHINO) are the cleanest source for tests.

### 7. Speed

Median search completes in 4.5 s; 635 of 1,108 hit slskd's 250-peer response limit in under 3 s. The 90th
percentile is 21 s and the worst 44 s. The 120 s search budget is generous; 30 s would lose almost nothing.
Only 14 of 809 songs had no relevant file anywhere; they are obscure uploads, not real catalogue.

## What we recommend

Each of these is its own small change.

1. **Search once with the bare `title - artist`.** Drop the noise-stripped tier as a *search* shape; the
   bare query already holds the requested version 42 times out of 52. Clean the artist of channel suffixes
   (`VEVO`, `- Topic`, `Official`, `Records`) and strip quotes before searching.
2. **Detect server suppression and fall back to title only.** Zero peers after the full timeout for a query
   that names a known artist means the artist is dropped upstream. Retry with the title alone, and require the
   artist in the folder path when picking.
3. **Make the picker version-aware.** Reject files whose name carries a version word the request did not
   ask for; reject DJ-pool signatures; feed the matcher the folder path as well as the filename. This moves
   precision from 70% to 81% with recall still at 95%, before any ranking.
4. **Rank by length.** Map slskd's `length` into `SearchFile` and sort accepted candidates by distance to the
   YouTube duration before availability. Tolerate 10 s for audio-track entries and 30 s for video entries.
5. **Keep the labelled sample as a test.** `TrackMatchingServiceLabelledTest` runs the matcher over all
   44,008 labelled files and fails if precision or recall drops below a floor. Raise the floors as the picker
   improves; the number is the argument.

## What this does not settle

- Labels come from a model reading file paths, audited at 0.4% disagreement with a second run of the same
  model. They are not human-verified.
- Popular songs return more than 500 files; the harness kept the first 500 per search, and only 60 per song
  were labelled. Precision figures are on those samples.
- One evening on one Soulseek connection. Who is online changes what comes back.
- Michael Jackson style catalogues with dozens of official versions remain hard; nothing here fixes that.

## How to rerun

See `tools/search-lab/README.md`. The whole run took about six hours: five for the searches (one at a time,
by design) and the labelling ran alongside.

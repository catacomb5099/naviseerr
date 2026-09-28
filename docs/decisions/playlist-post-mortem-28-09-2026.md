# Post-mortem: two playlists, 203 songs, 63 failures (27-09-2026 evening)

Date: 2026-09-28. Status: findings, plus the fix this PR ships for the biggest class of failure.

## What happened

The owner requested two playlists on the evening of 27-09-2026: "Indie Pop" (133 songs) and "2010's indie
rock" (70 songs). Indie Pop finished 89 of 133 (67%), 2010's indie rock 51 of 70 (73%). The target is 95%, so
at most 10 failures out of 203; there were 63. They split into two groups:

- **49 songs ended `NO_CANDIDATES`** ("Soulseek had nothing we would accept").
- **14 songs ended `SOURCES_EXHAUSTED`** ("every file we tried to download stalled or failed").

Where the numbers come from: naviseerr's own Postgres (`downloads` and `download_tasks`), slskd's search list
(`GET /api/v0/searches`, 305 searches between 21:12 and 22:17 local time) and its transfer list
(`GET /api/v0/transfers/downloads`). Nothing in this document is a guess; each claim was checked against one
of those three.

## A. The 49 "no candidates": the picker was judging files by the wrong name

Every song has a name in the shape `<what YouTube calls the video> - <the channel that uploaded it>`, for
example `Neon Indian - Polish Girl - toomainstream` or `slimdan - 2 Dollar Bill (Official Visualizer) -
slimdan`. Soulseek was asked for the song with a cleaned-up wording and, for most of these 49, answered with
plenty of files: "Neon Indian Polish Girl" came back with 300 files from 235 people, "2 Dollar Bill" with
2,376, "BAD DREAMS" with 1,815, "The Con- Tegan and Sara" with 2,437.

The file picker (the part that looks at each returned file and decides "is this the song we want, by the right
artist, in the right version?") was then handed the **raw** YouTube name, not the cleaned one. It reads a name
as `Title - Artist`, takes whatever comes before the first dash as the title, and insists that every word of
that title appears in the file's own name. For `Neon Indian - Polish Girl - toomainstream` it decided the title
was "Neon Indian" and threw out every file called "Polish Girl". All 300 of them.

The 49 names fall into three classes:

- **14 are `Artist - Title - Artist`.** Official channels are named after the artist, so the artist appears
  twice. The search wording already removed the echo; the picker never saw that cleaned version.
- **13 are `Artist - Title - fan channel`** (toomainstream, SyncYouNow, Lorem Ipsum, Julia, Marta Sinestesia,
  MultiBananachips...). Two things went wrong here. First, the first search asked for the song *with the
  channel as the artist* and got zero results every single time (41 of the 77 empty searches that evening were
  this), wasting about 10 seconds each. Second, when the next wording did find files, the picker rejected them
  as above. Three of these also came back empty on the second wording because a trailing "Lyrics" / "w/ Lyrics"
  had survived the clean-up ("The Postal Service Such Great Heights Lyrics"). And "Two Door Cinema Club" is an
  artist the Soulseek server silently drops (like MGMT, see the 26-09 lab), so a wording without the artist was
  needed and did not exist for this shape.
- **22 are the normal `Title - Artist`.** About 10 of these returned nothing on either wording because the
  artist is tiny or brand new (Venus Anon, Wilmah, jo from school, Pool Girl, nothhingspecial, Finnian James,
  Ethan French, Ruby Waters): they are not on Soulseek at all, which puts a ceiling of roughly 95% on this
  particular playlist. The rest returned files the picker rejected for smaller reasons: "Tongue Tied by
  Grouplove - Hyde" (the "by" credit was read as part of the title), "The Con- Tegan and Sara" (a dash with no
  space before it was not read as a separator), "Floette ft. John Glacier" (a guest credit in the title that no
  Soulseek file carries), "bloodstream (stripped)" (asks for a stripped version, so rejecting the plain files may
  be right), "I Needed You to Know" (84 files, all below the 320 kbps floor as far as we can tell), "Asleep
  Talking (Acoustic)" (112 files, none saying "acoustic").

## B. The 14 "sources exhausted", and why everything was slow: sharers who queue forever

slskd still lists 58 transfers from that evening sitting at "Queued, Remotely" and 0%. Almost all belong to
two sharers: `SKYLiGHT_B` (about 40, scene-release folders) and `musicmasterrdjpool` (about 10, DJ-pool
"(Radio).mp3" / "(Main).mp3" files). Each of those transfers cost the full 10-minute "queued" allowance before
naviseerr moved on to the next candidate. `SKYLiGHT_B` alone stalled 23 songs that later succeeded from someone
else; the 12 songs whose *every* candidate was one of those two ended `SOURCES_EXHAUSTED`.

The cost shows up as time. Songs whose first source worked finished on average 22 minutes after the playlist
was requested; one failover pushed that to 33 minutes, two to 36, three to 43. The transfers themselves were
not slow (median 4 seconds to start, median 6 MB/s); the waiting was. The abandoned transfers are never
cancelled in slskd (the code says "cancelling it is the next PR"), so they also sit in slskd's list forever.

Smaller transfer failures that evening: 8 "File not shared" (the sharer's index is stale; retrying the same file
is pointless), 2 "Overwhelmed with requests" (we asked one sharer for several files at once), 3 "Remote
connection closed", 1 wait timeout.

Today's candidate ranking puts "most people share a file of this length" first, and only then free upload
slot, queue length and speed. Whether `SKYLiGHT_B` claimed a free slot is unknown: the free-slot flag and queue
length are not stored on the candidate.

**This section is being fixed in a separate PR** (sharer reliability: skip sharers who keep us queued, cancel
abandoned transfers). It is recorded here so the whole evening is in one place.

## C. Search throughput (minor)

305 searches in 43 minutes, about 7 a minute, two at a time, each bounded by slskd's own 10-second timeout.
77 (25%) returned nothing, and 41 of those were the channel-as-artist wordings from section A. Search is not
the bottleneck; the queued-remotely stalls are.

## Fixes in this PR (section A)

One idea runs through all of them: **search generally, pick precisely.** The search wording gets looser and
more varied so Soulseek has every chance to return the song; the picker gets the *same cleaned name* the
searches are built from, plus the version words it needs, and does the discriminating.

### The picker is handed the cleaned name

`SearchQueryTiers` now exposes `pickerName`, and `DownloadStepExecutor` hands that to the picker instead of the
raw YouTube name. It is the same parts the searches are built from, joined with " - ", with two things kept
that the searches drop: bracketed version words such as "(Live)", "(Acoustic)", "(Remix)", because the picker
chooses the version from them; and a version-naming middle segment, folded into brackets ("Kiss Me - Radio
Edit - Sixpence None The Richer" becomes "Kiss Me (Radio Edit) - Sixpence None The Richer") so the picker
reads it as a qualifier, not as a title.

### Three-part names are read for what they are

A name with three parts is either `Title - Qualifier - Artist` (the middle names a version: "Radio Edit",
"Remastered", "Live at...") or `Artist - Title - channel` (anything else). For the second shape the picker
accepts a file when *any* of the leading parts is fully present in the file's own name and at least two of the
three parts appear somewhere in it. The channel is never required.

### The name is cleaned a little further

- A trailing "Lyrics", "w/ Lyrics" or "with lyrics" comes off the title.
- "Tongue Tied by Grouplove - Hyde": a lower-case "by" followed by a capitalised name is a credit, so the song
  becomes "Tongue Tied - Grouplove" and the uploader is dropped. "Stand By Me" (capital B) and "Blinded by the
  Light" (lower-case "the") are left alone.
- "Floette ft. John Glacier": a "feat./ft./featuring" credit in the title comes off everywhere. Soulseek files
  rarely carry it, and a search must match every word, so keeping it could only lose results.
- "The Con- Tegan and Sara": a dash glued to the word before it but not the one after is a separator.
  "Metric-Black Sheep" is **not** handled: the text alone cannot tell it apart from "Tone-Loc" or "Jay-Z", so
  it stays one word and that song still fails.

### More search wordings, most specific first, at most four

A wording is only tried when the one before it produced no file the picker accepts, so a looser wording costs
nothing when a tighter one hits. Four wordings is a hard cap: about 40 seconds of slskd time for a song that is
not there.

For `Title - Artist`:

1. `Title - Artist` (the bare pair, as before)
2. `Title` (the Soulseek server silently drops some artists; as before)
3. `Short title - Artist`, where the short title is the first five words with any dash qualifier removed,
   only when that is different
4. `Short title`

For `Artist - Title - channel`:

1. `Artist Title` (never the channel first: it found nothing in 41 tries out of 41)
2. `Title` (the artist may be one the server drops: Two Door Cinema Club, MGMT)
3. `Artist Short title`, then `Short title`, when different
4. `Artist Title - channel`, last and only if there is room

The rule "only advance when the previous wording produced no accepted candidate" is unchanged, and so is the
storage: only the index of the wording in use is kept on the task row, the wordings are recomputed from the
name every time, so these rules can change again without a migration.

### Guard rails

The two labelled tests over the 26-09 search-lab fixture (44,008 real Soulseek files, labelled by hand) still
pass, now run through the same cleaning the executor uses:

| | before | after |
|---|---|---|
| per-file precision | 0.865 | 0.866 |
| per-file recall | 0.859 | 0.891 |
| songs with a pick (of 718) | 687 | 695 |
| top pick is the right song and version | 665 (96.8%) | 672 (96.7%) |

Recall is up because a file named the other way round ("6 FEET UNDER - Ruby Waters.mp3", artist last) is now
judged on its whole name instead of failing the "title in the last segment" rule. More accepted right files also
means more fallback sources when a sharer stalls (section B).

### Measured recovery, search-only, on the real slskd

The 49 failed names were searched again on 28-09-2026 with every wording the old and new rules produce (the
channel-as-artist wordings excepted, already measured at 0 results), two searches at a time, no transfers.
The saved results were then replayed through the old pipeline (old wordings, old picker, raw name) and the
new one (new wordings, new picker, cleaned name).

111 searches, about 19 minutes, two at a time.

| | old pipeline | new pipeline |
|---|---|---|
| songs with at least one accepted candidate, of 49 | 2 | 25 |
| rescued by the 1st wording | 1 | 15 |
| rescued by the 2nd wording | 1 | 10 |
| rescued by the short-title wordings | - | 0 (only 4 songs had them; all 4 are artists not on Soulseek) |

(The old pipeline scores 2 here against 0 on the night: Soulseek results vary with who is online.)

The 24 still failing, and why:

- **Not on Soulseek in 320 kbps or FLAC (16).** Ally Evenson, Pool Girl (both songs), Gus Dapperton "Queen"
  (3 files, none good), Fine "Everything in the shade", jo from school, Venus Anon, nothhingspecial, Saskia,
  Wilmah "My Abby" (only other Abbys), Finnian James, Tove Styrke "Me/You" (one file, below 320), Whitney Woerz
  "Sherry Wine" (7 good files, all other songs), Ethan French, Sigur Rós "Heima Trailer" (not a song), The XX
  "Intro long version]" (a broken bracket in the name; 5 YouTube rips).
- **Rejected, and rightly (6).** "Asleep Talking (Acoustic)": 156 good files, none acoustic. "bloodstream
  (stripped)": 81 good files, none stripped. "She's Gonna Break My Heart": only DJ-pool "(Radio)" edits.
  "I Needed You to Know", "I DON'T GET HIGH ANYMORE" (Phantogram's song came back), "i think of you the most":
  other songs sharing the words.
- **Known limits (2).** "Metric-Black Sheep": 54 good files, the dash is not split (see above). Tone-Loc: the 2
  good files sit on a "Various Artists" soundtrack with no artist in the name, and the picker insists on the artist
  when the title is searched alone.

So of the 49, 25 are recovered, 22 cannot be recovered by any wording or rule, and 2 are known limits.

### The search is version-blind, the picker is version-aware (follow-up, same day)

Owner's decision: when a request names a version (acoustic, stripped, live, remix...) and no such file is
shared, any version of the song is better than no song; and a request for the original should still fall
back to a live or remixed take once every exact option has been tried. Remastered is never a version.

Two halves:

- **No search wording ever carries a version word.** Whether it sat in brackets ("(Live)"), in its own dash
  segment ("Wonderwall - Remastered - Oasis", "Kiss Me - Radio Edit - X") or inline at the end of the title
  ("Wonderwall Live at Wembley", "Remastered 2009", "'95 Version"), it comes out, and the wordings collapse and
  de-duplicate ("Wonderwall - Oasis", "Wonderwall"). Soulseek matches every word, so a version word in the
  search could only ever lose files. `pickerName` keeps every one of those qualifiers, in brackets, because
  the picker needs them.
- **The picker grades instead of accepting or rejecting.** `TrackMatchingService.grade` returns `EXACT` (the
  song, by the artist, in the requested version), `OTHER_VERSION` (the song by the artist but a different
  version, in either direction) or `NONE` (not the song: DJ-pool edits, album siblings, other artists; those
  rules are unchanged). `SlskdSearchResultProcessor` keeps `OTHER_VERSION` files and ranks every `EXACT` file
  ahead of every `OTHER_VERSION` one; within a grade the order is as before (the length most files share, then
  free slot, queue, speed; the length vote is counted within the grade so other versions cannot outvote the
  exact ones). Because the candidate list is walked in order, a live take is only downloaded once every exact
  option has failed, with no change to the state machine. The cap of ten candidates is unchanged: other
  versions take whatever places the exact ones leave, so a song with ten exact copies never falls back, and a
  song with none gets ten fallbacks. Each stored candidate now carries its grade (`grade` in the JSON, null on
  older rows), and a song that succeeds from an `OTHER_VERSION` candidate says so in the log.

Measured on the labelled fixture: an `EXACT` file is the right song by the right artist 97.7% of the time, an
`OTHER_VERSION` file 91.7% (5,994 files). Requiring the artist's name in the file path for the fallback grade
would have raised that only to 91.9% while dropping a tenth of the good fallbacks, so it was not added. The
per-file precision and recall floors and the top-pick figure are unchanged by this change (0.866 / 0.891 /
672 of 695), because `isMatch` is `EXACT` only and `EXACT` still ranks first.

Replay of the saved 28-09 results for the 49 names: 25 songs with an exact candidate (as before), **28 with a
candidate of any grade**. The three that moved: "Asleep Talking (Acoustic)" and "bloodstream (stripped)" now
fall back to the studio take, as intended; "Me/You - Tove Styrke" is a false positive (a title-only search
for two common words, no file names Styrke), the known weakness of title-only searches from the 26-09 lab,
now no longer masked by the version rule. "She's Gonna Break My Heart" stays out: its only good files are
DJ-pool "(Radio)" edits, and that rule still runs first.

### Known limits left in

- `Metric-Black Sheep` (a dash with no spaces) is not split; see above.
- An `Artist - Title - channel` name whose title happens to contain a version word ("Live and Let Die") is
  read as `Title - Qualifier - Artist` and behaves as before this PR.
- A `Title - Subtitle - Artist` name whose subtitle has no version word ("sticks and stones to telephones -
  day 52 - Ethan French") is read as `Artist - Title - channel`; the wordings are odd but the picker still
  demands two of the three parts, so precision holds.
- Songs by artists who are not on Soulseek at all cannot be recovered by any wording.

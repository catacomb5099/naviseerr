# YouTube Music albums: joining songs to their album, and tagging the files

**Date:** 04-10-2026
**Status:** Accepted. Three PRs: songs filed in their album's folder (B1, this one); finished files
carry YouTube Music's details in their tags (B2); a song you already have is not downloaded again (B3).
The schema for all three is V12 ([persistence.md](../architecture/persistence.md#v12-album-metadata)).
**Builds on:** [library-organiser-27-09-2026.md](library-organiser-27-09-2026.md),
[download-metadata-25-09-2026.md](download-metadata-25-09-2026.md)

## Context

The owner's asks (04-10-2026): YouTube Music is the source of truth for a song's details, but details
a file already has are not overwritten; and "if a couple songs are found with different parent albums
but the actual youtube search reveals they should have the same album then join them together to a
common album youtube music specifies".

Before this, a song or playlist track was filed in `<artist>/<song title>/`: ten songs of one album
requested from a playlist made ten one-song folders, and Jellyfin, which makes one album per folder,
showed ten albums. Navidrome groups by tags, which come from whichever sharer's file it was.

YouTube Music's album for a song cannot simply be taken (measured live on 04-10-2026):

- **It is often not the artist's album.** "Don't You (Forget About Me)" is on "Driving", a Various
  Artists compilation; "Lose Yourself" is track 2 of the "Just Lose It" single; "Blinding Lights" is
  the one-track "Blinding Lights" single. Filing those there scatters an artist's songs further.
- **An official video has no album at all.** `/v1/songs/{id}/details` says `album: null` for
  `6hzrDeceEKc` (Wonderwall's video), while the audio upload `hpSrLjc5SMs` names "(What's The Story)
  Morning Glory?".
- **Songs of one album are named on different editions.** Supersonic's details name "Definitely
  Maybe"; Live Forever's name "Definitely Maybe (Deluxe Edition Remastered)". Two editions, two folders.
- **Ids repeat and differ.** An album page often lists the official-video id, a playlist the audio
  id, so the same track arrives under two ids with lengths 276 s and 324 s; and one album can list one
  id twice under two titles (the 30th Anniversary edition's tracks 21 and 24 are both `h7-BHdjeEY0`).

## Decision

### When YouTube's album counts (P1)

An album download always uses the release the user picked. For a song or playlist track, YouTube
Music's album is **trusted** only when its type is `Album` or `EP` and its artists include the song's
first artist (names compared case-, accent- and punctuation-blind; the uploader's channel id is never
used, because an official video's channel is not the artist's YouTube Music channel). Never a Single,
never a Various Artists compilation. No trusted album: the song is handled as before.

*Flip:* `SongAlbumResolver.Want.trusts` is the whole rule. Adding `Single` there brings back the
"Just Lose It" case.

### Where the album is looked for

`SongAlbumResolver`, per YouTube id, one adapter call at a time:

1. A suggested-playlist (CURATED) song: the curator's own album id for it (the curator picked the song
   off that album's page). Read again from the curator at lookup time rather than stored at admission:
   no column for it, and an edition replaced since, or a curator that is down, just falls through to 2.
2. Otherwise `/v1/songs/{id}/details` → its album id.
3. No album (an official video), or the album is not trusted: `/v1/search/songs` with the wording that
   names the artist (`SearchQueryTiers.of(song_name).get(1)`, "Wonderwall - Oasis"), first five rows.
   A row counts when its artist and title match the song and its length is within 3 s of the song's
   (an official video's own length is the video's, so there the first matching row's length is used).
   Each such row's album is opened in turn until one is trusted and has the song.
4. The song's row on that album: the row with one of the song's ids AND its title, else its title and
   a length within 3 s. Titles are compared with version qualifiers kept ("Wonderwall (Unplugged)" is
   not "Wonderwall") but remaster and guest notes dropped ("(2001 Remastered Version)",
   "(feat. Rihanna)": the same recording). An album that does not have the song is not its album.

Answers go to `song_albums` (`album_id` NULL = looked, nothing trusted) with the album's own
`media_items` row written first. A NULL answer is asked again after 7 days, for a song requested again.
An adapter outage stores nothing; the song is asked again on the next tick.

### The plainest edition (P4)

The album found is replaced by the plainest edition that also has the song: the shortest title among
the album and its "Other versions" that are the same artist's Album or EP, the smaller browse id on a
tie. At most one extra album fetch, and only when the album found is not already the plainest. It is
decided from the album page alone, so two songs of one playlist, and two installs, land on the same
edition whichever edition each was found on: Supersonic and Live Forever both end in "Definitely
Maybe" (`MPREb_Hl8XJR59OrY`). Morning Glory has two editions with the identical plain title; the
smaller id wins, so every song of it agrees.

Rejected: "the edition most sibling songs already use" (critic-product #4). It depends on which song
was looked up first, so the same playlist could file differently on two installs. An ALBUM download is
never moved to another edition.

*Flip:* `SongAlbumResolver.plainest` returning its argument keeps YouTube's own edition per song.

### Its own loop, never the history

The lookup runs on its own `Flux.interval` (the download loop's interval and batch size), not inside
`DownloadTaskRunner.pass()`: one song costs two to six adapter calls of 1-15 s, and searching and
polling must never wait on them. It only looks at songs still downloading, or finished within the
organiser's ten-minute window and not filed yet: the install's history is never trawled, and files
already filed are never moved (P8). Off when the organiser is off. The writes are idempotent upserts,
so a second instance only wastes calls.

### Folders (P3)

A song or playlist track with a trusted album is filed in `root/<album artist>/<album title>/`, the
folder an album download of that album uses, so Jellyfin shows one album. Otherwise as before
(`root/<artist>/<song title>/`). Album downloads as before.

A finished song or playlist track with no answer yet waits up to two minutes
(`LibraryOrganiser.ALBUM_LOOKUP_GRACE`) for one, then is filed by its own name. The wait is in the
filing query's WHERE, so waiting songs never take the batch's places from songs that are ready.

*Flip:* drop the `albumTitle` branch in `LibraryOrganiser.targetFolder` to file every song by its own
name again; the lookup then only feeds the tags.

### Tags (P1, P2, P10: B2)

Under a trusted album (and always for an album download) ALBUM, ALBUM ARTIST, the release date in every
field the format has, TRACK, TRACK TOTAL and DISC 1/1 are written from YouTube Music, and the tags that
split an album in Navidrome and Jellyfin are cleared (MusicBrainz release-level ids, compilation
flags, album version, original date, stale Vorbis YEAR/TOTALTRACKS/TOTALDISCS, label and catalogue
number). Everything else, and every album tag without a trusted album, is fill-only: kept when the
file has it, filled when it is empty. Opus, APE and WavPack files are filed untagged; a tagging failure
never stops a file being filed.

### Already owned (P7: B3)

A song already in the library from an EXACT pick whose file still exists is not downloaded again: at
admission by album and track number, or for a song by its YouTube id with the same title; at filing, a
trusted song whose album and track number are already filed points at the existing file.

## Consequences

- Blinding Lights stays unjoined: every route YouTube offers from its ids leads to a single, and the
  search's first five rows never list it on "After Hours". Filed by its own name, as before.
- A song YouTube only has on compilations or singles is never joined. Lose Yourself: the 8 Mile
  soundtrack is Various Artists, and Eminem's own "Curtain Call: The Hits" lists it as "Lose Yourself
  (From "8 Mile" Soundtrack)", a different title by the rule above.
- Until B3, a song requested again after its album was downloaded whole lands in that album's folder
  as a second copy, so Jellyfin shows it twice in the album (before, a one-song album of its own).
- Navidrome groups by tags, not folders: until B2 writes them it shows the same albums as before.
- An older naviseerr on the same database files songs and playlist tracks at once, by their own name.
- A week-old "nothing trusted" answer counts as an answer for filing; a song re-requested after a week
  whose lookup has not re-run by the time it finishes is filed by its own name (`ponytail:` in
  `TASKS_TO_ORGANISE_SQL`).
- Ids that time out every time are retried every tick, oldest first (`ponytail:` in
  `SongAlbumResolver.resolveDue`).

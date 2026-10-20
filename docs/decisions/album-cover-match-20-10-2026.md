# A downloaded album always carries the picture you clicked (20-10-2026)

## The problem

The owner downloaded Tame Impala's *Currents* and the music server showed a different picture from the one naviseerr
showed. Naviseerr's side was right: YouTube Music has one *Currents* (`MPREb_AjwAqo5hI0U`) with one cover, used on the
search card, the album page, the artist page and every song row (measured 20-10-2026). The files were what differed.

Under a trusted album the tagger replaces the file's picture with YouTube's (P1 in
[youtube-album-tags-04-10-2026.md](youtube-album-tags-04-10-2026.md)). Two holes left the sharer's own picture in place:

- **A. The cover fetch failed and the song was filed anyway.** `SongTagger.write` only deletes the old picture when it
  has a new one to write, and a failed fetch was one INFO line. Nothing re-tags a filed song, so one failed fetch left
  the sharer's picture for good. The owner's 09-10 container log showed DNS failures for `yt3.googleusercontent.com`,
  the only host the tagger fetches from. Most likely cause (by elimination; the owner's NAS log was not readable).
- **C. A FLAC can carry its picture in a Vorbis comment** (`METADATA_BLOCK_PICTURE`, or the older `COVERART`), written by
  some taggers and Ogg-to-FLAC converters. jaudiotagger 3.0.1's `FlacTag.deleteArtworkField` removes only the FLAC
  PICTURE blocks, so the sharer's picture stayed next to YouTube's.

(B, a format the tag library cannot write such as Opus, is unchanged: filed as it came, P10.)

## The decision

- **A song of a trusted album waits for its cover.** In `LibraryOrganiser.fileBlocking`, when the song has a trusted
  album, its picture is YouTube Music album art, and the cover came back empty, the song is not filed on this pass.
  It is asked again on the next pass, until `COVER_GRACE` (5 minutes) after it finished.
- **After 5 minutes it is filed anyway, keeping the sharer's picture**, with one WARN naming the album:
  `Filing song <id> without the cover of album '<album>': YouTube's picture would not load within 5
  minutes; the file keeps the picture it came with`. A picture beats a grey square.
- **A failed cover is asked for again every 30 seconds at most** (`SongTagger.COVER_RETRY`), not on every 2-second pass,
  so an outage costs one INFO line ("Could not load the cover ...") per album per 30 s.
- **A FLAC loses the sharer's picture everywhere** when YouTube's is written: the Vorbis comment pictures are cleared
  next to the PICTURE blocks.

## Why

- Follows Apple Music and Spotify: an album added from the catalogue shows the catalogue's artwork in your library.
  That was already the rule (P1); the bug is that it gave up silently.
- 5 minutes stays inside the organiser's 10-minute window (`GIVE_UP_AFTER`), so waiting never leaves a song unfiled.
  A short blip (most DNS and network failures) is over well before then.
- Songs without a trusted album are not held: their cover only fills a file that has none (P2).

## Flip

- No wait (today's old behaviour): `LibraryOrganiser.COVER_GRACE = Duration.ZERO`. Longer: up to 9 minutes (keep it
  below `GIVE_UP_AFTER`).
- No picture rather than the sharer's after the wait: in `SongTagger.write`, call `tag.deleteArtworkField()` when
  `plan.album()` and the cover is empty. Navidrome then shows no picture (or Last.fm's, if configured).
- Retry cadence: `SongTagger.COVER_RETRY`.

## Not done

- Files already in a library are not re-tagged. To fix one album (the owner's *Currents*): delete its folder from the
  library and download it again; admission checks the disk, so it is fetched and tagged afresh. A "refresh every
  album's picture" pass would need a Flyway slot and was not built (owner question Q29, default a).
- A different DNS resolver for the cover fetch (`fix/ai-cover-fetch-dns`) waits for the owner's NAS log to show
  `Failed to resolve 'yt3.googleusercontent.com'`.
- Opus, APE and WavPack files keep the sharer's picture (P10). During a long YouTube image outage songs sit in slskd's
  downloads folder up to 5 minutes longer before they reach the library; the download itself finishes on time in the app.

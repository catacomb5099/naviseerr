# Choose the download quality (19-10-2026)

> Status: accepted. Owner's ask 14 of 19-10-2026 (dictated, 22:20): "there should be a screen both in the setup wizard
> and the settings to allow the users to select the music quality. This should mirror what Spotify looks like ... something
> simple as low, medium, high, ultra high, lossless ... forget the format, the format is just going to cause more issues."
> Research in the sweep notes (`research/download-quality.md`, read-only, measured on the 44,008-file search-lab fixture).

## The problem

Naviseerr had no quality setting. The picker refused every lossy file under 320 kbps (`slskd-service.min-bit-rate`) and
every lossy file whose bit rate Soulseek did not report, and took FLAC and 320 kbps as equals: among the files that are
the song, the sharer who could serve first won. Measured on 718 real songs that landed on lossless 61% of the time and on
320 kbps 39%. Two things followed: nobody could ask for smaller files (a phone, a small disk) or insist on lossless, and
a song shared only below 320 kbps failed with `NO_CANDIDATES` (1 of 718 in the lab; 16 of the 28-09 playlist failures).

## The decision

- **Spotify's list, Spotify's words, no format.** Five rows, lowest first: Low (about 128 kbit/s), Normal (about 192),
  High (about 256), Very high (about 320), Lossless (FLAC and other lossless files). The bit rates are what Soulseek
  files actually are (the MP3 rungs), not Spotify's streaming rates. One Lossless tier, no hi-res split (Spotify; Apple
  and Tidal split it). Flip: the owner's own words (low / medium / high / ultra high / lossless).
- **One setting for every download**: songs, playlists and albums alike (Spotify mobile and Apple Music keep one
  download-quality setting; we have only downloads).
- **A preference, not a floor.** The picker prefers files (and album folders) in the chosen tier; when the tier has
  nothing acceptable it tries every higher tier, nearest first, and only then the lower tiers, nearest first:
  Lossless → Very high → High → Normal → Low; Very high → Lossless → High → Normal → Low; High → Very high → Lossless →
  Normal → Low; Normal → High → Very high → Lossless → Low; Low → Normal → High → Very high → Lossless.
  **Owner's call (20-10-2026, 10:50):** "high should check very high and lossless before it checks normal, same with
  normal - check all higher options before going lower." The 19-10 rule was the nearest tier with ties going up
  (High → Very high → Normal → Lossless → Low; Normal → High → Low → Very high → Lossless): a High chooser whose song
  was shared at 192 kbps and in FLAC got the 192. Only the High and Normal orders change; the song list, the sharer
  round-robin and the album folder order all key on `DownloadQuality.distance`, so the change is that one method.
  Flip: back to the nearest tier, ties going up (`distance` = `|tier - chosen| * 2 + (tier below chosen ? 1 : 0)`).
  The 320 kbps floor goes with it: a song shared only at 192 kbps now
  downloads at 192 instead of failing. What was taken shows in the song's Choose-a-file pop-up (the person icon on
  a Downloads row, `GET /downloads/{id}/candidates`): every file's quality, the one being taken marked. The Downloads
  row itself names the sharer only (review of 20-10-2026: the first wording said "the Downloads page's Quality column",
  which does not exist; putting the taken file's quality on the row is a small follow-up).
  Flip: "only this tier" (fail instead of falling back); not built.
- **Default = Lossless, "what naviseerr did until now".** The nearest single tier to the old behaviour: replayed on the
  lab fixture, lossless picks go 61% → 97% and the right-version rate does not move (0.931 → 0.933 with the
  research's proxy; `PickerLabelledTest` with the real matcher: 699 songs with a pick, 0.976 exact, 0.964 lossless). What an existing
  install will notice: the ~39% of picks that were a 320 kbps MP3 because that sharer was quicker become FLAC (bigger
  files). Flip A: Very high (matches the words "320 floor" but turns ~60% of today's FLAC picks into MP3s). Flip B: a
  sixth row "Automatic" keeping the old floor rule verbatim (two picker rules alive; not under ponytail).
- **First screen of the setup (1 of 5), first section of Settings**, the same component, saving the moment a row is
  chosen (as the playlists switch does); the wizard's Next moves on, Skip keeps the default.
- **A file's tier**: suffix first (lossless formats report no bit rate: `flac`, `wav`, `aif`, `aiff`, `ape`, `wv`, today's
  set), then slskd's `bitRate` (a VBR file's is its average): Very high ≥ 288, High 224-287, Normal 160-223, Low 1-159,
  the midpoints between the measured MP3 rungs (320: 13,671 files; 128: 3,060; 192: 2,552; 160: 749; 256: 562); 160 is
  equidistant and goes up. A lossy file with no reported bit rate (1,173 files, 2.7%, mostly m4a and opus) has no tier
  and stays out of the automatic pick as before, shown "—" in the manual pop-up. Not widened: `alac`/`dsf` and
  ALAC-in-`.m4a` (rare; a flip in its own PR).
- **Where the tier sits in the song order**: grade → overloaded sharer last → tier distance → the length most files
  share → availability. Measured 97.8% lossless picks with the tier before the length vote against 95.9% after it, same
  exactness; the tier never overrides the version protection (grade) or the never-serves protection (overloaded).
  Flip: tier after the length vote. The sharer round-robin that follows the sort (no sharer's second file before every
  other sharer's first, so a stalling sharer costs one candidate) runs within a run of equal grade, overloaded-ness and
  tier distance (review of 20-10-2026: per grade, as before, it put another sharer's 128 kbps file ahead of the first
  sharer's second lossless copy; until 19-10 the list held lossless and 320 only, so the spread never cost a tier).
  Price: a sharer alone in your tier keeps its files in a row. Flip: spread per grade again.
- **Album folders**: usable = every audio file with a known tier (the floor goes here too); a folder is as far from
  the choice as its farthest matched file; `bestFirst` = overloaded → tier distance → most tracks → free slot → queue
  → fewer extras → speed (review of 20-10-2026: the overloaded term sat after "most tracks", so with the three free FLAC
  sharers of the Definitely Maybe fixture stalling, a Lossless chooser's eleven songs all went first to niccage, no slot
  and 52 queued, and waited out their budget before fennyfom's free 320 kbps whole folder; the never-serves protection
  comes first for albums as it does for songs). Tier before "most tracks" on purpose: with the floor gone a 128 kbps whole folder exists for
  popular albums (Discovery fixture: 14 MP3@128 in one folder), and a Lossless chooser should get the FLAC 13-of-14 and
  one own search, not 14 songs at 128. Price: a FLAC part beats a 320 whole folder (Laughing Stock fixture: Nilkse's
  6 FLAC and moburma's 5 FLAC now rank above Baron53's 6 MP3@320). Flip: tier after "most tracks".
- **Storage and read**: `install_settings.download_quality` (V19, text, null = default), `GET/PUT
  /settings/download-quality` → `{quality, default}`, read from the row once per completed search (one SELECT), so a
  change in Settings applies to the next download, no restart, and every process agrees.

## The PRs

Server, on #192 (own slskd): W5a `feat(AI): remember your download quality` (V19, the enum, the routes) → W5b
`feat(AI): the picker honours your download quality` (the rule for songs and album folders, the floor's removal, the
per-search read). Client, on #115: K6 `feat(AI): first-run setup: your download quality`. The client needs W5a only; a
404 on the GET is an older server ("downloads take lossless or 320 kbps files, as before").

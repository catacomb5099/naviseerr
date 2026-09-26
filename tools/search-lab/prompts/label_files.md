# Labelling Soulseek results

You get a JSON file: a list of songs. Each song has `title`, `artists`, `album`, `duration` (seconds, from YouTube Music, may be a video so can run longer than the audio) and `files`: Soulseek results with `id`, `path` (last folders + filename), `length` (seconds, may be null), `bitRate`, `ext`.

For each song decide first what version was **requested**, from the YouTube title only:
`original` (a normal studio release; remasters count as original), `live`, `remix`, `acoustic`, `instrumental`, `other`.

Then label every file:
- `relevant`: true only if the file is a recording of **this song by this artist** (any version). False for a different song that shares words, a different artist's cover, karaoke or instrumental backing tracks by others, mashups, DJ sets, full albums or mixes.
- `version`: `original`, `live`, `remix`, `acoustic`, `instrumental`, `karaoke`, `cover`, `other`, or `unknown` when the path gives no hint (an `unknown` with matching artist and title should be treated as `original` — plain filenames are the normal case).
- `confident`: false when you are guessing.

Judge every file individually by reading its path. Do not write a script or regex to assign labels. A file that merely shares a word with the title is NOT relevant: for a song "The Deed", files like "Deed I Do" or "Poetry Of The Deed" by other artists are irrelevant; for "Parvati", a label called Parvati Records is irrelevant. Both the song title and the artist (or an obvious album/folder of that artist) should be recognisable. Other tracks by the same artist are NOT relevant either: in a folder "Red Hot Chili Peppers/1999 - Californication/", only the file whose own name is "Californication" is relevant; "01 - Around The World.mp3" is not, even though the folder matches.

Use the folder names, track numbers, length versus duration, and words like live, remix, acoustic, unplugged, karaoke, instrumental, cover, tribute, sped up, slowed, nightcore, 8D as clues. Do not skip files.

Two cases that are `other`, not original: acapella / a cappella stems (they are not acoustic), and DJ-pool edits — files with intro/outro/break edits, "Clean"/"Dirty", BPM and key tags like `12A 125`, or promo-site tags like `[www.dj-promo.org]`. A plain radio edit with none of those marks stays `original`.

Write one JSON object per line to the output path you were given, nothing else in the file:

```
{"song":"<song id>","requested":"original"}
{"song":"<song id>","id":"<file id>","relevant":true,"version":"original","confident":true}
```

One `requested` line per song, then one line per file. Every file id in the input must appear exactly once.

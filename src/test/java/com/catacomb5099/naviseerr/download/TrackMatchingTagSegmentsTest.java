package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import com.catacomb5099.naviseerr.util.TrackMatchingService.Match;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * Two file-name shapes seen in real album searches on 09-10-2026 that the matcher used to grade NONE, on the
 * album picker and on the song's own search alike: a trailing dash segment that tags the file ("- Remastered
 * 2021", "- 320 kbps", or the artist last), and a title that is nothing but a number ("1979", "22").
 */
class TrackMatchingTagSegmentsTest {

    private TrackMatchingService matching;
    private AlbumFolderPicker picker;

    @BeforeEach
    void setUp() {
        matching = new TrackMatchingService();
        SlskdSearchResultProcessor processor = new SlskdSearchResultProcessor(mock(SlskdService.class), matching);
        ReflectionTestUtils.setField(processor, "minBitRate", 320);
        ReflectionTestUtils.setField(processor, "maxSharerQueue", 50);
        picker = new AlbumFolderPicker(matching, processor);
    }

    // ---- a trailing "- Remastered 2021" / "- 320 kbps" segment, or the artist last, hides the title ----

    @Test
    void aFileWhoseLastDashSegmentIsARemasterTag_isStillTheSong() {
        // 13 such files in the real 'Nevermind' search (a 2021 remaster folder) and 13 "- 320 kbps" ones in '21':
        // the title is there, but the last segment is a tag, not the title.
        assertEquals(Match.EXACT, matching.grade("Smells Like Teen Spirit",
                "FLAC\\Nirvana\\Nevermind [2021 Remaster]\\01 - Smells Like Teen Spirit - Remastered 2021.flac"),
                "album picker: title-only request");
        assertEquals(Match.EXACT, matching.grade("Smells Like Teen Spirit - Nirvana",
                "FLAC\\Nirvana\\Nevermind [2021 Remaster]\\01 - Smells Like Teen Spirit - Remastered 2021.flac",
                "Smells Like Teen Spirit"), "the song's own search, title wording");
        assertEquals(Match.EXACT, matching.grade("Rolling in the Deep",
                "media\\Adele\\2011 - 21\\01 - Rolling in the Deep - 320 kbps.mp3"));
        // a real other take is still never taken as the plain track
        assertNotEquals(Match.EXACT, matching.grade("Come As You Are",
                "Nirvana\\Nevermind (Super Deluxe)\\10 - Come As You Are - Live In Del Mar, California1991.flac"));
    }

    @Test
    void aFolderNamingTheTitleBeforeTheArtist_isAWholeFolder() {
        // 21 such files in the real 'Definitely Maybe' search: the Japanese pressing names its files
        // "Definitely Maybe (ESCA 6045) - 01 Rock 'n' Roll Star - Oasis.flac", the artist last, so the
        // title-only grade found no title in the last segment and the folder held nothing.
        List<DownloadTask> songs = album("Oasis", "Rock 'n' Roll Star", 324, "Shakermaker", 309, "Live Forever", 277);
        SearchResponseItem japanese = peer("jp", "Music\\Oasis\\Definitely Maybe (ESCA 6045)\\",
                file("Definitely Maybe (ESCA 6045) - 01 Rock 'n' Roll Star - Oasis.flac", 324),
                file("Definitely Maybe (ESCA 6045) - 02 Shakermaker - Oasis.flac", 309),
                file("Definitely Maybe (ESCA 6045) - 03 Live Forever - Oasis.flac", 277));

        List<AlbumFolderPicker.Folder> whole = picker.folders(List.of(japanese), songs, "Definitely Maybe",
                List.of("Oasis"), s -> false);

        assertEquals(1, whole.size(), "the folder holds every song");
        assertEquals(3, whole.getFirst().files().size());
    }

    // ---- a title that is nothing but a number is erased by normalize() ------------------------------

    @Test
    void aTrackWhoseTitleIsANumber_isStillTheSong() {
        // normalize() drops a leading "NN - " as a track number and every 4-digit number as a year, so "1979"
        // and "22 - Taylor Swift" used to compare as empty strings on either path.
        assertEquals(Match.EXACT, matching.grade("1979",
                "The Smashing Pumpkins\\Mellon Collie and the Infinite Sadness\\2-05 - 1979.flac"), "album picker");
        assertEquals(Match.EXACT, matching.grade("1979 - The Smashing Pumpkins",
                "The Smashing Pumpkins\\Mellon Collie and the Infinite Sadness\\2-05 - 1979.flac", "1979"),
                "own search");
        assertEquals(Match.EXACT, matching.grade("22 - Taylor Swift", "Taylor Swift\\Red\\06 - 22.flac", "22"));
        assertEquals(Match.EXACT, matching.grade("1999 - Prince", "Prince\\1999\\01 - 1999.flac", "1999"));
        // a different number is still not the song
        assertEquals(Match.NONE, matching.grade("1979 - The Smashing Pumpkins",
                "The Smashing Pumpkins\\Mellon Collie and the Infinite Sadness\\2-06 - 1980.flac", "1979"));
    }

    @Test
    void anAlbumWithANumberedTitle_isWhole_notAPart() {
        // "1979" is the one song the folder seemed to lack, so a 3-track slice of Mellon Collie was a part
        // folder of 2, never whole, and that song searched alone and ended "No source found".
        List<DownloadTask> mellon = album("The Smashing Pumpkins", "Tonight, Tonight", 254, "1979", 266, "Zero", 161);
        SearchResponseItem folder = peer("a", "Music\\The Smashing Pumpkins\\Mellon Collie and the Infinite Sadness\\",
                file("1-02 - Tonight, Tonight.flac", 254), file("1-05 - 1979.flac", 266), file("1-04 - Zero.flac", 161));

        List<AlbumFolderPicker.Folder> whole = picker.folders(List.of(folder), mellon, "Mellon Collie and the Infinite Sadness",
                List.of("The Smashing Pumpkins"), s -> false);

        assertEquals(1, whole.size());
        assertEquals(3, whole.getFirst().files().size(), "'1979' is in the folder too");
    }

    // ---- helpers (as AlbumFolderPickerTest) ----------------------------------------------------------

    private static List<DownloadTask> album(String artist, Object... titlesAndSeconds) {
        List<DownloadTask> songs = new ArrayList<>();
        for (int i = 0; i < titlesAndSeconds.length; i += 2) {
            songs.add(song((String) titlesAndSeconds[i], artist, i / 2 + 1, (Integer) titlesAndSeconds[i + 1]));
        }
        return songs;
    }

    private static DownloadTask song(String title, String artist, int number, int seconds) {
        return DownloadTask.initial(UUID.randomUUID(), "yt-" + number, title + " - " + artist, T0).toBuilder()
                .trackTitle(title).trackNumber(number).durationSeconds(seconds).build();
    }

    private static SearchFile file(String name, int seconds) {
        return new SearchFile(name, 30_000_000L, 1L, false, "", Optional.empty(), Optional.of(seconds));
    }

    private static SearchResponseItem peer(String username, String folder, SearchFile... files) {
        List<SearchFile> inFolder = Arrays.stream(files)
                .map(f -> new SearchFile(folder + f.getFilename(), f.getSize(), f.getCode(), false, "",
                        Optional.empty(), f.getLength()))
                .toList();
        return new SearchResponseItem(inFolder.size(), inFolder, true, 0, List.of(), 0, 1, 1_000_000, username);
    }
}

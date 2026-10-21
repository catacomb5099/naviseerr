package com.catacomb5099.naviseerr.learning;

import com.catacomb5099.naviseerr.download.DownloadCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchReportTest {

    @Test
    void shortPath_keepsTwoFoldersAndTheFile_withoutTheShareRootOrTheSharersName() {
        assertEquals("Oasis/(1995) Morning Glory/02 - Wonderwall.flac",
                SearchReport.shortPath("@@music\\bob\\rips\\Oasis\\(1995) Morning Glory\\02 - Wonderwall.flac", "bob"));
        assertEquals("Oasis/02 - Wonderwall.flac", SearchReport.shortPath("@@music\\BOB\\Oasis\\02 - Wonderwall.flac", "bob"));
        assertEquals("song.mp3", SearchReport.shortPath("song.mp3", "bob"));
    }

    @Test
    void of_dropsTheSharer_andNamesTheWinningWording() {
        DownloadCandidate chosen = new DownloadCandidate("bob", "@@music\\bob\\Oasis\\Wonderwall.flac", "flac", null,
                31_457_280L, 1L, false, true, 3, 900_000, "EXACT", DownloadCandidate.MANUAL, 258, "wonderwall oasis");

        SearchReport report = SearchReport.of(UUID.randomUUID(), Instant.EPOCH, "SONG", null, List.of(), chosen, null, 1, "LOSSLESS");

        assertEquals(new SearchReport.Won("wonderwall oasis", "MANUAL"), report.won());
        assertEquals("Oasis/Wonderwall.flac", report.file().path());
        assertFalse(report.toString().contains("bob"), "no Soulseek user name anywhere in a report: " + report);
    }

    @Test
    void of_anAlbumFolderFile_isWonByTheAlbumSearch() {
        DownloadCandidate chosen = new DownloadCandidate("bob", "x\\Definitely Maybe\\01.flac", "flac", null, 1L, 1L, false,
                true, 0, 1, "EXACT", DownloadCandidate.ALBUM_FOLDER, 300, null);

        SearchReport report = SearchReport.of(UUID.randomUUID(), Instant.EPOCH, "ALBUM", null, List.of(), chosen,
                "Definitely Maybe Oasis", 1, "LOSSLESS");

        assertEquals(new SearchReport.Won("Definitely Maybe Oasis", "ALBUM_FOLDER"), report.won());
    }
}

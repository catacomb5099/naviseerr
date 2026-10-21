package com.catacomb5099.naviseerr.mediaserver;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SongFinderTest {

    private static final SongFinder.Wanted WONDERWALL = new SongFinder.Wanted("Wonderwall", List.of("Oasis"), 259,
            "Oasis/Morning Glory/03 - Wonderwall.flac");

    private static SongFinder.Candidate song(String id, String path, String artists, Integer seconds, String created) {
        return new SongFinder.Candidate(id, "al-" + id, path, artists, seconds, created);
    }

    @Test
    void searchTerms_youtubeTitleFirst_thenTheFileNameWithoutTrackNumber() {
        assertEquals(List.of("Wonderwall"), SongFinder.searchTerms(WONDERWALL), "the same text is not searched twice");
        assertEquals(List.of("Bitter Sweet Symphony", "The Verve - Bitter Sweet Symphony (Remastered)"),
                SongFinder.searchTerms(new SongFinder.Wanted("Bitter Sweet Symphony", List.of("The Verve"), 358,
                        "The Verve/Urban Hymns/01. The Verve - Bitter Sweet Symphony (Remastered).mp3")));
        assertEquals(List.of("track"), SongFinder.searchTerms(new SongFinder.Wanted(null, List.of(), null, "a/7 track.mp3")));
    }

    @Test
    void byPath_takesOnlyTheSongWhoseOwnPathEndsWithTheFiledFile() {
        List<SongFinder.Candidate> found = List.of(
                song("twin", "/music/Oasis/Best Of/03 - Wonderwall.flac", "Oasis", 259, "2026-10-21"),
                song("it", "D:\\Music\\Oasis\\Morning Glory\\03 - Wonderwall.flac", "Oasis", 259, "2026-01-01"));

        assertEquals(Optional.of(new SongFinder.Found("it", "al-it")), SongFinder.byPath(WONDERWALL, found));
        assertEquals(Optional.empty(), SongFinder.byPath(WONDERWALL, List.of(found.getFirst())));
        assertEquals(Optional.empty(), SongFinder.byPath(WONDERWALL,
                List.of(song("x", "/music/NotOasis/Morning Glory/03 - Wonderwall.flac.bak", "Oasis", 259, null))));
    }

    @Test
    void byPathOrTags_withoutARealPath_needsTheArtistAndTheLength_newestWins() {
        List<SongFinder.Candidate> found = List.of(
                song("cover", "Ryan Adams/Love Is Hell/05 - Wonderwall.mp3", "Ryan Adams", 259, "2026-10-21"),
                song("live", "Oasis/Familiar/02 - Wonderwall.mp3", "Oasis", 300, "2026-10-21"),
                song("old", "Oasis/Morning Glory/03 - Wonderwall.mp3", "oasis / Oasis", 257, "2026-01-01"),
                song("new", "Oasis/Morning Glory/03 - Wonderwall.mp3", "OASIS", 261, "2026-10-20"));

        assertEquals("new", SongFinder.byPathOrTags(WONDERWALL, found).orElseThrow().id());
    }

    @Test
    void byPathOrTags_aRealPathWins_andAccentsAndCaseAreFolded() {
        SongFinder.Wanted beyonce = new SongFinder.Wanted("Halo", List.of("Beyoncé"), 261, "Beyoncé/I Am/01 Halo.mp3");
        List<SongFinder.Candidate> found = List.of(
                song("tagged", "Beyonce/Halo.mp3", "BEYONCE feat. nobody", 262, "2026-10-21"),
                song("filed", "/data/music/Beyoncé/I Am/01 Halo.mp3", "someone else", 999, "2020-01-01"));

        assertEquals("filed", SongFinder.byPathOrTags(beyonce, found).orElseThrow().id());
        assertEquals("tagged", SongFinder.byPathOrTags(beyonce, List.of(found.getFirst())).orElseThrow().id());
    }

    @Test
    void byPathOrTags_unknownLengthOrArtist_isNotHeldAgainstTheSong() {
        SongFinder.Wanted loose = new SongFinder.Wanted("Song", List.of(), null, "x/Song.mp3");
        assertTrue(SongFinder.byPathOrTags(loose, List.of(song("a", "y/z.mp3", null, null, null))).isPresent());
        assertTrue(SongFinder.byPathOrTags(WONDERWALL, List.of(song("a", "y/z.mp3", "Oasis", null, null))).isEmpty(),
                "a song with no length cannot be checked against a known one");
    }
}

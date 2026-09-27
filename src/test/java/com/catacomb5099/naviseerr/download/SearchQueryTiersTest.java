package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchQueryTiersTest {

    @Test
    void bracketsGo_thenTheTitleAloneIsTheFallback() {
        assertEquals(List.of("Hello - Oasis", "Hello"),
                SearchQueryTiers.of("Hello (Official Lyric Video) - Oasis"));
        assertEquals(List.of("Morning Glory - Oasis", "Morning Glory"),
                SearchQueryTiers.of("Morning Glory (Official HD Remastered Video) - Oasis"));
    }

    @Test
    void theQualifierIsNotSearched_thePickerSeesItInTheSongName() {
        // The lab: the bare query already held the requested remix or live take for 42 of 52 songs;
        // keeping the qualifier in the search won 32 and came back empty 48 times out of 180.
        assertEquals(List.of("Wonderwall - Oasis", "Wonderwall"),
                SearchQueryTiers.of("Wonderwall (Remix) [Official Video] - Oasis"));
    }

    @Test
    void artistRepeatedByYouTubesOwnTitle_isDeduped() {
        assertEquals(List.of("Don't Look Back In Anger - Oasis", "Don't Look Back In Anger"),
                SearchQueryTiers.of("Oasis - Don't Look Back In Anger (Official Video) - Oasis"));
        assertEquals(List.of("Wonderwall - Oasis", "Wonderwall"),
                SearchQueryTiers.of("Oasis – Wonderwall (Official Video) - Oasis"));
    }

    @Test
    void aCleanTitle_isSearchedOnce_andFallsBackToTheTitleAlone() {
        assertEquals(List.of("Wonderwall - Oasis", "Wonderwall"), SearchQueryTiers.of("Wonderwall - Oasis"));
    }

    @Test
    void channelSuffixOnTheArtist_isRemoved() {
        // "Maria - BlondieVEVO" found 8,092 files for the title and none for the "artist".
        assertEquals(List.of("Maria - Blondie", "Maria"), SearchQueryTiers.of("Maria - BlondieVEVO"));
        assertEquals(List.of("Since You're Gone - The Cars", "Since You're Gone"),
                SearchQueryTiers.of("Since You're Gone - The Cars - Topic"));
    }

    @Test
    void straightQuotes_areRemoved_theyKillASoulseekSearch() {
        assertEquals(List.of("KATSEYE Animal Official MV - KATSEYE", "KATSEYE Animal Official MV"),
                SearchQueryTiers.of("KATSEYE \"Animal\" Official MV - KATSEYE"));
    }

    @Test
    void noiseWordsOutsideBrackets_areTheSong_notNoise() {
        assertEquals(List.of("Video Games - Lana Del Rey", "Video Games"),
                SearchQueryTiers.of("Video Games - Lana Del Rey"));
        assertEquals(List.of("Audio - Sia", "Audio"), SearchQueryTiers.of("Audio - Sia"));
    }

    @Test
    void noiseAsItsOwnSegment_isDropped_notPromotedToTheTitle() {
        assertEquals(List.of("Wonderwall - Oasis", "Wonderwall"),
                SearchQueryTiers.of("Wonderwall - Official Video - Oasis"));
        assertEquals(List.of("Hello - Oasis", "Hello"), SearchQueryTiers.of("Hello | Official HD Video | Oasis"));
    }

    @Test
    void extraSegments_stayInTheTitle_neverPickedFrom() {
        // Picking one segment searched "Remastered - Oasis" and downloaded a different Oasis song.
        assertEquals(List.of("Wonderwall Remastered - Oasis", "Wonderwall Remastered"),
                SearchQueryTiers.of("Wonderwall - Remastered - Oasis"));
    }

    @Test
    void aNameThatIsNothingButNoise_fallsBackToItself_notToAnEmptySearch() {
        assertEquals(List.of("(Official Video)"), SearchQueryTiers.of("(Official Video)"));
    }

    @Test
    void aNameWithoutAnArtist_isOneTier() {
        assertEquals(List.of("Wonderwall"), SearchQueryTiers.of("Wonderwall (Official Video)"));
    }
}

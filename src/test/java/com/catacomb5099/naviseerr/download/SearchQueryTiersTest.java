package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchQueryTiersTest {

    @Test
    void officialLyricVideo_isNeverSearched_andTheBareTierCollapsesOntoTheFirst() {
        assertEquals(List.of("Hello - Oasis"),
                SearchQueryTiers.of("Hello (Official Lyric Video) - Oasis"));
    }

    @Test
    void officialHdRemasteredVideo_isConsumedAsOnePhrase() {
        assertEquals(List.of("Morning Glory - Oasis"),
                SearchQueryTiers.of("Morning Glory (Official HD Remastered Video) - Oasis"));
    }

    @Test
    void officialVideo_isNoise() {
        assertEquals(List.of("Little By Little - Oasis"),
                SearchQueryTiers.of("Little By Little (Official Video) - Oasis"));
    }

    @Test
    void artistRepeatedByYouTubesOwnTitle_isDedupedInTheBareTier() {
        assertEquals(List.of(
                        "Oasis - Don't Look Back In Anger - Oasis",
                        "Don't Look Back In Anger - Oasis"),
                SearchQueryTiers.of("Oasis - Don't Look Back In Anger (Official Video) - Oasis"));
    }

    @Test
    void lyricVideo_inParentheses_isNoise() {
        assertEquals(List.of("Cast No Shadow - Oasis"),
                SearchQueryTiers.of("Cast No Shadow (Official Lyric Video) - Oasis"));
    }

    @Test
    void firstTierKeepsRemix_bareTierDropsEveryBracket() {
        // "If something specifically asks for a remix, of course get that remix" -- the remix is a
        // different recording, so the first search must keep it; only the last-ditch tier lets it go.
        assertEquals(List.of("Wonderwall (Remix) - Oasis", "Wonderwall - Oasis"),
                SearchQueryTiers.of("Wonderwall (Remix) [Official Video] - Oasis"));
    }

    @Test
    void aCleanTitle_isExactlyOneTier_soItIsNeverSearchedTwice() {
        assertEquals(List.of("Wonderwall - Oasis"), SearchQueryTiers.of("Wonderwall - Oasis"));
    }

    @Test
    void noiseWordsOutsideBrackets_areTheSong_notNoise() {
        assertEquals(List.of("Video Games - Lana Del Rey"),
                SearchQueryTiers.of("Video Games - Lana Del Rey"));
        assertEquals(List.of("Audio - Sia"), SearchQueryTiers.of("Audio - Sia"));
    }

    @Test
    void aNameThatIsNothingButNoise_fallsBackToItself_notToAnEmptySearch() {
        assertEquals(List.of("(Official Video)"), SearchQueryTiers.of("(Official Video)"));
    }

    @Test
    void enDashAndPipe_areSeparatorsToo_soTheArtistEchoIsStillDeduped() {
        assertEquals(List.of("Oasis - Wonderwall - Oasis", "Wonderwall - Oasis"),
                SearchQueryTiers.of("Oasis – Wonderwall (Official Video) - Oasis"));
        assertEquals(List.of("Oasis - Wonderwall - Oasis", "Wonderwall - Oasis"),
                SearchQueryTiers.of("Oasis - Wonderwall | Official Video - Oasis"));
    }

    @Test
    void noiseAsItsOwnSegment_isDropped_notPromotedToTheTitle() {
        assertEquals(List.of("Wonderwall - Oasis"),
                SearchQueryTiers.of("Wonderwall - Official Video - Oasis"));
        assertEquals(List.of("Hello - Oasis"), SearchQueryTiers.of("Hello - Lyrics - Oasis"));
    }

    @Test
    void doubledSpaces_doNotHideAPhraseFromTheNoiseList() {
        assertEquals(List.of("Hello - Oasis"),
                SearchQueryTiers.of("Hello  (OFFICIAL   Lyric   VIDEO)   -  Oasis"));
    }

    @Test
    void nestedBrackets_andABracketBetweenTwoDashes_leaveNoDebris() {
        assertEquals(List.of("Song - A"), SearchQueryTiers.of("Song (Official Video [HD]) - A"));
        assertEquals(List.of("Hello - Oasis"), SearchQueryTiers.of("Hello - (Official Video) - Oasis"));
    }

    @Test
    void extraSegments_stayInTheTitle_neverPickedFrom() {
        // Picking one segment searched "Remastered - Oasis" and downloaded a different Oasis song.
        assertEquals(List.of("Wonderwall - Remastered - Oasis", "Wonderwall Remastered - Oasis"),
                SearchQueryTiers.of("Wonderwall - Remastered - Oasis"));
    }
}

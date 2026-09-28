package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void extraSegments_stayInTheTitle_neverPickedFrom_thenTheQualifierIsDropped() {
        // Picking one segment searched "Remastered - Oasis" and downloaded a different Oasis song.
        // The qualifier-free wordings come after, so the picker (which still sees "(Remastered)")
        // gets to choose among plain files when the qualifier wording finds nothing.
        assertEquals(List.of("Wonderwall Remastered - Oasis", "Wonderwall Remastered", "Wonderwall - Oasis", "Wonderwall"),
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

    // ---- the 28-09-2026 post-mortem: "Artist - Title - channel" names ----

    @Test
    void artistTitleChannel_searchesArtistTitleFirst_thenTitleAlone_channelLast() {
        // The channel wording found nothing in all 41 tries; the artist may be one Soulseek drops.
        assertEquals(List.of("Neon Indian Polish Girl", "Polish Girl", "Neon Indian Polish Girl - toomainstream"),
                SearchQueryTiers.of("Neon Indian - Polish Girl - toomainstream"));
        assertEquals(List.of("Two Door Cinema Club What You Know", "What You Know",
                        "Two Door Cinema Club What You Know - Lorem Ipsum"),
                SearchQueryTiers.of("Two Door Cinema Club - What You Know - Lorem Ipsum"));
        assertEquals(List.of("Sea Wolf You're a Wolf", "You're a Wolf", "Sea Wolf You're a Wolf - MultiBananachips"),
                SearchQueryTiers.of("Sea Wolf - You're a Wolf - MultiBananachips"));
        assertEquals(List.of("Discovery Swing Tree", "Swing Tree", "Discovery Swing Tree - Jamzar1000"),
                SearchQueryTiers.of("Discovery - Swing Tree - Jamzar1000"));
        assertEquals(List.of("Whitney Whitney Sherry Wine", "Sherry Wine", "Whitney Whitney Sherry Wine - Whitney Woerz"),
                SearchQueryTiers.of("Whitney Whitney - Sherry Wine (Official Visualizer) - Whitney Woerz"));
    }

    @Test
    void artistEchoedByAnOfficialChannel_isATwoPartName() {
        assertEquals(List.of("2 Dollar Bill - slimdan", "2 Dollar Bill"),
                SearchQueryTiers.of("slimdan - 2 Dollar Bill (Official Visualizer) - slimdan"));
        assertEquals(List.of("BAD DREAMS - Nightly", "BAD DREAMS"),
                SearchQueryTiers.of("Nightly \u2013 BAD DREAMS (Official Lyric Video) - Nightly"));
    }

    @Test
    void aVersionInTheMiddle_stillReadsAsTitleQualifierArtist_thenTheQualifierGoes() {
        assertEquals(List.of("Kiss Me Radio Edit - Sixpence None The Richer", "Kiss Me Radio Edit",
                        "Kiss Me - Sixpence None The Richer", "Kiss Me"),
                SearchQueryTiers.of("Kiss Me - Radio Edit - Sixpence None The Richer"));
    }

    @Test
    void aGuestCredit_isNotSearched_andALongTitle_isAlsoSearchedByItsFirstFiveWords() {
        assertEquals(List.of("Floette - Arlo Parks", "Floette"),
                SearchQueryTiers.of("Floette ft. John Glacier (Official Video) - Arlo Parks"));
        assertEquals("Floette - Arlo Parks", SearchQueryTiers.pickerName("Floette ft. John Glacier (Official Video) - Arlo Parks"));
        assertEquals(List.of("Another thing that I'm supposed to do - Gretel", "Another thing that I'm supposed to do",
                        "Another thing that I'm supposed - Gretel", "Another thing that I'm supposed"),
                SearchQueryTiers.of("Gretel - Another thing that I'm supposed to do (that I don't want to) (Official Video) - Gretel"));
        // never more than four searches for one song
        assertEquals(4, SearchQueryTiers.of("Red Wire Black Wire - Breathing Fire Like A Dragon Does (official video) - ToughCustomerRecords").size());
        assertEquals(List.of("Red Wire Black Wire Breathing Fire Like A Dragon Does", "Breathing Fire Like A Dragon Does",
                        "Red Wire Black Wire Breathing Fire Like A Dragon", "Breathing Fire Like A Dragon"),
                SearchQueryTiers.of("Red Wire Black Wire - Breathing Fire Like A Dragon Does (official video) - ToughCustomerRecords"));
        assertEquals(List.of("No Small Feat - Someone", "No Small Feat"), SearchQueryTiers.of("No Small Feat - Someone"));
    }

    @Test
    void trailingLyricsTag_isRemoved() {
        assertEquals(List.of("The Postal Service Such Great Heights", "Such Great Heights",
                        "The Postal Service Such Great Heights - Julia"),
                SearchQueryTiers.of("The Postal Service - Such Great Heights Lyrics - Julia"));
        assertEquals(List.of("Moldy Peaches Anyone Else But You", "Anyone Else But You",
                        "Moldy Peaches Anyone Else But You - pirateie"),
                SearchQueryTiers.of("Moldy Peaches - \"Anyone Else But You\" w/ Lyrics - pirateie"));
        assertEquals(List.of("Tire Swing - Kimya dawson"), SearchQueryTiers.of("Kimya dawson - Tire Swing (Lyrics) - Kimya dawson").subList(0, 1));
    }

    @Test
    void byArtist_becomesItsOwnPart_songsWithByInTheTitleDoNot() {
        assertEquals(List.of("Tongue Tied - Grouplove", "Tongue Tied"), SearchQueryTiers.of("Tongue Tied by Grouplove - Hyde"));
        assertEquals("Tongue Tied - Grouplove", SearchQueryTiers.pickerName("Tongue Tied by Grouplove - Hyde"));
        assertEquals(List.of("Stand By Me - Ben E. King", "Stand By Me"), SearchQueryTiers.of("Stand By Me - Ben E. King"));
        assertEquals(List.of("Blinded by the Light - Manfred Mann", "Blinded by the Light"),
                SearchQueryTiers.of("Blinded by the Light - Manfred Mann"));
    }

    @Test
    void aDashGluedToTheWordBefore_isASeparator_hyphenatedNamesAreNot() {
        assertEquals(List.of("The Con Tegan and Sara", "Tegan and Sara", "The Con Tegan and Sara - YourWorstNightmare18"),
                SearchQueryTiers.of("The Con- Tegan and Sara (with lyrics) - YourWorstNightmare18"));
        assertEquals(List.of("If I'm Gonna Eat Somebody - Tone-Loc", "If I'm Gonna Eat Somebody"),
                SearchQueryTiers.of("If I'm Gonna Eat Somebody (It Might As Well Be You) - Tone-Loc"));
        assertEquals(List.of("Empire State of Mind - Jay-Z", "Empire State of Mind"),
                SearchQueryTiers.of("Empire State of Mind - Jay-Z"));
        // "Metric-Black Sheep" cannot be told apart from "Jay-Z" by the text alone, so it is left as is.
        assertEquals(List.of("Metric-Black Sheep - SyncYouNow", "Metric-Black Sheep"),
                SearchQueryTiers.of("Metric-Black Sheep - SyncYouNow"));
    }

    // ---- what the picker is handed ----

    @Test
    void pickerName_isTheCleanedParts_withVersionQualifiersKept() {
        assertEquals("Neon Indian - Polish Girl - toomainstream",
                SearchQueryTiers.pickerName("Neon Indian - Polish Girl - toomainstream"));
        assertEquals("2 Dollar Bill - slimdan", SearchQueryTiers.pickerName("slimdan - 2 Dollar Bill (Official Visualizer) - slimdan"));
        assertEquals("Wonderwall (Remix) - Oasis", SearchQueryTiers.pickerName("Wonderwall (Remix) [Official Video] - Oasis"));
        assertEquals("Asleep Talking (Acoustic) - Magnus Ferrell", SearchQueryTiers.pickerName("Asleep Talking (Acoustic) - Magnus Ferrell"));
        assertEquals("Wonderwall (Live at Wembley) - Oasis", SearchQueryTiers.pickerName("Wonderwall (Live at Wembley) - Oasis"));
        assertEquals("Kiss Me (Radio Edit) - Sixpence None The Richer",
                SearchQueryTiers.pickerName("Kiss Me - Radio Edit - Sixpence None The Richer"));
        assertEquals("Such Great Heights - The Postal Service - Julia",
                SearchQueryTiers.pickerName("The Postal Service - Such Great Heights Lyrics - Julia").replace("The Postal Service - Such Great Heights", "Such Great Heights - The Postal Service"));
        assertEquals("(Official Video)", SearchQueryTiers.pickerName("(Official Video)"));
        assertFalse(SearchQueryTiers.pickerName("Hello (Official Lyric Video) - Oasis").contains("("));
    }
}

package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchQueryTiersTest {

    @Test
    void bracketsGo_theTitleAloneGoesFirst_thenWithTheArtist() {
        assertEquals(List.of("Hello", "Hello - Oasis"),
                SearchQueryTiers.of("Hello (Official Lyric Video) - Oasis"));
        assertEquals(List.of("Morning Glory", "Morning Glory - Oasis", "Morning Oasis"),
                SearchQueryTiers.of("Morning Glory (Official HD Remastered Video) - Oasis"));
    }

    @Test
    void theQualifierIsNotSearched_thePickerSeesItInTheSongName() {
        // The lab: the bare query already held the requested remix or live take for 42 of 52 songs;
        // keeping the qualifier in the search won 32 and came back empty 48 times out of 180.
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"),
                SearchQueryTiers.of("Wonderwall (Remix) [Official Video] - Oasis"));
    }

    @Test
    void artistRepeatedByYouTubesOwnTitle_isDeduped() {
        assertEquals(List.of("Don't Look Back In Anger", "Don't Look Back In Anger - Oasis", "Don't Look Back Anger Oasis"),
                SearchQueryTiers.of("Oasis - Don't Look Back In Anger (Official Video) - Oasis"));
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"),
                SearchQueryTiers.of("Oasis – Wonderwall (Official Video) - Oasis"));
    }

    @Test
    void aCleanTitle_isSearchedAlone_thenWithTheArtist() {
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"), SearchQueryTiers.of("Wonderwall - Oasis"));
    }

    @Test
    void channelSuffixOnTheArtist_isRemoved() {
        // "Maria - BlondieVEVO" found 8,092 files for the title and none for the "artist".
        assertEquals(List.of("Maria", "Maria - Blondie"), SearchQueryTiers.of("Maria - BlondieVEVO"));
        assertEquals(List.of("Since You're Gone", "Since You're Gone - The Cars", "Since You're Cars"),
                SearchQueryTiers.of("Since You're Gone - The Cars - Topic"));
    }

    @Test
    void straightQuotes_areRemoved_theyKillASoulseekSearch() {
        assertEquals(List.of("KATSEYE Animal Official MV", "KATSEYE Animal Official MV - KATSEYE", "KATSEYE Animal Official KATSEYE"),
                SearchQueryTiers.of("KATSEYE \"Animal\" Official MV - KATSEYE"));
    }

    @Test
    void noiseWordsOutsideBrackets_areTheSong_notNoise() {
        assertEquals(List.of("Video Games", "Video Games - Lana Del Rey", "Games Lana Rey"),
                SearchQueryTiers.of("Video Games - Lana Del Rey"));
        assertEquals(List.of("Audio", "Audio - Sia"), SearchQueryTiers.of("Audio - Sia"));
    }

    @Test
    void noiseAsItsOwnSegment_isDropped_notPromotedToTheTitle() {
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"),
                SearchQueryTiers.of("Wonderwall - Official Video - Oasis"));
        assertEquals(List.of("Hello", "Hello - Oasis"), SearchQueryTiers.of("Hello | Official HD Video | Oasis"));
    }

    @Test
    void aVersionInItsOwnSegment_isNotSearched_andNeverPickedFrom() {
        // Picking one segment once searched "Remastered - Oasis" and downloaded a different Oasis song.
        // Now the segment is a qualifier for the picker and nothing for the search.
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"), SearchQueryTiers.of("Wonderwall - Remastered - Oasis"));
        assertEquals("Wonderwall (Remastered) - Oasis", SearchQueryTiers.pickerName("Wonderwall - Remastered - Oasis"));
    }

    // ---- the search is version-blind, the picker is version-aware ----

    @Test
    void versionWords_neverReachTheSearch_inAnyOfTheThreeShapes() {
        // in brackets
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"), SearchQueryTiers.of("Wonderwall (Live) - Oasis"));
        assertEquals("Wonderwall (Live) - Oasis", SearchQueryTiers.pickerName("Wonderwall (Live) - Oasis"));
        // in a dash segment
        assertEquals(List.of("Kiss Me", "Kiss Me - Sixpence None The Richer", "Kiss Sixpence None Richer"),
                SearchQueryTiers.of("Kiss Me - Radio Edit - Sixpence None The Richer"));
        // inline, no punctuation
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"), SearchQueryTiers.of("Wonderwall Live at Wembley - Oasis"));
        assertEquals("Wonderwall (Live at Wembley) - Oasis", SearchQueryTiers.pickerName("Wonderwall Live at Wembley - Oasis"));
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"), SearchQueryTiers.of("Wonderwall Remastered 2009 - Oasis"));
        assertEquals(List.of("Bloodstream", "Bloodstream - Alyssa Grace", "Bloodstream Alyssa"), SearchQueryTiers.of("Bloodstream Acoustic Version - Alyssa Grace"));
        assertEquals("Bloodstream (Acoustic Version) - Alyssa Grace", SearchQueryTiers.pickerName("Bloodstream Acoustic Version - Alyssa Grace"));
        assertEquals(List.of("Song", "Song - Artist"), SearchQueryTiers.of("Song '95 Version - Artist"));
        // stripping collapses the wordings into one deduplicated list
        assertEquals(List.of("Wonderwall", "Wonderwall - Oasis"),
                SearchQueryTiers.of("Wonderwall - Remastered (Live at Wembley) [Official Video] - Oasis"));
        assertEquals("Wonderwall (Live at Wembley) (Remastered) - Oasis",
                SearchQueryTiers.pickerName("Wonderwall - Remastered (Live at Wembley) [Official Video] - Oasis"));
        // a title that is nothing but a version word keeps it
        assertEquals(List.of("Live", "Live - Artist"), SearchQueryTiers.of("Live - Artist"));
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
    void artistTitleChannel_searchesTheTitleFirst_thenArtistTitle_channelLast() {
        // The channel wording found nothing in all 41 tries; the artist may be one Soulseek drops.
        assertEquals(List.of("Polish Girl", "Neon Indian Polish Girl", "Polish Indian", "Neon Indian Polish Girl - toomainstream"),
                SearchQueryTiers.of("Neon Indian - Polish Girl - toomainstream"));
        assertEquals(List.of("What You Know", "Two Door Cinema Club What You Know", "What Know Door Cinema Club",
                        "Two Door Cinema Club What You Know - Lorem Ipsum"),
                SearchQueryTiers.of("Two Door Cinema Club - What You Know - Lorem Ipsum"));
        assertEquals(List.of("You're a Wolf", "Sea Wolf You're a Wolf", "You're Wolf Wolf", "Sea Wolf You're a Wolf - MultiBananachips"),
                SearchQueryTiers.of("Sea Wolf - You're a Wolf - MultiBananachips"));
        assertEquals(List.of("Swing Tree", "Discovery Swing Tree", "Swing Discovery", "Discovery Swing Tree - Jamzar1000"),
                SearchQueryTiers.of("Discovery - Swing Tree - Jamzar1000"));
        assertEquals(List.of("Sherry Wine", "Whitney Whitney Sherry Wine", "Sherry Whitney", "Whitney Whitney Sherry Wine - Whitney Woerz"),
                SearchQueryTiers.of("Whitney Whitney - Sherry Wine (Official Visualizer) - Whitney Woerz"));
    }

    @Test
    void artistEchoedByAnOfficialChannel_isATwoPartName() {
        assertEquals(List.of("2 Dollar Bill", "2 Dollar Bill - slimdan", "Dollar Bill slimdan"),
                SearchQueryTiers.of("slimdan - 2 Dollar Bill (Official Visualizer) - slimdan"));
        assertEquals(List.of("BAD DREAMS", "BAD DREAMS - Nightly", "DREAMS Nightly"),
                SearchQueryTiers.of("Nightly \u2013 BAD DREAMS (Official Lyric Video) - Nightly"));
    }

    @Test
    void aVersionInTheMiddle_stillReadsAsTitleQualifierArtist_notAsArtistTitleChannel() {
        assertEquals(List.of("Kiss Me", "Kiss Me - Sixpence None The Richer", "Kiss Sixpence None Richer"),
                SearchQueryTiers.of("Kiss Me - Radio Edit - Sixpence None The Richer"));
    }

    @Test
    void aGuestCredit_isNotSearched_andALongTitle_isAlsoSearchedByItsFirstFiveWords() {
        assertEquals(List.of("Floette", "Floette - Arlo Parks", "Floette Parks"),
                SearchQueryTiers.of("Floette ft. John Glacier (Official Video) - Arlo Parks"));
        assertEquals("Floette - Arlo Parks", SearchQueryTiers.pickerName("Floette ft. John Glacier (Official Video) - Arlo Parks"));
        assertEquals(List.of("Another thing that I'm supposed to do", "Another thing that I'm supposed to do - Gretel",
                        "Another thing I'm supposed to do Gretel", "Another thing that I'm supposed - Gretel"),
                SearchQueryTiers.of("Gretel - Another thing that I'm supposed to do (that I don't want to) (Official Video) - Gretel"));
        // never more than four searches for one song
        assertEquals(4, SearchQueryTiers.of("Red Wire Black Wire - Breathing Fire Like A Dragon Does (official video) - ToughCustomerRecords").size());
        assertEquals(List.of("Breathing Fire Like A Dragon Does", "Red Wire Black Wire Breathing Fire Like A Dragon Does",
                        "Breathing Fire Like Dragon Does Wire Black Wire", "Red Wire Black Wire Breathing Fire Like A Dragon"),
                SearchQueryTiers.of("Red Wire Black Wire - Breathing Fire Like A Dragon Does (official video) - ToughCustomerRecords"));
        assertEquals(List.of("No Small Feat", "No Small Feat - Someone", "Small Feat Someone"), SearchQueryTiers.of("No Small Feat - Someone"));
    }

    @Test
    void trailingLyricsTag_isRemoved() {
        assertEquals(List.of("Such Great Heights", "The Postal Service Such Great Heights", "Great Heights Postal Service",
                        "The Postal Service Such Great Heights - Julia"),
                SearchQueryTiers.of("The Postal Service - Such Great Heights Lyrics - Julia"));
        assertEquals(List.of("Anyone Else But You", "Moldy Peaches Anyone Else But You", "Anyone Else But Peaches",
                        "Moldy Peaches Anyone Else But You - pirateie"),
                SearchQueryTiers.of("Moldy Peaches - \"Anyone Else But You\" w/ Lyrics - pirateie"));
        assertEquals(List.of("Tire Swing", "Tire Swing - Kimya dawson", "Swing dawson"), SearchQueryTiers.of("Kimya dawson - Tire Swing (Lyrics) - Kimya dawson"));
    }

    @Test
    void byArtist_becomesItsOwnPart_songsWithByInTheTitleDoNot() {
        assertEquals(List.of("Tongue Tied", "Tongue Tied - Grouplove", "Tongue Grouplove"), SearchQueryTiers.of("Tongue Tied by Grouplove - Hyde"));
        assertEquals("Tongue Tied - Grouplove", SearchQueryTiers.pickerName("Tongue Tied by Grouplove - Hyde"));
        assertEquals(List.of("Stand By Me", "Stand By Me - Ben E. King", "Stand Me Ben King"), SearchQueryTiers.of("Stand By Me - Ben E. King"));
        assertEquals(List.of("Blinded by the Light", "Blinded by the Light - Manfred Mann", "Blinded the Light Manfred"),
                SearchQueryTiers.of("Blinded by the Light - Manfred Mann"));
    }

    @Test
    void aDashGluedToTheWordBefore_isASeparator_hyphenatedNamesAreNot() {
        assertEquals(List.of("Tegan and Sara", "The Con Tegan and Sara", "Tegan Sara Con", "The Con Tegan and Sara - YourWorstNightmare18"),
                SearchQueryTiers.of("The Con- Tegan and Sara (with lyrics) - YourWorstNightmare18"));
        assertEquals(List.of("If I'm Gonna Eat Somebody", "If I'm Gonna Eat Somebody - Tone-Loc", "If Gonna Eat Somebody Tone-Loc"),
                SearchQueryTiers.of("If I'm Gonna Eat Somebody (It Might As Well Be You) - Tone-Loc"));
        assertEquals(List.of("Empire State of Mind", "Empire State of Mind - Jay-Z", "Empire State Mind Jay-Z"),
                SearchQueryTiers.of("Empire State of Mind - Jay-Z"));
        // "Metric-Black Sheep" cannot be told apart from "Jay-Z" by the text alone, so it is left as is.
        assertEquals(List.of("Metric-Black Sheep", "Metric-Black Sheep - SyncYouNow", "Metric-Black SyncYouNow"),
                SearchQueryTiers.of("Metric-Black Sheep - SyncYouNow"));
    }

    // ---- 28-09-2026: Soulseek blocks whole phrases; a wording missing one word of each name gets through ----

    @Test
    void aWordingMissingOneWordOfTheTitleAndOfTheArtist_followsTheArtistWording() {
        // Probed live: "Bad Romance", "Lady Gaga" and "Bad Romance Gaga" return zero peers; "Romance Gaga" 723 files.
        assertEquals(List.of("Bad Romance", "Bad Romance - Lady Gaga", "Romance Gaga"),
                SearchQueryTiers.of("Bad Romance - Lady Gaga"));
        assertEquals(List.of("18 and Life", "18 and Life - Skid Row", "18 Life Skid"),
                SearchQueryTiers.of("18 and Life - Skid Row"));
        assertEquals(List.of("Happy Song", "Happy Song - Bring Me The Horizon", "Happy Bring The Horizon"),
                SearchQueryTiers.of("Happy Song (Official Audio) - Bring Me The Horizon"));
        assertEquals(List.of("Born This Way", "Born This Way - Lady Gaga", "Born Way Gaga"),
                SearchQueryTiers.of("Born This Way - Lady Gaga"));
        // a one-word title keeps its word; the artist still loses one ("Judas Gaga": 1,042 files, all hers)
        assertEquals(List.of("Judas", "Judas - Lady Gaga", "Judas Gaga"), SearchQueryTiers.of("Judas - Lady Gaga"));
        // nothing to drop on either side: no such wording
        assertEquals(List.of("Hello", "Hello - Oasis"), SearchQueryTiers.of("Hello (Official Lyric Video) - Oasis"));
        // "Artist - Title - channel": the dodge uses the real artist, never the channel
        assertEquals(List.of("Polish Girl", "Neon Indian Polish Girl", "Polish Indian", "Neon Indian Polish Girl - toomainstream"),
                SearchQueryTiers.of("Neon Indian - Polish Girl - toomainstream"));
    }

    @Test
    void lessOneWord_dropsTheFirstStopWord_elseTheShortestWord_andLeavesOneWordAlone() {
        assertEquals("18 Life", SearchQueryTiers.lessOneWord("18 and Life"));
        assertEquals("Romance", SearchQueryTiers.lessOneWord("Bad Romance"));
        assertEquals("Gaga", SearchQueryTiers.lessOneWord("Lady Gaga"));
        assertEquals("Jackson", SearchQueryTiers.lessOneWord("Michael Jackson"));
        assertEquals("Skid", SearchQueryTiers.lessOneWord("Skid Row"));
        assertEquals("Bring The Horizon", SearchQueryTiers.lessOneWord("Bring Me The Horizon"));
        assertEquals("Rihanna", SearchQueryTiers.lessOneWord("Rihanna"));
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

package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.download.SearchQueryTiers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackMatchingServiceTest {

    private final TrackMatchingService matcher = new TrackMatchingService();

    @Test
    void plainStudioFileStillMatches() {
        assertTrue(matcher.isMatch("Wonderwall - Oasis", "Oasis/(What's the Story) Morning Glory/03 - Wonderwall.flac"));
    }

    @Test
    void unrequestedVersionIsRejected_requestedVersionIsKept() {
        assertFalse(matcher.isMatch("Wonderwall - Oasis", "Oasis/Familiar to Millions/12 - Wonderwall (Live).mp3"));
        assertFalse(matcher.isMatch("Wonderwall - Oasis", "Oasis - Wonderwall (Acoustic).mp3"));
        assertTrue(matcher.isMatch("Wonderwall (Remix) - Oasis", "Oasis - Wonderwall (Remix).mp3"));
    }

    @Test
    void djPoolEditsAreRejected() {
        assertFalse(matcher.isMatch("Dai Dai - Shakira", "Shakira, Burna Boy - Dai Dai (DJ Franco Pop) (Intro-Outro) 12A 116 [www.dj-promo.org].mp3"));
        assertFalse(matcher.isMatch("Dai Dai - Shakira", "Shakira & Burna Boy - Dai Dai (Clean) 116.mp3"));
    }

    @Test
    void requestedVersionMustAppearInFilename() {
        assertFalse(matcher.isMatch("Wonderwall (Live) - Oasis", "Oasis - Wonderwall.flac"));
        assertTrue(matcher.isMatch("Wonderwall (Live) - Oasis", "Oasis - Wonderwall (Live at Wembley).flac"));
        // no family word in the file, but the request's own qualifier "wembley" is there
        assertTrue(matcher.isMatch("Wonderwall (Live at Wembley) - Oasis", "Oasis - Wonderwall (Wembley 1996).flac"));
        assertFalse(matcher.isMatch("Wonderwall (Remix) - Oasis", "Oasis - Wonderwall.flac"));
    }

    @Test
    void titleMustBeInLastSegmentOfFilename() {
        String request = "Californication - Red Hot Chili Peppers";
        assertFalse(matcher.isMatch(request, "Red Hot Chili Peppers - Californication - 09 - Emit Remmus.flac"));
        assertTrue(matcher.isMatch(request, "Red Hot Chili Peppers - Californication - 01 - Californication.flac"));
        assertTrue(matcher.isMatch(request, "Red Hot Chili Peppers/Californication/01 Californication.flac"));
    }

    // ---- the 28-09-2026 post-mortem: 49 songs whose every file was rejected ----

    /** What the executor hands the picker. */
    private boolean matches(String youtubeName, String file) {
        return matcher.isMatch(SearchQueryTiers.pickerName(youtubeName), file);
    }

    @Test
    void artistTitleChannel_acceptsTheSong_neverNeedsTheChannel() {
        assertTrue(matches("Neon Indian - Polish Girl - toomainstream", "Neon Indian/Era Extraña/03 - Polish Girl.flac"));
        assertTrue(matches("Neon Indian - Polish Girl - toomainstream", "Neon Indian - Polish Girl.mp3"));
        assertFalse(matches("Neon Indian - Polish Girl - toomainstream", "Neon Indian - Deadbeat Summer.mp3"));
        assertTrue(matches("Sea Wolf - You're a Wolf - MultiBananachips", "Sea Wolf/Leaves in the River/02 - You're a Wolf.flac"));
        assertTrue(matches("Discovery - Swing Tree - Jamzar1000", "Discovery - LP - 05 - Swing Tree.flac"));
        assertTrue(matches("Two Door Cinema Club - Undercover Martyn - A M", "Two Door Cinema Club/Tourist History/03 Undercover Martyn.flac"));
        assertTrue(matches("Whitney Whitney - Sherry Wine (Official Visualizer) - Whitney Woerz", "Whitney Woerz - Sherry Wine.mp3"));
    }

    @Test
    void aFileNamedTitleThenArtist_isJudgedOnItsWholeName() {
        assertTrue(matches("6 FEET UNDER - Ruby Waters", "6 FEET UNDER - Ruby Waters.mp3"));
        // the album-sibling guard still holds: the last segment is neither the title nor the artist
        assertFalse(matches("Californication - Red Hot Chili Peppers", "Red Hot Chili Peppers - Californication - 09 - Emit Remmus.flac"));
    }

    @Test
    void artistEchoedByTheChannel_acceptsTheSong() {
        assertTrue(matches("slimdan - 2 Dollar Bill (Official Visualizer) - slimdan", "slimdan - 2 Dollar Bill.mp3"));
        assertTrue(matches("Nightly \u2013 BAD DREAMS (Official Lyric Video) - Nightly", "Nightly/Nightly - Bad Dreams.flac"));
    }

    @Test
    void trailingLyricsAndByArtist_areNotPartOfTheTitle() {
        assertTrue(matches("The Postal Service - Such Great Heights Lyrics - Julia", "The Postal Service/Give Up/02 - Such Great Heights.flac"));
        assertTrue(matches("Moldy Peaches - \"Anyone Else But You\" w/ Lyrics - pirateie", "The Moldy Peaches - Anyone Else But You.mp3"));
        assertTrue(matches("Tongue Tied by Grouplove - Hyde", "Grouplove/Never Trust a Happy Song/03 - Tongue Tied.flac"));
    }

    @Test
    void theVersionRulesStillSeeTheRawNamesQualifier() {
        assertFalse(matches("Asleep Talking (Acoustic) - Magnus Ferrell", "Magnus Ferrell - Asleep Talking.mp3"));
        assertTrue(matches("Asleep Talking (Acoustic) - Magnus Ferrell", "Magnus Ferrell - Asleep Talking (Acoustic).mp3"));
        assertFalse(matches("Wonderwall (Official Video) - Oasis", "Oasis - Wonderwall (Live).mp3"));
        assertTrue(matches("Kiss Me - Radio Edit - Sixpence None The Richer", "Sixpence None The Richer - Kiss Me (Radio Edit).mp3"));
        assertFalse(matches("Kiss Me - Radio Edit - Sixpence None The Richer", "Sixpence None The Richer - Breathe Your Name (Radio Edit).mp3"));
    }
}

package com.catacomb5099.naviseerr.util;

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
}

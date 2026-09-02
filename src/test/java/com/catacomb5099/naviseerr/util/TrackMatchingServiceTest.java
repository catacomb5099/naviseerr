package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.request.TrackQuery;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation tests for {@link TrackMatchingService#isMatch}. This class was originally
 * written, and run GREEN, against the pre-Task-5 {@code isMatch(String, String)} signature, to
 * capture what the class did before the refactor to {@code isMatch(TrackQuery, String)} below.
 * The task-5 report records that baseline run and which of these six outcomes changed once the
 * signature and matching logic changed:
 *
 * <ul>
 *   <li>Unchanged: {@code hyphenInsideTheSongTitle}, {@code noHyphenAtAll}, and
 *       {@code hyphenatedNameWithNoArtistMetadata} -- all three exercise {@code artists()} empty,
 *       which still falls through to the exact old extractParts-split logic
 *       ({@code isMatchByLegacySplit}). This is deliberate: it is the only reason that method
 *       still exists.</li>
 *   <li>Unchanged (same true, different reason): {@code artistAfterTitleInTheFilename} -- now
 *       exercises the new non-empty-artists path, and matches via BOTH the fuzzy composite and
 *       {@code containsSongAndAnyArtist}, same as it matched via containsBothParts before.</li>
 *   <li>CHANGED, the actual behaviour change this task introduces:
 *       {@code collabWithPartialArtistCredit} -- rejected under the old whole-string
 *       containsBothParts check (verified separately, before the refactor, in this same test
 *       method's earlier form), now ACCEPTED because the fuzzy composite uses only the primary
 *       artist and containsSongAndAnyArtist requires only one artist, not all of them.</li>
 * </ul>
 */
class TrackMatchingServiceTest {

    private final TrackMatchingService service = new TrackMatchingService();

    /**
     * Empty artists -- the deprecated route / backfilled-row shape. Falls through to
     * {@code isMatchByLegacySplit}, which is byte-for-byte the old logic: {@code extractParts}
     * still guesses artist/title by splitting on the first "-", so a song genuinely titled
     * "Twenty-One" is still misparsed into artist="Twenty", title="One", and an unrelated file
     * that happens to contain both common words as substrings is still wrongly accepted. This is
     * the bug the brief calls out -- it survives ONLY here, in the no-artist-known fallback, not
     * in the primary path below.
     */
    @Test
    void hyphenInsideTheSongTitle_stillCausesAFalsePositiveInTheEmptyArtistsFallback() {
        boolean result = service.isMatch(
                new TrackQuery("Twenty-One", List.of()),
                "01 One More Time - DJ Twenty.mp3");

        assertTrue(result, "the legacy split fallback still mis-parses a hyphenated title");
    }

    /**
     * Empty artists, no hyphen in the song name either: extractParts returns a null artist and
     * containsBothParts is skipped; the match succeeds purely on the fuzzy fallback finding the
     * exact substring "yesterday". Unchanged by the refactor.
     */
    @Test
    void noHyphenAtAll_emptyArtists_fallsBackToFuzzyOnly_andStillMatches() {
        boolean result = service.isMatch(
                new TrackQuery("Yesterday", List.of()),
                "The Beatles - Yesterday.mp3");

        assertTrue(result, "fuzzy-only fallback should still find an exact substring match");
    }

    /**
     * Real artist metadata this time (non-empty {@code artists()}), so this exercises the NEW
     * primary path, not the legacy split. The fuzzy composite is "artist songName" regardless of
     * what order the filename lists them in, and containsSongAndAnyArtist is two independent
     * "contains" checks -- so a filename listing the title before the artist (the reverse of the
     * composite's own order) still matches, same as it did under the old order-insensitive
     * containsBothParts.
     */
    @Test
    void artistAfterTitleInTheFilename_stillMatchesUnderTheNewPathToo() {
        boolean result = service.isMatch(
                new TrackQuery("Riptide", List.of("Vance Joy")),
                "Riptide - Vance Joy.flac");

        assertTrue(result, "order of artist vs. title in the filename doesn't matter");
    }

    /**
     * THE BEHAVIOUR CHANGE. A five-artist collab, filename crediting only the primary artist --
     * the realistic Soulseek case. Before the refactor (verified separately against the old
     * {@code isMatch(String, String)} with an equivalent comma-joined cleanTitle: tokenScore=54,
     * partialScore=64, both under threshold, and the old containsBothParts required the WHOLE
     * artist string incl. every collaborator) this was REJECTED. After the refactor, the fuzzy
     * composite uses only the primary artist ("Metro Boomin We Own It", which exactly matches the
     * normalized filename, tokenScore=100) and containsSongAndAnyArtist requires only one artist,
     * not all five -- so it is now ACCEPTED. This is the actual hit-rate improvement Task 5 makes.
     */
    @Test
    void collabWithPartialArtistCredit_wasRejectedBeforeTheRefactor_isAcceptedNow() {
        boolean result = service.isMatch(
                new TrackQuery("We Own It",
                        List.of("Metro Boomin", "Travis Scott", "21 Savage", "Future", "Young Thug")),
                "Metro Boomin - We Own It.mp3");

        assertTrue(result, "matching only the primary artist should be enough for a collab credit");
    }

    /**
     * A genuine near miss under the new primary path: different song, different artist. Both
     * fuzzy scores must stay under threshold and containsSongAndAnyArtist must fail (the filename
     * contains neither the song name nor the artist).
     */
    @Test
    void nearMissBelowBothThresholds_isRejected() {
        boolean result = service.isMatch(
                new TrackQuery("Yesterday", List.of("The Beatles")),
                "Tomorrow Never Knows - The Rolling Stones.mp3");

        assertFalse(result, "an unrelated song by an unrelated artist must not match");
    }

    /**
     * Empty artists again, this time with the artist actually baked into the raw song name
     * ("Artist - Title" shape) -- exactly what a caller with no separate artist metadata hands
     * in today. This is precisely the degraded fallback path {@code isMatch(TrackQuery, String)}
     * takes when {@code query.artists()} is empty, delegating to the same extractParts logic as
     * before. Unchanged by the refactor.
     */
    @Test
    void hyphenatedNameWithNoArtistMetadata_matchesViaTheLegacySplitFallback() {
        boolean result = service.isMatch(
                new TrackQuery("Eurythmics - Sweet Dreams", List.of()),
                "Eurythmics - Sweet Dreams (Remastered).flac");

        assertTrue(result, "extractParts split finds both fragments in the filename");
    }

    /**
     * Proves {@code buildFuzzyComposite} honors {@code useAllArtists}, mirroring
     * {@code SlskdQueryBuilder.build}'s {@code artistPrefix}. The song title in the filename is
     * deliberately word-reordered ("It We Own" instead of "We Own It") so that
     * {@code containsSongAndAnyArtist}'s literal song-name substring check fails and can't rescue
     * the match on its own -- the outcome here depends entirely on the fuzzy composite.
     *
     * <p>With the toggle on, the composite joins all five credited artists, which is the exact
     * same multiset of words (order aside) as the filename, so {@code tokenSortRatio} is a
     * perfect 100. With only the primary artist ({@code useAllArtists=false}, or the pre-fix code
     * that ignored the toggle entirely), the composite is missing four artists' worth of words
     * that the filename has, so both fuzzy scores drop well under threshold and the match is
     * rejected. This is the interaction the review flagged as having zero coverage: a query
     * configured (via the toggle) to search on every artist must also be *scored* on every
     * artist, or a real hit gets thrown away.
     */
    @Test
    void useAllArtists_true_joinsEveryArtistInTheFuzzyComposite_soAFullCollabCreditStillMatches() {
        TrackMatchingService allArtistsService = new TrackMatchingService();
        ReflectionTestUtils.setField(allArtistsService, "useAllArtists", true);

        boolean result = allArtistsService.isMatch(
                new TrackQuery("We Own It",
                        List.of("Metro Boomin", "Travis Scott", "21 Savage", "Future", "Young Thug")),
                "It We Own - Metro Boomin Travis Scott 21 Savage Future Young Thug.mp3");

        assertTrue(result,
                "with useAllArtists=true the fuzzy composite must join every artist, same as SlskdQueryBuilder's search query does, to score this full collab credit as a match");
    }

    /**
     * Same filename and query as above, but with the toggle left at its default ({@code false}).
     * The composite is primary-artist-only, so it no longer shares the filename's full word set --
     * this is the control proving the fixture actually depends on the toggle rather than matching
     * unconditionally.
     */
    @Test
    void useAllArtists_false_primaryArtistOnlyComposite_doesNotMatchTheFullCollabCredit() {
        boolean result = service.isMatch(
                new TrackQuery("We Own It",
                        List.of("Metro Boomin", "Travis Scott", "21 Savage", "Future", "Young Thug")),
                "It We Own - Metro Boomin Travis Scott 21 Savage Future Young Thug.mp3");

        assertFalse(result,
                "primary-artist-only composite should be missing too many of the filename's words to match");
    }
}

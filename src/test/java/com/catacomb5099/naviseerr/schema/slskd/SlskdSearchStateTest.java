package com.catacomb5099.naviseerr.schema.slskd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlskdSearchStateTest {

    @Test
    void erroredIsAFailure() {
        assertTrue(SlskdSearchState.isFailure("Errored", 0));
    }

    @Test
    void timedOutIsNotAFailureForASearch() {
        assertFalse(SlskdSearchState.isFailure("Completed, TimedOut", 0));
    }

    @Test
    void unknownStateIsNotTreatedAsFailure_soItFallsThroughToTheNoCandidateGuard() {
        assertFalse(SlskdSearchState.isFailure("SomethingSlskdAddedLater", 0));
    }

    /**
     * Seen live on 27-09-2026 in slskd's own search list, for all 50 searches of a playlist: every
     * finished search reads either {@code "Completed, TimedOut"} (results dried up) or
     * {@code "Completed, ResponseLimitReached"} (250 responses arrived first). Both are how a search
     * normally ends, and both parse to the values this enum guessed at before they were confirmed.
     */
    @Test
    void theTwoLiveVerifiedCompletionStates_parseAndAreNotFailures() {
        assertEquals(List.of(SlskdSearchState.COMPLETED, SlskdSearchState.TIMED_OUT),
                SlskdSearchState.parse("Completed, TimedOut"));
        assertEquals(List.of(SlskdSearchState.COMPLETED, SlskdSearchState.RESPONSE_LIMIT_REACHED),
                SlskdSearchState.parse("Completed, ResponseLimitReached"));
        assertFalse(SlskdSearchState.isFailure("Completed, ResponseLimitReached", 0));
    }

    /** The response cap overflowed: seen live for 5 of 1,463 searches by 04-10-2026, each with 251-252 responses. */
    @Test
    void erroredWithResponsesIsNotAFailure_erroredWithNoneStillIs() {
        assertFalse(SlskdSearchState.isFailure("Completed, Errored", 252));
        assertTrue(SlskdSearchState.isFailure("Completed, Errored", 0));
        assertTrue(SlskdSearchState.isFailure("Completed, Cancelled", 252));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertFalse(SlskdSearchState.isFailure(null, 0));
        assertFalse(SlskdSearchState.isFailure("  ", 0));
    }
}

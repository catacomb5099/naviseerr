package com.catacomb5099.naviseerr.schema.slskd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlskdSearchStateTest {

    @Test
    void erroredIsAFailure() {
        assertTrue(SlskdSearchState.isFailure("Errored"));
    }

    @Test
    void timedOutIsNotAFailureForASearch() {
        assertFalse(SlskdSearchState.isFailure("Completed, TimedOut"));
    }

    @Test
    void unknownStateIsNotTreatedAsFailure_soItFallsThroughToTheNoCandidateGuard() {
        assertFalse(SlskdSearchState.isFailure("SomethingSlskdAddedLater"));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertFalse(SlskdSearchState.isFailure(null));
        assertFalse(SlskdSearchState.isFailure("  "));
    }
}

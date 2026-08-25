package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Percentage progress: docs/decisions/download-progress-reporting-17-08-2026.md. Every assertion on
 * a stored/observed value uses compareTo, never equals -- Postgres returns NUMERIC(5,2) as e.g.
 * "43.00", and BigDecimal.valueOf(43).equals(new BigDecimal("43.00")) is false.
 */
class DownloadStateMachineProgressTest {

    private static final Duration SEARCH_BUDGET = Duration.ofSeconds(120);
    private static final Duration DOWNLOAD_BUDGET = Duration.ofSeconds(3600);
    private static final Duration SEARCH_POLL = Duration.ofSeconds(2);
    private static final Duration DOWNLOAD_POLL = Duration.ofSeconds(5);
    private static final Duration MISSING_GRACE = Duration.ofSeconds(60);
    private static final int RETRY_LIMIT = 2;

    private final DownloadStateMachine machine = new DownloadStateMachine(
            SEARCH_POLL, DOWNLOAD_POLL, SEARCH_BUDGET, DOWNLOAD_BUDGET, MISSING_GRACE, RETRY_LIMIT);

    // --- toProgress -----------------------------------------------------------------------------

    @Test
    void toProgress_null_meansNoObservation() {
        assertNull(DownloadStateMachine.toProgress(null));
    }

    @Test
    void toProgress_nan_meansNoObservation() {
        assertNull(DownloadStateMachine.toProgress(Float.NaN));
    }

    @Test
    void toProgress_infinite_meansNoObservation() {
        assertNull(DownloadStateMachine.toProgress(Float.POSITIVE_INFINITY));
    }

    @Test
    void toProgress_clampsBelowZero() {
        assertEquals(0, DownloadStateMachine.toProgress(-5f).compareTo(BigDecimal.ZERO));
    }

    @Test
    void toProgress_clampsAboveOneHundred() {
        assertEquals(0, DownloadStateMachine.toProgress(150f).compareTo(new BigDecimal("100")));
    }

    @Test
    void toProgress_roundsToTwoDecimalPlaces() {
        assertEquals(0, DownloadStateMachine.toProgress(43.456f).compareTo(new BigDecimal("43.46")));
    }

    // --- afterDownloadPoll: observing progress ---------------------------------------------------

    @Test
    void downloadPoll_inProgress_capturesPercentComplete() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "InProgress", 43f), T0.plusSeconds(10));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(0, next.progressPercent().compareTo(new BigDecimal("43.00")));
    }

    @Test
    void downloadPoll_inProgress_withNullPercentComplete_leavesPriorValueUnchanged() {
        DownloadTask task = downloadPolling(candidates("alice"), 0, 0, "abc")
                .withProgress(new BigDecimal("60.00"));

        DownloadDecision d = machine.afterDownloadPoll(
                task, SlskdFixtures.transfer("abc", "alice", "InProgress", null), T0.plusSeconds(10));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(0, next.progressPercent().compareTo(new BigDecimal("60.00")),
                "an absent source value must never overwrite the last known one");
    }

    @Test
    void downloadPoll_transferMissingFromBatchResponse_leavesProgressUnchanged() {
        DownloadTask task = downloadPolling(candidates("alice"), 0, 0, "abc")
                .withProgress(new BigDecimal("60.00"));

        DownloadDecision d = machine.afterDownloadPoll(task, null, T0.plusSeconds(10));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(0, next.progressPercent().compareTo(new BigDecimal("60.00")),
                "cannot see the transfer is not evidence it lost progress -- avoid the flicker");
    }

    // --- reset on retry / candidate failover -----------------------------------------------------

    @Test
    void downloadPoll_failureUnderRetryLimit_resetsProgressToZero() {
        DownloadTask task = downloadPolling(candidates("alice", "bob"), 0, 0, "abc")
                .withProgress(new BigDecimal("87.00"));

        DownloadDecision d = machine.afterDownloadPoll(
                task, SlskdFixtures.transfer("abc", "alice", "Completed, TimedOut"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(0, next.progressPercent().compareTo(BigDecimal.ZERO),
                "a retry of the same candidate starts a fresh transfer, not a resume");
    }

    @Test
    void downloadPoll_retriesExhausted_movingToNextCandidate_resetsProgressToZero() {
        DownloadTask task = downloadPolling(candidates("alice", "bob"), 0, RETRY_LIMIT, "abc")
                .withProgress(new BigDecimal("99.00"));

        DownloadDecision d = machine.afterDownloadPoll(
                task, SlskdFixtures.transfer("abc", "alice", "Errored"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(1, next.candidateIndex());
        assertEquals(0, next.progressPercent().compareTo(BigDecimal.ZERO));
    }

    @Test
    void callFailed_inDownloadPhase_resetsProgressToZero() {
        DownloadTask task = downloadPolling(candidates("alice", "bob"), 0, 0, "abc")
                .withProgress(new BigDecimal("55.00"));

        DownloadDecision d = machine.onCallFailed(task, new RuntimeException("boom"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(0, next.progressPercent().compareTo(BigDecimal.ZERO));
    }

    @Test
    void searchPollAdvancingToDownloadInit_startsAtZero() {
        DownloadDecision d = machine.afterSearchPoll(searchPolling("s1"),
                SlskdFixtures.searchState("s1", true, "Completed"),
                candidates("alice"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(0, next.progressPercent().compareTo(BigDecimal.ZERO));
    }

    // --- withPhase / dueAt carry progress forward within the same lifecycle -----------------------

    @Test
    void withPhase_carriesProgressForwardByDefault() {
        DownloadTask task = at(DownloadPhase.DOWNLOAD_POLL).withProgress(new BigDecimal("70.00"));

        DownloadTask moved = task.withPhase(DownloadPhase.DOWNLOAD_INIT, T0);

        assertEquals(0, moved.progressPercent().compareTo(new BigDecimal("70.00")));
    }

    @Test
    void dueAt_preservesProgress() {
        DownloadTask task = at(DownloadPhase.DOWNLOAD_POLL).withProgress(new BigDecimal("70.00"));

        DownloadTask rescheduled = task.dueAt(T0.plusSeconds(5));

        assertEquals(0, rescheduled.progressPercent().compareTo(new BigDecimal("70.00")));
    }
}

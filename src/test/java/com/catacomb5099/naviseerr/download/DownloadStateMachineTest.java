package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadStateMachineTest {

    private static final Duration SEARCH_BUDGET = Duration.ofSeconds(120);
    private static final Duration DOWNLOAD_BUDGET = Duration.ofSeconds(3600);
    private static final Duration SEARCH_POLL = Duration.ofSeconds(2);
    private static final Duration DOWNLOAD_POLL = Duration.ofSeconds(5);
    private static final Duration MISSING_GRACE = Duration.ofSeconds(60);
    private static final int RETRY_LIMIT = 2;

    private final DownloadStateMachine machine = new DownloadStateMachine(
            SEARCH_POLL, DOWNLOAD_POLL, SEARCH_BUDGET, DOWNLOAD_BUDGET, MISSING_GRACE, RETRY_LIMIT);

    @Test
    void searchInit_recordsSearchId_andAdvancesToSearchPoll() {
        DownloadDecision d = machine.afterSearchInit(
                at(DownloadPhase.SEARCH_INIT), SlskdFixtures.searchState("s1", false, "InProgress"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.SEARCH_POLL, next.phase());
        assertEquals("s1", next.searchId());
    }

    @Test
    void searchInit_withNoSearchId_failsRatherThanPollingNothing() {
        DownloadDecision d = machine.afterSearchInit(
                at(DownloadPhase.SEARCH_INIT), SlskdFixtures.searchState(null, false, "InProgress"), T0);

        assertEquals(DownloadStatus.FAILED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).status());
    }

    @Test
    void searchPoll_incomplete_continuesAtPollInterval_andKeepsPhaseBudget() {
        DownloadTask task = searchPolling("s1");
        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", false, "InProgress"), List.of(), T0.plusSeconds(4));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.SEARCH_POLL, next.phase());
        assertEquals(T0.plusSeconds(6), next.nextAttemptAt());
        assertEquals(task.phaseEnteredAt(), next.phaseEnteredAt(), "budget must not be refreshed");
    }

    @Test
    void searchPoll_hardFailureState_failsImmediately() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", false, "Errored"), List.of(), T0);

        DownloadDecision.Terminal t = assertInstanceOf(DownloadDecision.Terminal.class, d);
        assertEquals(DownloadStatus.FAILED, t.status());
        assertEquals(DownloadFailureCode.SEARCH_FAILED, t.failureCode());
    }

    @Test
    void searchPoll_timedOutIsNormalCompletionForASearch_notAFailure() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed, TimedOut"),
                candidates("alice"), T0);

        assertInstanceOf(DownloadDecision.Advance.class, d);
    }

    @Test
    void searchPoll_completeWithNoCandidates_fails() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed"), List.of(), T0);

        assertEquals(DownloadFailureCode.NO_CANDIDATES,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void searchPoll_completeWithCandidates_storesThemAndAdvancesToDownloadInit() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed"),
                candidates("alice", "bob"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(2, next.candidates().size());
        assertEquals(0, next.candidateIndex());
    }

    @Test
    void searchPoll_missingFromBatchResponse_treatedAsStillRunning() {
        // The batched GET /searches simply omits a search it doesn't know about — a null SearchState,
        // not an error. Deliberately indistinguishable from "still running": there is no way to tell
        // "not there yet" apart from "slskd forgot it", so this rides the existing budget timeout
        // rather than needing a dedicated branch.
        DownloadDecision d = machine.afterSearchPoll(searchPolling("s1"), null, List.of(), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(T0.plus(SEARCH_POLL), next.nextAttemptAt());
    }

    @Test
    void searchPoll_pastBudgetWhileStillRunning_timesOut() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", false, "InProgress"),
                List.of(), T0.plus(SEARCH_BUDGET).plusSeconds(1));

        assertEquals(DownloadFailureCode.TIMED_OUT,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void searchPoll_completeJustPastBudget_stillProceeds() {
        DownloadDecision d = machine.afterSearchPoll(
                searchPolling("s1"), SlskdFixtures.searchState("s1", true, "Completed"),
                candidates("alice"), T0.plus(SEARCH_BUDGET).plusSeconds(1));

        assertInstanceOf(DownloadDecision.Advance.class, d);
    }

    @Test
    void downloadInit_enqueued_advancesToPollWithTransferId() {
        DownloadDecision d = machine.afterDownloadInit(
                downloadInit(candidates("alice"), 0, 0),
                SlskdFixtures.enqueued("abc", "alice"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_POLL, next.phase());
        assertEquals("abc", next.slskdTransferId());
        assertEquals("alice", next.slskdUsername());
    }

    @Test
    void downloadInit_emptyEnqueuedList_retriesInsteadOfThrowing() {
        DownloadDecision d = machine.afterDownloadInit(
                downloadInit(candidates("alice"), 0, 0),
                SlskdFixtures.enqueueRejected(), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(1, next.retryIndex());
    }

    @Test
    void downloadPoll_succeeded_isTerminalSuccess() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, Succeeded"), T0);

        assertEquals(DownloadStatus.SUCCEEDED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).status());
    }

    @Test
    void downloadPoll_inProgress_continuesAtDownloadPollInterval() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "InProgress"), T0.plusSeconds(10));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(T0.plusSeconds(15), next.nextAttemptAt());
    }

    @Test
    void downloadPoll_transferMissingFromBatchResponse_keepsPollingOnlyWithinTheGraceWindow() {
        // A transfer absent from GET /transfers/downloads is tolerated briefly, to cover the gap
        // between enqueueing it and it showing up in the list.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"), null, T0.plusSeconds(10));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(T0.plusSeconds(15), next.nextAttemptAt());
    }

    @Test
    void downloadPoll_transferMissingPastTheGraceWindow_failsFast_ratherThanPollingForTheFullHour() {
        // THE REGRESSION GUARD for the stranded-poll bug. This case used to alias onto the
        // "still running" branch, so a lookup that could never resolve was indistinguishable from a
        // transfer in progress and the row polled for the whole 1h downloadBudget before timing out.
        // It now terminates after MISSING_GRACE (60s) with a reason that names the actual problem.
        // Note the deliberate choice of FAILED over SUCCEEDED: not being able to see a transfer is
        // not evidence that it finished.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"), null, T0.plusSeconds(61));

        DownloadDecision.Terminal terminal = assertInstanceOf(DownloadDecision.Terminal.class, d);
        assertEquals(DownloadStatus.FAILED, terminal.status());
        assertEquals(DownloadFailureCode.TRANSFER_NOT_FOUND, terminal.failureCode());
        assertTrue(Duration.between(T0, T0.plusSeconds(61)).compareTo(DOWNLOAD_BUDGET) < 0,
                "must fail well before the download budget, otherwise this proves nothing");
    }

    @Test
    void downloadPoll_transferPresentButWithAnUnparseableState_isTreatedAsNotFound() {
        // Defensive: a found transfer whose state string yields no recognised token is just as
        // undecidable as a missing one, and must not be mistaken for progress either.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "SomethingSlskdInventedLater"),
                T0.plusSeconds(61));

        assertEquals(DownloadFailureCode.TRANSFER_NOT_FOUND,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void downloadPoll_failureUnderRetryLimit_retriesSameCandidate() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, TimedOut"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(0, next.candidateIndex());
        assertEquals(1, next.retryIndex());
    }

    @Test
    void downloadPoll_retriesExhausted_movesToNextCandidateAndResetsRetryIndex() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, RETRY_LIMIT, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Errored"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(1, next.candidateIndex());
        assertEquals(0, next.retryIndex());
    }

    @Test
    void downloadPoll_allCandidatesExhausted_fails() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, RETRY_LIMIT, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Errored"), T0);

        assertEquals(DownloadFailureCode.SOURCES_EXHAUSTED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void downloadPoll_pastBudget_timesOut() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "InProgress"),
                T0.plus(DOWNLOAD_BUDGET).plusSeconds(1));

        assertEquals(DownloadFailureCode.TIMED_OUT,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void callFailed_inSearchPhase_withUnrecognisedError_fails() {
        DownloadDecision d = machine.onCallFailed(
                at(DownloadPhase.SEARCH_POLL), new RuntimeException("boom"), T0);

        assertEquals(DownloadFailureCode.SEARCH_FAILED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void callFailed_inSearchPhase_withTransportFailure_withinBudget_retries() {
        DownloadDecision d = machine.onCallFailed(
                at(DownloadPhase.SEARCH_POLL), SlskdFixtures.transportFailure(), T0.plusSeconds(1));

        assertInstanceOf(DownloadDecision.Continue.class, d);
    }

    @Test
    void callFailed_inSearchPhase_with5xx_withinBudget_retries() {
        DownloadDecision d = machine.onCallFailed(
                at(DownloadPhase.SEARCH_POLL), SlskdFixtures.responseFailure(502), T0.plusSeconds(1));

        assertInstanceOf(DownloadDecision.Continue.class, d);
    }

    @Test
    void callFailed_inSearchPhase_with4xx_withinBudget_retries() {
        // 4xx is retried too, not treated as permanent -- see DownloadStateMachine.onSearchCallFailed.
        DownloadDecision d = machine.onCallFailed(
                at(DownloadPhase.SEARCH_POLL), SlskdFixtures.responseFailure(400), T0.plusSeconds(1));

        assertInstanceOf(DownloadDecision.Continue.class, d);
    }

    @Test
    void callFailed_inSearchPhase_withRetryableError_pastBudget_fails() {
        DownloadDecision d = machine.onCallFailed(
                at(DownloadPhase.SEARCH_POLL), SlskdFixtures.transportFailure(),
                T0.plus(SEARCH_BUDGET).plusSeconds(1));

        assertEquals(DownloadFailureCode.SEARCH_FAILED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void callFailed_inDownloadPhase_retriesOrMovesOn() {
        DownloadDecision d = machine.onCallFailed(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                new RuntimeException("boom"), T0);

        assertEquals(1, assertInstanceOf(DownloadDecision.Continue.class, d).next().retryIndex());
    }
}

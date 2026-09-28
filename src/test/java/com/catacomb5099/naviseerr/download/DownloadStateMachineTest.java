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
    private static final Duration QUEUED_BUDGET = Duration.ofMinutes(10);
    private static final int RETRY_LIMIT = 2;
    private static final int FIRST_WORDING_MIN_CANDIDATES = 3;

    private final StallingSharers stallingSharers = new StallingSharers(Duration.ofHours(6));
    private final DownloadStateMachine machine = new DownloadStateMachine(
            SEARCH_POLL, DOWNLOAD_POLL, SEARCH_BUDGET, DOWNLOAD_BUDGET, QUEUED_BUDGET, MISSING_GRACE,
            RETRY_LIMIT, FIRST_WORDING_MIN_CANDIDATES, stallingSharers);

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
    void searchPoll_completeWithNoCandidates_withAWordingLeft_movesOnToTheOneWithTheArtist() {
        DownloadTask task = searchPolling("s1").toBuilder()
                .songName("Wonderwall (Remix) [Official Video] - Oasis").build();
        Instant later = T0.plusSeconds(30);

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), List.of(), later);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.SEARCH_INIT, next.phase());
        assertEquals(1, next.searchTier());
        assertNull(next.searchId(), "the old search must not be polled again");
        assertEquals(later, next.phaseEnteredAt(), "each tier gets a fresh search budget");
        assertEquals("Wonderwall - Oasis", next.searchQuery());
    }

    @Test
    void searchPoll_theTitleAloneFoundTooFew_movesOn_butKeepsWhatItFound() {
        // The owner's rule: the title alone is "enough" at three acceptable files; under that, the
        // wordings that name the artist get their turn, but the few files stay on the row.
        DownloadTask task = searchPolling("s1").toBuilder().songName("Judas - Lady Gaga").build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), candidates("alice", "bob"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.SEARCH_INIT, next.phase());
        assertEquals("Judas - Lady Gaga", next.searchQuery());
        assertEquals(candidates("alice", "bob"), next.candidates());
    }

    @Test
    void searchPoll_theTitleAloneFoundEnough_downloadsWithoutAnotherSearch() {
        DownloadTask task = searchPolling("s1").toBuilder().songName("Judas - Lady Gaga").build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), candidates("a", "b", "c"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(0, next.searchTier());
    }

    @Test
    void searchPoll_theArtistWordingsFoundNothing_downloadTheFewTheTitleAloneFound() {
        // "Judas - Lady Gaga" returns zero peers: Soulseek blocks the phrase. The two files "Judas" found
        // travel along through the remaining wordings and stand once the last one is empty too.
        int lastTier = SearchQueryTiers.of("Judas - Lady Gaga").size() - 1;
        DownloadTask task = searchPolling("s1").toBuilder().songName("Judas - Lady Gaga").searchTier(1)
                .candidates(candidates("alice", "bob")).build();

        DownloadTask carried = assertInstanceOf(DownloadDecision.Advance.class, machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed, TimedOut"), List.of(), T0)).next();
        assertEquals(DownloadPhase.SEARCH_INIT, carried.phase());
        assertEquals(candidates("alice", "bob"), carried.candidates(), "kept across the next wording");

        DownloadDecision d = machine.afterSearchPoll(
                carried.toBuilder().searchTier(lastTier).build(),
                SlskdFixtures.searchState("s1", true, "Completed, TimedOut"), List.of(), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(candidates("alice", "bob"), next.candidates());
        assertEquals("alice", next.currentCandidate().username());
    }

    @Test
    void searchPoll_aLaterWording_isEnoughWithOneFile() {
        DownloadTask task = searchPolling("s1").toBuilder().songName("Judas - Lady Gaga").searchTier(1).build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), candidates("alice"), T0);

        assertEquals(DownloadPhase.DOWNLOAD_INIT, assertInstanceOf(DownloadDecision.Advance.class, d).next().phase());
    }

    @Test
    void searchPoll_theTitleAloneFoundTooFew_butNoWordingIsLeft_downloadsThem() {
        // "Hello (Official Lyric Video)" has no artist, so the title is the only wording: one file is enough.
        DownloadTask task = searchPolling("s1").toBuilder().songName("Hello (Official Lyric Video)").build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), candidates("alice"), T0);

        assertEquals(DownloadPhase.DOWNLOAD_INIT, assertInstanceOf(DownloadDecision.Advance.class, d).next().phase());
    }

    /** The same candidates with another picker grade ("OTHER_VERSION", "UNVERIFIED"). */
    private static List<DownloadCandidate> graded(String grade, String... usernames) {
        return candidates(usernames).stream().map(c -> new DownloadCandidate(c.username(), c.filename(),
                c.extension(), c.bitRate(), c.size(), c.code(), c.isLocked(), c.hasFreeUploadSlot(),
                c.queueLength(), c.uploadSpeed(), grade)).toList();
    }

    @Test
    void searchPoll_theTitleAlone_threeStudioCopiesOfARequestedLiveTake_areNotEnough() {
        // "Wonderwall" alone returns Oasis's studio take three times over; the live take the request asked
        // for may only turn up once the wording names the artist, so the studio copies do not settle it.
        DownloadTask task = searchPolling("s1").toBuilder().songName("Wonderwall (Live at Wembley) - Oasis").build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), graded("OTHER_VERSION", "a", "b", "c"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.SEARCH_INIT, next.phase());
        assertEquals("Wonderwall - Oasis", next.searchQuery());
        assertEquals(graded("OTHER_VERSION", "a", "b", "c"), next.candidates(), "kept: any version beats none");
    }

    @Test
    void searchPoll_unverifiedFiles_travelAsTheLastResort_andAConfirmedFileReplacesThem() {
        // "This Charming Man - Lo Mejor del Rock de los 80": the "artist" is a channel, so no path names it.
        DownloadTask task = searchPolling("s1").toBuilder().songName("This Charming Man - Lo Mejor del Rock de los 80").build();

        DownloadTask carried = assertInstanceOf(DownloadDecision.Advance.class, machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), graded("UNVERIFIED", "smiths1", "smiths2"), T0)).next();
        assertEquals(DownloadPhase.SEARCH_INIT, carried.phase());
        assertEquals(graded("UNVERIFIED", "smiths1", "smiths2"), carried.candidates());

        // the artist wording finds one confirmed file: it wins over the unverified pair
        DownloadTask confirmed = assertInstanceOf(DownloadDecision.Advance.class, machine.afterSearchPoll(
                carried, SlskdFixtures.searchState("s2", true, "Completed"), candidates("real"), T0)).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, confirmed.phase());
        assertEquals(candidates("real"), confirmed.candidates());

        // ... or every artist wording comes back empty (the channel is in no path): the unverified pair stands
        int lastTier = SearchQueryTiers.of(task.songName()).size() - 1;
        DownloadTask lastResort = assertInstanceOf(DownloadDecision.Advance.class, machine.afterSearchPoll(
                carried.toBuilder().searchTier(lastTier).build(),
                SlskdFixtures.searchState("s3", true, "Completed, TimedOut"), List.of(), T0)).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, lastResort.phase());
        assertEquals(graded("UNVERIFIED", "smiths1", "smiths2"), lastResort.candidates());
    }

    @Test
    void searchPoll_aLaterWording_isNotEnoughWithOnlyUnverifiedFiles() {
        // a long title has more wordings after "title - artist" (the short-title forms), so there is one to move on to
        DownloadTask task = searchPolling("s1").toBuilder().songName("Another thing that I'm supposed to do - Gretel").searchTier(1).build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), graded("UNVERIFIED", "x"), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.SEARCH_INIT, next.phase());
        assertEquals(graded("UNVERIFIED", "x"), next.candidates());
    }

    @Test
    void aLaterSearch_thatTimesOut_errorsOrCannotStart_downloadsTheKeptFilesInsteadOfFailing() {
        DownloadTask kept = searchPolling("s2").toBuilder().songName("Judas - Lady Gaga").searchTier(1)
                .candidates(candidates("alice", "bob")).build();

        // stuck in slskd past the search budget
        DownloadDecision timedOut = machine.afterSearchPoll(kept, SlskdFixtures.searchState("s2", false, "InProgress"),
                List.of(), T0.plus(SEARCH_BUDGET).plusSeconds(1));
        assertEquals(DownloadPhase.DOWNLOAD_INIT, assertInstanceOf(DownloadDecision.Advance.class, timedOut).next().phase());
        // slskd reports the search errored
        DownloadDecision errored = machine.afterSearchPoll(kept, SlskdFixtures.searchState("s2", false, "Errored"), List.of(), T0);
        assertEquals(candidates("alice", "bob"), assertInstanceOf(DownloadDecision.Advance.class, errored).next().candidates());
        // slskd answered the start with no search id
        DownloadDecision blank = machine.afterSearchInit(kept.withPhase(DownloadPhase.SEARCH_INIT, T0),
                SlskdFixtures.searchState(null, false, "InProgress"), T0);
        assertEquals(DownloadPhase.DOWNLOAD_INIT, assertInstanceOf(DownloadDecision.Advance.class, blank).next().phase());
        // the slskd call kept failing until the budget ran out
        DownloadDecision failed = machine.onCallFailed(kept, SlskdFixtures.transportFailure(), T0.plus(SEARCH_BUDGET).plusSeconds(1));
        assertEquals(DownloadPhase.DOWNLOAD_INIT, assertInstanceOf(DownloadDecision.Advance.class, failed).next().phase());
    }

    @Test
    void searchPoll_theLastWordingFoundNothing_andNothingWasKept_fails() {
        int lastTier = SearchQueryTiers.of("Judas - Lady Gaga").size() - 1;
        DownloadTask task = searchPolling("s1").toBuilder().songName("Judas - Lady Gaga").searchTier(lastTier).build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed, TimedOut"), List.of(), T0);

        assertEquals(DownloadFailureCode.NO_CANDIDATES,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void searchPoll_completeWithNoCandidates_onAnArtistEchoTitle_movesOnToTheDedupedArtistWording() {
        DownloadTask task = searchPolling("s1").toBuilder()
                .songName("Oasis - Don't Look Back In Anger (Official Video) - Oasis").build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), List.of(), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(1, next.searchTier());
        assertEquals("Don't Look Back In Anger - Oasis", next.searchQuery());
    }

    @Test
    void searchPoll_completeWithNoCandidates_onTheLastTier_fails() {
        DownloadTask task = searchPolling("s1").toBuilder()
                .songName("Wonderwall (Remix) [Official Video] - Oasis").searchTier(1).build();

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), List.of(), T0);

        assertEquals(DownloadFailureCode.NO_CANDIDATES,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void searchPoll_completeWithNoCandidates_onACleanTitle_stillMovesOnToTheArtistWording() {
        // The title alone came back with nothing acceptable, so the artist wording gets its turn even
        // though nothing was cleaned out of the name.
        DownloadTask task = searchPolling("s1").toBuilder().songName("Thriller - Michael Jackson").build();
        assertEquals("Thriller", task.searchQuery());

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), List.of(), T0);

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals("Thriller - Michael Jackson", next.searchQuery());
    }

    @Test
    void searchPoll_completeWithNoCandidates_onATitleWithoutAnArtist_failsWithoutRetrying() {
        // No artist to drop, so there is no looser wording left: one tier, then NO_CANDIDATES.
        DownloadTask task = searchPolling("s1").toBuilder()
                .songName("Hello (Official Lyric Video)").build();
        assertEquals("Hello", task.searchQuery());

        DownloadDecision d = machine.afterSearchPoll(
                task, SlskdFixtures.searchState("s1", true, "Completed"), List.of(), T0);

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

    // ---- a peer that keeps us in its queue -------------------------------------------------------

    @Test
    void downloadPoll_queuedRemotelyPastTheQueuedBudget_movesToTheNextCandidate_skippingSamePeerRetries() {
        // The measured case: peer SKYLiGHT_B held 'Whip It - Devo 2.0' at "Queued, Remotely" 0% for the
        // whole hour while seven other candidates were never tried. Straight to the next peer, not a
        // retry of this one -- a peer that queued us for ten minutes will queue us again.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Queued, Remotely"),
                T0.plus(QUEUED_BUDGET).plusSeconds(1));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(1, next.candidateIndex(), "next peer, even though retries on alice remained");
        assertEquals(0, next.retryIndex());
        assertNull(next.slskdTransferId());
    }

    @Test
    void downloadPoll_queuedRemotelyWithinTheQueuedBudget_keepsWaiting() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Queued, Remotely"),
                T0.plus(QUEUED_BUDGET).minusSeconds(1));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_POLL, next.phase());
        assertEquals(0, next.candidateIndex());
    }

    @Test
    void downloadPoll_queuedLocallyPastTheQueuedBudget_isSlskdsBacklogNotThePeers_soKeepsWaiting() {
        // "Queued, Locally" means slskd's own download slots are full and it has not asked the peer
        // yet. Abandoning the peer for that would cycle through every candidate while slskd is
        // saturated; only the hour-long download budget bounds it.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Queued, Locally"),
                T0.plus(QUEUED_BUDGET).plusSeconds(1));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_POLL, next.phase());
        assertEquals(0, next.candidateIndex());
    }

    @Test
    void downloadPoll_inProgressWithBytesMoving_pastTheQueuedBudget_isNotTouchedByIt() {
        // Only the hour-long download budget bounds a transfer that is actually moving.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "InProgress", 40f, 40L),
                T0.plus(QUEUED_BUDGET).plusSeconds(300));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_POLL, next.phase());
        assertEquals(0, next.candidateIndex());
    }

    @Test
    void downloadPoll_nominallyInProgressButNoBytesMoved_pastTheQueuedBudget_isTreatedAsStuck() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "InProgress", 0f, 0L),
                T0.plus(QUEUED_BUDGET).plusSeconds(1));

        assertEquals(1, assertInstanceOf(DownloadDecision.Continue.class, d).next().candidateIndex());
    }

    @Test
    void downloadPoll_lastCandidateQueuedPastTheQueuedBudget_exhaustsSources() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Queued, Remotely"),
                T0.plus(QUEUED_BUDGET).plusSeconds(1));

        assertEquals(DownloadFailureCode.SOURCES_EXHAUSTED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    // ---- a sharer that stalled one song is skipped by every other song ---------------------------

    @Test
    void downloadPoll_aSharerThatStalledOneSong_isSkippedByTheNextSong_whileAnotherSharerRemains() {
        // Song 1 burns its ten minutes on alice.
        machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Queued, Remotely"),
                T0.plus(QUEUED_BUDGET).plusSeconds(1));

        // Song 2's search finishes with alice ranked first: it must start on carol instead.
        DownloadDecision d = machine.afterSearchPoll(searchPolling("s2"),
                SlskdFixtures.searchState("s2", true, "Completed"),
                candidates("alice", "carol"), T0.plus(QUEUED_BUDGET).plusSeconds(2));

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(1, next.candidateIndex(), "alice is stalling, so carol goes first");
    }

    @Test
    void downloadPoll_failingOver_skipsAStallingSharer_inFavourOfTheOneAfterIt() {
        stallingSharers.markStalled("bob", T0);

        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob", "carol"), 0, 2, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, Errored"), T0.plusSeconds(30));

        assertEquals(2, assertInstanceOf(DownloadDecision.Continue.class, d).next().candidateIndex());
    }

    @Test
    void downloadPoll_whenEveryRemainingSharerIsStalling_theyAreStillTried_ratherThanFailing() {
        stallingSharers.markStalled("bob", T0);
        stallingSharers.markStalled("carol", T0);

        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob", "carol"), 0, 2, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, Errored"), T0.plusSeconds(30));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(1, next.candidateIndex(), "a slow success beats SOURCES_EXHAUSTED");
    }

    @Test
    void downloadPoll_aStallingSharer_isForgivenOnceItsCooldownHasPassed() {
        stallingSharers.markStalled("alice", T0);

        DownloadDecision d = machine.afterSearchPoll(searchPolling("s2"),
                SlskdFixtures.searchState("s2", true, "Completed"),
                candidates("alice", "bob"), T0.plus(Duration.ofHours(6)));

        assertEquals(0, assertInstanceOf(DownloadDecision.Advance.class, d).next().candidateIndex());
    }

    // ---- a sharer that says no ---------------------------------------------------------------

    @Test
    void downloadPoll_rejected_goesStraightToTheNextCandidate_notASameFileRetry() {
        // "Transfer rejected: File not shared." -- the sharer's share index is stale; asking for the
        // same file again gets the same answer. Measured 27-09-2026: 8 such retries, all for nothing.
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice", "bob"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, Rejected"), T0.plusSeconds(3));

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(1, next.candidateIndex(), "next sharer, even though retries on alice remained");
        assertEquals(0, next.retryIndex());
    }

    @Test
    void downloadPoll_rejectedOnTheLastCandidate_exhaustsSources() {
        DownloadDecision d = machine.afterDownloadPoll(
                downloadPolling(candidates("alice"), 0, 0, "abc"),
                SlskdFixtures.transfer("abc", "alice", "Completed, Rejected"), T0.plusSeconds(3));

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

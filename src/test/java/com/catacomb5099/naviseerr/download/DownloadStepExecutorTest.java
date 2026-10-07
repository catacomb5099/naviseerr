package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DownloadStepExecutorTest {

    private SlskdService slskdService;
    private SlskdSearchResultProcessor searchProcessor;
    private DownloadTaskRepository repository;
    private DownloadStepExecutor executor;

    @BeforeEach
    void setUp() {
        slskdService = mock(SlskdService.class);
        searchProcessor = mock(SlskdSearchResultProcessor.class);
        repository = mock(DownloadTaskRepository.class);
        when(repository.saveSearchResults(any(), any(), any())).thenReturn(Mono.just(1L));
        DownloadStateMachine machine = new DownloadStateMachine(
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(120), Duration.ofSeconds(3600), Duration.ofMinutes(10),
                Duration.ofSeconds(60), 2, 3, 2, new StallingSharers(Duration.ofHours(6)));
        executor = new DownloadStepExecutor(slskdService, searchProcessor, machine, repository,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    /** A pass with none of our transfers in flight. */
    private static SharerLoad none() {
        return SharerLoad.of(List.of(), Map.of());
    }

    @Test
    void searchInit_callsSearchOnceAndAdvances() {
        when(slskdService.searchResults("never gonna give you up"))
                .thenReturn(Mono.just(SlskdFixtures.searchState("s1", false, "InProgress")));

        DownloadDecision d = executor
                .execute(at(DownloadPhase.SEARCH_INIT), Map.of(), Map.of(), none()).block();

        assertEquals(DownloadPhase.SEARCH_POLL,
                assertInstanceOf(DownloadDecision.Advance.class, d).next().phase());
        verify(slskdService, times(1)).searchResults(any());
    }

    @Test
    void searchInit_searchesTheNameWithoutVideoNoise_neverTheRawName() {
        // The very FIRST search is the title alone: the noise never reaches slskd, there is no tier on which it would.
        DownloadTask task = at(DownloadPhase.SEARCH_INIT).toBuilder()
                .songName("Hello (Official Lyric Video) - Oasis").build();
        when(slskdService.searchResults("Hello"))
                .thenReturn(Mono.just(SlskdFixtures.searchState("s2", false, "InProgress")));

        DownloadDecision d = executor.execute(task, Map.of(), Map.of(), none()).block();

        assertEquals("s2", assertInstanceOf(DownloadDecision.Advance.class, d).next().searchId());
        verify(slskdService).searchResults("Hello");
        verify(slskdService, never()).searchResults("Hello (Official Lyric Video) - Oasis");
    }

    @Test
    void searchPoll_complete_selectsAgainstTheCleanedSongName_notTheLooseQuery() {
        // The search is loose on purpose; the picker needs the qualifier and the artist from the name.
        DownloadTask task = searchPolling("s1").toBuilder()
                .songName("Hello (Official Lyric Video) - Oasis").build();
        var summary = SlskdFixtures.searchState("s1", true, "Completed");
        var full = SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of());
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any(), any(), any())).thenReturn(Mono.just(List.of()));

        executor.execute(task, Map.of("s1", summary), Map.of(), none()).block();

        // the picker sees the cleaned name, not the raw YouTube one, and the wording that was searched;
        // a song that is no album's track keeps files of any length
        verify(searchProcessor).selectBestFiles(eq(full), eq("Hello - Oasis"), eq("Hello"), isNull());
    }

    @Test
    void searchPoll_complete_anAlbumsSong_holdsTheFilesToTheAlbumTracksLength() {
        // P6: only an album row carries a YouTube track number; a playlist row has a length but no number.
        DownloadTask albumSong = searchPolling("s1").toBuilder().trackNumber(3).durationSeconds(270).build();
        DownloadTask playlistSong = searchPolling("s1").toBuilder().durationSeconds(270).build();
        var summary = SlskdFixtures.searchState("s1", true, "Completed");
        var full = SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of());
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any(), any(), any())).thenReturn(Mono.just(List.of()));

        executor.execute(albumSong, Map.of("s1", summary), Map.of(), none()).block();
        executor.execute(playlistSong, Map.of("s1", summary), Map.of(), none()).block();

        verify(searchProcessor).selectBestFiles(eq(full), any(), any(), eq(270));
        verify(searchProcessor).selectBestFiles(eq(full), any(), any(), isNull());
    }

    @Test
    void searchPoll_readsFromTheBatchedMap_makesNoPerRowCall() {
        SearchState state = SlskdFixtures.searchState("s1", false, "InProgress");

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", state), Map.of(), none()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
        verify(slskdService, never()).getAllSearches();
        verifyNoInteractions(searchProcessor);
    }

    @Test
    void searchPoll_missingFromTheBatch_treatedAsStillRunning() {
        DownloadDecision d = executor.execute(searchPolling("s1"), Map.of(), Map.of(), none()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
        verifyNoInteractions(searchProcessor);
    }

    @Test
    void searchPoll_complete_refetchesTheSearchForItsResponses_andSelectsFromThat() {
        // THE REGRESSION GUARD. The batched summary is complete but carries no responses -- that is
        // not a fixture convenience, it is what GET /searches actually returns, since it has no
        // includeResponses parameter. Selecting off the summary therefore found zero candidates for
        // every search and killed the download on NO_CANDIDATES seconds after the search completed.
        // Selection must run against the refetched state, never the batched one.
        var summary = SlskdFixtures.searchState("s1", true, "Completed, ResponseLimitReached");
        var file = new SearchFile("music/alice/song.flac", 10L, 7L, false, "flac", Optional.of(1411), Optional.of(240));
        var peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 900, "alice");
        var full = SlskdFixtures.searchStateWithResponses("s1", true,
                "Completed, ResponseLimitReached", List.of(peer));
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any(), any(), any()))
                .thenReturn(Mono.just(List.of(new SlskdSearchResultProcessor.Pick(peer, file, TrackMatchingService.Match.EXACT))));

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", summary), Map.of(), none()).block();

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals("alice", next.candidates().getFirst().username());
        assertEquals(1411, next.candidates().getFirst().bitRate());
        verify(slskdService).getSearchWithResponses("s1");
        verify(searchProcessor, never()).selectBestFiles(eq(summary), any(), any(), any());
    }

    @Test
    void searchPoll_completeWithNoCandidatesEvenAfterRefetch_fails() {
        var summary = SlskdFixtures.searchState("s1", true, "Completed");
        var full = SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of());
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any(), any(), any())).thenReturn(Mono.just(List.of()));

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", summary), Map.of(), none()).block();

        assertEquals(DownloadFailureCode.NO_CANDIDATES,
                assertInstanceOf(DownloadDecision.Terminal.class, d).failureCode());
    }

    @Test
    void searchPoll_completedWithResponseLimitReached_isNotAFailure() {
        // "Completed, ResponseLimitReached" is slskd saying the search stopped because it found
        // PLENTY -- a healthy outcome, not an error, and nothing the app needs to recover from.
        var summary = SlskdFixtures.searchState("s1", true, "Completed, ResponseLimitReached");
        var file = new SearchFile("music/alice/song.flac", 10L, 7L, false, "flac", Optional.of(1411), Optional.of(240));
        var peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 900, "alice");
        var full = SlskdFixtures.searchStateWithResponses("s1", true,
                "Completed, ResponseLimitReached", List.of(peer));
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any(), any(), any()))
                .thenReturn(Mono.just(List.of(new SlskdSearchResultProcessor.Pick(peer, file, TrackMatchingService.Match.EXACT))));

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", summary), Map.of(), none()).block();

        assertInstanceOf(DownloadDecision.Advance.class, d);
    }

    // ---- remembering what the search found (manual pick) ----------------------------------------

    private static SlskdSearchResultProcessor.Pick pick(String username, String path, int length,
                                                        TrackMatchingService.Match grade) {
        var file = new SearchFile(path, 10L, 7L, false, "", Optional.of(192), Optional.of(length));
        var peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 900, username);
        return new SlskdSearchResultProcessor.Pick(peer, file, grade);
    }

    @Test
    void searchPoll_complete_remembersEveryRelevantFile_withItsLength_cappedAtAHundred() {
        var summary = SlskdFixtures.searchState("s1", true, "Completed");
        var full = SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of());
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any(), any(), any())).thenReturn(Mono.just(List.of()));
        List<SlskdSearchResultProcessor.Pick> found = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            found.add(pick("sharer" + i, "music/" + i + "/song.mp3", 240 + i, TrackMatchingService.Match.OTHER_VERSION));
        }
        when(searchProcessor.relevantFiles(eq(full), eq("never gonna give you up"), eq("never gonna give you up")))
                .thenReturn(found);

        executor.execute(searchPolling("s1"), Map.of("s1", summary), Map.of(), none()).block();

        @SuppressWarnings("unchecked")
        var files = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveSearchResults(eq(TASK_ID), files.capture(), eq(T0));
        assertEquals(100, files.getValue().size(), "the first hundred in the picker's order");
        DownloadCandidate first = (DownloadCandidate) files.getValue().getFirst();
        assertEquals("sharer0", first.username());
        assertEquals(240, first.length());
        assertEquals("OTHER_VERSION", first.grade());
        assertEquals(192, first.bitRate(), "a 192 kbps file the automatic picker would drop is kept for a person");
    }

    @Test
    void searchPoll_stillRunning_remembersNothing() {
        var state = SlskdFixtures.searchState("s1", false, "InProgress");

        executor.execute(searchPolling("s1"), Map.of("s1", state), Map.of(), none()).block();

        verify(repository, never()).saveSearchResults(any(), any(), any());
        verify(searchProcessor, never()).relevantFiles(any(), any(), any());
    }

    @Test
    void searchPoll_complete_aFailedCacheWrite_leavesTheDecisionUnchanged() {
        // THE GUARD: a Postgres hiccup on the side cache must never turn a good search into a retry.
        var summary = SlskdFixtures.searchState("s1", true, "Completed");
        var file = new SearchFile("music/alice/song.flac", 10L, 7L, false, "flac", Optional.of(1411), Optional.of(240));
        var peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 900, "alice");
        var full = SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of(peer));
        var exact = new SlskdSearchResultProcessor.Pick(peer, file, TrackMatchingService.Match.EXACT);
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.relevantFiles(eq(full), any(), any())).thenReturn(List.of(exact));
        when(searchProcessor.selectBestFiles(eq(full), any(), any(), any())).thenReturn(Mono.just(List.of(exact)));
        when(repository.saveSearchResults(any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("database away")));

        DownloadDecision d = executor.execute(searchPolling("s1"), Map.of("s1", summary), Map.of(), none()).block();

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals("alice", next.candidates().getFirst().username());
        assertEquals(240, next.candidates().getFirst().length(), "the length now travels on the candidate too");
    }

    @Test
    void searchPoll_stillRunning_makesNoRefetch() {
        var state = SlskdFixtures.searchState("s1", false, "InProgress");

        executor.execute(searchPolling("s1"), Map.of("s1", state), Map.of(), none()).block();

        verify(slskdService, never()).getSearchWithResponses(any());
    }

    @Test
    void downloadInit_callsEnqueueDirectly() {
        when(slskdService.enqueueDownload(eq("alice"), any()))
                .thenReturn(Mono.just(SlskdFixtures.enqueued("abc", "alice")));

        DownloadDecision d = executor
                .execute(downloadInit(candidates("alice"), 0, 0), Map.of(), Map.of(), none()).block();

        assertEquals("abc",
                assertInstanceOf(DownloadDecision.Advance.class, d).next().slskdTransferId());
        verify(slskdService).enqueueDownload(eq("alice"), any());
    }

    @Test
    void downloadInit_whenTheSharerAlreadyHoldsTwoOfOurs_waitsWithoutAskingSlskd() {
        SharerLoad sharers = SharerLoad.of(List.of(
                new DownloadTaskRepository.TransferInFlight("alice", "t1"),
                new DownloadTaskRepository.TransferInFlight("alice", "t2")), Map.of());

        DownloadDecision d = executor
                .execute(downloadInit(candidates("alice", "bob"), 0, 0), Map.of(), Map.of(), sharers).block();

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals(T0.plusSeconds(5), next.nextAttemptAt());
        verify(slskdService, never()).enqueueDownload(any(), any());
    }

    @Test
    void downloadInit_twoSongsForOneSharerInOnePass_onlyOneTakesItsLastFreePlace() {
        when(slskdService.enqueueDownload(eq("alice"), any()))
                .thenReturn(Mono.just(SlskdFixtures.enqueued("abc", "alice")));
        SharerLoad sharers = SharerLoad.of(List.of(
                new DownloadTaskRepository.TransferInFlight("alice", "t1")), Map.of());

        DownloadDecision first = executor
                .execute(downloadInit(candidates("alice"), 0, 0), Map.of(), Map.of(), sharers).block();
        DownloadDecision second = executor
                .execute(downloadInit(candidates("alice"), 0, 0), Map.of(), Map.of(), sharers).block();

        assertInstanceOf(DownloadDecision.Advance.class, first);
        assertEquals(DownloadPhase.DOWNLOAD_INIT,
                assertInstanceOf(DownloadDecision.Continue.class, second).next().phase());
        verify(slskdService, times(1)).enqueueDownload(eq("alice"), any());
    }

    @Test
    void downloadPoll_waitingBehindASiblingTheSharerIsSending_isKept_andNotCancelled() {
        TransferedFile queued = SlskdFixtures.transfer("abc", "alice", "Queued, Remotely");
        TransferedFile sending = SlskdFixtures.transfer("sib", "alice", "InProgress", 50f, 500L);
        SharerLoad sharers = SharerLoad.of(List.of(
                new DownloadTaskRepository.TransferInFlight("alice", "abc"),
                new DownloadTaskRepository.TransferInFlight("alice", "sib")),
                Map.of("abc", queued, "sib", sending));
        DownloadTask task = downloadPolling(candidates("alice", "bob"), 0, 0, "abc").toBuilder()
                .phaseEnteredAt(T0.minus(Duration.ofMinutes(70))).build();

        DownloadDecision d = executor.execute(task, Map.of(), Map.of("abc", queued), sharers).block();

        DownloadTask next = assertInstanceOf(DownloadDecision.Continue.class, d).next();
        assertEquals(0, next.candidateIndex());
        assertEquals(T0, next.phaseEnteredAt());
        verify(slskdService, never()).cancelDownload(any(), any());
    }

    @Test
    void downloadPoll_readsFromTheBatchedMap_makesNoPerRowCall() {
        TransferedFile file = SlskdFixtures.transfer("abc", "alice", "Completed, Succeeded");

        DownloadDecision d = executor
                .execute(downloadPolling(candidates("alice"), 0, 0, "abc"), Map.of(), Map.of("abc", file), none())
                .block();

        assertEquals(DownloadStatus.SUCCEEDED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).status());
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void downloadPoll_givingUpOnAQueuedSharer_cancelsTheAbandonedTransferInSlskd() {
        when(slskdService.cancelDownload("alice", "abc")).thenReturn(Mono.empty());
        TransferedFile stuck = SlskdFixtures.transfer("abc", "alice", "Queued, Remotely");
        // phaseEnteredAt is T0 and the clock is fixed at T0, so back-date the phase past the budget.
        DownloadTask task = downloadPolling(candidates("alice", "bob"), 0, 0, "abc").toBuilder()
                .phaseEnteredAt(T0.minus(Duration.ofMinutes(11))).build();

        DownloadDecision d = executor.execute(task, Map.of(), Map.of("abc", stuck), none()).block();

        assertEquals(1, assertInstanceOf(DownloadDecision.Continue.class, d).next().candidateIndex());
        verify(slskdService).cancelDownload("alice", "abc");
    }

    @Test
    void downloadPoll_aTransferSlskdAlreadyCompleted_isNotCancelled() {
        TransferedFile done = SlskdFixtures.transfer("abc", "alice", "Completed, Rejected");

        executor.execute(downloadPolling(candidates("alice", "bob"), 0, 0, "abc"), Map.of(),
                Map.of("abc", done), none()).block();

        verify(slskdService, never()).cancelDownload(any(), any());
    }

    @Test
    void downloadPoll_missingFromTheBatch_treatedAsStillRunning() {
        DownloadDecision d = executor
                .execute(downloadPolling(candidates("alice"), 0, 0, "abc"), Map.of(), Map.of(), none()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
    }

    @Test
    void anSlskdErrorDuringSearchInit_becomesADecision_notAnErrorSignal() {
        when(slskdService.searchResults(any()))
                .thenReturn(Mono.error(new RuntimeException("slskd is down")));

        StepVerifier.create(executor.execute(at(DownloadPhase.SEARCH_INIT), Map.of(), Map.of(), none()))
                .assertNext(d -> assertEquals(DownloadStatus.FAILED,
                        assertInstanceOf(DownloadDecision.Terminal.class, d).status()))
                .verifyComplete();
    }

    @Test
    void downloadInit_withCandidateIndexOutOfRange_becomesADecision_notASynchronousThrow() {
        // task.currentCandidate() throws IndexOutOfBoundsException on an empty candidate list.
        // Before wrapping the DOWNLOAD_INIT branch in Mono.defer, this threw synchronously while
        // building the switch expression in step() -- escaping execute() before its onErrorResume
        // ever attached, which would abort the whole pass in DownloadTaskRunner exactly like the
        // row-mapping bug this fix is paired with. With retryIndex already at the retry limit (2)
        // and no further candidates, this resolves deterministically to Terminal/SOURCES_EXHAUSTED.
        DownloadTask task = downloadInit(List.of(), 0, 2);

        DownloadDecision d = executor.execute(task, Map.of(), Map.of(), none()).block();

        DownloadDecision.Terminal terminal = assertInstanceOf(DownloadDecision.Terminal.class, d);
        assertEquals(DownloadStatus.FAILED, terminal.status());
        assertEquals(DownloadFailureCode.SOURCES_EXHAUSTED, terminal.failureCode());
    }

    @Test
    void slskdNotAnswering_costsOneWarningLine_withoutAStackTrace() {
        Logger logger = (Logger) LoggerFactory.getLogger(DownloadStepExecutor.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            when(slskdService.searchResults(any())).thenReturn(Mono.error(SlskdFixtures.transportFailure()));

            executor.execute(at(DownloadPhase.SEARCH_INIT), Map.of(), Map.of(), none()).block();

            // Connection refused or timed out says it all; the stack trace per attempt did not
            // (04-10-2026 handled slskd's 409 this way, 07-10-2026 slskd not answering at all).
            assertEquals(1, logs.list.size(), () -> "one line: " + logs.list);
            assertEquals(Level.WARN, logs.list.getFirst().getLevel());
            assertNull(logs.list.getFirst().getThrowableProxy(), "no stack trace");
            assertTrue(logs.list.getFirst().getFormattedMessage().contains("Operation timed out"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void anSlskdErrorDuringEnqueue_becomesADecision_notAnErrorSignal() {
        when(slskdService.enqueueDownload(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("slskd is down")));

        StepVerifier.create(executor.execute(
                        downloadInit(candidates("alice", "bob"), 0, 0), Map.of(), Map.of(), none()))
                .assertNext(d -> assertEquals(1,
                        assertInstanceOf(DownloadDecision.Continue.class, d).next().retryIndex()))
                .verifyComplete();
    }
}

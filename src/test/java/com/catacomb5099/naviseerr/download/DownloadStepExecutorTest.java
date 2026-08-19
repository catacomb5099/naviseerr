package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private DownloadStepExecutor executor;

    @BeforeEach
    void setUp() {
        slskdService = mock(SlskdService.class);
        searchProcessor = mock(SlskdSearchResultProcessor.class);
        DownloadStateMachine machine = new DownloadStateMachine(
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(120), Duration.ofSeconds(3600), Duration.ofSeconds(60), 2);
        executor = new DownloadStepExecutor(slskdService, searchProcessor, machine,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void searchInit_callsSearchOnceAndAdvances() {
        when(slskdService.searchResults("never gonna give you up"))
                .thenReturn(Mono.just(SlskdFixtures.searchState("s1", false, "InProgress")));

        DownloadDecision d = executor
                .execute(at(DownloadPhase.SEARCH_INIT), Map.of(), Map.of()).block();

        assertEquals(DownloadPhase.SEARCH_POLL,
                assertInstanceOf(DownloadDecision.Advance.class, d).next().phase());
        verify(slskdService, times(1)).searchResults(any());
    }

    @Test
    void searchPoll_readsFromTheBatchedMap_makesNoPerRowCall() {
        SearchState state = SlskdFixtures.searchState("s1", false, "InProgress");

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", state), Map.of()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
        verify(slskdService, never()).getAllSearches();
        verifyNoInteractions(searchProcessor);
    }

    @Test
    void searchPoll_missingFromTheBatch_treatedAsStillRunning() {
        DownloadDecision d = executor.execute(searchPolling("s1"), Map.of(), Map.of()).block();

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
        var file = new SearchFile("music/alice/song.flac", 10L, 7L, false, "flac", Optional.of(1411));
        var peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 900, "alice");
        var full = SlskdFixtures.searchStateWithResponses("s1", true,
                "Completed, ResponseLimitReached", List.of(peer));
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any()))
                .thenReturn(Mono.just(List.of(Map.entry(peer, file))));

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", summary), Map.of()).block();

        DownloadTask next = assertInstanceOf(DownloadDecision.Advance.class, d).next();
        assertEquals(DownloadPhase.DOWNLOAD_INIT, next.phase());
        assertEquals("alice", next.candidates().getFirst().username());
        assertEquals(1411, next.candidates().getFirst().bitRate());
        verify(slskdService).getSearchWithResponses("s1");
        verify(searchProcessor, never()).selectBestFiles(eq(summary), any());
    }

    @Test
    void searchPoll_completeWithNoCandidatesEvenAfterRefetch_fails() {
        var summary = SlskdFixtures.searchState("s1", true, "Completed");
        var full = SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of());
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any())).thenReturn(Mono.just(List.of()));

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", summary), Map.of()).block();

        assertEquals(DownloadStateMachine.NO_CANDIDATES,
                assertInstanceOf(DownloadDecision.Terminal.class, d).message());
    }

    @Test
    void searchPoll_completedWithResponseLimitReached_isNotAFailure() {
        // "Completed, ResponseLimitReached" is slskd saying the search stopped because it found
        // PLENTY -- a healthy outcome, not an error, and nothing the app needs to recover from.
        var summary = SlskdFixtures.searchState("s1", true, "Completed, ResponseLimitReached");
        var file = new SearchFile("music/alice/song.flac", 10L, 7L, false, "flac", Optional.of(1411));
        var peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 900, "alice");
        var full = SlskdFixtures.searchStateWithResponses("s1", true,
                "Completed, ResponseLimitReached", List.of(peer));
        when(slskdService.getSearchWithResponses("s1")).thenReturn(Mono.just(full));
        when(searchProcessor.selectBestFiles(eq(full), any()))
                .thenReturn(Mono.just(List.of(Map.entry(peer, file))));

        DownloadDecision d = executor
                .execute(searchPolling("s1"), Map.of("s1", summary), Map.of()).block();

        assertInstanceOf(DownloadDecision.Advance.class, d);
    }

    @Test
    void searchPoll_stillRunning_makesNoRefetch() {
        var state = SlskdFixtures.searchState("s1", false, "InProgress");

        executor.execute(searchPolling("s1"), Map.of("s1", state), Map.of()).block();

        verify(slskdService, never()).getSearchWithResponses(any());
    }

    @Test
    void downloadInit_callsEnqueueDirectly() {
        when(slskdService.enqueueDownload(eq("alice"), any()))
                .thenReturn(Mono.just(SlskdFixtures.enqueued("abc", "alice")));

        DownloadDecision d = executor
                .execute(downloadInit(candidates("alice"), 0, 0), Map.of(), Map.of()).block();

        assertEquals("abc",
                assertInstanceOf(DownloadDecision.Advance.class, d).next().slskdTransferId());
        verify(slskdService).enqueueDownload(eq("alice"), any());
    }

    @Test
    void downloadPoll_readsFromTheBatchedMap_makesNoPerRowCall() {
        TransferedFile file = SlskdFixtures.transfer("abc", "alice", "Completed, Succeeded");

        DownloadDecision d = executor
                .execute(downloadPolling(candidates("alice"), 0, 0, "abc"), Map.of(), Map.of("abc", file))
                .block();

        assertEquals(DownloadStatus.SUCCEEDED,
                assertInstanceOf(DownloadDecision.Terminal.class, d).status());
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void downloadPoll_missingFromTheBatch_treatedAsStillRunning() {
        DownloadDecision d = executor
                .execute(downloadPolling(candidates("alice"), 0, 0, "abc"), Map.of(), Map.of()).block();

        assertInstanceOf(DownloadDecision.Continue.class, d);
    }

    @Test
    void anSlskdErrorDuringSearchInit_becomesADecision_notAnErrorSignal() {
        when(slskdService.searchResults(any()))
                .thenReturn(Mono.error(new RuntimeException("slskd is down")));

        StepVerifier.create(executor.execute(at(DownloadPhase.SEARCH_INIT), Map.of(), Map.of()))
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

        DownloadDecision d = executor.execute(task, Map.of(), Map.of()).block();

        DownloadDecision.Terminal terminal = assertInstanceOf(DownloadDecision.Terminal.class, d);
        assertEquals(DownloadStatus.FAILED, terminal.status());
        assertEquals(DownloadStateMachine.SOURCES_EXHAUSTED, terminal.message());
    }

    @Test
    void anSlskdErrorDuringEnqueue_becomesADecision_notAnErrorSignal() {
        when(slskdService.enqueueDownload(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("slskd is down")));

        StepVerifier.create(executor.execute(
                        downloadInit(candidates("alice", "bob"), 0, 0), Map.of(), Map.of()))
                .assertNext(d -> assertEquals(1,
                        assertInstanceOf(DownloadDecision.Continue.class, d).next().retryIndex()))
                .verifyComplete();
    }
}

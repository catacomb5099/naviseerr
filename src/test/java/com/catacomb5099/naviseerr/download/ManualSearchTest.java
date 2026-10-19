package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ManualSearchTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final Duration POLL = Duration.ofSeconds(2);
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private final UUID downloadId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    private final SlskdService slskd = mock(SlskdService.class);
    private final SlskdSearchResultProcessor processor = mock(SlskdSearchResultProcessor.class);
    private final DownloadTaskRepository repository = mock(DownloadTaskRepository.class);
    private final ManualSearch search = new ManualSearch(slskd, processor, repository, Clock.fixed(NOW, ZoneOffset.UTC), POLL, BUDGET);

    private final SearchFile file = new SearchFile("@@music\\Pulp\\Different Class\\06 - Common People.flac", 30_000_000L, 1L,
            false, "flac", Optional.empty(), Optional.of(350));
    private final SearchResponseItem peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 900, "alice");
    private final SlskdSearchResultProcessor.Pick pick = new SlskdSearchResultProcessor.Pick(peer, file, TrackMatchingService.Match.EXACT);

    private static DownloadTaskRepository.CachedSearch song() {
        return new DownloadTaskRepository.CachedSearch(UUID.randomUUID(), "FAILED", "Common People - Pulp", 1, List.of(), 0,
                null, null, List.of(), NOW.minusSeconds(600), "old-search", "NO_CANDIDATES");
    }

    @BeforeEach
    void defaults() {
        when(repository.cachedSearch(downloadId, taskId)).thenReturn(Mono.just(song()));
        when(repository.saveSearchResults(eq(taskId), any(), any(), any())).thenReturn(Mono.just(1L));
        when(slskd.searchResults("Common People Pulp 1995")).thenReturn(Mono.just(SlskdFixtures.searchState("new-1", false, "InProgress")));
    }

    @Test
    void pollsUntilSlskdSaysComplete_gradesEveryFileWithTheTextAsWording_andStoresTheList() {
        SearchState running = SlskdFixtures.searchStateWithResponses("new-1", false, "InProgress", List.of());
        SearchState done = SlskdFixtures.searchStateWithResponses("new-1", true, "Completed, TimedOut", List.of(peer));
        when(slskd.getSearchWithResponses("new-1")).thenReturn(Mono.just(running), Mono.just(running), Mono.just(done));
        when(processor.allFiles(done, "Common People - Pulp", "Common People Pulp 1995")).thenReturn(List.of(pick));

        StepVerifier.withVirtualTime(() -> search.song(downloadId, taskId, "Common People Pulp 1995"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(4))
                .assertNext(view -> {
                    assertEquals(PickListStatus.READY, view.status());
                    assertEquals("Common People Pulp 1995", view.query());   // the text, not the loop's wording
                    assertEquals("new-1", view.searchId());                 // slskd's id for THIS search
                    assertEquals(NOW, view.searchedAt());
                    assertEquals(DownloadStage.FAILED, view.songStage());
                    assertEquals(1, view.candidates().size());
                    assertEquals("alice", view.candidates().getFirst().username());
                    assertEquals("EXACT", view.candidates().getFirst().grade());
                })
                .verifyComplete();

        verify(slskd, times(3)).getSearchWithResponses("new-1");
        // The new slskd id lands on the row too, so a reopened pop-up's grey line points at THIS search.
        verify(repository).saveSearchResults(eq(taskId), argThat(files -> files.size() == 1
                && files.getFirst().filename().equals(file.getFilename())), eq(NOW), eq("new-1"));
        assertEquals(0, search.inFlight(), "the slot is given back when the search ends");
    }

    @Test
    void atTheBudget_theAnswersSoFarAreUsed_andTheSearchIsNotWaitedForAnyLonger() {
        SearchState partial = SlskdFixtures.searchStateWithResponses("new-1", false, "InProgress", List.of(peer));
        when(slskd.getSearchWithResponses("new-1")).thenReturn(Mono.just(partial));
        when(processor.allFiles(eq(partial), any(), any())).thenReturn(List.of(pick));

        StepVerifier.withVirtualTime(() -> search.song(downloadId, taskId, "Common People Pulp 1995"))
                .expectSubscription()
                .expectNoEvent(BUDGET)
                .assertNext(view -> assertEquals(1, view.candidates().size()))
                .verifyComplete();

        // One read at once, then one per poll interval until the budget: 30 s / 2 s = 15 more, no 16th.
        verify(slskd, atLeast(15)).getSearchWithResponses("new-1");
        verify(slskd, atMost(16)).getSearchWithResponses("new-1");
    }

    @Test
    void nothingFound_isNoneWithNoResults_andTheEmptyListIsStillOfferedToTheGuardedSave() {
        SearchState done = SlskdFixtures.searchStateWithResponses("new-1", true, "Completed, TimedOut", List.of());
        when(slskd.getSearchWithResponses("new-1")).thenReturn(Mono.just(done));
        when(processor.allFiles(eq(done), any(), any())).thenReturn(List.of());

        StepVerifier.withVirtualTime(() -> search.song(downloadId, taskId, "Common People Pulp 1995"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(view -> {
                    assertEquals(PickListStatus.NONE, view.status());
                    assertEquals("NO_RESULTS", view.reason());
                    assertEquals("Common People Pulp 1995", view.query());
                    assertTrue(view.candidates().isEmpty());
                })
                .verifyComplete();

        // SAVE_SEARCH_RESULTS_SQL itself decides: an empty list never wipes a remembered one.
        verify(repository).saveSearchResults(eq(taskId), eq(List.of()), eq(NOW), eq("new-1"));
    }

    @Test
    void holdsOneSearchSlotWhileRunning_evenWhenSlskdRefuses() {
        when(slskd.searchResults("Common People Pulp 1995")).thenReturn(Mono.error(SlskdFixtures.soulseekOffline()));

        AtomicInteger seen = new AtomicInteger(-1);
        StepVerifier.create(search.song(downloadId, taskId, "Common People Pulp 1995")
                        .doOnSubscribe(s -> seen.set(search.inFlight())))
                .expectError(org.springframework.web.reactive.function.client.WebClientResponseException.Conflict.class)
                .verify();

        assertEquals(0, search.inFlight(), "the slot is given back on an error too");
        verify(repository, never()).saveSearchResults(any(), any(), any(), any());
    }

    @Test
    void oneFailedPoll_isReadAgain_notTheEndOfTheWait() {
        SearchState done = SlskdFixtures.searchStateWithResponses("new-1", true, "Completed, TimedOut", List.of(peer));
        when(slskd.getSearchWithResponses("new-1")).thenReturn(Mono.error(SlskdFixtures.transportFailure()), Mono.just(done));
        when(processor.allFiles(eq(done), any(), any())).thenReturn(List.of(pick));

        StepVerifier.withVirtualTime(() -> search.song(downloadId, taskId, "Common People Pulp 1995"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(view -> assertEquals(1, view.candidates().size()))
                .verifyComplete();

        verify(slskd, times(2)).getSearchWithResponses("new-1");
    }

    @Test
    void whileTheLoopsOwnSearchRuns_theSongIsRefused_withoutSearching() {
        when(repository.cachedSearch(downloadId, taskId)).thenReturn(Mono.just(new DownloadTaskRepository.CachedSearch(
                taskId, "SEARCH_POLL", "Common People - Pulp", 0, List.of(), 0, null, null, List.of(), null, "loop-1", null)));

        StepVerifier.create(search.song(downloadId, taskId, "Common People Pulp 1995"))
                .expectError(ManualSearch.SearchRunningException.class)
                .verify();

        verify(slskd, never()).searchResults(any());
    }

    @Test
    void aSearchSlskdTookWithoutAnId_isAnError_notAnEndlessPoll() {
        when(slskd.searchResults("Common People Pulp 1995")).thenReturn(Mono.just(SlskdFixtures.searchState(null, false, "InProgress")));

        StepVerifier.create(search.song(downloadId, taskId, "Common People Pulp 1995"))
                .expectError(IllegalStateException.class)
                .verify();

        verify(slskd, never()).getSearchWithResponses(any());
    }

    @Test
    void anUnknownSong_isEmpty_andSearchesNothing() {
        when(repository.cachedSearch(downloadId, taskId)).thenReturn(Mono.empty());

        StepVerifier.create(search.song(downloadId, taskId, "Common People Pulp 1995")).verifyComplete();

        verify(slskd, never()).searchResults(any());
    }
}

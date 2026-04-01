package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SlskdSearchResultProcessorTest {

    private final SlskdService slskdService = mock(SlskdService.class);
    private final TrackMatchingService trackMatchingService = mock(TrackMatchingService.class);
    private final SlskdSearchResultProcessor processor = new SlskdSearchResultProcessor(slskdService, trackMatchingService);

    @BeforeEach
    void setUp() {
        // sensible defaults
        ReflectionTestUtils.setField(processor, "minBitRate", 128);
        ReflectionTestUtils.setField(processor, "maxFilesPerDownload", 5);
        ReflectionTestUtils.setField(processor, "maxPollAttempts", 3);
        ReflectionTestUtils.setField(processor, "firstBackOffDuration", 10L);
    }

    @Test
    void pollUntilComplete_emptyQuery_returnsEmptyMono_andDoesNotPollProgress() {
        when(slskdService.searchResults("")).thenReturn(Mono.empty());

        StepVerifier.create(processor.pollUntilComplete(""))
                .verifyComplete();

        verify(slskdService, never()).searchResults(anyString());
        verify(slskdService, never()).getSearchResultsProgress(anyString());
    }

    @Test
    void pollUntilComplete_searchResultsErrors_propagatesError_andDoesNotCallProgress() {
        when(slskdService.searchResults("q")).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(processor.pollUntilComplete("q"))
                .verifyError();

        verify(slskdService).searchResults("q");
        verify(slskdService, never()).getSearchResultsProgress(anyString());
    }

    @Test
    void pollUntilComplete_searchReturnsStartState_butProgressErrors_calledOnceThenError() {
        // ensure only one poll attempt by setting maxPollAttempts=1
        ReflectionTestUtils.setField(processor, "maxPollAttempts", 1);

        SearchState startState = mock(SearchState.class);
        when(startState.getId()).thenReturn("start-id");
        when(slskdService.searchResults("q")).thenReturn(Mono.just(startState));
        when(slskdService.getSearchResultsProgress("start-id")).thenReturn(Mono.error(new RuntimeException("progress fail")));

        StepVerifier.create(processor.pollUntilComplete("q"))
                .verifyError();

        verify(slskdService).searchResults("q");
        // called once due to single poll attempt configured
        verify(slskdService, times(1)).getSearchResultsProgress("start-id");
    }

    @Test
    void pollUntilComplete_progressReturnsCompleteState_emitsThatState() {
        SearchState startState = mock(SearchState.class);
        when(startState.getId()).thenReturn("start-id");
        SearchState complete = mock(SearchState.class);
        when(complete.getIsComplete()).thenReturn(true);
        when(complete.getFileCount()).thenReturn(1);
        when(slskdService.searchResults("q")).thenReturn(Mono.just(startState));
        when(slskdService.getSearchResultsProgress("start-id")).thenReturn(Mono.just(complete));

        StepVerifier.create(processor.pollUntilComplete("q"))
                .expectNextMatches(SearchState::getIsComplete)
                .verifyComplete();
    }

    @Test
    void selectBestFiles_emptyResponses_returnsEmptyList() {
        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of());
        when(state.getFileCount()).thenReturn(0);

        StepVerifier.create(processor.selectBestFiles(state, "track"))
                .expectNextMatches(List::isEmpty)
                .verifyComplete();
    }

    @Test
    void selectBestFiles_filtersByRelevance_onlyRelevantIncluded() {
        // two entries, only one matches via TrackMatchingService and both pass bitrate/extension
        SearchResponseItem itemA = mock(SearchResponseItem.class);
        SearchResponseItem itemB = mock(SearchResponseItem.class);
        SearchFile fileA = mock(SearchFile.class);
        SearchFile fileB = mock(SearchFile.class);

        when(itemA.getFiles()).thenReturn(List.of(fileA));
        when(itemB.getFiles()).thenReturn(List.of(fileB));
        when(fileA.getFilename()).thenReturn("match.mp3");
        when(fileB.getFilename()).thenReturn("nope.mp3");
        when(fileA.getBitRate()).thenReturn(Optional.of(192));
        when(fileB.getBitRate()).thenReturn(Optional.of(192));
        when(fileA.getExtension()).thenReturn("mp3");
        when(fileB.getExtension()).thenReturn("mp3");

        when(trackMatchingService.isMatch(eq("track"), eq("match.mp3"))).thenReturn(true);
        when(trackMatchingService.isMatch(eq("track"), eq("nope.mp3"))).thenReturn(false);

        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of(itemA, itemB));
        when(state.getFileCount()).thenReturn(2);

        StepVerifier.create(processor.selectBestFiles(state, "track"))
                .expectNextMatches(list -> list.size() == 1 && list.getFirst().getValue().getFilename().equals("match.mp3"))
                .verifyComplete();
    }

    @Test
    void selectBestFiles_keepsAboveMinAndFlac_filtersOutBelowMin() {
        ReflectionTestUtils.setField(processor, "minBitRate", 160);

        SearchResponseItem above = mock(SearchResponseItem.class);
        SearchResponseItem below = mock(SearchResponseItem.class);
        SearchResponseItem flacItem = mock(SearchResponseItem.class);

        SearchFile fileAbove = mock(SearchFile.class);
        SearchFile fileBelow = mock(SearchFile.class);
        SearchFile fileFlac = mock(SearchFile.class);

        when(above.getFiles()).thenReturn(List.of(fileAbove));
        when(below.getFiles()).thenReturn(List.of(fileBelow));
        when(flacItem.getFiles()).thenReturn(List.of(fileFlac));

        when(fileAbove.getFilename()).thenReturn("a.mp3");
        when(fileBelow.getFilename()).thenReturn("b.mp3");
        when(fileFlac.getFilename()).thenReturn("c.flac");

        when(fileAbove.getBitRate()).thenReturn(Optional.of(192));
        when(fileBelow.getBitRate()).thenReturn(Optional.of(128));
        when(fileFlac.getBitRate()).thenReturn(Optional.empty());

        when(fileAbove.getExtension()).thenReturn("mp3");
        when(fileBelow.getExtension()).thenReturn("mp3");
        when(fileFlac.getExtension()).thenReturn("flac");

        when(trackMatchingService.isMatch(anyString(), anyString())).thenReturn(true);

        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of(above, below, flacItem));
        when(state.getFileCount()).thenReturn(3);

        StepVerifier.create(processor.selectBestFiles(state, "track"))
                .expectNextMatches(list -> list.size() == 2
                        && list.stream().anyMatch(e -> e.getValue().getFilename().equals("a.mp3"))
                        && list.stream().anyMatch(e -> e.getValue().getFilename().equals("c.flac"))
                        && list.stream().noneMatch(e -> e.getValue().getFilename().equals("b.mp3")))
                .verifyComplete();
    }

    @Test
    void selectBestFiles_ordersByUploadSpeed_descending() {
        // three responses with different upload speeds, all relevant and above min bitrate
        SearchResponseItem fast = mock(SearchResponseItem.class);
        SearchResponseItem med = mock(SearchResponseItem.class);
        SearchResponseItem slow = mock(SearchResponseItem.class);

        SearchFile fastFile = mock(SearchFile.class);
        SearchFile medFile = mock(SearchFile.class);
        SearchFile slowFile = mock(SearchFile.class);

        when(fast.getFiles()).thenReturn(List.of(fastFile));
        when(med.getFiles()).thenReturn(List.of(medFile));
        when(slow.getFiles()).thenReturn(List.of(slowFile));

        when(fastFile.getFilename()).thenReturn("fast.mp3");
        when(medFile.getFilename()).thenReturn("med.mp3");
        when(slowFile.getFilename()).thenReturn("slow.mp3");

        when(fast.getUploadSpeed()).thenReturn(300);
        when(med.getUploadSpeed()).thenReturn(200);
        when(slow.getUploadSpeed()).thenReturn(100);

        when(fastFile.getBitRate()).thenReturn(Optional.of(192));
        when(medFile.getBitRate()).thenReturn(Optional.of(192));
        when(slowFile.getBitRate()).thenReturn(Optional.of(192));

        when(fastFile.getExtension()).thenReturn("mp3");
        when(medFile.getExtension()).thenReturn("mp3");
        when(slowFile.getExtension()).thenReturn("mp3");

        when(trackMatchingService.isMatch(anyString(), anyString())).thenReturn(true);

        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of(fast, med, slow));
        when(state.getFileCount()).thenReturn(3);

        StepVerifier.create(processor.selectBestFiles(state, "track"))
                .expectNextMatches(list ->
                        list.size() == 3 &&
                                list.getFirst().getKey().getUploadSpeed() == 300 &&
                                list.get(1).getKey().getUploadSpeed() == 200 &&
                                list.get(2).getKey().getUploadSpeed() == 100)
                .verifyComplete();
    }
}

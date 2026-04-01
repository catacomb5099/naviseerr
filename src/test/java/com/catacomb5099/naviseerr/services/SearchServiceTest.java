package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.lastfm.LastFMService;
import com.catacomb5099.naviseerr.services.slskd.SlskdDownloadProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SearchServiceTest {

    private final LastFMService lastFMService = mock(LastFMService.class);
    private final SlskdSearchResultProcessor slskdSearchResultProcessor = mock(SlskdSearchResultProcessor.class);
    private final SlskdDownloadProcessor slskdDownloadProcessor = mock(SlskdDownloadProcessor.class);
    private final SearchService searchService = new SearchService(lastFMService, slskdSearchResultProcessor, slskdDownloadProcessor);

    @Test
    void emptySelectedFilesDoesNotTriggerSlskdDownloadPoll() {
        String query = "test-query";

        when(slskdSearchResultProcessor.pollUntilComplete(eq(query))).thenReturn(Mono.just( Mockito.mock(SearchState.class)));
        // simulate no selection (empty result)
        when(slskdSearchResultProcessor.selectBestFiles(any(), eq(query))).thenReturn(Mono.empty());

        // execute
        TransferedFile result = searchService.download(query).onErrorResume(e -> Mono.empty()).block();

        // no transfer produced
        assertNull(result);
        // download processor should not be invoked
        verify(slskdDownloadProcessor, never()).pollUntilComplete(any());
    }

    @Test
    void failedSearchResultsDoesNotPollDownloads() {
        String query = "failing-query";

        // simulate search polling failure
        when(slskdSearchResultProcessor.pollUntilComplete(eq(query))).thenReturn(Mono.empty());

        // execute (suppress error for test flow)
        searchService.download(query).block();

        // selectBestFiles and download poll should not be called
        verify(slskdSearchResultProcessor, never()).selectBestFiles(any(), any());
        verify(slskdDownloadProcessor, never()).pollUntilComplete(any());
    }
}

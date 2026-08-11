package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdDownloadProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadFulfillmentTest {

    private final SlskdSearchResultProcessor searchProcessor = mock(SlskdSearchResultProcessor.class);
    private final SlskdDownloadProcessor downloadProcessor = mock(SlskdDownloadProcessor.class);
    private final DownloadFulfillment fulfillment = new DownloadFulfillment(searchProcessor, downloadProcessor);

    @Test
    void noSelectedFiles_doesNotTriggerDownloadPoll() {
        String song = "test-song";
        when(searchProcessor.pollUntilComplete(eq(song))).thenReturn(Mono.just(mock(SearchState.class)));
        when(searchProcessor.selectBestFiles(any(), eq(song))).thenReturn(Mono.empty());

        StepVerifier.create(fulfillment.fulfill(song)).verifyComplete();

        verify(downloadProcessor, never()).pollUntilComplete(any());
    }

    @Test
    void emptySearch_doesNotSelectOrDownload() {
        String song = "failing-song";
        when(searchProcessor.pollUntilComplete(eq(song))).thenReturn(Mono.empty());

        StepVerifier.create(fulfillment.fulfill(song)).verifyComplete();

        verify(searchProcessor, never()).selectBestFiles(any(), any());
        verify(downloadProcessor, never()).pollUntilComplete(any());
    }

    @Test
    void candidatesFound_emitsTransferedFile() {
        String song = "good-song";
        SearchState state = mock(SearchState.class);
        List<Map.Entry<SearchResponseItem, SearchFile>> files =
                List.of(Map.entry(mock(SearchResponseItem.class), mock(SearchFile.class)));
        TransferedFile transfered = mock(TransferedFile.class);

        when(searchProcessor.pollUntilComplete(eq(song))).thenReturn(Mono.just(state));
        when(searchProcessor.selectBestFiles(eq(state), eq(song))).thenReturn(Mono.just(files));
        when(downloadProcessor.pollUntilComplete(eq(files))).thenReturn(Mono.just(transfered));

        StepVerifier.create(fulfillment.fulfill(song)).expectNext(transfered).verifyComplete();
    }
}

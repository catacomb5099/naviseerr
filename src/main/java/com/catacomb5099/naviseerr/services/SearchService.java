package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.lastfm.LastFMService;
import com.catacomb5099.naviseerr.services.slskd.SlskdDownloadProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@AllArgsConstructor
public class SearchService {
    private final LastFMService lastFMService;
    private final SlskdService slskdService;
    private final SlskdSearchResultProcessor slskdSearchResultProcessor;
    private final SlskdDownloadProcessor slskdDownloadProcessor;

    @RequestMapping("/search/{query}")
    Mono<String> search(@PathVariable String query) {
        return lastFMService.getResults(query, LastFMAPIMethod.TRACK_SEARCH);
    }

    @RequestMapping("/download/{query}")
    Mono<TransferedFile> downloadSearch(@PathVariable String query) {
        return slskdService.searchResults(query)
            .flatMap(searchState -> slskdSearchResultProcessor.pollUntilComplete(searchState.getId()))
            .flatMap(finishedState -> slskdSearchResultProcessor.selectBestFile(finishedState, query))
            .flatMap(entry -> slskdService.enqueueDownload(entry.getKey().getUsername(), entry.getValue()))
                .flatMap(queueDownloadResponse -> slskdDownloadProcessor.pollUntilComplete(queueDownloadResponse.getEnqueued().getFirst().getUsername(), queueDownloadResponse.getEnqueued().getFirst().getId()));
    }

    @RequestMapping("/download/search/progress/{searchId}")
    Mono<SearchState> downloadSearchProgress(@PathVariable String searchId) {
        return slskdService.getSearchResultsProgress(searchId);
    }
}

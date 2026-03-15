package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class SearchService {
    private final LastFMService lastFMService;
    private final SlskdService slskdService;
    private final SearchResultProcessor searchResultProcessor;

    public SearchService(LastFMService lastFMService, SlskdService slskdService, SearchResultProcessor searchResultProcessor) {
        this.lastFMService = lastFMService;
        this.slskdService = slskdService;
        this.searchResultProcessor = searchResultProcessor;
    }

    @RequestMapping("/search/{query}")
    Mono<String> search(@PathVariable String query) {
        return lastFMService.getResults(query, LastFMAPIMethod.TRACK_SEARCH);
    }

    @RequestMapping("/download/{query}")
    Mono<String> downloadSearch(@PathVariable String query) {
        return slskdService.searchResults(query)
            .flatMap(searchState -> searchResultProcessor.pollUntilComplete(searchState.getId()))
            .flatMap(finishedState -> searchResultProcessor.selectBestFile(finishedState, query))
            .flatMap(entry -> slskdService.enqueueDownload(entry.getKey().getUsername(), entry.getValue()));
    }

    @RequestMapping("/download/search/progress/{searchId}")
    Mono<SearchState> downloadSearchProgress(@PathVariable String searchId) {
        return slskdService.getSearchResultsProgress(searchId);
    }
}

package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;

@RestController
public class SearchService {
    private final LastFMService lastFMService;
    private final SlskdService slskdService;

    public SearchService(LastFMService lastFMService, SlskdService slskdService) {
        this.lastFMService = lastFMService;
        this.slskdService = slskdService;
    }

    @RequestMapping("/search/{query}")
    Mono<String> search(@PathVariable String query) {
        return lastFMService.getResults(query, LastFMAPIMethod.TRACK_SEARCH);
    }

    @RequestMapping("/download/{query}")
    Mono<Boolean> downloadSearch(@PathVariable String query) {
        return slskdService.searchResults(query)
                .delayElement(Duration.ofSeconds(30))
                .flatMap(searchState -> slskdService.getSearchResultsProgress(searchState.getId()))
                .flatMap(finishedSearchState -> {
                    SearchResponseItem responseItem = finishedSearchState.getResponses().getFirst();
                    return slskdService.enqueueDownload(responseItem.getUsername(), responseItem.getFiles().getFirst());
                });
    }

    @RequestMapping("/download/search/progress/{searchId}")
    Mono<SearchState> downloadSearchProgress(@PathVariable String searchId) {
        return slskdService.getSearchResultsProgress(searchId);
    }
}

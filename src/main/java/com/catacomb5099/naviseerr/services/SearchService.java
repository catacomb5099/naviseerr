package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.lastfm.LastFMService;
import com.catacomb5099.naviseerr.services.slskd.SlskdDownloadProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import static reactor.netty.http.HttpConnectionLiveness.log;

@RestController
@AllArgsConstructor
public class SearchService {
    private final LastFMService lastFMService;
    private final SlskdSearchResultProcessor slskdSearchResultProcessor;
    private final SlskdDownloadProcessor slskdDownloadProcessor;

    @RequestMapping("/search/{query}")
    Mono<SearchResponse> search(@PathVariable String query) {
        // TODO: log result count
        log.info("Received LastFM general search request for query='{}'", query);
        return lastFMService.getResults(query)
            .doOnSubscribe(subscription -> log.debug("Starting LastFM general search for query='{}' (subscription={})", query, subscription))
            .doOnSuccess(result -> log.info("Completed LastFM general search for query='{}'", query))
            .doOnError(error -> log.error("LastFM general search failed for query='{}'", query, error));
    }

    @RequestMapping("/search/{query}/tracks")
    Mono<SearchResponse> searchTracks(@PathVariable String query) {
        // TODO: log result count
        log.info("Received LastFM track search request for query='{}'", query);
        return lastFMService.getResults(query, LastFMAPIMethod.TRACK_SEARCH)
                .doOnSubscribe(subscription -> log.debug("Starting LastFM track search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed LastFM track search for query='{}'", query))
                .doOnError(error -> log.error("LastFM track search failed for query='{}'", query, error));
    }

    @RequestMapping("/search/{query}/albums")
    Mono<SearchResponse> searchAlbums(@PathVariable String query) {
        // TODO: log result count
        log.info("Received LastFM album search request for query='{}'", query);
        return lastFMService.getResults(query, LastFMAPIMethod.ALBUM_SEARCH)
                .doOnSubscribe(subscription -> log.debug("Starting LastFM album search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed LastFM album search for query='{}'", query))
                .doOnError(error -> log.error("LastFM album search failed for query='{}'", query, error));
    }

    @RequestMapping("/search/{query}/artists")
    Mono<SearchResponse> searchArtists(@PathVariable String query) {
        // TODO: log result count
        log.info("Received LastFM artist search request for query='{}'", query);
        return lastFMService.getResults(query, LastFMAPIMethod.ARTIST_SEARCH)
                .doOnSubscribe(subscription -> log.debug("Starting LastFM artist search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed LastFM artist search for query='{}'", query))
                .doOnError(error -> log.error("LastFM artist search failed for query='{}'", query, error));
    }

    @RequestMapping("/download/{query}")
    Mono<TransferedFile> download(@PathVariable String query) {
        log.info("Received Slskd download search request for query='{}'", query);
        return slskdSearchResultProcessor.pollUntilComplete(query)
            .flatMap(finishedState -> slskdSearchResultProcessor.selectBestFiles(finishedState, query))
            .flatMap(slskdDownloadProcessor::pollUntilComplete);
    }

}

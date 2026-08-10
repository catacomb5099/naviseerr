package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicSearchType;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@AllArgsConstructor
public class SearchService {
    private final YtMusicService ytMusicService;

    @GetMapping("/search/{query}")
    Mono<SearchResponse> search(@PathVariable String query) {
        // TODO: log result count
        log.info("Received YtMusic general search request for query='{}'", query);
        return ytMusicService.getResults(query)
            .doOnSubscribe(subscription -> log.debug("Starting YtMusic general search for query='{}' (subscription={})", query, subscription))
            .doOnSuccess(result -> log.info("Completed YtMusic general search for query='{}'", query))
            .doOnError(error -> log.error("YtMusic general search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/tracks")
    Mono<SearchResponse> searchTracks(@PathVariable String query) {
        // TODO: log result count
        log.info("Received YtMusic track search request for query='{}'", query);
        return ytMusicService.getResults(query, YtMusicSearchType.SONGS)
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic track search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic track search for query='{}'", query))
                .doOnError(error -> log.error("YtMusic track search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/albums")
    Mono<SearchResponse> searchAlbums(@PathVariable String query) {
        // TODO: log result count
        log.info("Received YtMusic album search request for query='{}'", query);
        return ytMusicService.getResults(query, YtMusicSearchType.ALBUMS)
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic album search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic album search for query='{}'", query))
                .doOnError(error -> log.error("YtMusic album search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/artists")
    Mono<SearchResponse> searchArtists(@PathVariable String query) {
        // TODO: log result count
        log.info("Received YtMusic artist search request for query='{}'", query);
        return ytMusicService.getResults(query, YtMusicSearchType.ARTISTS)
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic artist search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic artist search for query='{}'", query))
                .doOnError(error -> log.error("YtMusic artist search failed for query='{}'", query, error));
    }

    // AGENTS.md: "report 'no good match' distinctly from provider errors, timeouts, and
    // cancellations." An empty result set already reaches the client as 200 with empty lists
    // (see YtMusicSearchResponseMapper); these two handlers give the remaining two cases their
    // own status codes instead of falling through to WebFlux's default 500.
    @ExceptionHandler(YtMusicBadRequestException.class)
    ResponseEntity<Void> handleBadRequest(YtMusicBadRequestException ex) {
        log.warn("Rejecting search request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(YtMusicUnavailableException.class)
    ResponseEntity<Void> handleUnavailable(YtMusicUnavailableException ex) {
        log.error("ytmusic-adapter unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

}

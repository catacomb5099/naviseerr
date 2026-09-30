package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Playlist;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@AllArgsConstructor
public class SearchService {
    /** How many of each playlist source keep YouTube Music's own ranking at the top of the shelf. */
    static final int PINNED_PER_SOURCE = 2;
    /**
     * A category search's first page. "Show more" on the client asks the same route again with a
     * bigger {@code limit} and keeps the ones it has not shown yet: YouTube Music has no way to ask
     * for "the next 20", and its order shifts a little between calls anyway.
     */
    static final int FIRST_PAGE = 20;
    /** The adapter's own ceiling (its {@code limit} is {@code le=100}); past it the adapter answers 422. */
    static final int MAX_LIMIT = 100;

    private final YtMusicService ytMusicService;

    @GetMapping("/search/{query}")
    Mono<SearchResponse> search(@PathVariable String query) {
        log.info("Received YtMusic general search request for query='{}'", query);
        return ytMusicService.getResults(query)
            .doOnSubscribe(subscription -> log.debug("Starting YtMusic general search for query='{}' (subscription={})", query, subscription))
            .doOnSuccess(result -> log.info("Completed YtMusic general search for query='{}': tracks={}, albums={}, artists={}, playlists={}",
                    query, size(result.getTracks()), size(result.getAlbums()), size(result.getArtists()), size(result.getPlaylists())))
            .doOnError(error -> log.error("YtMusic general search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/tracks")
    Mono<SearchResponse> searchTracks(@PathVariable String query, @RequestParam(defaultValue = "" + FIRST_PAGE) int limit) {
        log.info("Received YtMusic track search request for query='{}', limit={}", query, limit);
        return ytMusicService.getResults(query, YtMusicSearchType.SONGS, clamp(limit))
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic track search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic track search for query='{}': tracks={}", query, size(result.getTracks())))
                .doOnError(error -> log.error("YtMusic track search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/albums")
    Mono<SearchResponse> searchAlbums(@PathVariable String query, @RequestParam(defaultValue = "" + FIRST_PAGE) int limit) {
        log.info("Received YtMusic album search request for query='{}', limit={}", query, limit);
        return ytMusicService.getResults(query, YtMusicSearchType.ALBUMS, clamp(limit))
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic album search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic album search for query='{}': albums={}", query, size(result.getAlbums())))
                .doOnError(error -> log.error("YtMusic album search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/artists")
    Mono<SearchResponse> searchArtists(@PathVariable String query, @RequestParam(defaultValue = "" + FIRST_PAGE) int limit) {
        log.info("Received YtMusic artist search request for query='{}', limit={}", query, limit);
        return ytMusicService.getResults(query, YtMusicSearchType.ARTISTS, clamp(limit))
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic artist search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic artist search for query='{}': artists={}", query, size(result.getArtists())))
                .doOnError(error -> log.error("YtMusic artist search failed for query='{}'", query, error));
    }

    /**
     * Fan-made and YouTube Music's own featured playlists together: both searches run at once
     * and {@link #mix} orders the union. The featured leg is a bonus -- when it fails (an
     * adapter image without that route, say) the shelf still shows the fan-made list; the
     * fan-made leg keeps its usual error handling.
     *
     * <p>{@code limit} grows only the fan-made leg. The featured one stays at the configured
     * default: YouTube Music's featured search is one or two relevant playlists and then unrelated
     * filler (Kidz Bop, classical), so asking it for more only buys more filler.
     */
    @GetMapping("/search/{query}/playlists")
    Mono<SearchResponse> searchPlaylists(@PathVariable String query, @RequestParam(defaultValue = "" + FIRST_PAGE) int limit) {
        log.info("Received YtMusic playlist search request for query='{}', limit={}", query, limit);
        Mono<List<Playlist>> featured = ytMusicService.getResults(query, YtMusicSearchType.FEATURED_PLAYLISTS)
                .map(result -> orEmpty(result.getPlaylists()))
                .onErrorResume(error -> {
                    log.warn("Featured playlist search failed for query='{}', showing fan-made playlists only: {}", query, error.toString());
                    return Mono.just(List.of());
                });
        Mono<List<Playlist>> fanMade = ytMusicService.getResults(query, YtMusicSearchType.PLAYLISTS, clamp(limit))
                .map(result -> orEmpty(result.getPlaylists()));
        return Mono.zip(featured, fanMade, (editorial, community) -> mix(editorial, community, ThreadLocalRandom.current()))
                .map(playlists -> new SearchResponse(List.of(), List.of(), List.of(), playlists))
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic playlist search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic playlist search for query='{}': playlists={}", query, size(result.getPlaylists())))
                .doOnError(error -> log.error("YtMusic playlist search failed for query='{}'", query, error));
    }

    /**
     * The top {@value #PINNED_PER_SOURCE} featured playlists, then the top {@value #PINNED_PER_SOURCE}
     * fan-made ones, each in YouTube Music's own order; everything after those is shuffled together
     * so repeat searches surface different playlists from both pools.
     */
    static List<Playlist> mix(List<Playlist> featured, List<Playlist> fanMade, Random random) {
        int pinnedFeatured = Math.min(PINNED_PER_SOURCE, featured.size());
        int pinnedFanMade = Math.min(PINNED_PER_SOURCE, fanMade.size());
        List<Playlist> mixed = new ArrayList<>(featured.subList(0, pinnedFeatured));
        mixed.addAll(fanMade.subList(0, pinnedFanMade));
        List<Playlist> rest = new ArrayList<>(featured.subList(pinnedFeatured, featured.size()));
        rest.addAll(fanMade.subList(pinnedFanMade, fanMade.size()));
        Collections.shuffle(rest, random);
        mixed.addAll(rest);
        return mixed;
    }

    /** Out-of-range asks are pulled into range rather than refused: "a lot" means the most there is. */
    static int clamp(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static List<Playlist> orEmpty(List<Playlist> playlists) {
        return playlists == null ? List.of() : playlists;
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
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

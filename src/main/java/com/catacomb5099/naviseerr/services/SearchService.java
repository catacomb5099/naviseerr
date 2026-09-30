package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.Playlist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.schema.response.Track;
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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

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
    /**
     * How long each of All's searches gets per try. A healthy one answers in under a second (the
     * slowest of 56 at limit 20 took 0.82 s, measured 30-09-2026); a stalled one hangs 5-10 s and
     * then fails, and asking again usually answers at once. All waits for its slowest search, so
     * without this one stall among six held the whole answer for 6-20 s.
     */
    static final Duration ALL_TRY = Duration.ofSeconds(3);

    private final YtMusicService ytMusicService;

    /**
     * "All": YouTube Music's own mixed page at the top of each shelf, then the category searches
     * ({@value #FIRST_PAGE} of each) for depth, all run at once. The mixed page alone is about 6
     * songs, 3 albums, 6 artists and 6 playlists whatever the limit; the category searches alone
     * rank worse (for "oasis" the songs search leads with Roberta Flack, the mixed page with
     * Champagne Supernova). "Show more" on a shelf carries on with the same category search. See
     * docs/decisions/search-all-per-category-30-09-2026.md.
     *
     * <p>Each search gets {@link #ALL_TRY} per try. The songs search is the canary: it gets two more
     * tries, and if it still fails the whole answer is an error, so an adapter that is down still
     * reads "search failed" and never "no results". The mixed page and the other three categories
     * get one more try, then drop out on their own and are named in
     * {@link SearchResponse#getUnavailable()} so the client can say "couldn't load albums" rather
     * than show no albums shelf, and still offer to fetch that category.
     */
    @GetMapping("/search/{query}")
    Mono<SearchResponse> search(@PathVariable String query) {
        log.info("Received YtMusic general search request for query='{}'", query);
        return Mono.defer(() -> {
            // Per request: the drop-outs below record themselves here, and the zip reads it once
            // they have all finished.
            Set<String> unavailable = ConcurrentHashMap.newKeySet();
            SearchResponse none = new SearchResponse(List.of(), List.of(), List.of(), List.of());
            Mono<SearchResponse> top = orEmptyShelf("mixed", query, withTries(ytMusicService.getResults(query), ALL_TRY, 1), none, unavailable);
            Mono<List<Track>> tracks = withTries(ytMusicService.getResults(query, YtMusicSearchType.SONGS, FIRST_PAGE)
                    .map(result -> orEmpty(result.getTracks())), ALL_TRY, 2);
            Mono<List<Album>> albums = orEmptyShelf("albums", query, withTries(ytMusicService.getResults(query, YtMusicSearchType.ALBUMS, FIRST_PAGE)
                    .map(result -> orEmpty(result.getAlbums())), ALL_TRY, 1), List.of(), unavailable);
            Mono<List<Artist>> artists = orEmptyShelf("artists", query, withTries(ytMusicService.getResults(query, YtMusicSearchType.ARTISTS, FIRST_PAGE)
                    .map(result -> orEmpty(result.getArtists())), ALL_TRY, 1), List.of(), unavailable);
            Mono<List<Playlist>> playlists = orEmptyShelf("playlists", query, playlists(query, FIRST_PAGE, ALL_TRY), List.of(), unavailable);
            return Mono.zip(top, tracks, albums, artists, playlists)
                    .map(all -> new SearchResponse(
                            topThenRest(orEmpty(all.getT1().getTracks()), all.getT2(), Track::getId),
                            topThenRest(orEmpty(all.getT1().getAlbums()), all.getT3(), Album::getId),
                            topThenRest(orEmpty(all.getT1().getArtists()), all.getT4(), Artist::getId),
                            topThenRest(orEmpty(all.getT1().getPlaylists()), all.getT5(), Playlist::getId),
                            unavailable.stream().sorted().toList()));
        })
            .doOnSubscribe(subscription -> log.debug("Starting YtMusic general search for query='{}' (subscription={})", query, subscription))
            .doOnSuccess(result -> log.info("Completed YtMusic general search for query='{}': tracks={}, albums={}, artists={}, playlists={}",
                    query, size(result.getTracks()), size(result.getAlbums()), size(result.getArtists()), size(result.getPlaylists())))
            .doOnError(error -> log.error("YtMusic general search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/tracks")
    Mono<SearchResponse> searchTracks(@PathVariable String query, @RequestParam(defaultValue = "" + FIRST_PAGE) int limit) {
        log.info("Received YtMusic track search request for query='{}', limit={}", query, limit);
        return withTries(ytMusicService.getResults(query, YtMusicSearchType.SONGS, clamp(limit)), tryFor(clamp(limit)), 1)
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic track search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic track search for query='{}': tracks={}", query, size(result.getTracks())))
                .doOnError(error -> log.error("YtMusic track search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/albums")
    Mono<SearchResponse> searchAlbums(@PathVariable String query, @RequestParam(defaultValue = "" + FIRST_PAGE) int limit) {
        log.info("Received YtMusic album search request for query='{}', limit={}", query, limit);
        return withTries(ytMusicService.getResults(query, YtMusicSearchType.ALBUMS, clamp(limit)), tryFor(clamp(limit)), 1)
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic album search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic album search for query='{}': albums={}", query, size(result.getAlbums())))
                .doOnError(error -> log.error("YtMusic album search failed for query='{}'", query, error));
    }

    @GetMapping("/search/{query}/artists")
    Mono<SearchResponse> searchArtists(@PathVariable String query, @RequestParam(defaultValue = "" + FIRST_PAGE) int limit) {
        log.info("Received YtMusic artist search request for query='{}', limit={}", query, limit);
        return withTries(ytMusicService.getResults(query, YtMusicSearchType.ARTISTS, clamp(limit)), tryFor(clamp(limit)), 1)
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
        return playlists(query, clamp(limit), tryFor(clamp(limit)))
                .map(playlists -> new SearchResponse(List.of(), List.of(), List.of(), playlists))
                .doOnSubscribe(subscription -> log.debug("Starting YtMusic playlist search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed YtMusic playlist search for query='{}': playlists={}", query, size(result.getPlaylists())))
                .doOnError(error -> log.error("YtMusic playlist search failed for query='{}'", query, error));
    }

    /**
     * Each half has its own time limit, so a stalled featured search (the bonus half) falls back to
     * fan-made only after one {@code perTry} instead of holding, or failing, the fan-made answer.
     */
    private Mono<List<Playlist>> playlists(String query, int limit, Duration perTry) {
        Mono<List<Playlist>> featured = ytMusicService.getResults(query, YtMusicSearchType.FEATURED_PLAYLISTS)
                .map(result -> orEmpty(result.getPlaylists()))
                .timeout(perTry)
                .onErrorResume(error -> {
                    log.warn("Featured playlist search failed for query='{}', showing fan-made playlists only: {}", query, error.toString());
                    return Mono.just(List.of());
                });
        Mono<List<Playlist>> fanMade = withTries(ytMusicService.getResults(query, YtMusicSearchType.PLAYLISTS, limit)
                .map(result -> orEmpty(result.getPlaylists())), perTry, 1);
        return Mono.zip(featured, fanMade, (editorial, community) -> mix(editorial, community, ThreadLocalRandom.current()));
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

    /**
     * {@code top} in its own order, then whatever in {@code rest} it does not hold yet. An item in
     * both keeps its place in {@code top} but takes {@code rest}'s copy: a category search's song
     * knows its album, the mixed page's does not.
     */
    static <T> List<T> topThenRest(List<T> top, List<T> rest, Function<T, String> id) {
        Map<String, T> merged = new LinkedHashMap<>();
        top.forEach(item -> merged.putIfAbsent(id.apply(item), item));
        rest.forEach(item -> merged.put(id.apply(item), item));
        return List.copyOf(merged.values());
    }

    /**
     * How long one try of a category search may take: 2 s plus 1 s per 20 asked for, so 3 s for the
     * first page and 7 s for 100. ytmusicapi fetches 20 at a time, one after another (100 healthy
     * ones took 3.1-3.5 s on 30-09-2026), and any of those fetches can stall. A stall used to run its
     * full 5-10 s before the adapter client's retry, up to three times over.
     */
    static Duration tryFor(int limit) {
        return Duration.ofMillis(2000 + 50L * limit);
    }

    /**
     * {@code search}, given {@code perTry} per try and {@code more} further tries after a try runs
     * out of time. Only a slow try is repeated here; an adapter error has already had its own
     * retries inside {@link YtMusicService}.
     */
    private static <T> Mono<T> withTries(Mono<T> search, Duration perTry, int more) {
        return search.timeout(perTry)
                .retryWhen(Retry.max(more).filter(TimeoutException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> new YtMusicUnavailableException(
                                "ytmusic-adapter search took over " + perTry.toMillis() + " ms, " + (more + 1) + " times", signal.failure())));
    }

    private static <T> Mono<T> orEmptyShelf(String what, String query, Mono<T> shelf, T empty, Set<String> unavailable) {
        return shelf.onErrorResume(error -> {
            log.warn("General search: {} search failed for query='{}', showing the rest without it: {}", what, query, error.toString());
            unavailable.add(what);
            return Mono.just(empty);
        });
    }

    private static <T> List<T> orEmpty(List<T> items) {
        return items == null ? List.of() : items;
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

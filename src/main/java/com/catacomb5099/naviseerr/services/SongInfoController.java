package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of one song by id -- the song info page. Same {@code videoId} the download route
 * takes, so the client can go from here to {@code POST /download/song/{videoId}} unchanged.
 * Resolved live from {@code ytmusic-adapter}'s {@code /v1/songs/{id}/details}; nothing is stored.
 */
@Slf4j
@RestController
@AllArgsConstructor
public class SongInfoController {
    /** Cap on {@code GET /songs/views}: each id is one adapter call, so this bounds what one request costs. */
    static final int MAX_VIEW_IDS = 50;

    /**
     * Lookups in flight at once for {@code GET /songs/views}. The adapter itself allows 8 concurrent
     * YouTube calls; more from here would only queue inside it, eating into our own timeout.
     */
    static final int VIEWS_CONCURRENCY = 8;

    private final YtMusicService ytMusicService;

    @GetMapping("/songs/{id}")
    Mono<ResponseEntity<SongInfoView>> song(@PathVariable String id) {
        if (id == null || id.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return ytMusicService.getSongDetails(id).map(details -> {
            SongInfoView view = SongInfoView.from(details, id);
            log.info("Resolved song '{}': {} credit sections", id, view.credits().size());
            return ResponseEntity.ok(view);
        });
    }

    /**
     * How many times each song was played, for the rows YouTube gives no play count at all (a
     * playlist's songs). Each number is the plays of that one video or upload, from the adapter's
     * {@code /v1/songs/{id}}: exact, and a different, smaller number than the "plays" wording album
     * and search rows carry, which is YouTube Music's combined count across the song's uploads. Costs
     * one adapter call per distinct id, hence {@link #MAX_VIEW_IDS}.
     *
     * <p>An id whose lookup fails for any reason (unknown id, adapter down, timeout) or that has no
     * count is left out of the object rather than failing the others: a row without a number is
     * better than a page of rows without one. The literal path wins over {@code /songs/{id}}.
     */
    @GetMapping("/songs/views")
    Mono<ResponseEntity<Map<String, Long>>> views(@RequestParam List<String> ids) {
        List<String> distinct = ids.stream().map(String::strip).filter(id -> !id.isEmpty()).distinct().toList();
        if (distinct.isEmpty() || distinct.size() > MAX_VIEW_IDS) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Flux.fromIterable(distinct)
                .flatMap(id -> ytMusicService.getSongViewCount(id)
                        .map(count -> Map.entry(id, count))
                        .onErrorResume(error -> {
                            log.debug("View count for song '{}' unavailable, left out: {}", id, error.getMessage());
                            return Mono.empty();
                        }), VIEWS_CONCURRENCY)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(ResponseEntity::ok);
    }

    /** Same mapping and same known trade-off as {@link CollectionController#handleNotFound}. */
    @ExceptionHandler(YtMusicBadRequestException.class)
    ResponseEntity<Void> handleNotFound(YtMusicBadRequestException ex) {
        log.warn("Song lookup rejected by ytmusic-adapter (answered 404): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(YtMusicUnavailableException.class)
    ResponseEntity<Void> handleUnavailable(YtMusicUnavailableException ex) {
        log.error("ytmusic-adapter unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}

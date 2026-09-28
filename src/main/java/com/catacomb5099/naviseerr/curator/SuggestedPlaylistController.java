package com.catacomb5099.naviseerr.curator;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The client's view of the playlist curator's work: the weekly "suggested playlists". naviseerr reads
 * them from the curator on every request (the curator holds the files; naviseerr keeps no copy, so
 * there is nothing to go stale) and hands them over in the client's own vocabulary. The client never
 * talks to the curator itself: the curator needs the shared token, which belongs on the server.
 *
 * <p>Off unless the curator is configured, the same rule as the weekly refresh: the list then says
 * {@code enabled: false} and the detail route answers 503, so a client can tell "this install has no
 * curator" from "the curator is down" (502) and "no edition yet" (404).
 */
@Slf4j
@RestController
@AllArgsConstructor
public class SuggestedPlaylistController {
    private final CuratorClient client;
    private final CuratorScheduler scheduler;

    /** The latest edition per category. Always 200 when the curator is off, with {@code enabled: false}. */
    @GetMapping("/suggested-playlists")
    Mono<SuggestedPlaylistsView> list() {
        if (!scheduler.isEnabled()) {
            return Mono.just(SuggestedPlaylistsView.off());
        }
        return client.getEditions().map(editions -> SuggestedPlaylistsView.of(scheduler.getRefreshDay(), editions));
    }

    /** One category's latest edition with every song. 404 when the curator has none for it yet. */
    @GetMapping("/suggested-playlists/{category}")
    Mono<ResponseEntity<SuggestedPlaylistView>> one(@PathVariable String category) {
        if (category == null || category.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        if (!scheduler.isEnabled()) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        return client.getEdition(category)
                .map(edition -> {
                    SuggestedPlaylistView view = SuggestedPlaylistView.from(category, edition);
                    log.info("Suggested playlist '{}' edition {}: {} songs", category, view.editionDate(),
                            view.trackCount());
                    return ResponseEntity.ok(view);
                });
    }

    /**
     * "Make this week's playlists now." Answers 202 with the run the moment the curator has accepted it;
     * the work takes minutes and the client follows it on {@code GET /suggested-playlists/refresh}.
     * Pressing during a run returns that run (the curator's trigger is idempotent). 503 with no curator.
     */
    @PostMapping("/suggested-playlists/refresh")
    Mono<ResponseEntity<CuratorRun>> refresh() {
        if (!scheduler.isEnabled()) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        return scheduler.refreshNow()
                .map(run -> ResponseEntity.status(HttpStatus.ACCEPTED).body(run));
    }

    /**
     * How the most recent run is going, whether a person or the weekly clock started it: queued, running,
     * or finished with one line per category. 404 when the curator has never run. The literal path wins
     * over {@code /{category}}, so a category cannot be called "refresh".
     */
    @GetMapping("/suggested-playlists/refresh")
    Mono<ResponseEntity<CuratorRun>> latestRun() {
        if (!scheduler.isEnabled()) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        return client.getLatestRun().map(ResponseEntity::ok);
    }

    /**
     * The curator's 404 ("no edition for that category", "no run yet") is a 404 to the client too. Anything else the
     * curator said or failed to say -- it is down, the token is wrong, it answered 500 -- is a 502: the
     * problem is behind naviseerr, and the log line says which.
     */
    @ExceptionHandler(CuratorException.class)
    ResponseEntity<Void> handleCurator(CuratorException ex) {
        if (ex.isNotFound()) {
            log.info("Suggested playlist not found at the curator: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        log.error("Curator unavailable while reading suggested playlists: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}

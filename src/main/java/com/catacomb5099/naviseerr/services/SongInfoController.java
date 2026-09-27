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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Read-only view of one song by id -- the song info page. Same {@code videoId} the download route
 * takes, so the client can go from here to {@code POST /download/song/{videoId}} unchanged.
 * Resolved live from {@code ytmusic-adapter}'s {@code /v1/songs/{id}/details}; nothing is stored.
 */
@Slf4j
@RestController
@AllArgsConstructor
public class SongInfoController {
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

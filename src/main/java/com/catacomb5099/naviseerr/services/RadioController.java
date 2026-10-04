package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/**
 * Radios: songs YouTube Music picks as similar to a song, album or playlist (the seed). Starting one
 * SAVES it, because YouTube builds a different list on every call (the same seed two minutes apart kept
 * 5-18 of 25 songs) and the list a person looks at must be the list that is later downloaded. So a radio
 * is a POST that creates something with its own id, and reading it again never asks YouTube.
 */
@Slf4j
@RestController
@AllArgsConstructor
public class RadioController {
    private final YtMusicService ytMusicService;
    private final RadioRepository repository;

    /**
     * Starts a radio from {@code seed}: a song's videoId, an album's {@code MPREb_} id or a playlist id,
     * the same ids the song, album and playlist pages already carry. 201 with the saved radio.
     */
    @PostMapping("/radios")
    Mono<ResponseEntity<RadioView>> start(@RequestParam String seed) {
        if (seed.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        UUID id = UUID.randomUUID();
        return ytMusicService.getRadioInfo(seed)
                .map(RadioController::named)
                .flatMap(radio -> repository.save(id, seed, radio).thenReturn(radio))
                .map(radio -> {
                    RadioView view = RadioView.from(id, radio);
                    log.info("Started radio {} from '{}': '{}', {} songs", id, seed, view.name(), view.trackCount());
                    return ResponseEntity.created(URI.create("/radios/" + id)).body(view);
                });
    }

    /** A radio started earlier, exactly as it was saved. 404 for an id that was never started. */
    @GetMapping("/radios/{id}")
    Mono<ResponseEntity<RadioView>> one(@PathVariable UUID id) {
        return repository.find(id)
                .map(radio -> ResponseEntity.ok(RadioView.from(id, radio)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * The adapter names a radio after its seed ("Billie Jean"); the word is added here, where product
     * wording lives. Songs without an id are dropped before saving, as a download would drop them.
     */
    static YoutubeCollectionInfo named(YoutubeCollectionInfo radio) {
        String name = radio.name() == null || radio.name().isBlank() ? "Radio" : radio.name() + " radio";
        return new YoutubeCollectionInfo(radio.id(),
                radio.songs().stream().filter(song -> song.id() != null).toList(), null, name,
                radio.authorNames(), radio.authorIds(), radio.imageUrl());
    }

    /** Same mapping as {@link CollectionController}: the adapter's 404 means YouTube has no radio for that id. */
    @ExceptionHandler(YtMusicBadRequestException.class)
    ResponseEntity<Void> handleNotFound(YtMusicBadRequestException ex) {
        log.warn("Radio seed rejected by ytmusic-adapter (answered 404): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(YtMusicUnavailableException.class)
    ResponseEntity<Void> handleUnavailable(YtMusicUnavailableException ex) {
        log.error("ytmusic-adapter unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}

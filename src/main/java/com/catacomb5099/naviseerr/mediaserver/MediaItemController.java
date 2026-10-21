package com.catacomb5099.naviseerr.mediaserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Where is this downloaded YouTube song in Navidrome / Jellyfin?" so a player such as Feishin can play it with its
 * own player: {@code GET /library/media-items?server=NAVIDROME|JELLYFIN&ids=a,b}. One media-server search per
 * id, asked only when someone presses Play, so nothing is cached.
 */
@Slf4j
@RestController
public class MediaItemController {

    static final int MAX_IDS = 50;
    /** Lookups in flight at once: a whole album's Play asks for up to {@link #MAX_IDS}. */
    private static final int CONCURRENCY = 4;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Answer(String id, String albumId, String reason) {}

    private final FiledSongs filed;
    private final MediaItems items;

    public MediaItemController(FiledSongs filed, MediaItems items) {
        this.filed = filed;
        this.items = items;
    }

    @GetMapping("/library/media-items")
    Mono<ResponseEntity<Object>> mediaItems(@RequestParam String server, @RequestParam List<String> ids) {
        MediaServer target;
        try {
            target = MediaServer.valueOf(server.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("message", "server must be NAVIDROME or JELLYFIN.")));
        }
        List<String> distinct = ids.stream().map(String::strip).filter(id -> !id.isEmpty()).distinct().toList();
        if (distinct.isEmpty() || distinct.size() > MAX_IDS) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("message", "Ask for between 1 and " + MAX_IDS + " song ids.")));
        }
        if (!items.connected(target)) {
            return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("reason", "MEDIA_SERVER_NOT_CONNECTED", "message", MediaItems.notConnected(target))));
        }
        return filed.newest(distinct).collectMap(FiledSongs.FiledSong::youtubeId)
                .flatMap(songs -> Flux.fromIterable(distinct)
                        .flatMapSequential(id -> songs.containsKey(id)
                                ? items.find(target, songs.get(id))
                                        .map(found -> Map.entry(id, new Answer(found.id(), found.albumId(), null)))
                                        .defaultIfEmpty(Map.entry(id, new Answer(null, null, "NOT_FOUND_IN_MEDIA_SERVER")))
                                : Mono.just(Map.entry(id, new Answer(null, null, "NOT_IN_LIBRARY"))), CONCURRENCY)
                        .collect(LinkedHashMap<String, Answer>::new, (map, e) -> map.put(e.getKey(), e.getValue())))
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(MediaServerException.class, e -> {
                    log.warn("Could not look songs up in {}: {}", target.displayName, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(Map.of("reason", "MEDIA_SERVER_UNREACHABLE", "message", e.getMessage())));
                });
    }
}

package com.catacomb5099.naviseerr.mediaserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Add this song to my playlist once it is downloaded" for a player such as Feishin: the player first asks for
 * the download as usual, then posts which songs of which download go into which of the media server's own
 * playlists. {@link PlaylistAdder} does the adding. See docs/decisions/playlist-additions-21-10-2026.md.
 */
@Slf4j
@RestController
public class PlaylistAdditionController {

    static final int MAX_ITEMS = 500;
    /** Added or given-up rows stay in the list this long, like finished downloads in the feed. */
    static final Duration RECENT = Duration.ofMinutes(10);

    public record Item(UUID downloadId, String youtubeId) {}

    public record Request(String server, String playlistId, String userId, List<Item> items) {}

    private final PlaylistAdditionRepository repository;
    private final MediaItems items;
    private final Clock clock;

    public PlaylistAdditionController(PlaylistAdditionRepository repository, MediaItems items, Clock clock) {
        this.repository = repository;
        this.items = items;
        this.clock = clock;
    }

    @PostMapping("/playlist-additions")
    Mono<ResponseEntity<Object>> request(@RequestBody Request body) {
        MediaServer server = server(body.server());
        if (server == null) {
            return bad("server must be NAVIDROME or JELLYFIN.");
        }
        if (body.playlistId() == null || body.playlistId().isBlank()) {
            return bad("playlistId is missing.");
        }
        String userId = body.userId() == null || body.userId().isBlank() ? null : body.userId().strip();
        if (server == MediaServer.JELLYFIN && userId == null) {
            return bad("userId (the playlist owner's Jellyfin user id) is needed for Jellyfin.");
        }
        if (body.items() == null || body.items().isEmpty() || body.items().size() > MAX_ITEMS) {
            return bad("Send between 1 and " + MAX_ITEMS + " songs.");
        }
        Map<String, UUID> songs = new LinkedHashMap<>();
        for (Item item : body.items()) {
            if (item == null || item.downloadId() == null || item.youtubeId() == null || item.youtubeId().isBlank()) {
                return bad("Every song needs its downloadId and youtubeId.");
            }
            songs.putIfAbsent(item.youtubeId().strip(), item.downloadId());
        }
        if (!items.connected(server)) {
            return conflict("MEDIA_SERVER_NOT_CONNECTED", MediaItems.notConnected(server));
        }
        String playlistId = body.playlistId().strip();
        Set<UUID> asked = new HashSet<>(songs.values());
        Instant now = clock.instant();
        return repository.existingDownloads(List.copyOf(asked)).collectList()
                .flatMap(found -> {
                    if (found.size() < asked.size()) {
                        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).<Object>body(Map.of(
                                "reason", "DOWNLOAD_NOT_FOUND", "message", "There is no such download.")));
                    }
                    return items.playlist(server, playlistId, userId)
                            .flatMap(playlist -> playlist.notEditable()
                                    ? Mono.just(conflictBody("NOT_EDITABLE", server.displayName
                                            + " does not let songs be added to this playlist (imported or smart)."))
                                    : repository.request(server, playlistId, userId, new ArrayList<>(songs.values()),
                                                    new ArrayList<>(songs.keySet()), now)
                                            .collectList()
                                            .flatMap(ids -> repository.views(ids).collectList())
                                            .map(views -> ResponseEntity.status(HttpStatus.ACCEPTED)
                                                    .<Object>body(Map.of("additions", views))))
                            .switchIfEmpty(Mono.fromSupplier(() -> conflictBody("PLAYLIST_NOT_FOUND", "naviseerr's "
                                    + server.displayName + " has no such playlist. Is Feishin using the same "
                                    + server.displayName + "?")));
                })
                .onErrorResume(MediaServerException.class, e -> {
                    log.warn("Could not check {} playlist {}: {}", server.displayName, playlistId, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(Map.of("reason", "MEDIA_SERVER_UNREACHABLE", "message", e.getMessage())));
                });
    }

    @GetMapping("/playlist-additions")
    Mono<ResponseEntity<Object>> list(@RequestParam(required = false) String server,
                                      @RequestParam(required = false) String playlistId) {
        MediaServer which = server == null || server.isBlank() ? null : server(server);
        if (server != null && !server.isBlank() && which == null) {
            return bad("server must be NAVIDROME or JELLYFIN.");
        }
        return repository.recent(clock.instant().minus(RECENT), which, playlistId == null || playlistId.isBlank() ? null : playlistId)
                .collectList()
                .map(views -> ResponseEntity.ok(Map.of("additions", views)));
    }

    @DeleteMapping("/playlist-additions/{additionId}")
    Mono<ResponseEntity<Object>> cancel(@PathVariable UUID additionId) {
        return repository.cancel(additionId).map(outcome -> switch (outcome) {
            case 1 -> ResponseEntity.noContent().build();
            case -1 -> conflictBody("ALREADY_ADDED", "This song is already in the playlist.");
            default -> ResponseEntity.notFound().build();
        });
    }

    private static MediaServer server(String name) {
        try {
            return name == null ? null : MediaServer.valueOf(name.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Mono<ResponseEntity<Object>> bad(String message) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("message", message)));
    }

    private static Mono<ResponseEntity<Object>> conflict(String reason, String message) {
        return Mono.just(conflictBody(reason, message));
    }

    private static ResponseEntity<Object> conflictBody(String reason, String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", reason, "message", message));
    }
}

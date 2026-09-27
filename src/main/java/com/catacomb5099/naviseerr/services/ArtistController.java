package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Playlist;
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

import java.util.List;

/**
 * Read-only artist page by channel id: header, top songs, albums, singles, playlists that mention
 * the artist, and similar artists. Same id the artist search hands out, so the client links straight
 * from a search card.
 */
@Slf4j
@RestController
@AllArgsConstructor
public class ArtistController {
    private final YtMusicService ytMusicService;

    /**
     * Two adapter calls, in sequence: the playlist search needs the artist's NAME, which only the
     * artist call returns, so they cannot run side by side. The search is best-effort — if it fails
     * the page still loads with an empty {@code playlists} shelf, because a header and top songs
     * are worth more than nothing, and the search is an approximation to begin with (see
     * {@link ArtistView#playlists()}).
     */
    @GetMapping("/artists/{channelId}")
    Mono<ArtistView> artist(@PathVariable String channelId) {
        return ytMusicService.getArtistInfo(channelId)
                .flatMap(artist -> playlistsMentioning(artist.getName())
                        .map(playlists -> ArtistView.from(artist, playlists, channelId)))
                .doOnNext(view -> log.info("Resolved artist '{}' ({}): {} songs, {} albums, {} singles, {} playlists, {} similar",
                        view.name(), channelId, view.topSongs().size(), view.albums().size(), view.singles().size(),
                        view.playlists().size(), view.similarArtists().size()));
    }

    private Mono<List<Playlist>> playlistsMentioning(String name) {
        if (name == null || name.isBlank()) {
            return Mono.just(List.of());
        }
        return ytMusicService.getResults(name, YtMusicSearchType.PLAYLISTS)
                .map(response -> response.getPlaylists() == null ? List.<Playlist>of() : response.getPlaylists())
                .onErrorResume(error -> {
                    log.warn("Playlist search for artist '{}' failed, answering without playlists: {}", name, error.getMessage());
                    return Mono.just(List.of());
                });
    }

    /** Same mapping as {@link CollectionController#handleNotFound}: a lookup by id the adapter rejects is a 404. */
    @ExceptionHandler(YtMusicBadRequestException.class)
    ResponseEntity<Void> handleNotFound(YtMusicBadRequestException ex) {
        log.warn("Artist lookup rejected by ytmusic-adapter (answered 404): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(YtMusicUnavailableException.class)
    ResponseEntity<Void> handleUnavailable(YtMusicUnavailableException ex) {
        log.error("ytmusic-adapter unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}

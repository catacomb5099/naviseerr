package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.download.DownloadType;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Read-only view of an album or playlist by id -- what the client shows between a search hit and a
 * download request. Same id and {@code type} the download route takes, so a client can go from this
 * page to {@code POST /download/collection/{id}?type=} without translating anything.
 */
@Slf4j
@RestController
@AllArgsConstructor
public class CollectionController {
    private final YtMusicService ytMusicService;

    /**
     * {@code type} is required for the same reason it is on the download route: albums and playlists
     * are two adapter endpoints, and guessing from an id prefix is a heuristic. Spring rejects an
     * unparseable value with 400 before this runs; {@code SONG} is rejected here.
     */
    @GetMapping("/collections/{id}")
    Mono<ResponseEntity<CollectionView>> collection(@PathVariable String id, @RequestParam DownloadType type) {
        if (id == null || id.isBlank() || !type.isCollection()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        Mono<YoutubeCollectionInfo> info = type == DownloadType.ALBUM
                ? ytMusicService.getAlbumInfo(id)
                : ytMusicService.getPlaylistInfo(id);
        return info.map(collection -> ResponseEntity.ok(CollectionView.from(collection, type)))
                .doOnSuccess(view -> log.info("Resolved {} '{}': {} tracks", type, id,
                        view.getBody() == null ? 0 : view.getBody().trackCount()));
    }

    /**
     * 404, not the 400 {@link SearchService} uses for the same exception. A search's bad request is
     * a malformed query; here the request is a lookup by id and the adapter's 404 (folded into this
     * exception, see {@code YtMusicService}) means "no such album or playlist", which is a 404 to the
     * client too.
     */
    @ExceptionHandler(YtMusicBadRequestException.class)
    ResponseEntity<Void> handleNotFound(YtMusicBadRequestException ex) {
        log.warn("Collection not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(YtMusicUnavailableException.class)
    ResponseEntity<Void> handleUnavailable(YtMusicUnavailableException ex) {
        log.error("ytmusic-adapter unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}

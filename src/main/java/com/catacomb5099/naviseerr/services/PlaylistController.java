package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.curator.CuratorException;
import com.catacomb5099.naviseerr.download.DownloadType;
import com.catacomb5099.naviseerr.download.TrackLists;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Your own playlists: a draft you add songs, albums and playlists to, reorder and rename, then "Create"
 * ({@code POST /download/collection/{id}?type=CUSTOM}), which downloads it in your order. Once created
 * it is frozen: every edit answers 409, and Duplicate makes a new draft from it. Songs are added by id
 * and resolved here, through the same {@link TrackLists} a download uses.
 */
@Slf4j
@RestController
@AllArgsConstructor
public class PlaylistController {

    /** Spotify's limits. */
    static final int MAX_TITLE = 100;
    static final int MAX_DESCRIPTION = 300;

    private final PlaylistRepository repository;
    private final TrackLists trackLists;

    record Change(String title, String description) {
    }

    /** {@code songIds}: only these songs of the source, still in the source's order; null = all of them. */
    record AddSongs(DownloadType type, String id, List<String> songIds) {
    }

    record AddResult(int added, int alreadyIn, int trackCount) {
    }

    /** Last edited first, like Spotify's "Recents". */
    @GetMapping("/playlists")
    Flux<PlaylistView> list() {
        return repository.list().map(PlaylistView::summary);
    }

    /** A new empty draft, 201. A blank or missing title becomes "My playlist #N" (Spotify's name). */
    @PostMapping("/playlists")
    Mono<ResponseEntity<?>> create(@RequestBody(required = false) Change change) {
        String title = change == null ? null : blankToNull(change.title());
        String description = change == null || change.description() == null ? "" : change.description().trim();
        ResponseEntity<?> invalid = invalid(title, description);
        if (invalid != null) return Mono.just(invalid);
        return repository.create(title, description)
                .flatMap(repository::find)
                .map(created -> {
                    log.info("New playlist {} '{}'", created.id(), created.title());
                    return ResponseEntity.created(URI.create("/playlists/" + created.id())).body(PlaylistView.detail(created));
                });
    }

    @GetMapping("/playlists/{id}")
    Mono<ResponseEntity<PlaylistView>> one(@PathVariable UUID id) {
        return repository.find(id).map(playlist -> ResponseEntity.ok(PlaylistView.detail(playlist)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /** Rename or describe a draft; a field left out stays as it is. A title cannot be blank. */
    @PatchMapping("/playlists/{id}")
    Mono<ResponseEntity<?>> change(@PathVariable UUID id, @RequestBody Change change) {
        String title = change.title() == null ? null : change.title().trim();
        String description = change.description() == null ? null : change.description().trim();
        if (title != null && title.isEmpty()) return Mono.just(badRequest("Give the playlist a name."));
        ResponseEntity<?> invalid = invalid(title, description);
        if (invalid != null) return Mono.just(invalid);
        return edited(id, repository.rename(id, title, description));
    }

    /**
     * Adds a song, or every song of an album, playlist, suggested playlist, radio or another of your
     * playlists (or only {@code songIds} of it), after the songs already there, skipping any the draft
     * has. One YouTube Music call; when YouTube is down nothing is added (the usual 502).
     */
    @PostMapping("/playlists/{id}/songs")
    Mono<ResponseEntity<?>> add(@PathVariable UUID id, @RequestBody AddSongs request) {
        if (request.type() == null || request.id() == null || request.id().isBlank()) {
            return Mono.just(badRequest("Say what to add: a type and an id."));
        }
        // Checked first, so an unknown or created playlist costs no YouTube call.
        return repository.find(id)
                .<ResponseEntity<?>>flatMap(playlist -> playlist.downloadedAt() != null ? Mono.just(created())
                        : trackLists.of(request.type(), request.id())
                                .map(source -> songsToAdd(source.songs(), request.songIds()))
                                .flatMap(songs -> repository.append(id, songs)
                                        .<ResponseEntity<?>>map(done -> {
                                            log.info("Added {} song(s) from {} '{}' to playlist {} ({} already in it)",
                                                    done.added(), request.type(), request.id(), id, songs.size() - done.added());
                                            return ResponseEntity.ok(new AddResult(done.added(), songs.size() - done.added(),
                                                    done.trackCount()));
                                        })
                                        .switchIfEmpty(Mono.defer(() -> refused(id, badRequest(
                                                "A playlist holds at most " + PlaylistRepository.MAX_SONGS + " songs."))))))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Reorder: the songs listed take that order, and any song not listed (one just added from another tab)
     * keeps its place after them. Unknown ids are ignored, so a stale tab never loses a song.
     */
    @PutMapping("/playlists/{id}/order")
    Mono<ResponseEntity<?>> reorder(@PathVariable UUID id, @RequestBody List<String> videoIds) {
        return edited(id, repository.reorder(id, videoIds.stream().distinct().toList()));
    }

    @DeleteMapping("/playlists/{id}/songs/{videoId}")
    Mono<ResponseEntity<?>> remove(@PathVariable UUID id, @PathVariable String videoId) {
        return edited(id, repository.remove(id, videoId));
    }

    /** Duplicate a draft or a created playlist: a new draft "Copy of <title>", 201. */
    @PostMapping("/playlists/{id}/copy")
    Mono<ResponseEntity<PlaylistView>> copy(@PathVariable UUID id) {
        return repository.copy(id).flatMap(repository::find)
                .map(copy -> ResponseEntity.created(URI.create("/playlists/" + copy.id())).body(PlaylistView.detail(copy)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /** Drafts only: a created playlist is in your music app and your downloads, which are never deleted either. */
    @DeleteMapping("/playlists/{id}")
    Mono<ResponseEntity<?>> delete(@PathVariable UUID id) {
        return repository.delete(id).flatMap(rows -> rows > 0 ? Mono.just(ResponseEntity.noContent().build())
                : refused(id, ResponseEntity.notFound().build()));
    }

    /** The source's songs with an id, only {@code songIds} when given, each once, without album-only fields. */
    static List<YoutubeSongInfo> songsToAdd(List<YoutubeSongInfo> source, List<String> songIds) {
        Set<String> wanted = songIds == null ? null : new HashSet<>(songIds);
        Map<String, YoutubeSongInfo> songs = new LinkedHashMap<>();
        source.stream()
                .filter(song -> song.id() != null && (wanted == null || wanted.contains(song.id())))
                // trackNumber is albums-only on task rows and would wrongly arm the album arm of the same-song check.
                .forEach(song -> songs.putIfAbsent(song.id(), new YoutubeSongInfo(song.id(), song.authorNames(),
                        song.authorIds(), song.name(), song.imageUrl(), song.durationSeconds(), null, null)));
        return List.copyOf(songs.values());
    }

    /** An edit statement's outcome: the fresh playlist, or why nothing changed. */
    private Mono<ResponseEntity<?>> edited(UUID id, Mono<Long> rows) {
        return rows.flatMap(updated -> updated > 0
                ? repository.find(id).<ResponseEntity<?>>map(playlist -> ResponseEntity.ok(PlaylistView.detail(playlist)))
                : refused(id, ResponseEntity.notFound().build()));
    }

    /** Nothing changed: 404 for an unknown id, 409 for a created playlist, else {@code otherwise}. */
    private Mono<ResponseEntity<?>> refused(UUID id, ResponseEntity<?> otherwise) {
        return repository.find(id)
                .<ResponseEntity<?>>map(playlist -> playlist.downloadedAt() != null ? created() : otherwise)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private static ResponseEntity<?> created() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "This playlist is created. Duplicate it to make changes."));
    }

    private static ResponseEntity<?> invalid(String title, String description) {
        if (title != null && title.length() > MAX_TITLE) return badRequest("A name can be at most " + MAX_TITLE + " characters.");
        if (description != null && description.length() > MAX_DESCRIPTION) {
            return badRequest("A description can be at most " + MAX_DESCRIPTION + " characters.");
        }
        return null;
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Same mapping as {@link CollectionController}: the adapter's 404 means YouTube has no such song, album or playlist. */
    @ExceptionHandler(YtMusicBadRequestException.class)
    ResponseEntity<Void> handleNotFound(YtMusicBadRequestException ex) {
        log.warn("Nothing added: ytmusic-adapter does not know that id (answered 404): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /** Same mapping as {@code SuggestedPlaylistController}: no edition is a 404, a curator that is down a 502. */
    @ExceptionHandler(CuratorException.class)
    ResponseEntity<Void> handleCurator(CuratorException ex) {
        log.warn("Nothing added: the curator could not give that suggested playlist: {}", ex.getMessage());
        return ResponseEntity.status(ex.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY).build();
    }
}

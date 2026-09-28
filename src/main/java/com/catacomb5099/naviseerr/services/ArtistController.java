package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Playlist;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicSearchType;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Read-only artist page by channel id: header, top songs, albums, singles, YouTube Music's featured
 * playlists the artist appears in, and similar artists. Same id the artist search hands out, so the
 * client links straight from a search card.
 */
@Slf4j
@RestController
@AllArgsConstructor
public class ArtistController {
    private final YtMusicService ytMusicService;

    /**
     * Twice the shelf's cap: {@link #withoutTitledAfter} throws part of the page away, and YouTube
     * puts the artist-titled ones first, so a page of exactly ten would often leave a near-empty shelf.
     */
    static final int FEATURED_SEARCH_LIMIT = 20;

    /**
     * Two adapter calls, in sequence: the playlist search needs the artist's NAME (and related
     * artists), which only the artist call returns, so they cannot run side by side. The search is
     * best-effort — if it fails the page still loads with an empty {@code playlists} shelf, because a
     * header and top songs are worth more than nothing, and the search is an approximation to begin
     * with (see {@link ArtistView#playlists()}).
     */
    @GetMapping("/artists/{channelId}")
    Mono<ArtistView> artist(@PathVariable String channelId) {
        return ytMusicService.getArtistInfo(channelId)
                .flatMap(artist -> featuredOn(artist)
                        .map(playlists -> ArtistView.from(artist, playlists, channelId)))
                .doOnNext(view -> log.info("Resolved artist '{}' ({}): {} songs, {} albums, {} singles, {} playlists, {} similar",
                        view.name(), channelId, view.topSongs().size(), view.albums().size(), view.singles().size(),
                        view.playlists().size(), view.similarArtists().size()));
    }

    private Mono<List<Playlist>> featuredOn(YtMusicDetailResponse.Artist artist) {
        String name = artist.getName();
        if (name == null || name.isBlank()) {
            return Mono.just(List.of());
        }
        List<String> related = artist.getRelated() == null ? List.of()
                : artist.getRelated().stream().map(YtMusicDetailResponse.RelatedArtist::getTitle).toList();
        return ytMusicService.getResults(name, YtMusicSearchType.FEATURED_PLAYLISTS, FEATURED_SEARCH_LIMIT)
                .map(response -> withoutTitledAfter(response.getPlaylists(),
                        Stream.concat(Stream.of(name), related.stream()).toList()))
                .onErrorResume(error -> {
                    log.warn("Featured-playlist search for artist '{}' failed, answering without playlists: {}", name, error.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * A featured-playlist search for "Oasis" answers the playlists Oasis is IN ("'90s Sing-Alongs")
     * mixed with ones ABOUT Oasis or a related act ("Presenting Oasis", "Presenting The Kooks"); the
     * shelf wants only the first kind. Order is YouTube's. A name matches when a run of whole words
     * of the title spells it: case, accents and punctuation are ignored ("Beyoncé" finds "beyonce",
     * "AC/DC" finds "ACDC" and "A C D C"), but "Blur" does not find "Blurred Lines" — a plain
     * substring test would empty the shelf for every short-named act (Muse, Air, Live, Kiss). A name
     * that folds to nothing is skipped rather than matched against everything.
     */
    static List<Playlist> withoutTitledAfter(List<Playlist> playlists, List<String> names) {
        List<String> keys = names.stream().map(name -> String.join("", fold(name))).filter(key -> !key.isEmpty()).toList();
        return playlists == null ? List.of() : playlists.stream()
                .filter(playlist -> {
                    List<String> words = fold(playlist.getName());
                    return keys.stream().noneMatch(key -> spelledByConsecutiveWords(words, key));
                })
                .toList();
    }

    private static boolean spelledByConsecutiveWords(List<String> words, String key) {
        for (int start = 0; start < words.size(); start++) {
            StringBuilder run = new StringBuilder();
            for (int end = start; end < words.size() && run.length() < key.length(); end++) {
                run.append(words.get(end));
                if (run.toString().equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lowercase words of letters and digits, accents stripped; empty for a null or symbol-only text. */
    private static List<String> fold(String text) {
        if (text == null) {
            return List.of();
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return Arrays.stream(plain.split("[^\\p{L}\\p{N}]+")).filter(word -> !word.isEmpty()).toList();
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

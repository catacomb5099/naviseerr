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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Read-only artist page by channel id: header, top songs, albums, singles, the featured playlists
 * whose track list names the artist, and similar artists. Same id the artist search hands out, so
 * the client links straight from a search card.
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
     * How many title-filtered candidates are opened to check the artist is really in them — up to
     * {@value} extra adapter calls per artist page, {@value #FEATURED_CHECK_CONCURRENCY} at a time,
     * roughly two to three seconds on a first load (the client caches the page for five minutes).
     * Worth it: YouTube's search links a playlist to an artist for reasons other than membership
     * (for Oasis, "Summer House" — 131 tracks, no Oasis), so only the track list can confirm
     * "featured in". Twelve rather than the shelf's ten because a couple usually fail the check.
     * The check reads the adapter's default {@code /v1/playlists} page of 100 tracks, so an artist
     * buried past track 100 of a very long playlist is missed.
     */
    static final int FEATURED_CHECK_LIMIT = 12;
    static final int FEATURED_CHECK_CONCURRENCY = 4;

    /**
     * Adapter calls in sequence: the artist, then the featured-playlist search (it needs the artist's
     * NAME and related artists, which only the artist call returns), then up to
     * {@value #FEATURED_CHECK_LIMIT} playlist lookups to confirm membership. Everything after the
     * artist call is best-effort — if it fails the page still loads with an empty {@code playlists}
     * shelf, because a header and top songs are worth more than nothing, and the search is an
     * approximation to begin with (see {@link ArtistView#playlists()}).
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
                .flatMap(candidates -> namingInTrackList(candidates, name))
                .onErrorResume(error -> {
                    log.warn("Featured-playlist lookup for artist '{}' failed, answering without playlists: {}", name, error.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * Keeps the first {@value #FEATURED_CHECK_LIMIT} candidates whose track list credits the artist:
     * a song's author folds (as {@link #fold}) to exactly the artist's folded name, whole — "Pixies
     * Tribute Band" is not Pixies. {@code flatMapSequential} runs the lookups
     * {@value #FEATURED_CHECK_CONCURRENCY} at a time and keeps YouTube's order. A playlist that
     * fails to open is dropped, not an error.
     */
    private Mono<List<Playlist>> namingInTrackList(List<Playlist> candidates, String name) {
        String key = String.join("", fold(name));
        if (key.isEmpty()) {
            return Mono.just(List.of());
        }
        int checked = Math.min(candidates.size(), FEATURED_CHECK_LIMIT);
        // ponytail: getPlaylistInfo reads the adapter's default first 100 tracks; pass a limit if artists deeper than that turn out to matter
        return Flux.fromIterable(candidates)
                .take(FEATURED_CHECK_LIMIT)
                .flatMapSequential(playlist -> ytMusicService.getPlaylistInfo(playlist.getId())
                        .filter(info -> info.songs().stream()
                                .flatMap(song -> song.authorNames().stream())
                                .anyMatch(author -> key.equals(String.join("", fold(author)))))
                        .map(info -> playlist)
                        .onErrorResume(error -> {
                            log.debug("Could not open playlist {} to check for '{}', dropped: {}", playlist.getId(), name, error.getMessage());
                            return Mono.empty();
                        }), FEATURED_CHECK_CONCURRENCY)
                .collectList()
                .doOnNext(kept -> log.debug("Artist '{}': {} of {} featured-playlist candidates dropped, track list does not name the artist",
                        name, checked - kept.size(), checked));
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

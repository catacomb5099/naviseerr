package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.schema.response.Track;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicSearchType;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Maps ytmusic-adapter search results onto the existing {@code schema.response} DTOs.
 *
 * The frontend contract this must honour (naviseerr-client has no normalization layer):
 * - {@code artists} must hold display NAMES, not channel ids -- the UI joins them directly.
 * - {@code Track.iconURL} / {@code Album.iconURL} are capital-URL; {@code Artist.iconUrl} is
 *   lowercase -- both must be preserved exactly as the client mirrors this inconsistency.
 * - Every list is non-null (empty, never null) -- {@code id}/{@code name} are React keys /
 *   display text on the client and must never be null either.
 *
 * Each typed sugar route ({@code /v1/search/songs|albums|artists}) is filtered server-side by
 * ytmusicapi, but items are re-filtered by {@code resultType} here defensively -- upstream shape
 * drift must not leak, e.g., a podcast into the artists list (see naviseerr gotchas.md #5, the
 * precedent for defensive image/index handling on the Last.fm path this replaces).
 */
public class YtMusicSearchResponseMapper {

    private YtMusicSearchResponseMapper() {
    }

    public static SearchResponse mapToSearchResponse(YtMusicSearchType type, YtMusicSearchResponse response) {
        List<YtMusicSearchResponse.Item> items = response != null && response.getItems() != null
                ? response.getItems()
                : Collections.emptyList();

        return switch (type) {
            case SONGS -> new SearchResponse(mapTracks(items), Collections.emptyList(), Collections.emptyList());
            case ALBUMS -> new SearchResponse(Collections.emptyList(), mapAlbums(items), Collections.emptyList());
            case ARTISTS -> new SearchResponse(Collections.emptyList(), Collections.emptyList(), mapArtists(items));
        };
    }

    private static List<Track> mapTracks(List<YtMusicSearchResponse.Item> items) {
        return items.stream()
                .filter(item -> "song".equals(item.getType()))
                .map(YtMusicSearchResponseMapper::mapTrack)
                .toList();
    }

    private static List<Album> mapAlbums(List<YtMusicSearchResponse.Item> items) {
        return items.stream()
                .filter(item -> "album".equals(item.getType()))
                .map(YtMusicSearchResponseMapper::mapAlbum)
                .toList();
    }

    private static List<Artist> mapArtists(List<YtMusicSearchResponse.Item> items) {
        return items.stream()
                .filter(item -> "artist".equals(item.getType()))
                .map(YtMusicSearchResponseMapper::mapArtist)
                .toList();
    }

    private static Track mapTrack(YtMusicSearchResponse.Item item) {
        return new Track(
                orEmpty(item.getVideoId()),
                orEmpty(item.getThumbnailUrl()),
                "", // the adapter deliberately exposes no streaming path; unread by the client
                orEmpty(item.getTitle()),
                mapArtistNames(item.getArtists()),
                item.getAlbum() != null ? orEmpty(item.getAlbum().getBrowseId()) : "",
                0 // song search items carry no year; Track.year has no reader in the client
        );
    }

    private static Album mapAlbum(YtMusicSearchResponse.Item item) {
        return new Album(
                orEmpty(item.getBrowseId()),
                orEmpty(item.getThumbnailUrl()),
                orEmpty(item.getTitle()),
                mapArtistNames(item.getArtists()),
                item.getYear() != null ? item.getYear() : 0
        );
    }

    private static Artist mapArtist(YtMusicSearchResponse.Item item) {
        return new Artist(
                orEmpty(item.getBrowseId()),
                orEmpty(item.getThumbnailUrl()),
                orEmpty(item.getTitle())
        );
    }

    private static List<String> mapArtistNames(List<YtMusicSearchResponse.ArtistRef> artists) {
        if (artists == null) {
            return Collections.emptyList();
        }
        return artists.stream()
                .map(YtMusicSearchResponse.ArtistRef::getName)
                .filter(Objects::nonNull)
                .toList();
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}

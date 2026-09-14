package com.catacomb5099.naviseerr.services.ytmusic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lombok-friendly models for the ytmusic-adapter's three "what is this id?" endpoints:
 * {@code GET /v1/songs/{videoId}}, {@code GET /v1/albums/{browseId}} and
 * {@code GET /v1/playlists/{playlistId}}.
 *
 * <p>Same conventions as {@link YtMusicSearchResponse}: no Pydantic aliases, so every field name is
 * the literal JSON key, and optional fields arrive as explicit nulls.
 *
 * <p>{@link Collection} covers BOTH the album and the playlist response, which differ in only two
 * keys — an album's id is {@code browseId} and its artists a list, a playlist's id is {@code id} and
 * its single author an object. Declaring all four and letting {@code ignoreUnknown} drop whichever
 * pair is absent is cheaper than two near-identical classes and a switch at the use site. Fields the
 * download pipeline does not read (durations, thumbnails, descriptions, an album's other-versions
 * buckets) are deliberately left off rather than mirrored.
 */
public final class YtMusicDetailResponse {

    private YtMusicDetailResponse() {}

    /** {@code GET /v1/songs/{videoId}}. Metadata only — the adapter exposes no streaming data. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Song {
        private String videoId;
        private String title;
        /** A single string here, unlike the {@code artists[]} of a track inside a collection. */
        private String author;
    }

    /** {@code GET /v1/albums/{browseId}} and {@code GET /v1/playlists/{playlistId}}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Collection {
        /** Albums. */
        private String browseId;
        /** Playlists. */
        private String id;
        private String title;
        /** Albums only; a playlist has no year. */
        private Integer year;
        /** Albums. */
        private List<YtMusicSearchResponse.ArtistRef> artists;
        /** Playlists. */
        private YtMusicSearchResponse.ArtistRef author;
        private List<Track> tracks;
    }

    /** One entry of a collection's {@code tracks[]}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Track {
        private String videoId;
        private String title;
        private List<YtMusicSearchResponse.ArtistRef> artists;
        /**
         * Playlists can carry an unavailable track (region-blocked, deleted). Null on album
         * responses, so only an explicit {@code false} means "do not try this one".
         */
        private Boolean isAvailable;
    }
}

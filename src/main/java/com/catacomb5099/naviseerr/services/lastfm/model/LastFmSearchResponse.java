package com.catacomb5099.naviseerr.services.lastfm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Lombok-friendly model for Last.fm album search responses.
 *
 * Handles both shapes:
 * - results.album : [ ... ]
 * - results.albummatches.album : { "album": [ ... ] }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class LastFmSearchResponse {

    @JsonProperty("results")
    private Results results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Results {

        @JsonProperty("opensearch:Query")
        private OpensearchQuery opensearchQuery;

        @JsonProperty("opensearch:totalResults")
        private String totalResults;

        @JsonProperty("opensearch:startIndex")
        private String startIndex;

        @JsonProperty("opensearch:itemsPerPage")
        private String itemsPerPage;

        // Some responses place albums directly under results.album
        @JsonProperty("album")
        private List<Album> album;

        // Other responses place albums under results.albummatches.album
        @JsonProperty("albummatches")
        private AlbumMatches albummatches;

        // Artist search results (results.artistmatches.artist)
        @JsonProperty("artistmatches")
        private ArtistMatches artistmatches;

        // Track search results (results.trackmatches.track)
        @JsonProperty("trackmatches")
        private TrackMatches trackmatches;

        @JsonProperty("@attr")
        private Attr attr;

        /**
         * Return whichever album list is present (never null).
         */
        @JsonIgnore
        public List<Album> getAlbumList() {
            if (album != null && !album.isEmpty()) return album;
            if (albummatches != null && albummatches.getAlbum() != null) return albummatches.getAlbum();
            return Collections.emptyList();
        }

        /**
         * Return whichever artist list is present (never null).
         */
        @JsonIgnore
        public List<Artist> getArtistList() {
            if (artistmatches != null && artistmatches.getArtist() != null) return artistmatches.getArtist();
            return Collections.emptyList();
        }

        /**
         * Return whichever track list is present (never null).
         */
        @JsonIgnore
        public List<Track> getTrackList() {
            if (trackmatches != null && trackmatches.getTrack() != null) return trackmatches.getTrack();
            return Collections.emptyList();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpensearchQuery {
        @JsonProperty("#text")
        private String text;
        private String role;
        private String searchTerms;
        private String startPage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlbumMatches {
        @JsonProperty("album")
        private List<Album> album;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArtistMatches {
        @JsonProperty("artist")
        private List<Artist> artist;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TrackMatches {
        @JsonProperty("track")
        private List<Track> track;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Album {
        private String name;
        private String artist;
        private String url;

        @JsonProperty("image")
        private List<Image> images;

        private String streamable;
        private String mbid;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Artist {
        private String name;
        private String listeners;
        private String mbid;
        private String url;
        private String streamable;

        @JsonProperty("image")
        private List<Image> images;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Track {
        private String name;
        private String artist;
        private String url;
        private String streamable;
        private String listeners;

        @JsonProperty("image")
        private List<Image> image;

        private String mbid;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Image {
        @JsonProperty("#text")
        private String url;
        private String size;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attr {
        @JsonProperty("for")
        private String forQuery;
    }
}


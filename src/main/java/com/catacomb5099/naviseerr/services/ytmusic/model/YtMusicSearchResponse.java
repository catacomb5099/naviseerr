package com.catacomb5099.naviseerr.services.ytmusic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lombok-friendly model for the ytmusic-adapter's {@code GET /v1/search*} responses.
 *
 * The adapter emits no Pydantic aliases and no {@code exclude_none}, so every field name
 * below is the literal JSON key, and every optional field is present as an explicit
 * {@code null} rather than omitted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class YtMusicSearchResponse {

    private String query;
    private String type;
    private int count;
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        // Verbatim ytmusicapi resultType: "song", "album", "artist", "playlist", "video", ...
        private String type;
        private String videoId;
        private String browseId;
        private String playlistId;
        private String title;
        private List<ArtistRef> artists;
        private AlbumRef album;
        private Integer durationSeconds;
        private String thumbnailUrl;
        private Boolean explicit;
        private Integer year;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArtistRef {
        private String name;
        private String channelId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlbumRef {
        private String name;
        private String browseId;
    }
}

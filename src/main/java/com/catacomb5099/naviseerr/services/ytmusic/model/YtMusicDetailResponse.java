package com.catacomb5099.naviseerr.services.ytmusic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lombok-friendly models for the ytmusic-adapter's five "what is this id?" endpoints:
 * {@code GET /v1/songs/{videoId}}, {@code GET /v1/songs/{videoId}/details},
 * {@code GET /v1/albums/{browseId}}, {@code GET /v1/playlists/{playlistId}} and
 * {@code GET /v1/artists/{channelId}}.
 *
 * <p>Same conventions as {@link YtMusicSearchResponse}: no Pydantic aliases, so every field name is
 * the literal JSON key, and optional fields arrive as explicit nulls.
 *
 * <p>{@link Collection} covers BOTH the album and the playlist response, which differ in only two
 * keys — an album's id is {@code browseId} and its artists a list, a playlist's id is {@code id} and
 * its single author an object. Declaring all four and letting {@code ignoreUnknown} drop whichever
 * pair is absent is cheaper than two near-identical classes and a switch at the use site. Fields the
 * download pipeline does not read (descriptions, an album's other-versions buckets) are deliberately
 * left off rather than mirrored.
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
        /** The uploader's channel, which YouTube Music resolves to the same artist page as the browse id. */
        private String channelId;
        /** The adapter's own name for a song's duration; collections call the same thing {@code durationSeconds}. */
        private Integer lengthSeconds;
        private String thumbnailUrl;
    }

    /**
     * {@code GET /v1/songs/{videoId}/details} -- the song page. The adapter stitches this from up to
     * four YouTube Music calls and degrades honestly: an official-video id has no album, year,
     * explicit flag or credits anywhere upstream, so those arrive null / empty rather than as an error.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SongDetails {
        private String videoId;
        private String title;
        private List<YtMusicSearchResponse.ArtistRef> artists;
        private YtMusicSearchResponse.AlbumRef album;
        private Integer durationSeconds;
        private Integer year;
        private Long viewCount;
        private Boolean explicit;
        private String thumbnailUrl;
        private List<Credit> credits;
    }

    /** One block of YouTube Music's "Song credits" panel: {@code role} is its localized heading. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Credit {
        private String role;
        private List<String> names;
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
        private String thumbnailUrl;
        private List<Track> tracks;
    }

    /**
     * One entry of a collection's {@code tracks[]}. Carries no thumbnail of its own — the adapter's
     * track shape has none — which is why {@code YtMusicService} has to decide where a track's
     * artwork comes from.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Track {
        private String videoId;
        private String title;
        private List<YtMusicSearchResponse.ArtistRef> artists;
        private Integer durationSeconds;
        /**
         * YouTube's own wording ("28M plays"). Present on album tracks, null on playlist and top-song
         * rows. Passed through, never parsed: the adapter warns the figure is lossy upstream.
         */
        private String views;
        /**
         * Playlists can carry an unavailable track (region-blocked, deleted). Null on album
         * responses, so only an explicit {@code false} means "do not try this one".
         */
        private Boolean isAvailable;
    }

    /**
     * {@code GET /v1/artists/{channelId}}. {@code topSongs} reuses {@link Track} — the adapter's
     * shape is the same {@code TrackDto}, plus an {@code albumName} nobody here reads (it carries no
     * browseId, so it cannot become a link). {@code videos}, {@code monthlyListeners} and
     * {@code views} are not mirrored: the artist page has no place for them.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Artist {
        private String channelId;
        private String name;
        private String description;
        /** Already worded by YouTube ("498K"); passed through, never parsed. */
        private String subscribers;
        private String thumbnailUrl;
        private List<Track> topSongs;
        private List<AlbumStub> albums;
        private List<AlbumStub> singles;
        private List<RelatedArtist> related;
    }

    /** One entry of an artist's {@code albums[]} or {@code singles[]}. Carries no artists of its own. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlbumStub {
        private String browseId;
        private String title;
        private Integer year;
        private String thumbnailUrl;
    }

    /** One entry of an artist's {@code related[]}. No thumbnail yet: ytmusicapi returns one, the adapter drops it. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelatedArtist {
        private String browseId;
        private String title;
    }
}

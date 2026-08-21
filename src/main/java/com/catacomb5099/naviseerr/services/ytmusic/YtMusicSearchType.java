package com.catacomb5099.naviseerr.services.ytmusic;

/**
 * The ytmusic-adapter's typed search sugar routes ({@code GET /v1/search/<pathSegment>}).
 * Mirrors {@link com.catacomb5099.naviseerr.util.LastFMAPIMethod}'s role for Last.fm.
 */
public enum YtMusicSearchType {
    SONGS("songs"),
    ALBUMS("albums"),
    ARTISTS("artists");

    private final String pathSegment;

    YtMusicSearchType(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public String getPathSegment() {
        return pathSegment;
    }
}

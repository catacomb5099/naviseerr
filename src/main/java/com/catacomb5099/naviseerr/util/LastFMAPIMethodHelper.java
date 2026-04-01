package com.catacomb5099.naviseerr.util;

import org.springframework.stereotype.Component;

@Component
public class LastFMAPIMethodHelper {
    public String getAPIMethodSpecificParam(LastFMAPIMethod apiMethod) {
        return switch (apiMethod) {
            case ALBUM_SEARCH -> "album";
            case ARTIST_SEARCH -> "artist";
            case TRACK_SEARCH -> "track";
        };
    }

    public String getRelevantMethodHeaderValue(LastFMAPIMethod apiMethod) {
        return switch (apiMethod) {
            case ALBUM_SEARCH -> "album.search";
            case ARTIST_SEARCH -> "artist.search";
            case TRACK_SEARCH -> "track.search";
        };
    }
}

package com.catacomb5099.naviseerr.schema.response;

import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SearchResponse {
    @Nullable
    List<Track> tracks;
    @Nullable
    List<Album> albums;
    @Nullable
    List<Artist> artists;
    @Nullable
    List<Playlist> playlists;
    /**
     * General search only: the parts that failed and came back empty, any of {@code mixed},
     * {@code albums}, {@code artists}, {@code playlists}, so the client can tell "none found" from
     * "could not ask". Empty when all answered; null on the category routes.
     */
    @Nullable
    List<String> unavailable;

    public SearchResponse(List<Track> tracks, List<Album> albums, List<Artist> artists, List<Playlist> playlists) {
        this(tracks, albums, artists, playlists, null);
    }
}


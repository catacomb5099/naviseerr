package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One of your playlists as the web app shows it. {@code GET /playlists} leaves {@code tracks} out;
 * {@code GET /playlists/{id}} has them, in the {@link CollectionView} track shape the album page uses.
 *
 * @param coverUrl     the picture you chose, a path on this server; null = draw the mosaic.
 * @param mosaic       the first four different song pictures, in playlist order, for the 2x2 cover.
 * @param downloadedAt when "Create" was pressed; null while it is a draft.
 */
public record PlaylistView(UUID id, String title, String description, int trackCount, String coverUrl,
                           List<String> mosaic, Instant downloadedAt,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<CollectionView.CollectionTrackView> tracks) {

    static PlaylistView summary(PlaylistRepository.Playlist playlist) {
        return new PlaylistView(playlist.id(), playlist.title(), playlist.description(), playlist.songs().size(),
                playlist.coverUrl(), mosaic(playlist.songs()), playlist.downloadedAt(), null);
    }

    static PlaylistView detail(PlaylistRepository.Playlist playlist) {
        PlaylistView summary = summary(playlist);
        return new PlaylistView(summary.id, summary.title, summary.description, summary.trackCount, summary.coverUrl,
                summary.mosaic, summary.downloadedAt, CollectionView.tracksOf(playlist.asCollection()));
    }

    /** Spotify's and YouTube Music's no-picture cover: four different album pictures, in playlist order. */
    static List<String> mosaic(List<YoutubeSongInfo> songs) {
        return songs.stream().map(YoutubeSongInfo::imageUrl).filter(Objects::nonNull).distinct().limit(4).toList();
    }
}

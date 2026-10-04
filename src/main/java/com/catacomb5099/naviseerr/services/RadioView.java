package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;

import java.util.List;
import java.util.UUID;

/**
 * A saved radio as the client shows it: the same header-plus-tracks a {@link CollectionView} has,
 * without the album-only {@code year} and the download {@code type}.
 *
 * @param id      the radio's own id, what {@code GET /radios/{id}} takes. Not the seed's.
 * @param name    "Billie Jean radio": the seed's title plus the word, or just "Radio".
 * @param artists the seed's artists (a playlist's author), for a "based on" line.
 * @param iconURL the seed's artwork.
 */
public record RadioView(UUID id, String name, List<String> artists, String iconURL, int trackCount,
                        List<CollectionView.CollectionTrackView> tracks) {

    static RadioView from(UUID id, YoutubeCollectionInfo radio) {
        List<CollectionView.CollectionTrackView> tracks = CollectionView.tracksOf(radio);
        return new RadioView(id, radio.name(), radio.authorNames(), radio.imageUrl(), tracks.size(), tracks);
    }
}

package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.download.DownloadType;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;

import java.util.List;
import java.util.stream.IntStream;

/**
 * An album or playlist as the client browses it before deciding to download: the header plus every
 * available track in order. Built from {@link YoutubeCollectionInfo}, so the tracks here are exactly
 * the tracks {@code POST /download/collection/{id}} would create task rows for.
 *
 * @param iconURL    capital {@code URL}, matching {@code Track}/{@code Album} on the search contract.
 * @param year       albums only; null for a playlist or when the adapter's year is not numeric.
 * @param trackCount {@code tracks.size()} -- the available tracks, not YouTube's advertised count.
 */
public record CollectionView(String id, DownloadType type, String name, List<String> artists,
                             String iconURL, Integer year, int trackCount,
                             List<CollectionTrackView> tracks) {

    /** @param position 1-based track order, the same {@code position} the download's task rows get. */
    public record CollectionTrackView(String id, String name, List<String> artists, String iconURL,
                                      Integer durationSeconds, int position) {
    }

    static CollectionView from(YoutubeCollectionInfo info, DownloadType type) {
        List<YoutubeSongInfo> songs = info.songs();
        List<CollectionTrackView> tracks = IntStream.range(0, songs.size())
                .mapToObj(i -> new CollectionTrackView(songs.get(i).id(), songs.get(i).name(),
                        songs.get(i).authorNames(), songs.get(i).imageUrl(),
                        songs.get(i).durationSeconds(), i + 1))
                .toList();
        return new CollectionView(info.id(), type, info.name(), info.authorNames(), info.imageUrl(),
                parseYear(info.year()), tracks.size(), tracks);
    }

    private static Integer parseYear(String year) {
        if (year == null) {
            return null;
        }
        try {
            return Integer.valueOf(year.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

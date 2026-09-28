package com.catacomb5099.naviseerr.curator;

import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * One suggested playlist as the client shows it: the header and every song in the order the curator
 * picked them. Field names follow the search contract ({@code id}, {@code name}, {@code artists},
 * {@code iconURL}) so the client's song rows and info pop-up work unchanged; {@code tier} and
 * {@code reason} are the curator's own words for why a song is in.
 *
 * @param category    the curator's category key, e.g. {@code 80s-indie-pop}; also the id to use for
 *                    {@code GET /suggested-playlists/{category}}.
 * @param filters     the Discogs filters behind the category ({@code year}, {@code genre}, {@code style}),
 *                    for a one-line description. Never null, may be empty.
 * @param editionDate {@code YYYY-MM-DD}, the day this edition was built.
 */
public record SuggestedPlaylistView(String category, String title, Map<String, String> filters,
                                    String editionDate, int trackCount, List<SuggestedTrackView> tracks) {

    /**
     * @param iconURL  YouTube's predictable thumbnail for the id -- the curator stores no artwork, and
     *                 the same fallback playlists use is a picture rather than a blank.
     * @param position 1-based, the curator's order.
     */
    public record SuggestedTrackView(String id, String name, List<String> artists, String album,
                                     Integer albumYear, Long popularity, String tier, String reason,
                                     String iconURL, int position) {
    }

    static SuggestedPlaylistView from(String category, CuratorEdition edition) {
        List<CuratorTrack> songs = edition.tracks().stream().filter(t -> t.videoId() != null).toList();
        List<SuggestedTrackView> tracks = IntStream.range(0, songs.size())
                .mapToObj(i -> {
                    CuratorTrack t = songs.get(i);
                    return new SuggestedTrackView(t.videoId(), t.title(), t.artists(), t.album(), t.albumYear(),
                            t.popularity(), t.tier(), t.reason(), YtMusicService.fallbackThumbnail(t.videoId()),
                            i + 1);
                })
                .toList();
        Map<String, String> filters = edition.category().entrySet().stream()
                .filter(e -> !"title".equals(e.getKey()) && e.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, java.util.LinkedHashMap::new));
        return new SuggestedPlaylistView(category, edition.title(), filters, edition.editionDate(),
                tracks.size(), tracks);
    }
}

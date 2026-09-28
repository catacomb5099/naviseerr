package com.catacomb5099.naviseerr.services.ytmusic.model;

import java.util.List;

/**
 * One track, as ytmusic-adapter describes it. The provider-facing answer to "what is this id?",
 * reduced to what the download pipeline stores and searches with: an id, a name, the artists that
 * name is disambiguated by, the artwork and length the download view shows, and the play count
 * the collection view shows.
 *
 * <p>Populated from two differently-shaped adapter responses — {@code GET /v1/songs/{videoId}},
 * whose {@code author} is a single string, and the {@code tracks[]} of an album or playlist, whose
 * {@code artists[]} is a list. {@link #authorNames()} is a list so both flatten into it without the
 * caller having to know which shape it came from.
 *
 * @param authorNames     never null; empty when the provider named no artist.
 * @param imageUrl        nullable. A track inside an album inherits the album's artwork; a track
 *                        inside a playlist has none of its own and falls back to YouTube's
 *                        predictable thumbnail URL for its videoId.
 * @param durationSeconds nullable; the adapter does not always know.
 * @param plays           nullable. YouTube's own wording ("28M plays"), carried only by an album's
 *                        tracks. A single song's details route ({@code GET /v1/songs/{id}/details},
 *                        read by {@code SongInfoView}) carries an exact integer {@code viewCount}
 *                        instead, which is why this field is not set for a single-song lookup;
 *                        artist top songs, playlist tracks and search results carry nothing.
 */
public record YoutubeSongInfo(String id, List<String> authorNames, String name, String imageUrl,
                              Integer durationSeconds, String plays) {

    /** Every source but an album has no worded play count to give, so they keep the shorter shape. */
    public YoutubeSongInfo(String id, List<String> authorNames, String name, String imageUrl,
                           Integer durationSeconds) {
        this(id, authorNames, name, imageUrl, durationSeconds, null);
    }
}

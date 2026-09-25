package com.catacomb5099.naviseerr.services.ytmusic.model;

import java.util.List;

/**
 * One track, as ytmusic-adapter describes it. The provider-facing answer to "what is this id?",
 * reduced to what the download pipeline stores and searches with: an id, a name, the artists that
 * name is disambiguated by, and the artwork and length the download view shows.
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
 */
public record YoutubeSongInfo(String id, List<String> authorNames, String name, String imageUrl,
                              Integer durationSeconds) {
}

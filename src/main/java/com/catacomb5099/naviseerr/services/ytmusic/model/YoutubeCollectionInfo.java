package com.catacomb5099.naviseerr.services.ytmusic.model;

import java.util.List;

/**
 * An album or a playlist, as ytmusic-adapter describes it: a title, artwork, and a track list.
 *
 * <p>One type for both on purpose. They are two adapter endpoints with two response shapes, but
 * what the download pipeline wants out of either is identical — a display title and picture for the
 * download row and a list of songs to create task rows from. Splitting them would mean two types, two
 * mappers and a switch at every use site, for a few nullable fields' worth of difference ({@code year}
 * and {@code type} here, {@code trackNumber} on each song: only albums have them). If they diverge
 * further than nullable fields, split then.
 *
 * @param year     null for a playlist — the adapter's {@code PlaylistDetail} has no year, only albums
 *                 do. Stored on the album's {@code media_items} row; identifies a specific pressing.
 * @param authorIds never null; the channel id behind each {@code authorNames} entry, {@code ""}
 *                 where unknown, index-aligned like {@link YoutubeSongInfo#authorIds()}. For a
 *                 playlist this is its author's channel, which need not be an artist at all.
 * @param imageUrl nullable; the collection's own artwork.
 * @param songs    never null; may be empty, which the caller must treat as "nothing to download"
 *                 rather than as a failure.
 * @param playlistId nullable; the playlist YouTube Music plays this as, for a "play it there" link:
 *                 an album's {@code OLAK5uy_...} (the adapter's {@code audioPlaylistId}), a
 *                 playlist's own bare id. Null for anything built here rather than read from YouTube.
 * @param type     albums only: {@code "Album"}, {@code "EP"} or {@code "Single"}, as YouTube words it.
 * @param trackCount nullable; YouTube's own count, which can exceed {@code songs.size()} (unavailable
 *                 tracks are dropped from {@code songs}, and a playlist can be longer than one page).
 */
public record YoutubeCollectionInfo(String id, List<YoutubeSongInfo> songs, Integer year,
                                    String name, List<String> authorNames, List<String> authorIds,
                                    String imageUrl, String playlistId, String type, Integer trackCount) {

    /** Not read from YouTube: nothing to play it as there, no type, no count of YouTube's own. */
    public YoutubeCollectionInfo(String id, List<YoutubeSongInfo> songs, Integer year,
                                 String name, List<String> authorNames, List<String> authorIds,
                                 String imageUrl) {
        this(id, songs, year, name, authorNames, authorIds, imageUrl, null, null, null);
    }

    /** Names without ids, for a caller that has none to give. */
    public YoutubeCollectionInfo(String id, List<YoutubeSongInfo> songs, Integer year,
                                 String name, List<String> authorNames, String imageUrl) {
        this(id, songs, year, name, authorNames, List.of(), imageUrl);
    }
}

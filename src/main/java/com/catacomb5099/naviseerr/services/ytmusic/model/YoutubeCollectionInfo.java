package com.catacomb5099.naviseerr.services.ytmusic.model;

import java.util.List;

/**
 * An album or a playlist, as ytmusic-adapter describes it: a title and a track list.
 *
 * <p>One type for both on purpose. They are two adapter endpoints with two response shapes, but
 * what the download pipeline wants out of either is identical — a display title for the download
 * row and a list of songs to create task rows from. Splitting them would mean two types, two
 * mappers and a switch at every use site, for one nullable field's worth of difference. If they do
 * diverge later (track numbers, per-track availability), split then.
 *
 * @param year  null for a playlist — the adapter's {@code PlaylistDetail} has no year, only albums
 *              do. Carried because it costs nothing and identifies a specific pressing.
 * @param songs never null; may be empty, which the caller must treat as "nothing to download"
 *              rather than as a failure.
 */
public record YoutubeCollectionInfo(String id, List<YoutubeSongInfo> songs, String year,
                                    String name, List<String> authorNames) {
}

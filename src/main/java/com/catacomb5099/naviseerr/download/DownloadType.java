package com.catacomb5099.naviseerr.download;

/**
 * What a single {@code downloads} row was a request for. Stored in {@code downloads.download_type}
 * and enforced there by a CHECK constraint.
 *
 * <p>An enum rather than the free string the wire carries, for two reasons. It makes the metadata
 * fetch in {@link DownloadTaskRunner} an exhaustive switch with no fallthrough branch to return null
 * from, and it makes the controller's validation free: Spring rejects an unparseable request
 * parameter with a 400 before any code here runs.
 *
 * <p>{@link #ALBUM} and {@link #PLAYLIST} are kept apart rather than folded into one COLLECTION
 * value because they are two different calls to two different ytmusic-adapter endpoints, and the id
 * spaces do not overlap (an album is a {@code MPREb_} browse id, a playlist a bare {@code PL...} id
 * or its {@code VL}-prefixed browse form). Nothing downstream has to tell them apart once the track list is in hand.
 */
public enum DownloadType {
    SONG,
    ALBUM,
    PLAYLIST;

    /** True when this type resolves to a track list rather than a single track. */
    public boolean isCollection() {
        return this != SONG;
    }
}

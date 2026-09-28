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
    PLAYLIST,
    /**
     * One edition of a suggested playlist from the playlist curator. The id is the curator's category
     * key ({@code 80s-indie-pop}), not a YouTube id; the track list is the edition's songs, fetched from
     * the curator at admission the way an album's is fetched from ytmusic-adapter. Filed into the
     * library and written out as a playlist file like a {@link #PLAYLIST}.
     */
    CURATED;

    /** True when this type resolves to a track list rather than a single track. */
    public boolean isCollection() {
        return this != SONG;
    }

    /** True for the kinds that get a playlist file in the library: a YouTube playlist and a curated edition. */
    public boolean isPlaylist() {
        return this == PLAYLIST || this == CURATED;
    }
}

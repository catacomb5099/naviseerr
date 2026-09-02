package com.catacomb5099.naviseerr.schema.request;

import java.util.List;

/**
 * What is known about the track a download is for: its name, and the artists credited on it. This is
 * the metadata the download loop carries between passes -- enough to word a provider query and to
 * score a candidate filename against, and deliberately nothing else. Image URLs and ids live in the
 * {@code songs} row; they are read by the client-facing feed, never by the loop.
 *
 * <p>It lives in {@code schema.request} rather than {@code download} on purpose. {@code services.slskd}
 * has to consume it (it is the input to how a track gets worded for Soulseek), and {@code download}
 * already depends on {@code services.slskd}, so putting it in {@code download} would make that
 * dependency circular at package level.
 *
 * <p>{@code artists} is empty and never null: a caller that does not know the artist is a legitimate
 * caller (the deprecated path route, and every row backfilled by {@code V5__song_metadata.sql}), so a
 * null is normalised here rather than at each of the several places that build one.
 */
public record TrackQuery(String songName, List<String> artists) {

    public TrackQuery(String songName, List<String> artists) {
        this.songName = songName;
        this.artists = artists == null ? List.of() : artists;
    }

    /** For call sites that know a name and nothing else -- the deprecated route and backfilled rows. */
    public TrackQuery(String songName) {
        this(songName, List.of());
    }
}

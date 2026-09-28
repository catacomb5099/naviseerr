package com.catacomb5099.naviseerr.curator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One song of an edition. {@code tier} is why it is there -- {@code top} (one of the most played songs
 * in the pool), {@code mid} (middle of the pack, one per artist and album) or {@code random} (a
 * discovery) -- and {@code reason} is the curator's one-line explanation, e.g. "#23 of 1036 by plays".
 * {@code popularity} is the YouTube Music play count the pick was based on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorTrack(String videoId, String title, List<String> artists, String album, String albumId,
                           Integer albumYear, Long popularity, String tier, String reason) {

    public List<String> artists() {
        return artists == null ? List.of() : artists;
    }
}

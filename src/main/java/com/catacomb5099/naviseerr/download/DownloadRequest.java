package com.catacomb5099.naviseerr.download;

import java.util.List;

/**
 * Body for {@code POST /download}. {@code songName} is required and rejected with {@code 400} when
 * null or blank; a null {@code artists} is a legitimate caller that simply does not know the
 * artist, not a bad request, so it is normalised to an empty list here rather than in the
 * controller or service.
 */
public record DownloadRequest(String songName, List<String> artists, String imageUrl) {

    public DownloadRequest(String songName, List<String> artists, String imageUrl) {
        this.songName = songName;
        this.artists = artists == null ? List.of() : artists;
        this.imageUrl = imageUrl;
    }
}

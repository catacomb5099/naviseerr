package com.catacomb5099.naviseerr.mediaserver;

/** The media server will never take a song into this playlist: it is gone, or it cannot be changed. Not worth retrying. */
public class PlaylistProblem extends MediaServerException {

    public enum Reason { PLAYLIST_GONE, NOT_EDITABLE }

    public final Reason reason;

    public PlaylistProblem(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}

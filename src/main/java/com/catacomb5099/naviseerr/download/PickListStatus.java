package com.catacomb5099.naviseerr.download;

/** Whether a candidates list has anything to show; with NONE the view carries a {@code reason}. */
public enum PickListStatus {
    /** The list is non-empty. */
    READY,
    /** Empty because the search is still running; the client polls again. */
    SEARCHING,
    /** Empty and will stay so until the song is retried. */
    NONE
}

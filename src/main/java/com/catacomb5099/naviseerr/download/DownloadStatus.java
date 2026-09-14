package com.catacomb5099.naviseerr.download;

public enum DownloadStatus {
    PENDING,
    IN_PROGRESS,
    FAILED,
    SUCCEEDED,
    /**
     * Only reachable for a collection: at least one song succeeded and at least one failed. A
     * single-song download has no partial outcome to report, so it never takes this value.
     */
    PARTIAL_SUCCESS
}

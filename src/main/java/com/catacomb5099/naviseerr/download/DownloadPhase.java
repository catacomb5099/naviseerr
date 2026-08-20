package com.catacomb5099.naviseerr.download;

/**
 * The four steps a download moves through. Each is exactly one slskd call, so a crash costs at
 * most one call's worth of work.
 */
public enum DownloadPhase {
    SEARCH_INIT,
    SEARCH_POLL,
    DOWNLOAD_INIT,
    DOWNLOAD_POLL
}

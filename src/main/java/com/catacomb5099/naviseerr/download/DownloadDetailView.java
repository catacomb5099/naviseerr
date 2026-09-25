package com.catacomb5099.naviseerr.download;

import java.util.List;

/**
 * Response to {@code GET /downloads/{id}}: the same card the feed shows, plus every song under it
 * in track order. For a single-song download {@code songs} has exactly one entry — the same shape,
 * no special case.
 */
public record DownloadDetailView(ActiveDownloadView download, List<DownloadSongView> songs) {
}

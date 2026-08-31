package com.catacomb5099.naviseerr.download;

import java.util.List;

/**
 * Response to {@code GET /downloads/all}. Ignores both the terminal filter and the retention
 * window, like {@link DownloadsByIdResponse} - the difference is this endpoint is paginated rather
 * than scoped to a specific set of ids.
 *
 * <p>{@code totalPages} is derived from the raw row count at query time, since only the query
 * knows the total and only the request knows the page size it should be divided by.
 */
public record AllDownloadsResponse(List<ActiveDownloadView> downloads, Integer totalPages) {
}

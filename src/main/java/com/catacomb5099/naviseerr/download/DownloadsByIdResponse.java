package com.catacomb5099.naviseerr.download;

import java.util.List;

/**
 * Response to {@code GET /downloads?ids=}. Ids with no matching download are simply absent, so the
 * caller compares what it asked for against what came back.
 */
public record DownloadsByIdResponse(List<ActiveDownloadView> downloads) {
}

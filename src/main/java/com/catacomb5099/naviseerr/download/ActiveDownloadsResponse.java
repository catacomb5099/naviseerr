package com.catacomb5099.naviseerr.download;

import java.util.List;

/**
 * {@code pollIntervalMs} is the server's own {@code download-task.download-poll-interval-ms}, so a
 * client can size its CSS transition duration to match the data's real resolution instead of
 * guessing a constant.
 */
public record ActiveDownloadsResponse(long pollIntervalMs, List<ActiveDownloadView> downloads) {
}

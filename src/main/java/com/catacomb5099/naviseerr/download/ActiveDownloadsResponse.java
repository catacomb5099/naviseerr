package com.catacomb5099.naviseerr.download;

import java.util.List;

/**
 * The download feed. {@code downloads} is ordered most-recently-updated first, so a client with no
 * stored history of its own still renders the list in the right order on a cold load.
 *
 * @param pollIntervalMs      the server's own {@code download-task.download-poll-interval-ms}, so a
 *                            client can size its CSS transition duration to match the data's real
 *                            resolution instead of guessing a constant.
 * @param terminalRetentionMs how long a finished download keeps appearing here. Published for the same
 *                            reason: the client's auto-dismiss delay and its memory of what the user
 *                            already dismissed both have to outlive this window, and hardcoding a guess
 *                            at it is how a dismissed card comes back.
 */
public record ActiveDownloadsResponse(long pollIntervalMs, long terminalRetentionMs,
                                      List<ActiveDownloadView> downloads) {
}

package com.catacomb5099.naviseerr.download;

/**
 * What the state machine decided. Only {@code Advance} runs again immediately; every non-progress
 * outcome is {@code Continue} so it is always rate-limited by the phase's poll interval. Retrying a
 * failed transfer with no delay would hammer slskd.
 */
public sealed interface DownloadDecision {

    /** Genuine phase transition — re-run on the next pass, no delay. */
    record Advance(DownloadTask next) implements DownloadDecision {}

    /** Re-poll, retry the same candidate, or move to the next candidate — re-run after a delay. */
    record Continue(DownloadTask next) implements DownloadDecision {}

    /**
     * Done. Write the download's status and mark the task terminal. {@code message} is the reason,
     * persisted as {@code failure_reason} so a self-hoster can see why. The task row is RETAINED, not
     * deleted — history a self-hoster needs, and free at read time because of a partial index (Task 3).
     */
    record Terminal(DownloadStatus status, String message) implements DownloadDecision {}
}

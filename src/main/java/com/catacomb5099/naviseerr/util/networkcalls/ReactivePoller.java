package com.catacomb5099.naviseerr.util.networkcalls;

import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;

/**
 * Shared retry policy for outbound provider calls.
 *
 * <p>This used to also hold the slskd poll-and-failover helpers ({@code pollUntil},
 * {@code pollUntilAny}). Those were deleted with the durable download state machine, which drives
 * download polling from the {@code download_tasks} table instead of holding a worker on a long
 * in-process poll. What is left is the part that was never download-specific: a backoff spec for
 * provider clients that retry a transient failure in place.
 *
 * <p>Search retry lives here rather than in the download state machine on purpose. A search is not a
 * download, so it would need either its own task type in a machine built for downloads, or a wider
 * remit for that machine covering both - and the second loosens its focus for no gain.
 */
public class ReactivePoller {

    private ReactivePoller() {
    }

    /**
     * Exponential backoff with jitter, counting retries across the whole subscription rather than
     * resetting on each new failure ({@code transientErrors(true)}).
     *
     * @param firstBackoff delay before the first retry; each subsequent one doubles
     * @param maxAttempts  number of retries after the initial attempt, so 0 disables retrying
     */
    public static RetryBackoffSpec defaultBackoff(
            Duration firstBackoff,
            int maxAttempts
    ) {
        return Retry.backoff(maxAttempts, firstBackoff)
                .jitter(0.2)
                .transientErrors(true);
    }
}

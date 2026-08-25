package com.catacomb5099.naviseerr.download;

/**
 * What the client renders. The ONLY vocabulary the client knows about a download's position, folding
 * {@code downloads.status} and {@code download_tasks.phase} into one field - see
 * {@link ActiveDownloadRepository#toStage}.
 *
 * <p>Deliberately not {@link DownloadPhase}. That enum is the four working steps of the state machine
 * and says nothing about a download that has not been admitted yet, which is a state the user can
 * watch for a while. Exposing {@code phase} raw would also force this enum's SUCCEEDED/FAILED onto it,
 * and every exhaustive switch in the state machine would gain two cases it can never reach.
 */
public enum DownloadStage {
    /** Accepted, no task row yet. The runner has not admitted it - capacity or its next tick. */
    QUEUED,
    /** Admitted; the search has not been issued yet. */
    STARTING,
    /** Search issued, polling slskd for results. */
    SEARCHING,
    /** Candidates picked; waiting on a transfer slot to enqueue one. */
    READY_TO_DOWNLOAD,
    /** A transfer is live. The only stage with a meaningful {@code progressPercent}. */
    DOWNLOADING,
    SUCCEEDED,
    FAILED
}

package com.catacomb5099.naviseerr.download;

/**
 * Why a download failed, as a closed set. Persisted by NAME into {@code download_tasks.failure_reason}
 * and handed to the client as {@code failureCode}; the client owns the wording.
 *
 * <p>A code, not prose, because the two audiences want different things from the same fact. A
 * self-hoster reading the row wants something greppable and stable; the UI wants a sentence a
 * non-technical user understands, and that sentence should be editable without a server release or a
 * question about rows already written. Rows written before this enum existed hold the old prose, which
 * is why the client falls back to a generic message on any value it does not recognise rather than
 * assuming the set is exhaustive.
 */
public enum DownloadFailureCode {
    /** slskd rejected or errored the search itself. */
    SEARCH_FAILED,
    /**
     * The search could not start because slskd is not logged in to Soulseek (a taken name, a wrong
     * password, a network that blocks it): slskd refused the last {@code POST /searches} with 409.
     * Retried exactly like {@link #SEARCH_FAILED}, since slskd reconnects by itself; this is only the
     * reason given once the retries run out. slskd's own sentence is in {@code last_error}.
     */
    SOULSEEK_OFFLINE,
    /** The search completed and nothing in it was usable. */
    NO_CANDIDATES,
    /** Every candidate was tried to its retry limit. */
    SOURCES_EXHAUSTED,
    /** A phase ran past its budget - search, transfer, or a transfer slskd never showed us. */
    TIMED_OUT,
    /** slskd stopped listing a transfer we had enqueued, past the grace window. */
    TRANSFER_NOT_FOUND,
    /**
     * ytmusic-adapter could not tell us what to download. Set at admission, before any task row
     * exists, so it is the one code that is written to {@code downloads} without a corresponding
     * {@code download_tasks} row -- there is nothing to search for yet.
     *
     * <p>Only for a NON-retryable failure: an id YouTube has no record of. A transient adapter
     * outage leaves the download PENDING for the next pass instead, so a restart of the sidecar
     * does not fail every download requested while it was down.
     */
    METADATA_UNAVAILABLE,
    /**
     * The user stopped it. A cancelled song is a FAILED row carrying this code rather than a phase of
     * its own, so every "is this song finished?" test in the SQL and the client already treats it as
     * finished; the read model counts these rows apart from real failures (songsCancelled) and the
     * client words and colours them differently. Retrying a download resets these rows like any
     * other failure, which is how a cancel is undone.
     */
    CANCELLED
}

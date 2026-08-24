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
    /** The search completed and nothing in it was usable. */
    NO_CANDIDATES,
    /** Every candidate was tried to its retry limit. */
    SOURCES_EXHAUSTED,
    /** A phase ran past its budget - search, transfer, or a transfer slskd never showed us. */
    TIMED_OUT,
    /** slskd stopped listing a transfer we had enqueued, past the grace window. */
    TRANSFER_NOT_FOUND
}

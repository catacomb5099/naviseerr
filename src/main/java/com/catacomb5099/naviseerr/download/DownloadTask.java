package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.request.TrackQuery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-memory carrier for one {@code download_tasks} row. This record IS the durable state, read from
 * and written back to the row on every step. Nothing about a download's position is held between
 * loop passes.
 *
 * <p>{@code query} is the one thing here that is NOT read from {@code download_tasks} itself: the
 * table no longer holds a song name, so {@code CLAIM_DUE_SQL} joins {@code songs} for it. It is also
 * the one thing {@code SAVE_SQL} does not write back -- the loop never rewrites metadata.
 */
public record DownloadTask(
        UUID downloadId,
        TrackQuery query,
        DownloadPhase phase,
        Instant phaseEnteredAt,
        Instant nextAttemptAt,
        String searchId,
        List<DownloadCandidate> candidates,
        int candidateIndex,
        int retryIndex,
        String slskdUsername,
        String slskdFilename,
        String slskdTransferId,
        String lastError,
        BigDecimal progressPercent) {

    /**
     * Not a record component, so the record's arity is unchanged and every positional constructor
     * call site kept compiling when {@code songName} became {@code query}. The slskd search and
     * match paths that used to call this were both converted to take the whole {@code TrackQuery}
     * instead (task 5 of the song-metadata-table plan); no production caller of this accessor
     * remains. It stays for test/debugging convenience -- tests that only care about the name still
     * use it rather than unwrapping {@code query} themselves.
     */
    public String songName() {
        return query.songName();
    }

    /** Convenience constructor for call sites that predate progress tracking: defaults to 0. */
    public DownloadTask(UUID downloadId, TrackQuery query, DownloadPhase phase, Instant phaseEnteredAt,
                        Instant nextAttemptAt, String searchId, List<DownloadCandidate> candidates,
                        int candidateIndex, int retryIndex, String slskdUsername,
                        String slskdFilename, String slskdTransferId, String lastError) {
        this(downloadId, query, phase, phaseEnteredAt, nextAttemptAt, searchId, candidates,
                candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId, lastError,
                BigDecimal.ZERO);
    }

    public static DownloadTask initial(UUID downloadId, TrackQuery query, Instant now) {
        return new DownloadTask(downloadId, query, DownloadPhase.SEARCH_INIT, now, now,
                null, List.of(), 0, 0, null, null, null, null);
    }

    /** Phase change: resets the phase budget. */
    public DownloadTask withPhase(DownloadPhase newPhase, Instant now) {
        return new DownloadTask(downloadId, query, newPhase, now, now, searchId, candidates,
                candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId,
                lastError, progressPercent);
    }

    /** Reschedule within the same phase: preserves the phase budget. */
    public DownloadTask dueAt(Instant next) {
        return new DownloadTask(downloadId, query, phase, phaseEnteredAt, next, searchId,
                candidates, candidateIndex, retryIndex, slskdUsername, slskdFilename,
                slskdTransferId, lastError, progressPercent);
    }

    /** Overwrites progress with a freshly observed value. A null observation leaves it unchanged. */
    public DownloadTask withProgress(BigDecimal observed) {
        return observed == null ? this : new DownloadTask(downloadId, query, phase,
                phaseEnteredAt, nextAttemptAt, searchId, candidates, candidateIndex, retryIndex,
                slskdUsername, slskdFilename, slskdTransferId, lastError, observed);
    }

    /** Zeroes progress. Used when moving to a new candidate or retry attempt of the same one. */
    public DownloadTask withProgressReset() {
        return new DownloadTask(downloadId, query, phase, phaseEnteredAt, nextAttemptAt,
                searchId, candidates, candidateIndex, retryIndex, slskdUsername, slskdFilename,
                slskdTransferId, lastError, BigDecimal.ZERO);
    }

    public DownloadCandidate currentCandidate() {
        return candidates.get(candidateIndex);
    }

    public boolean isPastBudget(Instant now, java.time.Duration budget) {
        return !now.isBefore(phaseEnteredAt.plus(budget));
    }
}

package com.catacomb5099.naviseerr.download;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-memory carrier for one {@code download_tasks} row. This record IS the durable state, read from
 * and written back to the row on every step. Nothing about a download's position is held between
 * loop passes.
 */
public record DownloadTask(
        UUID downloadId,
        String songName,
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

    /** Convenience constructor for call sites that predate progress tracking: defaults to 0. */
    public DownloadTask(UUID downloadId, String songName, DownloadPhase phase, Instant phaseEnteredAt,
                        Instant nextAttemptAt, String searchId, List<DownloadCandidate> candidates,
                        int candidateIndex, int retryIndex, String slskdUsername,
                        String slskdFilename, String slskdTransferId, String lastError) {
        this(downloadId, songName, phase, phaseEnteredAt, nextAttemptAt, searchId, candidates,
                candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId, lastError,
                BigDecimal.ZERO);
    }

    public static DownloadTask initial(UUID downloadId, String songName, Instant now) {
        return new DownloadTask(downloadId, songName, DownloadPhase.SEARCH_INIT, now, now,
                null, List.of(), 0, 0, null, null, null, null);
    }

    /** Phase change: resets the phase budget. */
    public DownloadTask withPhase(DownloadPhase newPhase, Instant now) {
        return new DownloadTask(downloadId, songName, newPhase, now, now, searchId, candidates,
                candidateIndex, retryIndex, slskdUsername, slskdFilename, slskdTransferId,
                lastError, progressPercent);
    }

    /** Reschedule within the same phase: preserves the phase budget. */
    public DownloadTask dueAt(Instant next) {
        return new DownloadTask(downloadId, songName, phase, phaseEnteredAt, next, searchId,
                candidates, candidateIndex, retryIndex, slskdUsername, slskdFilename,
                slskdTransferId, lastError, progressPercent);
    }

    /** Overwrites progress with a freshly observed value. A null observation leaves it unchanged. */
    public DownloadTask withProgress(BigDecimal observed) {
        return observed == null ? this : new DownloadTask(downloadId, songName, phase,
                phaseEnteredAt, nextAttemptAt, searchId, candidates, candidateIndex, retryIndex,
                slskdUsername, slskdFilename, slskdTransferId, lastError, observed);
    }

    /** Zeroes progress. Used when moving to a new candidate or retry attempt of the same one. */
    public DownloadTask withProgressReset() {
        return new DownloadTask(downloadId, songName, phase, phaseEnteredAt, nextAttemptAt,
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

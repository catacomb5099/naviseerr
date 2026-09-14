package com.catacomb5099.naviseerr.download;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-memory carrier for one {@code download_tasks} row — one SONG's pipeline. This record IS the
 * durable state, read from and written back to the row on every step. Nothing about a download's
 * position is held between loop passes.
 *
 * <p>One download has N of these: one for a song request, one per track for an album or playlist.
 * {@link #taskId} is the identity every statement keys on; {@link #downloadId} is the request this
 * song belongs to, and is what the capacity counting and the feed's aggregate group by.
 *
 * <p>Still a record, deliberately. {@link DownloadStateMachine} is a pure function of it and
 * returns new instances rather than mutating one, which a mutable carrier would quietly make
 * optional. {@code @Builder(toBuilder = true)} is what removes the cost that motivated making it a
 * class — threading sixteen positional arguments through every transition.
 */
@Builder(toBuilder = true)
public record DownloadTask(
        UUID taskId,
        UUID downloadId,
        /** The song's own YouTube {@code videoId}. Null for rows created before collections existed. */
        String youtubeId,
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

    /** One song, at the start of its pipeline. The task id is minted here, not by the database. */
    public static DownloadTask initial(UUID downloadId, String youtubeId, String songName, Instant now) {
        return DownloadTask.builder()
                .taskId(UUID.randomUUID())
                .downloadId(downloadId)
                .youtubeId(youtubeId)
                .songName(songName)
                .phase(DownloadPhase.SEARCH_INIT)
                .phaseEnteredAt(now)
                .nextAttemptAt(now)
                .candidates(List.of())
                .progressPercent(BigDecimal.ZERO)
                .build();
    }

    /** Phase change: resets the phase budget. */
    public DownloadTask withPhase(DownloadPhase newPhase, Instant now) {
        return toBuilder().phase(newPhase).phaseEnteredAt(now).nextAttemptAt(now).build();
    }

    /** Reschedule within the same phase: preserves the phase budget. */
    public DownloadTask dueAt(Instant next) {
        return toBuilder().nextAttemptAt(next).build();
    }

    /** Overwrites progress with a freshly observed value. A null observation leaves it unchanged. */
    public DownloadTask withProgress(BigDecimal observed) {
        return observed == null ? this : toBuilder().progressPercent(observed).build();
    }

    /** Zeroes progress. Used when moving to a new candidate or retry attempt of the same one. */
    public DownloadTask withProgressReset() {
        return toBuilder().progressPercent(BigDecimal.ZERO).build();
    }

    public DownloadCandidate currentCandidate() {
        return candidates.get(candidateIndex);
    }

    public boolean isPastBudget(Instant now, java.time.Duration budget) {
        return !now.isBefore(phaseEnteredAt.plus(budget));
    }
}

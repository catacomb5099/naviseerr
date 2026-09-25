package com.catacomb5099.naviseerr.download;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One song of one download, as {@code GET /downloads/{id}} reports it. This is the row a self-hoster
 * opens when a collection says "9 of 12" and they want to know which three, and why.
 *
 * <p>The audience is someone who runs the software and can read a log, so the pipeline's own
 * bookkeeping is on the wire rather than summarised away: which peer and file the current attempt is
 * against, how many candidates the search found and how far down the list this is, and the last
 * error slskd reported. Nothing here is a secret; it is all in {@code download_tasks} already.
 *
 * @param position        1-based track order within the collection. Null for rows admitted before
 *                        order was recorded.
 * @param title           from {@code media_items}; null only for a task admitted before V6.
 * @param stage           the SONG's own stage — for a finished song, its own outcome, whatever the
 *                        collection's is.
 * @param candidateCount  how many usable files the search turned up. 0 in the search stages.
 * @param candidateIndex  0-based; which of those this attempt is on. With {@code retryIndex}, "third
 *                        candidate, second retry".
 * @param slskdUsername   the peer the current (or last) transfer was from.
 * @param slskdFilename   the file, as the peer shares it.
 * @param lastError       slskd's last error text for this song, verbatim. Diagnostic, not for users.
 */
public record DownloadSongView(
        UUID taskId,
        String youtubeId,
        Integer position,
        String title,
        List<String> artists,
        String imageUrl,
        Integer durationSeconds,
        DownloadStage stage,
        BigDecimal progressPercent,
        String failureCode,
        Instant stageEnteredAt,
        Instant updatedAt,
        Instant finishedAt,
        int candidateCount,
        int candidateIndex,
        int retryIndex,
        String slskdUsername,
        String slskdFilename,
        String lastError) {
}

package com.catacomb5099.naviseerr.download;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the download feed, as the client sees it.
 *
 * <p>Neither {@code downloads.status} nor {@code download_tasks.phase} appears here: {@link #stage}
 * folds both into the one vocabulary the client renders, so the client cannot fall out of step with
 * the state machine's internals and there is no combination of the two for it to get wrong.
 *
 * @param artists         always present, never null -- an empty list, not a null, when a song has no
 *                        known artists. Mirrors the promise {@code songs.artists} and
 *                        {@link com.catacomb5099.naviseerr.schema.request.TrackQuery} already make, so
 *                        the client never has to null-check this field on top of everything else it
 *                        already doesn't have to.
 * @param imageUrl        nullable. Null for a download made through the deprecated path-based route
 *                        (which never collects an image), and for any download created before the
 *                        {@code songs} table existed -- the backfill that created its {@code songs}
 *                        row had no image to carry over.
 * @param progressPercent 0-100, meaningful only while {@link #stage} is
 *                        {@link DownloadStage#DOWNLOADING}. Nullable, and a null must never overwrite
 *                        a previously observed value client-side, for the same reason the server never
 *                        writes one: a bar that jumps backwards on a healthy download is the most
 *                        trust-destroying thing this feature can do.
 * @param stageEnteredAt  when the current stage began. What indeterminate stages show elapsed time
 *                        from, so a slow search reads as slow rather than as stuck.
 * @param updatedAt       when the row was last written. The recency sort key, and the only field that
 *                        moves when nothing but progress changes.
 * @param failureCode     a {@link DownloadFailureCode} name, or null. Deliberately a String, not the
 *                        enum: rows written before the enum existed hold free prose, and a read path
 *                        that throws on its own history is worse than one the client can't word.
 */
public record ActiveDownloadView(
        UUID downloadId,
        String songName,
        List<String> artists,
        String imageUrl,
        DownloadStage stage,
        BigDecimal progressPercent,
        Instant stageEnteredAt,
        Instant updatedAt,
        String failureCode) {
}

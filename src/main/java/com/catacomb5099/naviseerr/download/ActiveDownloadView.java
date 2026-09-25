package com.catacomb5099.naviseerr.download;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the download feed, as the client sees it: one DOWNLOAD, whether that is a song or a
 * five-hundred-track playlist. The per-song breakdown is {@code GET /downloads/{id}}
 * ({@link DownloadDetailView}), not this.
 *
 * <p>Neither {@code downloads.status} nor {@code download_tasks.phase} appears here: {@link #stage}
 * folds both into the one vocabulary the client renders, so the client cannot fall out of step with
 * the state machine's internals and there is no combination of the two for it to get wrong.
 *
 * @param youtubeId       what was requested, as YouTube Music identifies it; with
 *                        {@link #downloadType}, enough to link back to the thing itself.
 * @param title           the song's title, or the album/playlist's. Null between the request being
 *                        accepted and its metadata arriving — the request carries only an id — and
 *                        for a download whose id the provider could not resolve.
 * @param artists         never null; empty when unknown. A playlist's "artist" is its author.
 * @param imageUrl        artwork, nullable for the same reason {@code title} is.
 * @param progressPercent 0-100, the mean across the download's songs; meaningful only while
 *                        {@link #stage} is {@link DownloadStage#DOWNLOADING}. Nullable, and a null
 *                        must never overwrite a previously observed value client-side, for the same
 *                        reason the server never writes one: a bar that jumps backwards on a healthy
 *                        download is the most trust-destroying thing this feature can do.
 * @param songCount       how many songs this download resolved to. 0 while QUEUED — not yet known.
 * @param songsSucceeded  how many of them have a file. With {@code songsFailed}, lets a card read
 *                        "7 of 12" without a second request.
 * @param requestedAt     when the request arrived.
 * @param stageEnteredAt  when the current stage began. What indeterminate stages show elapsed time
 *                        from, so a slow search reads as slow rather than as stuck.
 * @param updatedAt       when the row was last written. The recency sort key, and the only field that
 *                        moves when nothing but progress changes.
 * @param finishedAt      when the last song settled. Null while anything is still running.
 * @param failureCode     a {@link DownloadFailureCode} name, or null. Deliberately a String, not the
 *                        enum: rows written before the enum existed hold free prose, and a read path
 *                        that throws on its own history is worse than one the client can't word. For a
 *                        collection, the first song's reason; the per-song view has the rest.
 */
public record ActiveDownloadView(
        UUID downloadId,
        String youtubeId,
        DownloadType downloadType,
        String title,
        List<String> artists,
        String imageUrl,
        DownloadStage stage,
        BigDecimal progressPercent,
        int songCount,
        int songsSucceeded,
        int songsFailed,
        Instant requestedAt,
        Instant stageEnteredAt,
        Instant updatedAt,
        Instant finishedAt,
        String failureCode) {
}

package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class DownloadService {

    private static final String CLAIM_PENDING_SQL = """
            UPDATE downloads
            SET status = 'IN_PROGRESS'
            WHERE download_id IN (
                SELECT download_id
                FROM downloads
                WHERE status = 'PENDING'
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            )
            RETURNING download_id, song_name, status, created_at
            """;

    private static final String MARK_STATUS_SQL = """
            UPDATE downloads
            SET status = :status
            WHERE download_id = :id AND status = 'IN_PROGRESS'
            """;

    // One statement so the download's status and the task's terminal phase can't be split by a crash.
    //
    // The task UPDATE's guard has to satisfy two things that pull in opposite directions:
    //
    //   1. Idempotence. Writing unconditionally means a second finish for an already-terminal download
    //      leaves `downloads` alone (the CTE guard holds) but still re-stamps finished_at -- which would
    //      slide a long-finished row back inside the feed's retention window and resurrect a card the
    //      user dismissed hours ago.
    //   2. No livelock. Gating purely on the CTE means a download whose `downloads` row went terminal by
    //      some path that did NOT mark the task (markStatusIfInProgress) can never have its task row
    //      marked terminal either -- so the row stays in the due-work partial index and the runner
    //      claims it forever.
    //
    // So: write if the CTE won the row, OR if the task is still non-terminal. A duplicate finish
    // satisfies neither and updates nothing; an orphaned task satisfies the second and gets closed.
    private static final String FINISH_DOWNLOAD_SQL = """
            WITH updated AS (
                UPDATE downloads
                   SET status = :status
                 WHERE download_id = :id
                   AND status NOT IN ('SUCCEEDED', 'FAILED')
                RETURNING download_id
            )
            UPDATE download_tasks
               SET phase = :status,
                   phase_entered_at = :now,
                   -- now(), not :now -- see the note in DownloadTaskRepository.SAVE_SQL. finished_at
                   -- stays on :now because the retention window is a rule tests must be able to steer.
                   updated_at = now(),
                   finished_at = :now,
                   failure_reason = :reason,
                   progress_percent = CASE WHEN :status = 'SUCCEEDED' THEN 100 ELSE progress_percent END,
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
               AND (download_id IN (SELECT download_id FROM updated)
                    OR phase NOT IN ('SUCCEEDED', 'FAILED'))
            """;

    // One statement so a download can never exist without its song -- no transaction manager needed,
    // same reasoning as ADMIT_SQL and FINISH_DOWNLOAD_SQL.
    //
    // `downloads.song_name` is deliberately NOT written any more. `DownloadTaskRepository.ADMIT_SQL`
    // and `ActiveDownloadRepository` both now join `songs` for the name instead, so nothing reads this
    // column, and writing it would be storing a second copy of the same fact for a column `V7` drops.
    // The column itself stays for one release so that rolling back to the previous image -- which
    // still queries it -- keeps working for every row created before this change; see the plan's
    // "V5 expands, V7 contracts" note.
    private static final String REQUEST_DOWNLOAD_SQL = """
            WITH created AS (
                INSERT INTO downloads (download_id, status, created_at)
                VALUES (:downloadId, 'PENDING', :now)
                RETURNING download_id
            )
            INSERT INTO songs (song_id, download_id, name, artists, image_url)
            SELECT :songId, download_id, :songName, :artists, :imageUrl FROM created
            """;

    private final R2dbcEntityTemplate entityTemplate;

    public DownloadService(R2dbcEntityTemplate entityTemplate) {
        this.entityTemplate = entityTemplate;
    }

    /** Delegates with no known artist and no image -- what the deprecated path-based route sends. */
    public Mono<Download> requestDownload(String songName) {
        return requestDownload(songName, List.of(), null);
    }

    /**
     * Creates a download and its song in one atomic statement (see {@link #REQUEST_DOWNLOAD_SQL}).
     * A null {@code artists} is normalised to empty here rather than trusting every caller to have
     * done it already -- {@code songs.artists} is {@code NOT NULL DEFAULT '{}'}, and binding a Java
     * null against it would fail the insert outright. The returned {@link Download} is built from
     * the same values used to bind the statement rather than re-read afterwards: every field on it
     * is one this method generated or was handed, so there is nothing a round trip would add.
     */
    public Mono<Download> requestDownload(String songName, List<String> artists, String imageUrl) {
        UUID downloadId = UUID.randomUUID();
        UUID songId = UUID.randomUUID();
        Instant now = Instant.now();
        List<String> normalisedArtists = artists == null ? List.of() : artists;
        Download download = Download.builder()
                .downloadId(downloadId)
                .songName(songName)
                .status(DownloadStatus.PENDING)
                .createdAt(now)
                .build();
        DatabaseClient.GenericExecuteSpec spec = entityTemplate.getDatabaseClient()
                .sql(REQUEST_DOWNLOAD_SQL)
                .bind("downloadId", downloadId)
                .bind("songId", songId)
                .bind("now", now)
                .bind("artists", normalisedArtists.toArray(String[]::new));
        // bind() throws synchronously on a null value; songs.name is NOT NULL so a null songName is
        // always rejected by Postgres regardless, but binding it safely rather than letting bind()
        // throw keeps that rejection a reactive error like every other failure path here, instead of
        // a synchronous exception thrown before the Mono is even returned.
        spec = songName == null
                ? spec.bindNull("songName", String.class)
                : spec.bind("songName", songName);
        spec = imageUrl == null
                ? spec.bindNull("imageUrl", String.class)
                : spec.bind("imageUrl", imageUrl);
        return spec.fetch()
                .rowsUpdated()
                .thenReturn(download)
                .doOnError(error -> log.error("Could not create download for song {}", songName, error));
    }

    // SKIP LOCKED stops two cycles claiming the same row; RETURNING yields exactly the rows won.
    public Flux<Download> claimPendingDownloads(int batchSize) {
        return entityTemplate.getDatabaseClient()
                .sql(CLAIM_PENDING_SQL)
                .bind("limit", batchSize)
                .map((row, meta) -> entityTemplate.getConverter().read(Download.class, row, meta))
                .all();
    }

    // Terminal status write. Applies only while the row is still IN_PROGRESS; returns 0 if it isn't.
    public Mono<Long> markStatusIfInProgress(UUID downloadId, DownloadStatus status) {
        return entityTemplate.getDatabaseClient()
                .sql(MARK_STATUS_SQL)
                .bind("status", status.name())
                .bind("id", downloadId)
                .fetch()
                .rowsUpdated()
                .doOnNext(rows -> {
                    if (rows == 0) {
                        log.warn("markStatusIfInProgress({}, {}) updated no rows - row was not IN_PROGRESS", downloadId, status);
                    }
                })
                .doOnError(error -> log.error("Could not write status {} for download {}",
                        status, downloadId, error));
    }

    /** Idempotent: a second call for an already-terminal download updates nothing and returns 0. */
    public Mono<Long> finishDownload(UUID downloadId, DownloadStatus status,
                                     DownloadFailureCode failureCode, Instant now) {
        DatabaseClient.GenericExecuteSpec spec = entityTemplate.getDatabaseClient()
                .sql(FINISH_DOWNLOAD_SQL)
                .bind("status", status.name())
                .bind("id", downloadId)
                .bind("now", now);
        // Stored by NAME, not prose: the client words it, so copy changes never touch this table.
        spec = failureCode == null
                ? spec.bindNull("reason", String.class)
                : spec.bind("reason", failureCode.name());
        return spec.fetch()
                .rowsUpdated()
                .doOnError(error -> log.error("Could not finish download {} as {}",
                        downloadId, status, error));
    }
}

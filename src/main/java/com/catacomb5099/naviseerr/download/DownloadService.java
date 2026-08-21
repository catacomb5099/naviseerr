package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
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
                   finished_at = :now,
                   failure_reason = :reason,
                   progress_percent = CASE WHEN :status = 'SUCCEEDED' THEN 100 ELSE progress_percent END,
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
            """;

    private final R2dbcEntityTemplate entityTemplate;

    public DownloadService(R2dbcEntityTemplate entityTemplate) {
        this.entityTemplate = entityTemplate;
    }

    public Mono<Download> requestDownload(String songName) {
        Download download = Download.builder()
                .downloadId(UUID.randomUUID())
                .songName(songName)
                .status(DownloadStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        // insert() forces an INSERT; save() would treat the pre-set @Id as an UPDATE.
        return entityTemplate.insert(download);
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

    public Mono<Long> finishDownload(UUID downloadId, DownloadStatus status, String reason,
                                     Instant now) {
        DatabaseClient.GenericExecuteSpec spec = entityTemplate.getDatabaseClient()
                .sql(FINISH_DOWNLOAD_SQL)
                .bind("status", status.name())
                .bind("id", downloadId)
                .bind("now", now);
        spec = reason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", reason);
        return spec.fetch()
                .rowsUpdated()
                .doOnError(error -> log.error("Could not finish download {} as {}",
                        downloadId, status, error));
    }
}

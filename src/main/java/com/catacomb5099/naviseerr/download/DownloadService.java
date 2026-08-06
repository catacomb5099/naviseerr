package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    // Claims the oldest PENDING rows in a single statement. FOR UPDATE SKIP LOCKED means a row is
    // never handed to two concurrent cycles/instances, and RETURNING yields exactly the rows this
    // call won (already flipped to IN_PROGRESS).
    public Flux<Download> claimPendingDownloads(int batchSize) {
        return entityTemplate.getDatabaseClient()
                .sql(CLAIM_PENDING_SQL)
                .bind("limit", batchSize)
                .map((row, meta) -> entityTemplate.getConverter().read(Download.class, row, meta))
                .all();
    }

    // Terminal status write once the pipeline reaches a success/fail state.
    //
    // The name spells out the precondition because it is not optional: the UPDATE only applies to a
    // row that is still IN_PROGRESS. Any other current status is left untouched - this is what keeps
    // the write idempotent and safe against future cancellation races. It runs in a transaction so
    // the milestone is persisted atomically.
    //
    // Returns the number of rows updated: 1 on a successful transition, 0 if the row was no longer
    // IN_PROGRESS (already terminal, or reclaimed elsewhere). A 0 is a legitimate outcome, not an
    // error, so callers that care must check it rather than relying on an exception.
    @Transactional
    public Mono<Long> markStatusIfInProgress(UUID downloadId, DownloadStatus status) {
        return entityTemplate.getDatabaseClient()
                .sql(MARK_STATUS_SQL)
                .bind("status", status.name())
                .bind("id", downloadId)
                .fetch()
                .rowsUpdated()
                // Logged here, at the write itself, rather than in the caller: this fires for every
                // caller and names the operation that actually failed.
                .doOnError(error -> log.error("Could not write status {} for download {}",
                        status, downloadId, error));
    }
}

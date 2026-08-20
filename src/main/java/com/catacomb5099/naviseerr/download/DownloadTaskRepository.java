package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All {@code download_tasks} SQL. Raw statements rather than Spring Data derived queries because
 * every one of them needs something the mapping layer cannot express: {@code FOR UPDATE SKIP LOCKED},
 * {@code RETURNING}, or a data-modifying CTE.
 */
@Slf4j
@Repository
public class DownloadTaskRepository {

    /** Admits, in one atomic statement, every non-terminal download that has no task row yet. */
    private static final String ADMIT_SQL = """
            WITH admitted AS (
                SELECT d.download_id, d.song_name
                  FROM downloads d
                 WHERE d.status IN ('PENDING', 'IN_PROGRESS')
                   AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                    WHERE t.download_id = d.download_id)
                 ORDER BY d.created_at
                   FOR UPDATE SKIP LOCKED
                 LIMIT :limit
            ), created AS (
                INSERT INTO download_tasks
                    (download_id, song_name, phase, phase_entered_at, next_attempt_at)
                SELECT download_id, song_name, 'SEARCH_INIT', :now, :now FROM admitted
                ON CONFLICT (download_id) DO NOTHING
                RETURNING download_id
            )
            UPDATE downloads SET status = 'IN_PROGRESS'
             WHERE download_id IN (SELECT download_id FROM created)
            """;

    /** Claims due, unleased, non-terminal tasks; excludes DOWNLOAD_INIT rows when no transfer slot is free. */
    private static final String CLAIM_DUE_SQL = """
            UPDATE download_tasks
               SET lease_owner = :owner,
                   lease_expires_at = :leaseExpiresAt
             WHERE download_id IN (
                   SELECT download_id FROM download_tasks
                    WHERE next_attempt_at <= :now
                      AND phase NOT IN ('SUCCEEDED', 'FAILED')
                      AND (:transferSlotsFree OR phase <> 'DOWNLOAD_INIT')
                      AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                    ORDER BY next_attempt_at
                      FOR UPDATE SKIP LOCKED
                    LIMIT :limit)
            RETURNING download_id, song_name, phase, phase_entered_at, next_attempt_at, search_id,
                      candidates, candidate_index, retry_index, slskd_username,
                      slskd_filename, slskd_transfer_id, last_error
            """;

    // Writes every field (DownloadTask is the complete state) and clears the lease.
    private static final String SAVE_SQL = """
            UPDATE download_tasks
               SET phase = :phase,
                   phase_entered_at = :phaseEnteredAt,
                   next_attempt_at = :nextAttemptAt,
                   search_id = :searchId,
                   candidates = :candidates,
                   candidate_index = :candidateIndex,
                   retry_index = :retryIndex,
                   slskd_username = :slskdUsername,
                   slskd_filename = :slskdFilename,
                   slskd_transfer_id = :slskdTransferId,
                   last_error = :lastError,
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
            """;

    // Counts DOWNLOADS in flight, not tasks, so one large collection can't lock out admission.
    private static final String COUNT_ACTIVE_DOWNLOADS_SQL = """
            SELECT count(*) AS total FROM downloads WHERE status = 'IN_PROGRESS'
            """;

    // Counts only DOWNLOAD_POLL, the only phase with a real live transfer; counting DOWNLOAD_INIT too
    // would deadlock the gate, since CLAIM_DUE_SQL excludes DOWNLOAD_INIT once this count is maxed out.
    private static final String COUNT_ACTIVE_TRANSFERS_SQL = """
            SELECT count(*) AS total FROM download_tasks
             WHERE phase = 'DOWNLOAD_POLL'
            """;

    private static final TypeReference<List<DownloadCandidate>> CANDIDATE_LIST =
            new TypeReference<>() {};

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;

    public DownloadTaskRepository(R2dbcEntityTemplate entityTemplate, ObjectMapper objectMapper) {
        this.client = entityTemplate.getDatabaseClient();
        this.objectMapper = objectMapper;
    }

    public Mono<Long> admitNewDownloads(int limit, Instant now) {
        return client.sql(ADMIT_SQL)
                .bind("limit", limit)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> countActiveDownloads() {
        return client.sql(COUNT_ACTIVE_DOWNLOADS_SQL)
                .map((row, meta) -> row.get("total", Long.class))
                .one();
    }

    public Mono<Long> countActiveTransfers() {
        return client.sql(COUNT_ACTIVE_TRANSFERS_SQL)
                .map((row, meta) -> row.get("total", Long.class))
                .one();
    }

    public Flux<DownloadTask> claimDueTasks(int limit, String owner, Instant now, Duration lease,
                                            boolean transferSlotsFree) {
        return client.sql(CLAIM_DUE_SQL)
                .bind("owner", owner)
                .bind("leaseExpiresAt", now.plus(lease))
                .bind("now", now)
                .bind("transferSlotsFree", transferSlotsFree)
                .bind("limit", limit)
                .map(this::toTask)
                .all();
    }

    public Mono<Long> save(DownloadTask task) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(SAVE_SQL)
                .bind("id", task.downloadId())
                .bind("phase", task.phase().name())
                .bind("phaseEnteredAt", task.phaseEnteredAt())
                .bind("nextAttemptAt", task.nextAttemptAt())
                .bind("candidates", writeCandidates(task.candidates()))
                .bind("candidateIndex", task.candidateIndex())
                .bind("retryIndex", task.retryIndex());
        spec = bindNullable(spec, "searchId", task.searchId());
        spec = bindNullable(spec, "slskdUsername", task.slskdUsername());
        spec = bindNullable(spec, "slskdFilename", task.slskdFilename());
        spec = bindNullable(spec, "slskdTransferId", task.slskdTransferId());
        spec = bindNullable(spec, "lastError", task.lastError());
        return spec.fetch().rowsUpdated();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private DownloadTask toTask(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return new DownloadTask(
                row.get("download_id", UUID.class),
                row.get("song_name", String.class),
                DownloadPhase.valueOf(row.get("phase", String.class)),
                row.get("phase_entered_at", Instant.class),
                row.get("next_attempt_at", Instant.class),
                row.get("search_id", String.class),
                readCandidates(row.get("candidates", String.class)),
                row.get("candidate_index", Integer.class),
                row.get("retry_index", Integer.class),
                row.get("slskd_username", String.class),
                row.get("slskd_filename", String.class),
                row.get("slskd_transfer_id", String.class),
                row.get("last_error", String.class));
    }

    private String writeCandidates(List<DownloadCandidate> candidates) {
        try {
            return objectMapper.writeValueAsString(candidates == null ? List.of() : candidates);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise download candidates", e);
        }
    }

    private List<DownloadCandidate> readCandidates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, CANDIDATE_LIST);
        } catch (Exception e) {
            // Falls back rather than throws: one bad row must not abort the whole claimed batch.
            log.error("Could not deserialise download candidates; treating as no candidates", e);
            return List.of();
        }
    }
}

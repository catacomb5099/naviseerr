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

    /**
     * Admits work in one atomic statement. Matches NON-TERMINAL downloads with no task row, not just
     * PENDING ones, which makes "every non-terminal download has a task row" an invariant the loop
     * continuously restores — so a download that somehow loses its task row is recovered rather than
     * stranded. NOT EXISTS rather than a LEFT JOIN because FOR UPDATE cannot be applied across an
     * outer join. ON CONFLICT DO NOTHING guards a concurrent admit.
     */
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

    /**
     * Claims due, unleased, non-terminal tasks.
     *
     * <p>The {@code :transferSlotsFree} predicate is the "don't melt slskd" guard. When no transfer
     * slots are free, DOWNLOAD_INIT tasks are excluded from the claim entirely rather than claimed
     * and then deferred — otherwise a 500-track collection sitting at DOWNLOAD_INIT would consume
     * every pass claiming and re-deferring rows, crowding out the transfers that are actually
     * running. Everything else (searches, and polling live transfers) is never gated: polling is one
     * cheap GET, and starving it stalls a download that slskd is happily finishing.
     */
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

    // Writes every field so there is no partial-update logic to get wrong: DownloadTask is the
    // complete state. Clearing the lease is what makes the row visible to the next pass.
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

    // Counts DOWNLOADS in flight, not tasks. A 500-track collection is ONE in-flight download, so
    // one large request cannot lock every other request out of admission.
    private static final String COUNT_ACTIVE_DOWNLOADS_SQL = """
            SELECT count(*) AS total FROM downloads WHERE status = 'IN_PROGRESS'
            """;

    // Counts only DOWNLOAD_POLL: the only phase with a real, live slskd transfer (a non-null
    // slskd_transfer_id). This is the resource that actually needs protecting: bandwidth, and peers'
    // upload queues. One collection may legitimately hold every slot.
    //
    // DOWNLOAD_INIT is deliberately EXCLUDED, even though it is "about to attempt an enqueue" — it is
    // not yet a real transfer (slskd_username/slskd_filename/slskd_transfer_id are all null for every
    // row entering or re-entering this phase). Including it would create a durable deadlock: CLAIM_DUE_SQL
    // refuses to claim DOWNLOAD_INIT rows once this count reaches max-concurrent-transfers, so those rows
    // could never advance out of DOWNLOAD_INIT, so the count could never drop, and the gate would stay
    // closed forever (a restart would not clear it, since this is backed by the DB). Counting only
    // DOWNLOAD_POLL keeps the gate self-healing: it can only decrease via a DOWNLOAD_POLL row reaching a
    // terminal state, which always happens eventually (success, failure, or budget timeout).
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
            // This runs inside the row-mapping stage of claimDueTasks's Flux<DownloadTask>: throwing
            // here would fail the whole Flux (and every other row already claimed in the same pass),
            // not just this row — one bad row/step must never abort a pass (see DownloadTaskRunner's
            // class javadoc). Logging and falling back to an empty candidate list keeps this row
            // flowing through normally; an empty list then fails the download cleanly at its next
            // DOWNLOAD_INIT/DOWNLOAD_POLL step (via DownloadStepExecutor/DownloadStateMachine's
            // candidate-exhaustion path) rather than poisoning every other claimed row.
            log.error("Could not deserialise download candidates; treating as no candidates", e);
            return List.of();
        }
    }
}

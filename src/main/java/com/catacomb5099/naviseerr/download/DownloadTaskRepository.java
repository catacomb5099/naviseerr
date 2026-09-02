package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.request.TrackQuery;
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
     * Admits, in one atomic statement, every non-terminal download that has no task row yet.
     *
     * <p>The join to {@code songs} is what supplies {@code song_id}, which is {@code NOT NULL} as of
     * {@code V6__download_tasks_song_id_not_null.sql}. It is an INNER join, and safely so: a download
     * and its song are created by one CTE in {@code DownloadService.requestDownload}, so a download
     * without a song cannot exist. Today that is 1:1, so the join can neither drop a row nor fan one
     * out; when collections land and a download has many songs, this is the statement that decides
     * what "one task per song" means and it will need revisiting (the {@code ON CONFLICT} below would
     * currently keep exactly one of them).
     *
     * <p>{@code FOR UPDATE OF d SKIP LOCKED}, not a bare {@code FOR UPDATE}: a bare lock clause takes
     * row locks in every table in the {@code FROM}, so with the join above it would start locking
     * {@code songs} rows that this statement has never locked and nothing needs locked. Naming the
     * alias keeps the lock exactly where it was -- on the {@code downloads} rows being admitted.
     */
    private static final String ADMIT_SQL = """
            WITH admitted AS (
                SELECT d.download_id, s.song_id
                  FROM downloads d
                  JOIN songs s ON s.download_id = d.download_id
                 WHERE d.status IN ('PENDING', 'IN_PROGRESS')
                   AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                    WHERE t.download_id = d.download_id)
                 ORDER BY d.created_at
                   FOR UPDATE OF d SKIP LOCKED
                 LIMIT :limit
            ), created AS (
                INSERT INTO download_tasks
                    (download_id, song_id, phase, phase_entered_at, next_attempt_at)
                SELECT download_id, song_id, 'SEARCH_INIT', :now, :now FROM admitted
                ON CONFLICT (download_id) DO NOTHING
                RETURNING download_id
            )
            UPDATE downloads SET status = 'IN_PROGRESS'
             WHERE download_id IN (SELECT download_id FROM created)
            """;

    /**
     * Claims due, unleased, non-terminal tasks; excludes DOWNLOAD_INIT rows when no transfer slot is
     * free.
     *
     * <p>This reverses V2's decision to denormalise {@code song_name} onto {@code download_tasks} so
     * that the due-work query needed no join. The reversal is deliberate: song metadata moved to its
     * own table (see {@code V5__song_metadata.sql}), because a name on {@code download_tasks} makes
     * the state-machine table a metadata carrier and a collection download has many names, not one.
     * The cost is a primary-key lookup into {@code songs} for at most {@code batch-size} rows per
     * pass, which is why it was judged affordable on a statement that runs every few seconds.
     *
     * <p>The join arrives through {@code FROM}, not a subquery in the {@code RETURNING} list, because
     * {@code UPDATE ... RETURNING} can only return columns of the table being updated. Two properties
     * of the original statement survive that rewrite and must keep surviving it:
     *
     * <ul>
     *   <li>{@code FOR UPDATE SKIP LOCKED} still selects from {@code download_tasks} alone, so it
     *       still locks only task rows -- the {@code songs} join sits in the outer UPDATE, which
     *       merely reads it.</li>
     *   <li>The lease guard ({@code lease_expires_at IS NULL OR lease_expires_at < :now}) is still
     *       what excludes a row another process holds. {@code DownloadTaskRepositoryIT} asserts that
     *       directly against this statement rather than trusting the pre-rewrite coverage.</li>
     * </ul>
     *
     * <p>The join is on {@code song_id}, a primary key, so it can never fan a task row out into two
     * claims. It is an INNER join, so a task row with a {@code song_id} pointing at nothing would go
     * unclaimable rather than throw -- {@code NOT NULL} plus the foreign key are what rule that out.
     */
    private static final String CLAIM_DUE_SQL = """
            UPDATE download_tasks t
               SET lease_owner = :owner,
                   lease_expires_at = :leaseExpiresAt
              FROM songs s
             WHERE s.song_id = t.song_id
               AND t.download_id IN (
                   SELECT download_id FROM download_tasks
                    WHERE next_attempt_at <= :now
                      AND phase NOT IN ('SUCCEEDED', 'FAILED')
                      AND (:transferSlotsFree OR phase <> 'DOWNLOAD_INIT')
                      AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                    ORDER BY next_attempt_at
                      FOR UPDATE SKIP LOCKED
                    LIMIT :limit)
            RETURNING t.download_id, s.name, s.artists, t.phase, t.phase_entered_at,
                      t.next_attempt_at, t.search_id, t.candidates, t.candidate_index,
                      t.retry_index, t.slskd_username, t.slskd_filename, t.slskd_transfer_id,
                      t.last_error, t.progress_percent
            """;

    // Writes every field (DownloadTask is the complete state) and clears the lease. Guarded on the
    // row still being non-terminal and still held by the caller's lease: a read-then-write would
    // have the same race this closes, so it has to be this one statement.
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
                   progress_percent = :progressPercent,
                   -- Wall clock, not the injected Clock, and deliberately: this is bookkeeping about
                   -- when the row was written, the same thing downloads.created_at uses now() for. It
                   -- also has to agree with FINISH_DOWNLOAD_SQL, since the feed sorts on it -- mixing a
                   -- test clock here with now() there would order rows inconsistently. Anything the
                   -- state machine actually reasons about (finished_at, next_attempt_at) stays on the
                   -- injected clock.
                   updated_at = now(),
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
               AND phase NOT IN ('SUCCEEDED', 'FAILED')
               AND lease_owner = :owner
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

    public Mono<Long> save(DownloadTask task, String owner) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(SAVE_SQL)
                .bind("id", task.downloadId())
                .bind("owner", owner)
                .bind("phase", task.phase().name())
                .bind("phaseEnteredAt", task.phaseEnteredAt())
                .bind("nextAttemptAt", task.nextAttemptAt())
                .bind("candidates", writeCandidates(task.candidates()))
                .bind("candidateIndex", task.candidateIndex())
                .bind("retryIndex", task.retryIndex())
                .bind("progressPercent", task.progressPercent());
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
                toQuery(row),
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
                row.get("last_error", String.class),
                row.get("progress_percent", java.math.BigDecimal.class));
    }

    /**
     * Reads the two {@code songs} columns {@link #CLAIM_DUE_SQL} joins for. {@code artists} is
     * {@code TEXT[] NOT NULL DEFAULT '{}'}, so it should never arrive null -- but a null-guard costs
     * nothing here and {@link TrackQuery} promises an empty list, not a null one, to every caller
     * downstream of this row mapping.
     */
    private TrackQuery toQuery(io.r2dbc.spi.Row row) {
        String[] artists = row.get("artists", String[].class);
        return new TrackQuery(row.get("name", String.class),
                artists == null ? List.of() : List.of(artists));
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

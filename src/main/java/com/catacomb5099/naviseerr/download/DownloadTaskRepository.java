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
 * {@code RETURNING}, {@code unnest}, or a data-modifying CTE.
 */
@Slf4j
@Repository
public class DownloadTaskRepository {

    /**
     * Downloads that are ready to be admitted. A SELECT only — it used to also create the task rows
     * and flip the status in the same statement, and it cannot any more: what task rows a download
     * needs is now a question only ytmusic-adapter can answer, so an HTTP call has to happen between
     * finding the row and writing its tasks. {@link #createTasks} is the other half.
     *
     * <p>{@code PENDING} alone is enough, where the old statement had to cover {@code IN_PROGRESS}
     * too. That breadth existed to make its own two halves crash-safe; now the flip to
     * {@code IN_PROGRESS} happens in the same statement as the task inserts, so an
     * {@code IN_PROGRESS} download always has task rows. A crash between this select and
     * {@code createTasks} leaves the row exactly as it was, and the next pass picks it up — which is
     * what the loop is for.
     *
     * <p>The {@code NOT EXISTS} is still here even though {@code PENDING} implies it, as the thing
     * that makes a repeat gather (two passes overlapping, a retried metadata call) idempotent rather
     * than duplicating every song.
     */
    private static final String ADMISSIBLE_SQL = """
            SELECT d.download_id, d.youtube_id, d.download_type, d.song_name, d.status, d.created_at
              FROM downloads d
             WHERE d.status = 'PENDING'
               AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                WHERE t.download_id = d.download_id)
             ORDER BY d.created_at
               FOR UPDATE SKIP LOCKED
             LIMIT :limit
            """;

    /**
     * Creates every task row for one download and admits it, in one statement. One row per song:
     * one for a song request, N for an album or playlist.
     *
     * <p>{@code unnest} of two parallel arrays rather than a multi-row VALUES list, because the song
     * count is only known at runtime and a statement whose text depends on it cannot be a constant,
     * cannot be prepared once, and puts string building on the write path.
     *
     * <p>The guards make it a no-op rather than a duplicator if it somehow runs twice for the same
     * download: {@code status = 'PENDING'} has already been consumed by the first run, and the
     * {@code NOT EXISTS} stops the insert itself. {@code song_name} is written here because this is
     * the first moment the server knows it — the request carried only an id.
     */
    private static final String CREATE_TASKS_SQL = """
            WITH created AS (
                INSERT INTO download_tasks
                    (task_id, download_id, youtube_id, song_name, phase, phase_entered_at,
                     next_attempt_at)
                SELECT gen_random_uuid(), :downloadId, s.youtube_id, s.song_name, 'SEARCH_INIT',
                       :now, :now
                  FROM unnest(:youtubeIds::text[], :songNames::text[]) AS s(youtube_id, song_name)
                 WHERE NOT EXISTS (SELECT 1 FROM download_tasks t
                                    WHERE t.download_id = :downloadId)
                RETURNING download_id
            )
            UPDATE downloads
               SET status = 'IN_PROGRESS',
                   song_name = :collectionName
             WHERE download_id = :downloadId
               AND status = 'PENDING'
               AND EXISTS (SELECT 1 FROM created)
            """;

    /** Claims due, unleased, non-terminal tasks; excludes DOWNLOAD_INIT rows when no transfer slot is free. */
    private static final String CLAIM_DUE_SQL = """
            UPDATE download_tasks
               SET lease_owner = :owner,
                   lease_expires_at = :leaseExpiresAt
             WHERE task_id IN (
                   SELECT task_id FROM download_tasks
                    WHERE next_attempt_at <= :now
                      AND phase NOT IN ('SUCCEEDED', 'FAILED')
                      AND (:transferSlotsFree OR phase <> 'DOWNLOAD_INIT')
                      AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                    ORDER BY next_attempt_at
                      FOR UPDATE SKIP LOCKED
                    LIMIT :limit)
            RETURNING task_id, download_id, youtube_id, song_name, phase, phase_entered_at,
                      next_attempt_at, search_id, candidates, candidate_index, retry_index,
                      slskd_username, slskd_filename, slskd_transfer_id, last_error, progress_percent
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
                   -- also has to agree with FINISH_TASK_SQL, since the feed sorts on it -- mixing a
                   -- test clock here with now() there would order rows inconsistently. Anything the
                   -- state machine actually reasons about (finished_at, next_attempt_at) stays on the
                   -- injected clock.
                   updated_at = now(),
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE task_id = :id
               AND phase NOT IN ('SUCCEEDED', 'FAILED')
               AND lease_owner = :owner
            """;

    /**
     * Gives a download its terminal status once every one of its tasks is terminal, and is the whole
     * reason the per-task terminal write no longer touches {@code downloads}.
     *
     * <p>Two tasks of the same download finishing at the same time cannot both conclude it, and —
     * the failure that matters — cannot both decline to. Each would otherwise read a snapshot in
     * which the other is still running, so neither writes, and the download stays
     * {@code IN_PROGRESS} forever with every task finished. Making the per-task statement bigger
     * does not fix that; a data-modifying CTE's writes are not visible to the rest of its own
     * statement. So no task decides. This runs once at the end of every pass and asks the question
     * of the whole table, which is the level-triggered rule the rest of this loop already follows:
     * a missed conclusion costs one interval, not a download.
     *
     * <p>{@code d.status = 'IN_PROGRESS'} makes it idempotent — it writes each download exactly
     * once and then stops matching. {@code PARTIAL_SUCCESS} needs one of each outcome, so it is
     * unreachable for a single-song download.
     */
    private static final String CONCLUDE_SQL = """
            UPDATE downloads d
               SET status = agg.status
              FROM (SELECT t.download_id,
                           CASE WHEN bool_or(t.phase = 'SUCCEEDED') AND bool_or(t.phase = 'FAILED')
                                     THEN 'PARTIAL_SUCCESS'
                                WHEN bool_or(t.phase = 'SUCCEEDED') THEN 'SUCCEEDED'
                                ELSE 'FAILED' END AS status
                      FROM download_tasks t
                     GROUP BY t.download_id
                    HAVING bool_and(t.phase IN ('SUCCEEDED', 'FAILED'))) agg
             WHERE d.download_id = agg.download_id
               AND d.status = 'IN_PROGRESS'
            """;

    /**
     * Fails a download that has no task rows and never will. Admission is the only place this
     * happens: the metadata call is the one step that can fail before a single task row exists, so
     * it is also the only failure the per-task terminal write cannot record.
     */
    private static final String FAIL_UNADMITTED_SQL = """
            UPDATE downloads
               SET status = 'FAILED'
             WHERE download_id = :id
               AND status = 'PENDING'
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

    /** Downloads ready for metadata gathering, oldest first. Writes nothing. */
    public Flux<Download> admitDownloads(int limit) {
        return client.sql(ADMISSIBLE_SQL)
                .bind("limit", limit)
                .map(DownloadTaskRepository::toDownload)
                .all();
    }

    /**
     * @param collectionName the download's display title — a song's title, or an album/playlist's
     * @return rows updated: 1 when the download was admitted, 0 when it had already been
     */
    public Mono<Long> createTasks(UUID downloadId, String collectionName,
                                  List<DownloadTask> tasks, Instant now) {
        if (tasks.isEmpty()) {
            return Mono.just(0L);
        }
        return client.sql(CREATE_TASKS_SQL)
                .bind("downloadId", downloadId)
                .bind("collectionName", collectionName == null ? "" : collectionName)
                .bind("youtubeIds", tasks.stream().map(DownloadTask::youtubeId).toArray(String[]::new))
                .bind("songNames", tasks.stream().map(DownloadTask::songName).toArray(String[]::new))
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> concludeDownloads() {
        return client.sql(CONCLUDE_SQL)
                .fetch()
                .rowsUpdated()
                .doOnError(error -> log.error("Could not conclude finished downloads", error));
    }

    public Mono<Long> failUnadmitted(UUID downloadId) {
        return client.sql(FAIL_UNADMITTED_SQL)
                .bind("id", downloadId)
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
                .bind("id", task.taskId())
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

    private static Download toDownload(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return Download.builder()
                .downloadId(row.get("download_id", UUID.class))
                .youtubeId(row.get("youtube_id", String.class))
                .downloadType(DownloadType.valueOf(row.get("download_type", String.class)))
                .songName(row.get("song_name", String.class))
                .status(DownloadStatus.valueOf(row.get("status", String.class)))
                .createdAt(row.get("created_at", Instant.class))
                .build();
    }

    private DownloadTask toTask(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return DownloadTask.builder()
                .taskId(row.get("task_id", UUID.class))
                .downloadId(row.get("download_id", UUID.class))
                .youtubeId(row.get("youtube_id", String.class))
                .songName(row.get("song_name", String.class))
                .phase(DownloadPhase.valueOf(row.get("phase", String.class)))
                .phaseEnteredAt(row.get("phase_entered_at", Instant.class))
                .nextAttemptAt(row.get("next_attempt_at", Instant.class))
                .searchId(row.get("search_id", String.class))
                .candidates(readCandidates(row.get("candidates", String.class)))
                .candidateIndex(row.get("candidate_index", Integer.class))
                .retryIndex(row.get("retry_index", Integer.class))
                .slskdUsername(row.get("slskd_username", String.class))
                .slskdFilename(row.get("slskd_filename", String.class))
                .slskdTransferId(row.get("slskd_transfer_id", String.class))
                .lastError(row.get("last_error", String.class))
                .progressPercent(row.get("progress_percent", java.math.BigDecimal.class))
                .build();
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

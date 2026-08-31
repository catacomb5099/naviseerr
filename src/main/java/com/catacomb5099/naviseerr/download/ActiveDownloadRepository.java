package com.catacomb5099.naviseerr.download;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Read model behind the download feed. Two queries over the same projection: everything the client
 * should be showing right now, and a by-id lookup for cards a client held across a restart.
 */
@Repository
public class ActiveDownloadRepository {

    /**
     * Shared so both queries and both endpoints derive {@link DownloadStage} from identical inputs.
     * {@code status} and {@code phase} are selected only to compute it; neither reaches the client.
     *
     * <p>Both timestamps fall back to {@code d.created_at}, because a download with no task row still
     * has to sort and still has to show the user how long it has been waiting. Reading them straight
     * off the LEFT JOIN yields nulls for exactly the QUEUED rows this projection exists to expose, and
     * a null sort key is how a batch of queued cards ends up in arbitrary order. For a queued
     * download, "when did this stage begin" genuinely is when it was requested. The COALESCE is a
     * no-op on the terminal branch, where the task row is always present.
     */
    private static final String PROJECTION = """
            d.download_id, d.song_name, d.status, t.phase, t.progress_percent,
                   t.failure_reason,
                   COALESCE(t.phase_entered_at, d.created_at) AS stage_entered_at,
                   COALESCE(t.updated_at, d.created_at)       AS updated_at""";

    /**
     * A UNION ALL of two separately-indexed branches rather than one query with an OR, because the two
     * halves are found in completely different ways and an OR would let neither use its index.
     *
     * <p>The live branch LEFT JOINs: a download the runner has not admitted yet has no task row at all,
     * and it is exactly the window in which the user is staring at the card wondering if the click
     * registered. An inner join here is what made every PENDING download invisible.
     *
     * <p>The finished branch is bounded by {@code finished_at}, and its predicate is written to match
     * {@code idx_download_tasks_recently_finished} so it never scans the history that V2 deliberately
     * keeps forever. The extra {@code d.status} test is not redundant: it makes the two branches
     * provably disjoint, so no row can be emitted twice whatever the two tables disagree about.
     */
    private static final String ACTIVE_DOWNLOADS_SQL = """
            SELECT %s
              FROM downloads d
              LEFT JOIN download_tasks t ON t.download_id = d.download_id
             WHERE d.status IN ('PENDING', 'IN_PROGRESS')
             UNION ALL
            SELECT %s
              FROM download_tasks t
              JOIN downloads d ON d.download_id = t.download_id
             WHERE t.phase IN ('SUCCEEDED', 'FAILED')
               AND d.status IN ('SUCCEEDED', 'FAILED')
               AND t.finished_at >= :cutoff
             ORDER BY updated_at DESC
            """.formatted(PROJECTION, PROJECTION);

    private static final String ALL_DOWNLOADS_SQL = """
            SELECT %s,
                   COUNT(*) OVER () AS total_count
              FROM downloads d
              LEFT JOIN download_tasks t ON t.download_id = d.download_id
             ORDER BY updated_at DESC, d.download_id DESC
             OFFSET (:pageSize * (:pageNumber - 1)) ROWS
             FETCH NEXT :pageSize ROWS ONLY
            """.formatted(PROJECTION);

    /**
     * No status filter and no window: this answers "what happened to these?" for a client that held
     * cards across a restart and outlived the retention window. An id with no row is simply absent from
     * the result, which is what lets the client treat absence here -- and only here -- as "gone".
     */
    private static final String BY_IDS_SQL = """
            SELECT %s
              FROM downloads d
              LEFT JOIN download_tasks t ON t.download_id = d.download_id
             WHERE d.download_id = ANY(:ids)
             ORDER BY updated_at DESC
            """.formatted(PROJECTION);

    private final DatabaseClient client;

    public ActiveDownloadRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    /** Everything non-terminal, plus anything that finished at or after {@code cutoff}. */
    public Flux<ActiveDownloadView> findActive(Instant cutoff) {
        return client.sql(ACTIVE_DOWNLOADS_SQL)
                .bind("cutoff", cutoff)
                .map(ActiveDownloadRepository::toView)
                .all();
    }

    public Flux<ActiveDownloadView> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }
        return client.sql(BY_IDS_SQL)
                .bind("ids", ids.toArray(UUID[]::new))
                .map(ActiveDownloadRepository::toView)
                .all();
    }

    public Mono<AllDownloadsResponse> findAll(Integer pageSize, Integer pageNumber) {
        return client.sql(ALL_DOWNLOADS_SQL)
                .bind("pageSize", pageSize)
                .bind("pageNumber", pageNumber)
                .map((row, meta) -> new PagedRow(toView(row, meta), row.get("total_count", Long.class)))
                .all()
                .collectList()
                .map(rows -> new AllDownloadsResponse(
                        rows.stream().map(PagedRow::view).toList(),
                        // A page past the end returns no rows, so the window function has nothing to
                        // report and the true total is unknowable from this query alone. Reporting 0
                        // here rather than issuing a second query is the signal the client uses to go
                        // back to page 1, which then returns the real total.
                        rows.isEmpty() ? 0 : (int) Math.ceilDiv(rows.get(0).totalCount(), pageSize)));
    }

    /** Every row of the paged query repeats the same total, so it is read once off the first row. */
    private record PagedRow(ActiveDownloadView view, long totalCount) {
    }

    private static ActiveDownloadView toView(Row row, RowMetadata meta) {
        DownloadStatus status = DownloadStatus.valueOf(row.get("status", String.class));
        return new ActiveDownloadView(
                row.get("download_id", UUID.class),
                row.get("song_name", String.class),
                toStage(status, row.get("phase", String.class)),
                row.get("progress_percent", BigDecimal.class),
                row.get("stage_entered_at", Instant.class),
                row.get("updated_at", Instant.class),
                row.get("failure_reason", String.class));
    }

    /**
     * The one place {@code status} and {@code phase} are combined. {@code status} decides terminality,
     * so a finished download reports its outcome whatever the task row says, and {@code phase} is only
     * consulted on the one branch where it is guaranteed to be a real working phase.
     */
    static DownloadStage toStage(DownloadStatus status, String phase) {
        return switch (status) {
            case SUCCEEDED -> DownloadStage.SUCCEEDED;
            case FAILED -> DownloadStage.FAILED;
            // Accepted but not admitted. There is no task row to read a phase from, and that absence
            // IS the state worth reporting.
            case PENDING -> DownloadStage.QUEUED;
            case IN_PROGRESS -> {
                DownloadPhase working = parsePhase(phase);
                yield working == null ? DownloadStage.QUEUED : switch (working) {
                    case SEARCH_INIT -> DownloadStage.STARTING;
                    case SEARCH_POLL -> DownloadStage.SEARCHING;
                    case DOWNLOAD_INIT -> DownloadStage.READY_TO_DOWNLOAD;
                    case DOWNLOAD_POLL -> DownloadStage.DOWNLOADING;
                };
            }
        };
    }

    /**
     * Lenient on purpose. The {@code phase} CHECK constraint also admits 'SUCCEEDED' and 'FAILED',
     * which {@link DownloadPhase} does not model, and the live query's LEFT JOIN yields null. Neither
     * is a reason to throw on a read path a UI polls every few seconds -- {@code status} has already
     * answered the question that matters by the time this is consulted. Kept exhaustive over
     * {@link DownloadPhase} so adding a working phase is a compile error in {@link #toStage}, not a
     * silently mislabelled card.
     */
    private static DownloadPhase parsePhase(String phase) {
        if (phase == null) {
            return null;
        }
        for (DownloadPhase candidate : DownloadPhase.values()) {
            if (candidate.name().equals(phase)) {
                return candidate;
            }
        }
        return null;
    }
}

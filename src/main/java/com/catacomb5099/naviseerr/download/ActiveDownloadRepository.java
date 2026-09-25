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
import java.util.List;
import java.util.UUID;

/**
 * Read model behind the download feed. Three queries over the same projection — everything the
 * client should be showing right now, a by-id lookup for cards a client held across a restart, and
 * the paged history — plus the per-song breakdown of one download.
 */
@Repository
public class ActiveDownloadRepository {

    /**
     * One row per DOWNLOAD, folded out of that download's N task rows — one per song. A ten-track
     * album must be one card, not ten identical {@code downloadId}s, so every task-side field here
     * is an aggregate. For a single-song download each aggregate is over one row and returns that
     * row's own value.
     *
     * <p>The reported stage is the LEAST advanced song's: a collection is still "searching" while
     * any of its tracks is, because the honest summary of mixed progress is the part that is not
     * done. {@code phase} is ranked to a number to take that minimum and mapped back, rather than
     * relying on the alphabetical order of the phase names, which is not the pipeline's order.
     *
     * <p>Progress is the mean across songs, so a collection's bar tracks the collection rather than
     * whichever track happens to be transferring. {@code updated_at} is the most recent write, since
     * that is the feed's recency sort key and any song's write means the download moved.
     * {@code failure_reason} is the first non-null: a collection reports a reason as soon as one
     * song has one, without waiting for the rest. The three counts are what let a card say
     * "7 of 12" without asking for the per-song view.
     */
    private static final String TASK_AGGREGATE = """
            SELECT t.download_id,
                   (ARRAY['SEARCH_INIT', 'SEARCH_POLL', 'DOWNLOAD_INIT', 'DOWNLOAD_POLL',
                          'FINISHED'])[
                       MIN(CASE t.phase WHEN 'SEARCH_INIT'   THEN 1
                                        WHEN 'SEARCH_POLL'   THEN 2
                                        WHEN 'DOWNLOAD_INIT' THEN 3
                                        WHEN 'DOWNLOAD_POLL' THEN 4
                                        ELSE 5 END)]           AS phase,
                   AVG(t.progress_percent)                     AS progress_percent,
                   MIN(t.failure_reason)                       AS failure_reason,
                   MIN(t.phase_entered_at)                     AS phase_entered_at,
                   MAX(t.updated_at)                           AS updated_at,
                   MAX(t.finished_at)                          AS finished_at,
                   COUNT(*)                                    AS song_count,
                   COUNT(*) FILTER (WHERE t.phase = 'SUCCEEDED') AS songs_succeeded,
                   COUNT(*) FILTER (WHERE t.phase = 'FAILED')    AS songs_failed
              FROM download_tasks t
             %s
             GROUP BY t.download_id""";

    /**
     * Shared so every query and every endpoint derives {@link DownloadStage} from identical inputs.
     * {@code status} and {@code phase} are selected only to compute it; neither reaches the client.
     *
     * <p>Both timestamps fall back to {@code d.created_at}, because a download with no task row still
     * has to sort and still has to show the user how long it has been waiting. Reading them straight
     * off the LEFT JOIN yields nulls for exactly the QUEUED rows this projection exists to expose, and
     * a null sort key is how a batch of queued cards ends up in arbitrary order. For a queued
     * download, "when did this stage begin" genuinely is when it was requested.
     *
     * <p>{@code failure_reason} prefers the download's own: that column is written only when
     * admission fails, in which case there are no task rows to have a reason. Otherwise the
     * aggregate's first song reason.
     *
     * <p>Every query joins {@code media_items} through {@code d.youtube_id} for the title, artists
     * and artwork. A LEFT JOIN, because the media row is written at admission and a QUEUED download
     * has not been admitted yet; a null title is that state, faithfully reported.
     */
    private static final String PROJECTION = """
            d.download_id, d.youtube_id, d.download_type, d.status, d.created_at, d.finished_at,
                   m.title, m.artists, m.image_url,
                   t.phase, t.progress_percent,
                   COALESCE(d.failure_reason, t.failure_reason) AS failure_reason,
                   COALESCE(t.song_count, 0)                    AS song_count,
                   COALESCE(t.songs_succeeded, 0)               AS songs_succeeded,
                   COALESCE(t.songs_failed, 0)                  AS songs_failed,
                   COALESCE(t.phase_entered_at, d.created_at)   AS stage_entered_at,
                   COALESCE(t.updated_at, d.created_at)         AS updated_at""";

    private static final String JOIN_MEDIA = "LEFT JOIN media_items m ON m.youtube_id = d.youtube_id";

    /**
     * Restricts {@link #TASK_AGGREGATE} to the live downloads, so the grouping never touches the
     * terminal history V2 deliberately keeps forever. Driven by
     * {@code idx_downloads_status_created_at}.
     */
    private static final String WHERE_LIVE = """
            WHERE t.download_id IN (SELECT download_id FROM downloads
                                     WHERE status IN ('PENDING', 'IN_PROGRESS'))""";

    /**
     * Restricts it to downloads with a song that finished inside the retention window. The inner
     * predicate matches {@code idx_download_tasks_recently_finished}, the same index the
     * pre-collections query read, so the same bound still holds. The subquery is needed because the
     * aggregate has to see ALL of a download's songs to summarise it, not only the ones that
     * finished recently.
     */
    private static final String WHERE_RECENTLY_FINISHED = """
            WHERE t.download_id IN (SELECT download_id FROM download_tasks
                                     WHERE phase IN ('SUCCEEDED', 'FAILED')
                                       AND finished_at >= :cutoff)""";

    /** The history endpoint reports every download, so its aggregate is unfiltered. */
    private static final String WHERE_ALL = "";

    /**
     * A UNION ALL of two separately-indexed branches rather than one query with an OR, because the two
     * halves are found in completely different ways and an OR would let neither use its index.
     *
     * <p>The live branch LEFT JOINs the aggregate: a download the runner has not admitted yet has no
     * task rows at all, and it is exactly the window in which the user is staring at the card
     * wondering if the click registered. An inner join here is what made every PENDING download
     * invisible.
     *
     * <p>The finished branch is bounded by {@code finished_at}. The extra {@code d.status} test is
     * not redundant: it makes the two branches provably disjoint, so no row can be emitted twice
     * whatever the two tables disagree about. It tests {@code PARTIAL_SUCCESS} too, or a collection
     * that half-succeeded would finish and then never be reported.
     */
    private static final String ACTIVE_DOWNLOADS_SQL = """
            SELECT %s
              FROM downloads d
              %s
              LEFT JOIN (%s) t ON t.download_id = d.download_id
             WHERE d.status IN ('PENDING', 'IN_PROGRESS')
             UNION ALL
            SELECT %s
              FROM (%s) t
              JOIN downloads d ON d.download_id = t.download_id
              %s
             WHERE t.phase = 'FINISHED'
               AND d.status IN ('SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS')
               AND t.finished_at >= :cutoff
             ORDER BY updated_at DESC
            """.formatted(PROJECTION, JOIN_MEDIA, TASK_AGGREGATE.formatted(WHERE_LIVE),
                          PROJECTION, TASK_AGGREGATE.formatted(WHERE_RECENTLY_FINISHED), JOIN_MEDIA);

    private static final String ALL_DOWNLOADS_SQL = """
            SELECT %s,
                   COUNT(*) OVER () AS total_count
              FROM downloads d
              %s
              LEFT JOIN (%s) t ON t.download_id = d.download_id
             ORDER BY updated_at DESC, d.download_id DESC
             OFFSET (:pageSize * (:pageNumber - 1)) ROWS
             FETCH NEXT :pageSize ROWS ONLY
            """.formatted(PROJECTION, JOIN_MEDIA, TASK_AGGREGATE.formatted(WHERE_ALL));

    /**
     * No status filter and no window: this answers "what happened to these?" for a client that held
     * cards across a restart and outlived the retention window. An id with no row is simply absent from
     * the result, which is what lets the client treat absence here -- and only here -- as "gone".
     */
    private static final String BY_IDS_SQL = """
            SELECT %s
              FROM downloads d
              %s
              LEFT JOIN (%s) t ON t.download_id = d.download_id
             WHERE d.download_id = ANY(:ids)
             ORDER BY updated_at DESC
            """.formatted(PROJECTION, JOIN_MEDIA,
                          TASK_AGGREGATE.formatted("WHERE t.download_id = ANY(:ids)"));

    /**
     * Every song of one download, in track order, with the pipeline bookkeeping a self-hoster wants
     * when asking "which three failed, and why". No aggregate: this is the one place the task rows
     * are shown as themselves. {@code candidate_count} is computed in SQL rather than by parsing the
     * JSON in Java, since it is the only thing this view wants from that column.
     */
    private static final String SONGS_SQL = """
            SELECT t.task_id, t.youtube_id, t.position, m.title, m.artists, m.image_url,
                   m.duration_seconds, t.phase, t.progress_percent, t.failure_reason,
                   t.phase_entered_at, t.updated_at, t.finished_at,
                   jsonb_array_length(t.candidates::jsonb) AS candidate_count,
                   t.candidate_index, t.retry_index, t.slskd_username, t.slskd_filename,
                   t.last_error
              FROM download_tasks t
              LEFT JOIN media_items m ON m.youtube_id = t.youtube_id
             WHERE t.download_id = :id
             ORDER BY t.position NULLS LAST, t.phase_entered_at, t.task_id
            """;

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

    /** The songs of one download, in track order. Empty for a download not yet admitted. */
    public Flux<DownloadSongView> findSongs(UUID downloadId) {
        return client.sql(SONGS_SQL)
                .bind("id", downloadId)
                .map(ActiveDownloadRepository::toSongView)
                .all();
    }

    /** Every row of the paged query repeats the same total, so it is read once off the first row. */
    private record PagedRow(ActiveDownloadView view, long totalCount) {
    }

    private static ActiveDownloadView toView(Row row, RowMetadata meta) {
        DownloadStatus status = DownloadStatus.valueOf(row.get("status", String.class));
        return new ActiveDownloadView(
                row.get("download_id", UUID.class),
                row.get("youtube_id", String.class),
                DownloadType.valueOf(row.get("download_type", String.class)),
                row.get("title", String.class),
                artists(row),
                row.get("image_url", String.class),
                toStage(status, row.get("phase", String.class)),
                row.get("progress_percent", BigDecimal.class),
                row.get("song_count", Long.class).intValue(),
                row.get("songs_succeeded", Long.class).intValue(),
                row.get("songs_failed", Long.class).intValue(),
                row.get("created_at", Instant.class),
                row.get("stage_entered_at", Instant.class),
                row.get("updated_at", Instant.class),
                row.get("finished_at", Instant.class),
                row.get("failure_reason", String.class));
    }

    private static DownloadSongView toSongView(Row row, RowMetadata meta) {
        return new DownloadSongView(
                row.get("task_id", UUID.class),
                row.get("youtube_id", String.class),
                row.get("position", Integer.class),
                row.get("title", String.class),
                artists(row),
                row.get("image_url", String.class),
                row.get("duration_seconds", Integer.class),
                toSongStage(row.get("phase", String.class)),
                row.get("progress_percent", BigDecimal.class),
                row.get("failure_reason", String.class),
                row.get("phase_entered_at", Instant.class),
                row.get("updated_at", Instant.class),
                row.get("finished_at", Instant.class),
                row.get("candidate_count", Integer.class),
                row.get("candidate_index", Integer.class),
                row.get("retry_index", Integer.class),
                row.get("slskd_username", String.class),
                row.get("slskd_filename", String.class),
                row.get("last_error", String.class));
    }

    /** Null from the LEFT JOIN (no media row yet) reads as "no artists", never as null. */
    private static List<String> artists(Row row) {
        String[] artists = row.get("artists", String[].class);
        return artists == null ? List.of() : List.of(artists);
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
            case PARTIAL_SUCCESS -> DownloadStage.PARTIAL_SUCCESS;
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
     * A single song's stage from its own {@code phase}. A task row always exists here, and its two
     * terminal phase values are its own outcome — so this is {@link #toStage} with the terminality
     * read off the phase instead of the download's status.
     */
    static DownloadStage toSongStage(String phase) {
        return switch (phase) {
            case "SUCCEEDED" -> DownloadStage.SUCCEEDED;
            case "FAILED" -> DownloadStage.FAILED;
            default -> toStage(DownloadStatus.IN_PROGRESS, phase);
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

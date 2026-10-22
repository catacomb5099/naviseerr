package com.catacomb5099.naviseerr.learning;

import com.catacomb5099.naviseerr.download.DownloadCandidate;
import com.catacomb5099.naviseerr.download.SearchLogEntry;
import com.catacomb5099.naviseerr.services.slskd.DownloadQuality;
import io.r2dbc.spi.Readable;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The reports of successful searches (search learning, 20-10-2026), built on demand from the rows that already exist:
 * no outbox table. A success is a song that was downloaded from a search AND filed in the library: a song the library
 * already had ({@code candidates = '[]'}) or one matched to a copy filed later ({@code slskd_filename} cleared) found
 * nothing on Soulseek, so it teaches nothing.
 */
@Repository
public class SearchLearningRepository {

    private static final String REPORT_SQL = """
            SELECT t.task_id, t.finished_at, d.download_type, t.youtube_id, t.song_name, t.search_log::text AS search_log,
                   COALESCE(t.track_title, m.title) AS title, m.artists,
                   COALESCE(t.duration_seconds, m.duration_seconds) AS seconds,
                   COALESCE(CASE WHEN d.download_type = 'ALBUM' THEN dm.title END, am.title) AS album,
                   (t.candidates::jsonb -> t.candidate_index)::text AS chosen, t.candidate_index + 1 AS attempt,
                   a.folders_query, s.download_quality
              FROM download_tasks t
              JOIN downloads d ON d.download_id = t.download_id
              LEFT JOIN install_settings s ON TRUE
              LEFT JOIN media_items m ON m.youtube_id = t.youtube_id
              LEFT JOIN media_items dm ON dm.youtube_id = d.youtube_id
              LEFT JOIN song_albums sa ON sa.youtube_id = t.youtube_id
              LEFT JOIN media_items am ON am.youtube_id = sa.album_id
              LEFT JOIN album_searches a ON a.download_id = t.download_id
             WHERE t.phase = 'SUCCEEDED' AND t.library_path IS NOT NULL AND t.slskd_filename IS NOT NULL
               AND jsonb_array_length(t.candidates::jsonb) > 0
            """;

    /** Newest first, for the export and for "See what is sent". */
    private static final String NEWEST_SQL = REPORT_SQL + """
               AND (CAST(:before AS TIMESTAMPTZ) IS NULL OR t.finished_at < :before)
             ORDER BY t.finished_at DESC, t.task_id
             LIMIT :limit
            """;

    private static final TypeReference<List<SearchLogEntry>> LOG = new TypeReference<>() {};

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;

    public SearchLearningRepository(R2dbcEntityTemplate template, ObjectMapper objectMapper) {
        this.client = template.getDatabaseClient();
        this.objectMapper = objectMapper;
    }

    /** Successful searches that finished before {@code before} (null: up to now), newest first. */
    public Flux<SearchReport> newest(Instant before, int limit) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(NEWEST_SQL).bind("limit", limit);
        spec = before == null ? spec.bindNull("before", Instant.class) : spec.bind("before", before);
        return spec.map(this::report).all();
    }

    private SearchReport report(Readable row) {
        String[] artists = row.get("artists", String[].class);
        String quality = row.get("download_quality", String.class);
        return SearchReport.of(row.get("task_id", UUID.class), row.get("finished_at", Instant.class),
                row.get("download_type", String.class),
                new SearchReport.Song(row.get("youtube_id", String.class), row.get("title", String.class),
                        artists == null ? List.of() : List.of(artists), row.get("album", String.class),
                        row.get("seconds", Integer.class), row.get("song_name", String.class)),
                objectMapper.readValue(row.get("search_log", String.class), LOG),
                objectMapper.readValue(row.get("chosen", String.class), DownloadCandidate.class),
                row.get("folders_query", String.class), row.get("attempt", Integer.class),
                quality == null ? DownloadQuality.DEFAULT.name() : quality);
    }
}

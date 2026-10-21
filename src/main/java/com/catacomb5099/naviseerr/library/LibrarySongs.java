package com.catacomb5099.naviseerr.library;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The one read behind {@code GET /library/songs}: every song row ever asked for under these YouTube ids, newest first. */
@Repository
public class LibrarySongs {

    /** One {@code download_tasks} row; {@code finished} is false while the song is still being worked on. */
    public record Row(String youtubeId, UUID downloadId, boolean finished, String libraryPath, Instant finishedAt) {}

    // ponytail: no index on youtube_id (admission's filed-copies read has none either); add one if the table grows large.
    private static final String SQL = """
            SELECT youtube_id, download_id, phase, library_path, finished_at
              FROM download_tasks
             WHERE youtube_id = ANY(:ids)
               AND phase <> 'FAILED'
             ORDER BY phase_entered_at DESC
            """;

    private final DatabaseClient client;

    public LibrarySongs(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    public Flux<Row> rows(List<String> youtubeIds) {
        return client.sql(SQL)
                .bind("ids", youtubeIds.toArray(String[]::new))
                .map(row -> new Row(row.get("youtube_id", String.class), row.get("download_id", UUID.class),
                        "SUCCEEDED".equals(row.get("phase", String.class)), row.get("library_path", String.class),
                        row.get("finished_at", Instant.class)))
                .all();
    }
}

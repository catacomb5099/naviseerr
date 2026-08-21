package com.catacomb5099.naviseerr.download;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read model for the client-facing progress endpoint: one row per non-terminal download. */
@Repository
public class ActiveDownloadRepository {

    private static final String ACTIVE_DOWNLOADS_SQL = """
            SELECT d.download_id, d.song_name, d.status, t.phase, t.progress_percent,
                   t.phase_entered_at
              FROM downloads d
              JOIN download_tasks t ON t.download_id = d.download_id
             WHERE d.status NOT IN ('SUCCEEDED', 'FAILED')
             ORDER BY d.created_at
            """;

    private final DatabaseClient client;

    public ActiveDownloadRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    public Flux<ActiveDownloadView> findActive() {
        return client.sql(ACTIVE_DOWNLOADS_SQL)
                .map((row, meta) -> new ActiveDownloadView(
                        row.get("download_id", UUID.class),
                        row.get("song_name", String.class),
                        DownloadStatus.valueOf(row.get("status", String.class)),
                        DownloadPhase.valueOf(row.get("phase", String.class)),
                        row.get("progress_percent", BigDecimal.class),
                        row.get("phase_entered_at", Instant.class)))
                .all();
    }
}

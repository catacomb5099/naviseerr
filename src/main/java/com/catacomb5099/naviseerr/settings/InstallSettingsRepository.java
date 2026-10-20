package com.catacomb5099.naviseerr.settings;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * All {@code install_settings} SQL (V18): the one row the first-run setup and the Settings page write.
 * Every write is an upsert on the one-row key, so the first save of any section creates the row and
 * the others leave each other's columns alone.
 */
@Repository
public class InstallSettingsRepository {

    private static final String FIND_SQL = """
            SELECT setup_completed_at, suggested_playlists, navidrome_url, navidrome_user, navidrome_password,
                   jellyfin_url, jellyfin_api_key, slskd_url, slskd_api_key
              FROM install_settings
            """;

    private static final String MARK_SETUP_DONE_SQL = """
            INSERT INTO install_settings (setup_completed_at) VALUES (now())
            ON CONFLICT (one) DO UPDATE SET setup_completed_at = now(), updated_at = now()
            """;

    private final DatabaseClient client;

    public InstallSettingsRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    /** The saved row, or {@link InstallSettings#NONE} when nothing was ever saved (a fresh install). */
    public Mono<InstallSettings> find() {
        return client.sql(FIND_SQL).map(row -> new InstallSettings(
                        row.get("setup_completed_at", Instant.class),
                        Boolean.TRUE.equals(row.get("suggested_playlists", Boolean.class)),
                        row.get("navidrome_url", String.class), row.get("navidrome_user", String.class),
                        row.get("navidrome_password", String.class),
                        row.get("jellyfin_url", String.class), row.get("jellyfin_api_key", String.class),
                        row.get("slskd_url", String.class), row.get("slskd_api_key", String.class)))
                .one()
                .defaultIfEmpty(InstallSettings.NONE);
    }

    /** Finish (or skip through) the first-run setup: from now on the app opens on its Home page. */
    public Mono<Void> markSetupDone() {
        return client.sql(MARK_SETUP_DONE_SQL).fetch().rowsUpdated().then();
    }
}

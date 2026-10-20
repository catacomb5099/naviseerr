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

    private static final String SAVE_NAVIDROME_SQL = """
            INSERT INTO install_settings (navidrome_url, navidrome_user, navidrome_password) VALUES (:url, :username, :password)
            ON CONFLICT (one) DO UPDATE SET navidrome_url = EXCLUDED.navidrome_url, navidrome_user = EXCLUDED.navidrome_user,
                                            navidrome_password = EXCLUDED.navidrome_password, updated_at = now()
            """;

    private static final String SAVE_JELLYFIN_SQL = """
            INSERT INTO install_settings (jellyfin_url, jellyfin_api_key) VALUES (:url, :apiKey)
            ON CONFLICT (one) DO UPDATE SET jellyfin_url = EXCLUDED.jellyfin_url, jellyfin_api_key = EXCLUDED.jellyfin_api_key,
                                            updated_at = now()
            """;

    private static final String CLEAR_NAVIDROME_SQL = "UPDATE install_settings SET navidrome_url = NULL, navidrome_user = NULL, "
            + "navidrome_password = NULL, updated_at = now()";

    private static final String CLEAR_JELLYFIN_SQL = "UPDATE install_settings SET jellyfin_url = NULL, jellyfin_api_key = NULL, "
            + "updated_at = now()";

    private static final String SET_SUGGESTED_PLAYLISTS_SQL = """
            INSERT INTO install_settings (suggested_playlists) VALUES (:on)
            ON CONFLICT (one) DO UPDATE SET suggested_playlists = EXCLUDED.suggested_playlists, updated_at = now()
            """;

    /** False with no row at all: a fresh install makes no playlists until someone says yes. */
    private static final String SUGGESTED_PLAYLISTS_SQL = "SELECT COALESCE((SELECT suggested_playlists FROM install_settings), FALSE) AS switched_on";

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

    /** The Navidrome the app tells about new music (after "Save and test" passed); wins over the install file's lines. */
    public Mono<Void> saveNavidrome(String url, String username, String password) {
        return client.sql(SAVE_NAVIDROME_SQL).bind("url", url).bind("username", username).bind("password", password)
                .fetch().rowsUpdated().then();
    }

    public Mono<Void> saveJellyfin(String url, String apiKey) {
        return client.sql(SAVE_JELLYFIN_SQL).bind("url", url).bind("apiKey", apiKey).fetch().rowsUpdated().then();
    }

    /** Back to the install file's lines (or to none) for that server. No row: nothing to clear. */
    public Mono<Void> clearNavidrome() {
        return client.sql(CLEAR_NAVIDROME_SQL).fetch().rowsUpdated().then();
    }

    public Mono<Void> clearJellyfin() {
        return client.sql(CLEAR_JELLYFIN_SQL).fetch().rowsUpdated().then();
    }

    /** "Make weekly playlists for me": the switch the setup's third screen and Settings flip. */
    public Mono<Void> setSuggestedPlaylists(boolean on) {
        return client.sql(SET_SUGGESTED_PLAYLISTS_SQL).bind("on", on).fetch().rowsUpdated().then();
    }

    /** Asked at every cron tick and on every suggested-playlists request: one SELECT on a one-row table. */
    public Mono<Boolean> suggestedPlaylists() {
        return client.sql(SUGGESTED_PLAYLISTS_SQL).map(row -> Boolean.TRUE.equals(row.get("switched_on", Boolean.class))).one();
    }
}

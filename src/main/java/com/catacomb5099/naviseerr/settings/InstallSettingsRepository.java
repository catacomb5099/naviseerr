package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.services.slskd.DownloadQuality;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;

/**
 * All {@code install_settings} SQL (V18, V19): the one row the first-run setup and the Settings page write.
 * Every write is an upsert on the one-row key, so the first save of any section creates the row and
 * the others leave each other's columns alone.
 */
@Repository
public class InstallSettingsRepository {

    private static final String FIND_SQL = """
            SELECT setup_completed_at, suggested_playlists, navidrome_url, navidrome_user, navidrome_password,
                   jellyfin_url, jellyfin_api_key, slskd_url, slskd_api_key, download_quality
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

    private static final String SAVE_SLSKD_SQL = """
            INSERT INTO install_settings (slskd_url, slskd_api_key) VALUES (:url, :apiKey)
            ON CONFLICT (one) DO UPDATE SET slskd_url = EXCLUDED.slskd_url, slskd_api_key = EXCLUDED.slskd_api_key, updated_at = now()
            """;

    private static final String CLEAR_SLSKD_SQL = "UPDATE install_settings SET slskd_url = NULL, slskd_api_key = NULL, updated_at = now()";

    private static final String SET_DOWNLOAD_QUALITY_SQL = """
            INSERT INTO install_settings (download_quality) VALUES (:quality)
            ON CONFLICT (one) DO UPDATE SET download_quality = EXCLUDED.download_quality, updated_at = now()
            """;

    /** Null with no row, or a row where nothing was chosen: the default applies. */
    private static final String DOWNLOAD_QUALITY_SQL = "SELECT (SELECT download_quality FROM install_settings) AS quality";

    /** On keeps the first switch-on moment (a second "on" does not move it); off clears it. */
    private static final String SET_SEARCH_LEARNING_SQL = """
            INSERT INTO install_settings (search_learning_since) VALUES (CASE WHEN :on THEN now() END)
            ON CONFLICT (one) DO UPDATE SET search_learning_since =
                CASE WHEN :on THEN COALESCE(install_settings.search_learning_since, now()) END, updated_at = now()
            """;

    /** Null with no row or the switch off. */
    private static final String SEARCH_LEARNING_SINCE_SQL = "SELECT (SELECT search_learning_since FROM install_settings) AS since";

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
                        row.get("slskd_url", String.class), row.get("slskd_api_key", String.class),
                        quality(row.get("download_quality", String.class))))
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

    /** Your own slskd (after "Save and test" reached it); wins over the install file's SLSKD_URL / SLSKD_API_KEY. */
    public Mono<Void> saveSlskd(String url, String apiKey) {
        return client.sql(SAVE_SLSKD_SQL).bind("url", url).bind("apiKey", apiKey).fetch().rowsUpdated().then();
    }

    /** Back to the install file's slskd. */
    public Mono<Void> clearSlskd() {
        return client.sql(CLEAR_SLSKD_SQL).fetch().rowsUpdated().then();
    }

    /** The download quality chosen on the setup's first screen or in Settings; stored as its word (V19). */
    public Mono<Void> setDownloadQuality(DownloadQuality quality) {
        return client.sql(SET_DOWNLOAD_QUALITY_SQL).bind("quality", quality.name()).fetch().rowsUpdated().then();
    }

    /**
     * The quality the picker prefers right now, {@link DownloadQuality#DEFAULT} until someone chooses. Read
     * once per completed search (one SELECT on a one-row table), so a change applies to the next download.
     */
    public Mono<DownloadQuality> downloadQuality() {
        return client.sql(DOWNLOAD_QUALITY_SQL)
                .map(row -> {
                    DownloadQuality chosen = quality(row.get("quality", String.class));
                    return chosen == null ? DownloadQuality.DEFAULT : chosen;
                })
                .one();
    }

    /** "Help improve search" (search learning, V22): on from now, or off. */
    public Mono<Void> setSearchLearning(boolean on) {
        return client.sql(SET_SEARCH_LEARNING_SQL).bind("on", on).fetch().rowsUpdated().then();
    }

    /** When "Help improve search" was switched on; empty while it is off (the default). */
    public Mono<Instant> searchLearningSince() {
        return client.sql(SEARCH_LEARNING_SINCE_SQL).map(row -> Optional.ofNullable(row.get("since", Instant.class))).one()
                .flatMap(Mono::justOrEmpty);
    }

    /** The stored word, or null for nothing chosen; V19's CHECK keeps it one of the five. */
    private static DownloadQuality quality(String stored) {
        return stored == null ? null : DownloadQuality.valueOf(stored);
    }
}

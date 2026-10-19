package com.catacomb5099.naviseerr.auth;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/** All {@code login_settings} SQL (V16): the one row saying how to reach Authentik, and whether the login is on. */
@Repository
public class LoginSettingsRepository {

    private static final String FIND_SQL = "SELECT issuer, client_id, client_secret, enabled FROM login_settings";

    private static final String SAVE_SQL = """
            INSERT INTO login_settings (issuer, client_id, client_secret)
            VALUES (:issuer, :clientId, :clientSecret)
            ON CONFLICT (one) DO UPDATE
               SET issuer = EXCLUDED.issuer, client_id = EXCLUDED.client_id,
                   client_secret = EXCLUDED.client_secret, updated_at = now()
            """;

    private static final String SET_ENABLED_SQL = "UPDATE login_settings SET enabled = :enabled, updated_at = now()";

    /** False with no row at all: an install that never connected Authentik is open. */
    private static final String IS_ENABLED_SQL = "SELECT COALESCE((SELECT enabled FROM login_settings), FALSE) AS enabled";

    private final DatabaseClient client;

    public LoginSettingsRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    /** The saved settings; empty when Authentik was never connected. */
    public Mono<LoginSettings> find() {
        return client.sql(FIND_SQL).map(row -> new LoginSettings(row.get("issuer", String.class),
                row.get("client_id", String.class), row.get("client_secret", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)))).one();
    }

    /** Writes the row, or replaces its values; {@code enabled} is left as it was. */
    public Mono<Void> save(String issuer, String clientId, String clientSecret) {
        return client.sql(SAVE_SQL).bind("issuer", issuer).bind("clientId", clientId).bind("clientSecret", clientSecret)
                .fetch().rowsUpdated().then();
    }

    /** True turns the door on (the first successful sign-in); false opens the app again ("Turn off"). */
    public Mono<Void> setEnabled(boolean enabled) {
        return client.sql(SET_ENABLED_SQL).bind("enabled", enabled).fetch().rowsUpdated().then();
    }

    /** Asked on every request by the filter: one SELECT on a one-row table, so there is no cache to get wrong. */
    public Mono<Boolean> isEnabled() {
        return client.sql(IS_ENABLED_SQL).map(row -> Boolean.TRUE.equals(row.get("enabled", Boolean.class))).one();
    }
}

package com.catacomb5099.naviseerr.auth;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * All {@code users} and {@code sessions} SQL (V16). A session row is the random id the browser's cookie
 * carries; the user it belongs to is read on every request through {@link #findBySession}.
 */
@Repository
public class AuthRepository {

    private static final String ANY_USER_SQL = "SELECT EXISTS (SELECT 1 FROM users) AS present";

    /** One statement, so of two first-run screens submitted at once the second normally sees the row and updates 0.
     *  A true tie under READ COMMITTED can still make two accounts; harmless, either could have been first. */
    private static final String CREATE_FIRST_USER_SQL = """
            INSERT INTO users (user_id, username, password_hash, role)
            SELECT :id, :username, :hash, 'ADMIN'
             WHERE NOT EXISTS (SELECT 1 FROM users)
            """;

    private static final String FIND_BY_USERNAME_SQL = """
            SELECT user_id, username, password_hash, role
              FROM users
             WHERE lower(username) = lower(:username)
            """;

    private static final String CREATE_SESSION_SQL = """
            INSERT INTO sessions (session_id, user_id, expires_at)
            VALUES (:id, :userId, :expiresAt)
            """;

    private static final String FIND_BY_SESSION_SQL = """
            SELECT u.user_id, u.username, u.password_hash, u.role
              FROM sessions s
              JOIN users u ON u.user_id = s.user_id
             WHERE s.session_id = :id AND s.expires_at > :now
            """;

    private static final String DELETE_SESSION_SQL = "DELETE FROM sessions WHERE session_id = :id";
    private static final String DELETE_EXPIRED_SQL = "DELETE FROM sessions WHERE expires_at <= :now";

    private final DatabaseClient client;

    public AuthRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    /** Does this install have an account yet? False is what shows the first-run screen. */
    public Mono<Boolean> anyUser() {
        return client.sql(ANY_USER_SQL).map(row -> Boolean.TRUE.equals(row.get("present", Boolean.class))).one();
    }

    /** Creates the first account; true when this call made it, false when one existed already. */
    public Mono<Boolean> createFirstUser(UUID id, String username, String passwordHash) {
        return client.sql(CREATE_FIRST_USER_SQL)
                .bind("id", id).bind("username", username).bind("hash", passwordHash)
                .fetch().rowsUpdated().map(n -> n > 0);
    }

    /** By name, case-insensitively; empty when unknown. */
    public Mono<UserRow> findByUsername(String username) {
        return client.sql(FIND_BY_USERNAME_SQL).bind("username", username).map(AuthRepository::user).one();
    }

    public Mono<Void> createSession(String sessionId, UUID userId, Instant expiresAt) {
        return client.sql(CREATE_SESSION_SQL)
                .bind("id", sessionId).bind("userId", userId).bind("expiresAt", expiresAt)
                .fetch().rowsUpdated().then();
    }

    /** The user a live session belongs to; empty for an unknown or expired id. */
    public Mono<UserRow> findBySession(String sessionId, Instant now) {
        return client.sql(FIND_BY_SESSION_SQL).bind("id", sessionId).bind("now", now).map(AuthRepository::user).one();
    }

    public Mono<Void> deleteSession(String sessionId) {
        return client.sql(DELETE_SESSION_SQL).bind("id", sessionId).fetch().rowsUpdated().then();
    }

    /** Sweeps expired rows; run at each login so the table never needs a scheduler. */
    public Mono<Void> deleteExpired(Instant now) {
        return client.sql(DELETE_EXPIRED_SQL).bind("now", now).fetch().rowsUpdated().then();
    }

    private static UserRow user(io.r2dbc.spi.Readable row) {
        return new UserRow(row.get("user_id", UUID.class), row.get("username", String.class),
                row.get("password_hash", String.class), row.get("role", String.class));
    }
}

package com.catacomb5099.naviseerr.auth;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * All {@code sessions} SQL (V17). A session row is the random id the browser's cookie carries and the
 * Authentik subject it was issued to; the filter reads it on every request while the login is on.
 */
@Repository
public class AuthRepository {

    private static final String CREATE_SQL = "INSERT INTO sessions (session_id, subject, expires_at) VALUES (:id, :subject, :expiresAt)";
    private static final String FIND_SQL = "SELECT subject FROM sessions WHERE session_id = :id AND expires_at > :now";
    private static final String DELETE_SQL = "DELETE FROM sessions WHERE session_id = :id";
    private static final String DELETE_EXPIRED_SQL = "DELETE FROM sessions WHERE expires_at <= :now";
    private static final String DELETE_ALL_SQL = "DELETE FROM sessions";

    private final DatabaseClient client;

    public AuthRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    public Mono<Void> createSession(String sessionId, String subject, Instant expiresAt) {
        return client.sql(CREATE_SQL).bind("id", sessionId).bind("subject", subject).bind("expiresAt", expiresAt)
                .fetch().rowsUpdated().then();
    }

    /** The subject a live session belongs to; empty for an unknown or expired id. */
    public Mono<String> findBySession(String sessionId, Instant now) {
        return client.sql(FIND_SQL).bind("id", sessionId).bind("now", now).map(row -> row.get("subject", String.class)).one();
    }

    public Mono<Void> deleteSession(String sessionId) {
        return client.sql(DELETE_SQL).bind("id", sessionId).fetch().rowsUpdated().then();
    }

    /** Sweeps expired rows; run at each sign-in so the table never needs a scheduler. */
    public Mono<Void> deleteExpired(Instant now) {
        return client.sql(DELETE_EXPIRED_SQL).bind("now", now).fetch().rowsUpdated().then();
    }

    /** Everyone out: "Turn off Authentik login". */
    public Mono<Void> deleteAll() {
        return client.sql(DELETE_ALL_SQL).fetch().rowsUpdated().then();
    }
}

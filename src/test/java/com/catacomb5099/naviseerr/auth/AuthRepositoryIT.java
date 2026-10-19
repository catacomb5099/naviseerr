package com.catacomb5099.naviseerr.auth;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class AuthRepositoryIT {

    @Autowired AuthRepository repository;
    @Autowired R2dbcEntityTemplate template;

    /** The context (and its database) is shared with the other ITs: leave the login tables as found. */
    @BeforeEach
    @AfterEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM users").fetch().rowsUpdated().block();
    }

    @Test
    void theFirstAccountIsMadeOnce_andNamesMatchWhateverTheCase() {
        assertEquals(Boolean.FALSE, repository.anyUser().block());

        UUID id = UUID.randomUUID();
        assertEquals(Boolean.TRUE, repository.createFirstUser(id, "Alex", "hash-1").block());
        assertEquals(Boolean.FALSE, repository.createFirstUser(UUID.randomUUID(), "bob", "hash-2").block(), "a second first user");
        assertEquals(Boolean.TRUE, repository.anyUser().block());

        UserRow alex = repository.findByUsername("aLEX").block();
        assertNotNull(alex);
        assertEquals(id, alex.userId());
        assertEquals("Alex", alex.username(), "stored as typed");
        assertEquals("hash-1", alex.passwordHash());
        assertEquals("ADMIN", alex.role());
        assertNull(repository.findByUsername("bob").block());
    }

    @Test
    void aSessionIsFoundUntilItExpires_orIsDeleted_andExpiredOnesAreSwept() {
        UUID id = UUID.randomUUID();
        repository.createFirstUser(id, "alex", "hash").block();
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        repository.createSession("live", id, now.plusSeconds(3600)).block();
        repository.createSession("stale", id, now.minusSeconds(1)).block();

        assertEquals("alex", repository.findBySession("live", now).map(UserRow::username).block());
        assertNull(repository.findBySession("live", now.plusSeconds(3600)).block(), "expiry is exclusive");
        assertNull(repository.findBySession("stale", now).block());
        assertNull(repository.findBySession("never", now).block());

        repository.deleteExpired(now).block();
        assertNotNull(repository.findBySession("live", now).block(), "the live one survives the sweep");
        repository.deleteSession("live").block();
        assertNull(repository.findBySession("live", now).block());
    }
}

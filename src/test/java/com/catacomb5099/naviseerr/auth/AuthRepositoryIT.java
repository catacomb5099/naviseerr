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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class AuthRepositoryIT {

    @Autowired AuthRepository repository;
    @Autowired R2dbcEntityTemplate template;

    /** The context (and its database) is shared with the other ITs: leave the table as found. */
    @BeforeEach
    @AfterEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM sessions").fetch().rowsUpdated().block();
    }

    @Test
    void aSessionIsFoundUntilItExpires_orIsDeleted_expiredOnesAreSwept_andTurnOffDropsAll() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        repository.createSession("live", "subject-a", now.plusSeconds(3600)).block();
        repository.createSession("stale", "subject-a", now.minusSeconds(1)).block();
        repository.createSession("other", "subject-b", now.plusSeconds(3600)).block();

        assertEquals("subject-a", repository.findBySession("live", now).block());
        assertNull(repository.findBySession("live", now.plusSeconds(3600)).block(), "expiry is exclusive");
        assertNull(repository.findBySession("stale", now).block());
        assertNull(repository.findBySession("never", now).block());

        repository.deleteExpired(now).block();
        assertNotNull(repository.findBySession("live", now).block(), "the live one survives the sweep");
        repository.deleteSession("live").block();
        assertNull(repository.findBySession("live", now).block());
        assertEquals("subject-b", repository.findBySession("other", now).block());

        repository.deleteAll().block();
        assertNull(repository.findBySession("other", now).block());
    }
}

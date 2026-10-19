package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class InstallSettingsRepositoryIT {

    @Autowired InstallSettingsRepository repository;
    @Autowired R2dbcEntityTemplate template;

    /** The context (and its database) is shared with the other ITs: leave the table as found. */
    @BeforeEach
    @AfterEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM install_settings").fetch().rowsUpdated().block();
    }

    private long rows() {
        return template.getDatabaseClient().sql("SELECT count(*) AS n FROM install_settings")
                .map(row -> row.get("n", Long.class)).one().block();
    }

    @Test
    void aFreshInstall_hasNoRow_andTheSetupIsNotCompleted() {
        InstallSettings s = repository.find().block();
        assertEquals(InstallSettings.NONE, s);
        assertFalse(s.setupCompleted());
        assertFalse(s.suggestedPlaylists(), "a fresh install makes no playlists until someone says yes");
    }

    @Test
    void markSetupDone_createsTheRow_andASecondCallKeepsOneRow() {
        repository.markSetupDone().block();
        InstallSettings first = repository.find().block();
        assertTrue(first.setupCompleted());
        assertNotNull(first.setupCompletedAt());

        repository.markSetupDone().block();
        assertTrue(repository.find().block().setupCompleted());
        assertEquals(1L, rows(), "one row, ever");
    }

    @Test
    void markSetupDone_leavesTheOtherColumnsAsTheyWere() {
        template.getDatabaseClient().sql("INSERT INTO install_settings (suggested_playlists) VALUES (TRUE)")
                .fetch().rowsUpdated().block();

        repository.markSetupDone().block();

        InstallSettings s = repository.find().block();
        assertTrue(s.setupCompleted());
        assertTrue(s.suggestedPlaylists(), "finishing the setup does not touch the answers");
        assertEquals(1L, rows());
    }
}

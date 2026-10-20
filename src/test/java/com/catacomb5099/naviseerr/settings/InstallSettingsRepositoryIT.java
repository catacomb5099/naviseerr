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

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
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

    /** The one data statement in V18, run again by hand: Flyway met an empty downloads table, so it never exercised the TRUE branch. */
    @Test
    void v18_seedsTheSwitchOn_whereASuggestedPlaylistWasDownloaded() throws IOException {
        var db = template.getDatabaseClient();
        db.sql("INSERT INTO downloads (download_id, status, download_type, youtube_id) VALUES ('00000000-0000-0000-0000-000000000018', 'SUCCEEDED', 'CURATED', 'v18-seed')")
                .fetch().rowsUpdated().block();
        String migration = new String(getClass().getResourceAsStream("/db/migration/V18__install_settings.sql").readAllBytes(), UTF_8);
        db.sql(migration.substring(migration.indexOf("INSERT INTO install_settings")).replace(";", "")).fetch().rowsUpdated().block();
        boolean seeded = repository.find().block().suggestedPlaylists();
        db.sql("DELETE FROM downloads WHERE download_id = '00000000-0000-0000-0000-000000000018'").fetch().rowsUpdated().block();
        assertTrue(seeded, "an upgrade keeps the weekly run where a suggested playlist was downloaded");
    }
}

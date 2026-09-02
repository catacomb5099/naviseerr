package com.catacomb5099.naviseerr.download;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves V5's backfill actually moves data, not just that it runs without error.
 *
 * <p>Every other IT in this package boots the full {@code @SpringBootTest} context, but that
 * context runs Flyway to the latest version (V5 included) before any test code gets to touch the
 * database -- there is no way to insert "pre-V5" rows through it. So this test drives Flyway
 * directly against its own container: migrate to V4, insert rows with plain JDBC exactly as an
 * existing install would have them, then migrate the rest of the way and check what came
 * out. No Spring context is involved at all.
 *
 * <p>Since V6 (which sets {@code download_tasks.song_id} {@code NOT NULL}) that final
 * {@code migrateTo(LATEST)} covers a second property for free: the constraint has to hold against a
 * populated, backfilled database, not just an empty one. If V5's backfill ever missed a row, V6
 * would fail there and this test would fail with it.
 *
 * <p>The second test below drives V6's own backfill the same way: migrate to V5 only (the one
 * version where {@code download_tasks.song_id} can legally be null), insert a row that models an
 * install caught in the window where V5 had shipped but {@code ADMIT_SQL} still did not populate
 * {@code song_id}, then migrate the rest of the way and assert V6 fixed the row up instead of
 * failing {@code SET NOT NULL} against it.
 */
@Testcontainers
class SongMetadataMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v5_backfillsExactlyOneSongPerDownload_andPopulatesTaskSongIdForAdmittedRows()
            throws Exception {
        // `POSTGRES` is a single container shared (via the static @Container field) across every
        // test method in this class, so without a clean() here this test would see whatever schema
        // state the previous test method left behind instead of starting from empty.
        clean();
        migrateTo(MigrationVersion.fromVersion("4"));

        UUID admitted = UUID.randomUUID();
        UUID notYetAdmitted = UUID.randomUUID();
        try (Connection connection = jdbcConnection()) {
            insertDownload(connection, admitted, "Song One");
            insertDownload(connection, notYetAdmitted, "Song Two");
            // Only `admitted` has a download_tasks row, exactly like a download the runner has
            // already picked up. `notYetAdmitted` models one still sitting at PENDING with no task
            // row yet -- it must still get a song, but there is no download_tasks.song_id to check.
            insertDownloadTask(connection, admitted, "Song One");
        }

        migrateTo(MigrationVersion.LATEST);

        try (Connection connection = jdbcConnection()) {
            // Not "at least one row" -- exactly one per download. A backfill that fans out (or a
            // join that fans in) would pass a looser count check and still be wrong.
            assertEquals(2L, countRows(connection, "songs"),
                    "one songs row per pre-existing downloads row");
            assertEquals("Song One", songNameFor(connection, admitted),
                    "the backfilled song must carry over downloads.song_name, not a placeholder");
            assertEquals("Song Two", songNameFor(connection, notYetAdmitted));

            UUID backfilledSongId = songIdFor(connection, admitted);
            assertNotNull(backfilledSongId);
            assertEquals(backfilledSongId, taskSongIdFor(connection, admitted),
                    "download_tasks.song_id must point at the song backfilled for the SAME "
                            + "download, not merely be non-null");
        }
    }

    @Test
    void v6_backfillsPreExistingNullSongId_insteadOfFailingTheNotNullConstraint() throws Exception {
        // Models the exact window the finding on V6 describes: an install that ran V5 while
        // ADMIT_SQL still did not populate download_tasks.song_id. That is possible ONLY between V5
        // and V6 -- V5 leaves song_id nullable for precisely this reason (see V5's comment on that
        // ALTER TABLE) -- so migrate to V5 only, then insert the row by hand exactly as that
        // unmodified ADMIT_SQL would have: a downloads row, the songs row V5's own backfill would
        // have produced for it, and a download_tasks row that joins neither and leaves song_id NULL.
        clean();
        migrateTo(MigrationVersion.fromVersion("5"));

        UUID downloadId = UUID.randomUUID();
        UUID songId;
        try (Connection connection = jdbcConnection()) {
            insertDownload(connection, downloadId, "Song Three");
            songId = insertSong(connection, downloadId, "Song Three");
            insertDownloadTaskWithNullSongId(connection, downloadId, "Song Three");
        }

        // V6 must backfill this row from `songs` rather than fail SET NOT NULL against it -- that
        // is the whole point of the fix: a null here should never stop the application from
        // booting.
        migrateTo(MigrationVersion.LATEST);

        try (Connection connection = jdbcConnection()) {
            assertEquals(songId, taskSongIdFor(connection, downloadId),
                    "V6 must backfill the pre-existing NULL song_id from songs by download_id, "
                            + "not merely tolerate it");
        }
    }

    private void migrateTo(MigrationVersion target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    /**
     * Drops the schema back to empty. {@code POSTGRES} is one container shared across every test
     * method in this class (a static {@code @Container} field), so {@code migrateTo} alone is not
     * enough to start a test from "empty" -- Flyway will not migrate backwards, so a target below
     * whatever a previous test method already left the schema at is silently a no-op. Every test
     * calls this first so its starting state does not depend on method execution order.
     */
    private void clean() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private Connection jdbcConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertDownload(Connection connection, UUID id, String songName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO downloads (download_id, song_name, status, created_at) "
                        + "VALUES (?, ?, 'PENDING', ?)")) {
            statement.setObject(1, id);
            statement.setString(2, songName);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void insertDownloadTask(Connection connection, UUID downloadId, String songName)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO download_tasks (download_id, song_name, phase, phase_entered_at, "
                        + "next_attempt_at) VALUES (?, ?, 'SEARCH_INIT', ?, ?)")) {
            statement.setObject(1, downloadId);
            statement.setString(2, songName);
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(3, now);
            statement.setTimestamp(4, now);
            statement.executeUpdate();
        }
    }

    private UUID insertSong(Connection connection, UUID downloadId, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO songs (download_id, name) VALUES (?, ?) RETURNING song_id")) {
            statement.setObject(1, downloadId);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return (UUID) resultSet.getObject(1);
            }
        }
    }

    private void insertDownloadTaskWithNullSongId(
            Connection connection, UUID downloadId, String songName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO download_tasks (download_id, song_name, song_id, phase, "
                        + "phase_entered_at, next_attempt_at) "
                        + "VALUES (?, ?, NULL, 'SEARCH_INIT', ?, ?)")) {
            statement.setObject(1, downloadId);
            statement.setString(2, songName);
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(3, now);
            statement.setTimestamp(4, now);
            statement.executeUpdate();
        }
    }

    private long countRows(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String songNameFor(Connection connection, UUID downloadId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM songs WHERE download_id = ?")) {
            statement.setObject(1, downloadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrueHasRow(resultSet, downloadId);
                return resultSet.getString(1);
            }
        }
    }

    private UUID songIdFor(Connection connection, UUID downloadId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT song_id FROM songs WHERE download_id = ?")) {
            statement.setObject(1, downloadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrueHasRow(resultSet, downloadId);
                return (UUID) resultSet.getObject(1);
            }
        }
    }

    private UUID taskSongIdFor(Connection connection, UUID downloadId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT song_id FROM download_tasks WHERE download_id = ?")) {
            statement.setObject(1, downloadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrueHasRow(resultSet, downloadId);
                return (UUID) resultSet.getObject(1);
            }
        }
    }

    private void assertTrueHasRow(ResultSet resultSet, UUID downloadId) throws Exception {
        if (!resultSet.next()) {
            throw new AssertionError("expected exactly one row for download " + downloadId);
        }
    }
}

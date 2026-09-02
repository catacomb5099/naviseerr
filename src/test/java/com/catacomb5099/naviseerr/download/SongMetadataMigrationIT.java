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
 */
@Testcontainers
class SongMetadataMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v5_backfillsExactlyOneSongPerDownload_andPopulatesTaskSongIdForAdmittedRows()
            throws Exception {
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

    private void migrateTo(MigrationVersion target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
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

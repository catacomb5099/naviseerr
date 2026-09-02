package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link DownloadService#requestDownload}'s write path: the CTE that creates a
 * {@code downloads} row and its {@code songs} row in one atomic statement.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadServiceRequestIT {

    @Autowired
    DownloadService downloadService;

    @Autowired
    R2dbcEntityTemplate template;

    @BeforeEach
    void clean() {
        // Both download_tasks and songs FK into downloads with no ON DELETE CASCADE, so either one
        // left behind by another test class sharing this Testcontainers instance blocks this delete.
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM songs").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    @Test
    void createsOneDownloadRowAndOneMatchingSongRowWithArtistsAndImageIntact() {
        Download saved = downloadService
                .requestDownload("Riptide", List.of("Vance Joy"), "https://example.com/cover.jpg")
                .block();

        assertEquals(1, countRows("downloads"));
        assertEquals(1, countRows("songs"));

        SongRow song = findSongFor(saved.getDownloadId());
        assertEquals("Riptide", song.name());
        assertEquals(List.of("Vance Joy"), song.artists());
        assertEquals("https://example.com/cover.jpg", song.imageUrl());
        assertEquals(saved.getDownloadId(), song.downloadId());
    }

    @Test
    void nullArtistsNormaliseToAnEmptyListRatherThanNull() {
        Download saved = downloadService.requestDownload("No Artist Known", null, null).block();

        SongRow song = findSongFor(saved.getDownloadId());
        assertEquals(List.of(), song.artists());
        assertNull(song.imageUrl());
    }

    @Test
    void singleArgOverloadDelegatesWithEmptyArtistsAndNoImage() {
        Download saved = downloadService.requestDownload("Legacy Route Song").block();

        SongRow song = findSongFor(saved.getDownloadId());
        assertEquals(List.of(), song.artists());
        assertNull(song.imageUrl());
        assertEquals("Legacy Route Song", saved.getSongName(),
                "downloads.song_name must still be written -- ADMIT_SQL still reads it until task 3");
    }

    @Test
    void aSongInsertThatViolatesANotNullConstraintLeavesNoDownloadRowEither() {
        // songs.name is NOT NULL and downloads.song_name is not (V5 dropped that NOT NULL). Calling
        // the service directly with a null songName -- bypassing the controller's own 400 check --
        // reaches a genuine constraint violation on the SECOND insert in the CTE, which is exactly
        // what proves the two inserts share one atomic statement rather than two separate ones.
        StepVerifier.create(downloadService.requestDownload(null, List.of(), null))
                .expectErrorMatches(error -> true)
                .verify();

        assertEquals(0, countRows("downloads"), "the CTE's first insert must not survive alone");
        assertEquals(0, countRows("songs"));
    }

    private long countRows(String table) {
        return template.getDatabaseClient()
                .sql("SELECT count(*) AS total FROM " + table)
                .map((row, meta) -> row.get("total", Long.class))
                .one()
                .block();
    }

    private record SongRow(UUID downloadId, String name, List<String> artists, String imageUrl) {
    }

    private SongRow findSongFor(UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT download_id, name, artists, image_url FROM songs WHERE download_id = :id")
                .bind("id", downloadId)
                .map((row, meta) -> new SongRow(
                        row.get("download_id", UUID.class),
                        row.get("name", String.class),
                        List.of(row.get("artists", String[].class)),
                        row.get("image_url", String.class)))
                .one()
                .block();
    }
}

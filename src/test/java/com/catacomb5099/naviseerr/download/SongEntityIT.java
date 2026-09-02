package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.data.relational.core.query.Criteria.where;

/**
 * Settles whether {@code Song.artists}, declared as {@code List<String>} over a Postgres
 * {@code TEXT[]} column, round-trips through Spring Data R2DBC's array support unassisted, or
 * whether the field has to be {@code String[]} instead. It does: inserting and reading back
 * through {@link R2dbcEntityTemplate} preserves order and content with no custom converter.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class SongEntityIT {

    @Autowired
    R2dbcEntityTemplate template;

    private UUID downloadId;

    @BeforeEach
    void seedDownload() {
        // Both download_tasks and songs FK into downloads with no ON DELETE CASCADE, so either one
        // left behind by another test class sharing this Testcontainers instance blocks this delete.
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM songs").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
        downloadId = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, song_name, status, created_at) "
                        + "VALUES (:id, 'song', 'PENDING', now())")
                .bind("id", downloadId)
                .fetch().rowsUpdated().block();
    }

    @Test
    void artistsRoundTripAsListOfStringWithNoCustomConverter() {
        Song song = Song.builder()
                .songId(UUID.randomUUID())
                .downloadId(downloadId)
                .name("Riptide")
                .artists(List.of("Vance Joy", "Someone Else"))
                .imageUrl("https://example.com/cover.jpg")
                .build();

        Song inserted = template.insert(song).block();
        Song read = template.select(Query.query(where("song_id").is(inserted.getSongId())), Song.class)
                .blockFirst();

        assertEquals(List.of("Vance Joy", "Someone Else"), read.getArtists());
        assertEquals("Riptide", read.getName());
        assertEquals("https://example.com/cover.jpg", read.getImageUrl());
    }

    @Test
    void emptyArtistsRoundTripAsEmptyListNotNull() {
        Song song = Song.builder()
                .songId(UUID.randomUUID())
                .downloadId(downloadId)
                .name("No Artist Known")
                .artists(List.of())
                .imageUrl(null)
                .build();

        template.insert(song).block();
        Song read = template.select(Query.query(where("song_id").is(song.getSongId())), Song.class)
                .blockFirst();

        assertEquals(List.of(), read.getArtists());
    }
}

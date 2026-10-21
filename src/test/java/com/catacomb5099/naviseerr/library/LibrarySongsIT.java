package com.catacomb5099.naviseerr.library;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class LibrarySongsIT {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired R2dbcEntityTemplate template;
    @Autowired LibrarySongs songs;

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM album_searches").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    private UUID task(String youtubeId, String phase, String libraryPath, Instant phaseEnteredAt) {
        UUID downloadId = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, :yt, 'SONG', 'IN_PROGRESS', now())")
                .bind("id", downloadId).bind("yt", youtubeId).fetch().rowsUpdated().block();
        var spec = template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (download_id, youtube_id, song_name, phase, phase_entered_at, next_attempt_at, "
                        + "finished_at, library_path) VALUES (:id, :yt, 'song', :phase, :at, :at, :at, :path)")
                .bind("id", downloadId).bind("yt", youtubeId).bind("phase", phase).bind("at", phaseEnteredAt);
        spec = libraryPath == null ? spec.bindNull("path", String.class) : spec.bind("path", libraryPath);
        spec.fetch().rowsUpdated().block();
        return downloadId;
    }

    @Test
    void rows_areTheAskedIdsMinusFailedOnes_newestFirst() {
        UUID older = task("a", "SUCCEEDED", "/music/a.mp3", NOW.minusSeconds(60));
        UUID newer = task("a", "SEARCH_POLL", null, NOW);
        task("b", "FAILED", null, NOW);
        task("other", "SUCCEEDED", "/music/o.mp3", NOW);

        List<LibrarySongs.Row> rows = songs.rows(List.of("a", "b")).collectList().block();

        assertEquals(List.of(newer, older), rows.stream().map(LibrarySongs.Row::downloadId).toList());
        assertFalse(rows.get(0).finished());
        assertTrue(rows.get(1).finished());
        assertEquals("/music/a.mp3", rows.get(1).libraryPath());
    }
}

package com.catacomb5099.naviseerr.mediaserver;

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
class FiledSongsIT {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired R2dbcEntityTemplate template;
    @Autowired FiledSongs filed;

    @BeforeEach
    void clean() {
        for (String table : List.of("album_searches", "download_tasks", "downloads", "media_items")) {
            template.getDatabaseClient().sql("DELETE FROM " + table).fetch().rowsUpdated().block();
        }
    }

    private void task(String youtubeId, String libraryPath, String trackTitle, Instant finishedAt) {
        UUID downloadId = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, :yt, 'SONG', 'SUCCEEDED', now())")
                .bind("id", downloadId).bind("yt", youtubeId).fetch().rowsUpdated().block();
        var spec = template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (download_id, youtube_id, song_name, phase, phase_entered_at, next_attempt_at, "
                        + "finished_at, library_path, track_title) VALUES (:id, :yt, 'search name', 'SUCCEEDED', :at, :at, :at, :path, :title)")
                .bind("id", downloadId).bind("yt", youtubeId).bind("at", finishedAt);
        spec = libraryPath == null ? spec.bindNull("path", String.class) : spec.bind("path", libraryPath);
        spec = trackTitle == null ? spec.bindNull("title", String.class) : spec.bind("title", trackTitle);
        spec.fetch().rowsUpdated().block();
    }

    @Test
    void newest_isTheLatestFiledCopyPerSong_withYoutubesTitleArtistsAndLength() {
        template.getDatabaseClient()
                .sql("INSERT INTO media_items (youtube_id, title, artists, duration_seconds) VALUES ('a', 'Wonderwall', '{Oasis}', 259)")
                .fetch().rowsUpdated().block();
        task("a", "/music/old.mp3", null, NOW.minusSeconds(60));
        task("a", "/music/new.mp3", null, NOW);
        task("b", null, "Unfiled", NOW);
        task("c", "/music/c.mp3", "Album row title", NOW);

        List<FiledSongs.FiledSong> songs = filed.newest(List.of("a", "b", "c")).collectList().block();

        assertEquals(List.of(
                new FiledSongs.FiledSong("a", "/music/new.mp3", "Wonderwall", List.of("Oasis"), 259),
                new FiledSongs.FiledSong("c", "/music/c.mp3", "Album row title", List.of(), null)), songs);
    }

    @Test
    void newest_takesTheChosenFilesOwnLength_overYoutubes() {
        template.getDatabaseClient()
                .sql("INSERT INTO media_items (youtube_id, title, artists, duration_seconds) VALUES ('a', 'Wonderwall', '{Oasis}', 259)")
                .fetch().rowsUpdated().block();
        task("a", "/music/a.mp3", null, NOW);
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET candidates = '[{\"filename\":\"x\",\"length\":200},{\"filename\":\"y\",\"length\":301}]', "
                        + "candidate_index = 1")
                .fetch().rowsUpdated().block();

        assertEquals(301, filed.newest(List.of("a")).blockFirst().durationSeconds());
    }
}

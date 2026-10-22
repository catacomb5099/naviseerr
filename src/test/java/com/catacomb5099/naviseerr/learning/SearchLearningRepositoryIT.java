package com.catacomb5099.naviseerr.learning;

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
class SearchLearningRepositoryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired SearchLearningRepository repository;

    static final Instant NOW = Instant.parse("2026-10-20T22:40:37Z");
    static final String FILE = "{\"username\":\"bob\",\"filename\":\"@@music\\\\bob\\\\Oasis\\\\Wonderwall.flac\","
            + "\"extension\":\"flac\",\"size\":31457280,\"code\":1,\"grade\":\"EXACT\",\"length\":258,\"wording\":\"Wonderwall\"}";
    static final String LOG = "[{\"wording\":\"Wonderwall\",\"tier\":0,\"manual\":false,\"responses\":250,\"files\":900,"
            + "\"exact\":2,\"otherVersion\":1,\"unverified\":40,\"none\":857,\"at\":\"2026-10-20T22:40:00Z\"}]";

    @BeforeEach
    void clean() {
        for (String table : List.of("album_searches", "download_tasks", "downloads", "media_items", "song_albums")) {
            sql("DELETE FROM " + table);
        }
        sql("INSERT INTO media_items (youtube_id, title, artists, duration_seconds) VALUES ('hpSrLjc5SMs', 'Wonderwall', '{Oasis}', 259)");
    }

    void sql(String statement) {
        template.getDatabaseClient().sql(statement).fetch().rowsUpdated().block();
    }

    /** One finished song download; {@code libraryPath}/{@code slskdFilename}/{@code candidates} decide whether it counts. */
    UUID song(Instant finished, String libraryPath, String slskdFilename, String candidates) {
        UUID download = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        template.getDatabaseClient().sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                + "VALUES (:id, 'hpSrLjc5SMs', 'SONG', 'SUCCEEDED', now())").bind("id", download).fetch().rowsUpdated().block();
        var spec = template.getDatabaseClient().sql("INSERT INTO download_tasks (task_id, download_id, youtube_id, song_name, phase, "
                        + "phase_entered_at, next_attempt_at, finished_at, candidates, library_path, slskd_filename, search_log) "
                        + "VALUES (:task, :id, 'hpSrLjc5SMs', 'Wonderwall - Oasis', 'SUCCEEDED', :at, :at, :at, :candidates, :path, "
                        + ":file, :log::jsonb)")
                .bind("task", task).bind("id", download).bind("at", finished).bind("candidates", candidates).bind("log", LOG);
        spec = libraryPath == null ? spec.bindNull("path", String.class) : spec.bind("path", libraryPath);
        spec = slskdFilename == null ? spec.bindNull("file", String.class) : spec.bind("file", slskdFilename);
        spec.fetch().rowsUpdated().block();
        return task;
    }

    @Test
    void newest_isEverySongDownloadedFromASearchAndFiled_newestFirst_withNoSharerName() {
        UUID older = song(NOW.minusSeconds(60), "/music/Oasis/Wonderwall.flac", "@@music\\bob\\Oasis\\Wonderwall.flac", "[" + FILE + "]");
        UUID newer = song(NOW, "/music/Oasis/Wonderwall (2).flac", "@@music\\bob\\Oasis\\Wonderwall.flac", "[" + FILE + "]");
        song(NOW, "/music/Oasis/Wonderwall.flac", "copied", "[]");                          // the library already had it
        song(NOW, "/music/Oasis/Wonderwall.flac", null, "[" + FILE + "]");                 // matched to a copy filed later
        song(NOW, null, "@@music\\bob\\Oasis\\Wonderwall.flac", "[" + FILE + "]");         // downloaded, never filed

        List<SearchReport> reports = repository.newest(null, 10).collectList().block();

        assertEquals(List.of(newer, older), reports.stream().map(SearchReport::id).toList());
        SearchReport report = reports.getFirst();
        assertEquals(new SearchReport.Song("hpSrLjc5SMs", "Wonderwall", List.of("Oasis"), null, 259, "Wonderwall - Oasis"), report.song());
        assertEquals(250, report.searches().getFirst().responses());
        assertEquals(new SearchReport.Won("Wonderwall", "SEARCH"), report.won());
        assertEquals("Oasis/Wonderwall.flac", report.file().path());
        assertEquals(1, report.file().attempt());
        assertEquals("LOSSLESS", report.quality(), "no quality chosen: the default");
        assertFalse(report.toString().contains("bob") || report.toString().contains("/music/"), report.toString());

        assertEquals(List.of(older), repository.newest(NOW, 10).map(SearchReport::id).collectList().block(), "paged back");
    }
}

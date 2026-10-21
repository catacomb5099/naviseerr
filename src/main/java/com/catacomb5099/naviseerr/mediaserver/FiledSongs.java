package com.catacomb5099.naviseerr.mediaserver;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;

/** Songs naviseerr filed into the library, with what it knows about them from YouTube: the input of {@link SongFinder}. */
@Repository
public class FiledSongs {

    public record FiledSong(String youtubeId, String libraryPath, String title, List<String> artists, Integer durationSeconds) {}

    private static final String NEWEST_SQL = """
            SELECT DISTINCT ON (t.youtube_id) t.youtube_id, t.library_path,
                   COALESCE(t.track_title, m.title, t.song_name) AS title, m.artists,
                   COALESCE(t.duration_seconds, m.duration_seconds) AS duration_seconds
              FROM download_tasks t
              LEFT JOIN media_items m ON m.youtube_id = t.youtube_id
             WHERE t.youtube_id = ANY(:ids)
               AND t.library_path IS NOT NULL
             ORDER BY t.youtube_id, t.finished_at DESC NULLS LAST
            """;

    private final DatabaseClient client;

    public FiledSongs(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    /** The newest filed copy of each of these YouTube songs; an id never filed is absent. */
    public Flux<FiledSong> newest(List<String> youtubeIds) {
        return client.sql(NEWEST_SQL)
                .bind("ids", youtubeIds.toArray(String[]::new))
                .map(row -> {
                    String[] artists = row.get("artists", String[].class);
                    return new FiledSong(row.get("youtube_id", String.class), row.get("library_path", String.class),
                            row.get("title", String.class), artists == null ? List.of() : Arrays.asList(artists),
                            row.get("duration_seconds", Integer.class));
                })
                .all();
    }
}

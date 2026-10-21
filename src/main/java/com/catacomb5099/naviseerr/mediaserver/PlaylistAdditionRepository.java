package com.catacomb5099.naviseerr.mediaserver;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** All {@code playlist_additions} SQL (V23). */
@Repository
public class PlaylistAdditionRepository {

    /** What a player shows for one song on its way into a playlist; the waiting states come from the song's task. */
    public record AdditionView(UUID additionId, String server, String playlistId, String youtubeId, UUID downloadId,
                               String title, List<String> artists, String imageUrl, Integer durationSeconds,
                               String state, String reason, Instant requestedAt, Instant addedAt) {}

    /** One row the adder can act on now: its song is filed, or its download ended without that song. */
    public record Due(UUID additionId, MediaServer server, String playlistId, String jellyfinUserId, String youtubeId,
                      int tries, FiledSongs.FiledSong song, Instant filedAt) {}

    /** The song's newest task in that download. */
    private static final String TASK = """
            LEFT JOIN LATERAL (SELECT t.task_id, t.phase, t.library_path, t.finished_at, t.track_title, t.song_name,
                                      t.duration_seconds
                                 FROM download_tasks t
                                WHERE t.download_id = a.download_id AND t.youtube_id = a.youtube_id
                                ORDER BY t.phase_entered_at DESC
                                LIMIT 1) t ON true
            LEFT JOIN media_items m ON m.youtube_id = a.youtube_id
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO playlist_additions (addition_id, media_server, playlist_id, jellyfin_user_id, download_id,
                                            youtube_id, position, requested_at, next_try_at)
            SELECT gen_random_uuid(), :server, :playlistId, :userId, x.download_id::uuid, x.youtube_id, x.position, :now, :now
              FROM unnest(:downloadIds::text[], :youtubeIds::text[], :positions::int[]) AS x(download_id, youtube_id, position)
            ON CONFLICT (media_server, playlist_id, youtube_id) DO UPDATE
               SET download_id = EXCLUDED.download_id, jellyfin_user_id = EXCLUDED.jellyfin_user_id,
                   position = EXCLUDED.position, requested_at = EXCLUDED.requested_at, next_try_at = EXCLUDED.next_try_at,
                   tries = 0, last_error = NULL, media_item_id = NULL, added_at = NULL, gave_up_at = NULL, gave_up_reason = NULL
            RETURNING addition_id
            """;

    private static final String VIEW_SQL = """
            SELECT a.addition_id, a.media_server, a.playlist_id, a.youtube_id, a.download_id, a.requested_at, a.added_at,
                   a.gave_up_reason, t.task_id, t.phase, t.library_path,
                   COALESCE(t.track_title, m.title) AS title, m.artists, m.image_url,
                   COALESCE(t.duration_seconds, m.duration_seconds) AS duration_seconds
              FROM playlist_additions a
            """ + TASK;

    private static final String DUE_SQL = """
            SELECT a.addition_id, a.media_server, a.playlist_id, a.jellyfin_user_id, a.youtube_id, a.tries,
                   t.library_path, t.finished_at, COALESCE(t.track_title, m.title, t.song_name) AS title, m.artists,
                   COALESCE(t.duration_seconds, m.duration_seconds) AS duration_seconds
              FROM playlist_additions a
              JOIN downloads d ON d.download_id = a.download_id
            """ + TASK + """
             WHERE a.added_at IS NULL AND a.gave_up_reason IS NULL AND a.next_try_at <= :now
               AND (t.library_path IS NOT NULL
                    OR (t.task_id IS NULL AND d.status IN ('SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS')))
               -- Album order: wait while an earlier song of the same request is filed but not in yet (one still
               -- downloading, or failed, does not hold the rest up).
               AND NOT EXISTS (SELECT 1 FROM playlist_additions p
                                 JOIN download_tasks pt ON pt.download_id = p.download_id AND pt.youtube_id = p.youtube_id
                                WHERE p.media_server = a.media_server AND p.playlist_id = a.playlist_id
                                  AND p.requested_at = a.requested_at AND p.position < a.position
                                  AND p.added_at IS NULL AND p.gave_up_reason IS NULL AND pt.library_path IS NOT NULL)
             ORDER BY a.requested_at, a.position
             LIMIT :limit
            """;

    private final DatabaseClient client;

    public PlaylistAdditionRepository(R2dbcEntityTemplate entityTemplate) {
        this.client = entityTemplate.getDatabaseClient();
    }

    /** Which of these downloads exist. */
    public Flux<UUID> existingDownloads(List<UUID> downloadIds) {
        return client.sql("SELECT download_id FROM downloads WHERE download_id = ANY(:ids::uuid[])")
                .bind("ids", downloadIds.stream().map(UUID::toString).toArray(String[]::new))
                .map(row -> row.get("download_id", UUID.class))
                .all();
    }

    /** One row per song (asked again = the same row, re-armed); the songs in order, each with its download. */
    public Flux<UUID> request(MediaServer server, String playlistId, String userId, List<UUID> downloadIds,
                              List<String> youtubeIds, Instant now) {
        Integer[] positions = new Integer[youtubeIds.size()];
        Arrays.setAll(positions, i -> i + 1);
        DatabaseClient.GenericExecuteSpec spec = client.sql(UPSERT_SQL)
                .bind("server", server.name())
                .bind("playlistId", playlistId)
                .bind("downloadIds", downloadIds.stream().map(UUID::toString).toArray(String[]::new))
                .bind("youtubeIds", youtubeIds.toArray(String[]::new))
                .bind("positions", positions)
                .bind("now", now);
        spec = userId == null ? spec.bindNull("userId", String.class) : spec.bind("userId", userId);
        return spec.map(row -> row.get("addition_id", UUID.class)).all();
    }

    public Flux<AdditionView> views(List<UUID> additionIds) {
        return client.sql(VIEW_SQL + " WHERE a.addition_id = ANY(:ids::uuid[]) ORDER BY a.requested_at, a.position")
                .bind("ids", additionIds.stream().map(UUID::toString).toArray(String[]::new))
                .map((row, meta) -> view(row))
                .all();
    }

    /** Everything not added yet, plus what was added or given up on after {@code since}; optionally one server / playlist. */
    public Flux<AdditionView> recent(Instant since, MediaServer server, String playlistId) {
        String sql = VIEW_SQL + " WHERE ((a.added_at IS NULL AND a.gave_up_at IS NULL) OR COALESCE(a.added_at, a.gave_up_at) > :since)"
                + (server == null ? "" : " AND a.media_server = :server")
                + (playlistId == null ? "" : " AND a.playlist_id = :playlistId")
                + " ORDER BY a.requested_at, a.position";
        DatabaseClient.GenericExecuteSpec spec = client.sql(sql).bind("since", since);
        if (server != null) spec = spec.bind("server", server.name());
        if (playlistId != null) spec = spec.bind("playlistId", playlistId);
        return spec.map((row, meta) -> view(row)).all();
    }

    private static AdditionView view(io.r2dbc.spi.Readable row) {
        String reason = row.get("gave_up_reason", String.class);
        String phase = row.get("phase", String.class);
        String state;
        if (row.get("added_at", Instant.class) != null) state = "ADDED";
        else if (reason != null) state = "GAVE_UP";
        else if ("FAILED".equals(phase)) state = "DOWNLOAD_FAILED";
        else if (row.get("library_path", String.class) != null) state = "WAITING_FOR_MEDIA_SERVER";
        else state = "WAITING_FOR_DOWNLOAD";
        String[] artists = row.get("artists", String[].class);
        return new AdditionView(row.get("addition_id", UUID.class), row.get("media_server", String.class),
                row.get("playlist_id", String.class), row.get("youtube_id", String.class), row.get("download_id", UUID.class),
                row.get("title", String.class), artists == null ? null : Arrays.asList(artists),
                row.get("image_url", String.class), row.get("duration_seconds", Integer.class), state, reason,
                row.get("requested_at", Instant.class), row.get("added_at", Instant.class));
    }

    /** Rows the adder can act on now, oldest request first, then album order. */
    public Flux<Due> due(Instant now, int limit) {
        return client.sql(DUE_SQL)
                .bind("now", now)
                .bind("limit", limit)
                .map((row, meta) -> {
                    String[] artists = row.get("artists", String[].class);
                    String path = row.get("library_path", String.class);
                    String youtubeId = row.get("youtube_id", String.class);
                    return new Due(row.get("addition_id", UUID.class), MediaServer.valueOf(row.get("media_server", String.class)),
                            row.get("playlist_id", String.class), row.get("jellyfin_user_id", String.class), youtubeId,
                            row.get("tries", Integer.class),
                            path == null ? null : new FiledSongs.FiledSong(youtubeId, path, row.get("title", String.class),
                                    artists == null ? List.of() : Arrays.asList(artists), row.get("duration_seconds", Integer.class)),
                            row.get("finished_at", Instant.class));
                })
                .all();
    }

    public Mono<Void> added(UUID additionId, String mediaItemId, Instant now) {
        return client.sql("UPDATE playlist_additions SET added_at = :now, media_item_id = :item, last_error = NULL WHERE addition_id = :id")
                .bind("now", now).bind("item", mediaItemId).bind("id", additionId)
                .fetch().rowsUpdated().then();
    }

    public Mono<Void> tryLater(UUID additionId, int tries, Instant nextTryAt, String error) {
        return client.sql("UPDATE playlist_additions SET tries = :tries, next_try_at = :next, last_error = :error WHERE addition_id = :id")
                .bind("tries", tries).bind("next", nextTryAt).bind("error", error).bind("id", additionId)
                .fetch().rowsUpdated().then();
    }

    public Mono<Void> giveUp(UUID additionId, String reason, Instant now) {
        return client.sql("UPDATE playlist_additions SET gave_up_at = :now, gave_up_reason = :reason WHERE addition_id = :id")
                .bind("now", now).bind("reason", reason).bind("id", additionId)
                .fetch().rowsUpdated().then();
    }

    /** Deletes a row not added yet: 1; 0 when there is none; -1 when it was already added (kept). */
    public Mono<Integer> cancel(UUID additionId) {
        return client.sql("""
                        WITH gone AS (DELETE FROM playlist_additions WHERE addition_id = :id AND added_at IS NULL RETURNING 1)
                        SELECT (SELECT count(*) FROM gone) AS deleted,
                               (SELECT count(*) FROM playlist_additions WHERE addition_id = :id AND added_at IS NOT NULL) AS kept
                        """)
                .bind("id", additionId)
                .map(row -> row.get("deleted", Long.class) > 0 ? 1 : row.get("kept", Long.class) > 0 ? -1 : 0)
                .one();
    }
}

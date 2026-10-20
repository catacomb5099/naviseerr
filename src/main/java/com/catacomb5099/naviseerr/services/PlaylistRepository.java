package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import io.r2dbc.spi.Readable;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All {@code playlists} SQL (V20). Every edit is one statement that also says {@code downloaded_at IS NULL},
 * so a created playlist cannot change, and 0 rows means "unknown or created" (the caller tells which).
 * ponytail: the songs are one JSONB document rewritten per edit; a playlist_songs table if 5,000-song drafts feel slow.
 */
@Repository
public class PlaylistRepository {

    /** YouTube Music's own playlist size limit. */
    public static final int MAX_SONGS = 5000;

    private static final String COLUMNS = """
            playlist_id, title, description, songs::text AS songs, cover_updated_at, updated_at, downloaded_at""";

    private static final String INSERT_SQL = """
            INSERT INTO playlists (playlist_id, title, description)
            VALUES (:id, COALESCE(:title, 'My playlist #' || ((SELECT count(*) FROM playlists) + 1)), :description)
            """;

    private static final String LIST_SQL = "SELECT " + COLUMNS + " FROM playlists ORDER BY updated_at DESC";

    private static final String FIND_SQL = "SELECT " + COLUMNS + " FROM playlists WHERE playlist_id = :id";

    private static final String RENAME_SQL = """
            UPDATE playlists SET title = COALESCE(:title, title), description = COALESCE(:description, description),
                                 updated_at = now()
             WHERE playlist_id = :id AND downloaded_at IS NULL
            """;

    // The row is locked first, so two adds from two tabs queue instead of one losing the other's songs.
    private static final String APPEND_SQL = """
            WITH cur AS (SELECT songs FROM playlists WHERE playlist_id = :id AND downloaded_at IS NULL FOR UPDATE),
                 fresh AS (SELECT COALESCE(jsonb_agg(n.s ORDER BY n.o), '[]'::jsonb) AS songs
                             FROM cur, jsonb_array_elements(CAST(:songs AS jsonb)) WITH ORDINALITY n(s, o)
                            WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(cur.songs) e(s)
                                               WHERE e.s->>'id' = n.s->>'id'))
            UPDATE playlists p SET songs = cur.songs || fresh.songs, updated_at = now()
              FROM cur, fresh
             WHERE p.playlist_id = :id AND jsonb_array_length(cur.songs) + jsonb_array_length(fresh.songs) <= :max
            RETURNING jsonb_array_length(fresh.songs) AS added, jsonb_array_length(p.songs) AS track_count
            """;

    // Listed songs first in the given order, then every song not listed in its old order: a stale tab loses nothing.
    private static final String REORDER_SQL = """
            UPDATE playlists p SET songs = (
                       SELECT COALESCE(jsonb_agg(e.s ORDER BY w.o IS NULL, w.o, e.o), '[]'::jsonb)
                         FROM jsonb_array_elements(p.songs) WITH ORDINALITY e(s, o)
                         LEFT JOIN unnest(CAST(:ids AS text[])) WITH ORDINALITY w(id, o) ON w.id = e.s->>'id'),
                   updated_at = now()
             WHERE playlist_id = :id AND downloaded_at IS NULL
            """;

    private static final String REMOVE_SQL = """
            UPDATE playlists SET songs = COALESCE((SELECT jsonb_agg(e.s ORDER BY e.o)
                                                     FROM jsonb_array_elements(songs) WITH ORDINALITY e(s, o)
                                                    WHERE e.s->>'id' <> :videoId), '[]'::jsonb),
                                 updated_at = now()
             WHERE playlist_id = :id AND downloaded_at IS NULL
            """;

    private static final String COPY_SQL = """
            INSERT INTO playlists (playlist_id, title, description, songs, cover, cover_updated_at)
            SELECT :newId, left('Copy of ' || title, 100), description, songs, cover, cover_updated_at
              FROM playlists WHERE playlist_id = :id
            """;

    private static final String SET_COVER_SQL = """
            UPDATE playlists SET cover = :cover, cover_updated_at = now(), updated_at = now()
             WHERE playlist_id = :id AND downloaded_at IS NULL
            """;

    private static final String CLEAR_COVER_SQL = """
            UPDATE playlists SET cover = NULL, cover_updated_at = NULL, updated_at = now()
             WHERE playlist_id = :id AND downloaded_at IS NULL
            """;

    private static final String COVER_SQL = "SELECT cover FROM playlists WHERE playlist_id = :id AND cover IS NOT NULL";

    // COALESCE: pressing Create twice (or "Download again") keeps the first date.
    private static final String FREEZE_SQL = """
            UPDATE playlists SET downloaded_at = COALESCE(downloaded_at, now())
             WHERE playlist_id = :id AND jsonb_array_length(songs) > 0
            """;

    private static final String DELETE_SQL = "DELETE FROM playlists WHERE playlist_id = :id AND downloaded_at IS NULL";

    private static final TypeReference<List<YoutubeSongInfo>> SONG_LIST = new TypeReference<>() {};

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;

    public PlaylistRepository(R2dbcEntityTemplate entityTemplate, ObjectMapper objectMapper) {
        this.client = entityTemplate.getDatabaseClient();
        this.objectMapper = objectMapper;
    }

    /** One row as read; {@code coverUpdatedAt} null = no picture, {@code downloadedAt} null = a draft. */
    public record Playlist(UUID id, String title, String description, List<YoutubeSongInfo> songs,
                           Instant coverUpdatedAt, Instant updatedAt, Instant downloadedAt) {

        /** The picture's address; a new picture is a new URL, so it can be cached for good. */
        public String coverUrl() {
            return coverUpdatedAt == null ? null : "/playlists/" + id + "/cover?v=" + coverUpdatedAt.toEpochMilli();
        }

        /** In the shape every collection has, for a download or for adding it into another playlist. No author: no user names. */
        public YoutubeCollectionInfo asCollection() {
            return new YoutubeCollectionInfo(id.toString(), songs, null, title, List.of(), List.of(), coverUrl());
        }
    }

    /** What an add did: songs appended, and the playlist's length after. */
    public record Added(int added, int trackCount) {
    }

    /** A new empty draft; a null title becomes "My playlist #N". */
    public Mono<UUID> create(String title, String description) {
        UUID id = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec spec = client.sql(INSERT_SQL).bind("id", id).bind("description", description);
        spec = title == null ? spec.bindNull("title", String.class) : spec.bind("title", title);
        return spec.fetch().rowsUpdated().thenReturn(id);
    }

    /** Every playlist, last edited first. */
    public Flux<Playlist> list() {
        return client.sql(LIST_SQL).map(this::toPlaylist).all();
    }

    public Mono<Playlist> find(UUID id) {
        return client.sql(FIND_SQL).bind("id", id).map(this::toPlaylist).one();
    }

    /** Null leaves that field as it is. */
    public Mono<Long> rename(UUID id, String title, String description) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(RENAME_SQL).bind("id", id);
        spec = title == null ? spec.bindNull("title", String.class) : spec.bind("title", title);
        spec = description == null ? spec.bindNull("description", String.class) : spec.bind("description", description);
        return spec.fetch().rowsUpdated();
    }

    /**
     * Appends the songs not already in the draft, in the order given (the caller passes no id twice).
     * Empty when the draft is unknown, created, or would pass {@link #MAX_SONGS}.
     */
    public Mono<Added> append(UUID id, List<YoutubeSongInfo> songs) {
        return client.sql(APPEND_SQL)
                .bind("id", id)
                .bind("songs", objectMapper.writeValueAsString(songs))
                .bind("max", MAX_SONGS)
                .map(row -> new Added(row.get("added", Integer.class), row.get("track_count", Integer.class)))
                .one();
    }

    /** The caller passes no id twice; unknown ids are ignored. */
    public Mono<Long> reorder(UUID id, List<String> videoIds) {
        return client.sql(REORDER_SQL).bind("id", id).bind("ids", videoIds.toArray(String[]::new)).fetch().rowsUpdated();
    }

    public Mono<Long> remove(UUID id, String videoId) {
        return client.sql(REMOVE_SQL).bind("id", id).bind("videoId", videoId).fetch().rowsUpdated();
    }

    /** A new draft "Copy of <title>" with the same description, songs and picture; empty when {@code id} is unknown. */
    public Mono<UUID> copy(UUID id) {
        UUID newId = UUID.randomUUID();
        return client.sql(COPY_SQL).bind("id", id).bind("newId", newId).fetch().rowsUpdated()
                .filter(rows -> rows > 0).map(rows -> newId);
    }

    /** A new picture is a new {@code cover_updated_at}, and so a new {@link Playlist#coverUrl()}. */
    public Mono<Long> setCover(UUID id, byte[] jpeg) {
        return client.sql(SET_COVER_SQL).bind("id", id).bind("cover", jpeg).fetch().rowsUpdated();
    }

    /** Back to the mosaic. */
    public Mono<Long> clearCover(UUID id) {
        return client.sql(CLEAR_COVER_SQL).bind("id", id).fetch().rowsUpdated();
    }

    /** The picture's bytes; empty when there is none. */
    public Mono<byte[]> cover(UUID id) {
        return client.sql(COVER_SQL).bind("id", id).map(row -> row.get("cover", byte[].class)).one();
    }

    /** "Create": no edit after this. 0 rows when the id is unknown or the playlist has no songs. */
    public Mono<Long> freeze(UUID id) {
        return client.sql(FREEZE_SQL).bind("id", id).fetch().rowsUpdated();
    }

    /** Drafts only. */
    public Mono<Long> delete(UUID id) {
        return client.sql(DELETE_SQL).bind("id", id).fetch().rowsUpdated();
    }

    private Playlist toPlaylist(Readable row) {
        return new Playlist(row.get("playlist_id", UUID.class), row.get("title", String.class),
                row.get("description", String.class), objectMapper.readValue(row.get("songs", String.class), SONG_LIST),
                row.get("cover_updated_at", Instant.class), row.get("updated_at", Instant.class),
                row.get("downloaded_at", Instant.class));
    }
}

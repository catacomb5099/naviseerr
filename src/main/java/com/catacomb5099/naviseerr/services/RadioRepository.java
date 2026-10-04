package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * All {@code radios} SQL (V13). A radio is written once, when someone starts it, and read whole after
 * that; nothing updates or deletes one.
 * ponytail: radios are never cleaned up; one is a few KB, add an age-based DELETE if the table ever matters.
 */
@Repository
public class RadioRepository {

    private static final String INSERT_SQL = """
            INSERT INTO radios (radio_id, seed_id, name, artists, artist_ids, image_url, songs)
            VALUES (:id, :seedId, :name, :artists, :artistIds, :imageUrl, :songs::jsonb)
            """;

    private static final String FIND_SQL = """
            SELECT name, artists, artist_ids, image_url, songs::text AS songs
              FROM radios
             WHERE radio_id = :id
            """;

    private static final TypeReference<List<YoutubeSongInfo>> SONG_LIST = new TypeReference<>() {};

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;

    public RadioRepository(R2dbcEntityTemplate entityTemplate, ObjectMapper objectMapper) {
        this.client = entityTemplate.getDatabaseClient();
        this.objectMapper = objectMapper;
    }

    /** Saves the radio under {@code id}; its {@code name} is stored as given, already worded for display. */
    public Mono<Void> save(UUID id, String seedId, YoutubeCollectionInfo radio) {
        String songs;
        try {
            songs = objectMapper.writeValueAsString(radio.songs());
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("Could not serialise radio songs", e));
        }
        DatabaseClient.GenericExecuteSpec spec = client.sql(INSERT_SQL)
                .bind("id", id)
                .bind("seedId", seedId)
                .bind("name", radio.name())
                .bind("artists", radio.authorNames().toArray(String[]::new))
                .bind("artistIds", radio.authorIds().toArray(String[]::new))
                .bind("songs", songs);
        spec = radio.imageUrl() == null ? spec.bindNull("imageUrl", String.class)
                : spec.bind("imageUrl", radio.imageUrl());
        return spec.fetch().rowsUpdated().then();
    }

    /** The saved radio in the shape every collection has, its id the radio's own; empty when there is none. */
    public Mono<YoutubeCollectionInfo> find(UUID id) {
        return client.sql(FIND_SQL)
                .bind("id", id)
                .map(row -> new YoutubeCollectionInfo(id.toString(),
                        objectMapper.readValue(row.get("songs", String.class), SONG_LIST), null,
                        row.get("name", String.class),
                        Arrays.asList(row.get("artists", String[].class)),
                        Arrays.asList(row.get("artist_ids", String[].class)),
                        row.get("image_url", String.class)))
                .one();
    }
}

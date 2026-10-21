package com.catacomb5099.naviseerr.mediaserver;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole "add to my playlist once downloaded" flow against a real Postgres and recorded Navidrome / Jellyfin
 * answers (shapes as Navidrome 0.64.2 and Jellyfin 10.x give them): the request checks, the adder's pass, the
 * states a player reads back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlaylistAdditionsIT {

    static final MockWebServer NAVIDROME = new MockWebServer();
    static final MockWebServer JELLYFIN = new MockWebServer();
    /** Path prefix (without query) -> answer; requests are recorded in order. */
    static final Map<String, Function<RecordedRequest, MockResponse>> ROUTES = new ConcurrentHashMap<>();
    static final List<RecordedRequest> SEEN = new CopyOnWriteArrayList<>();
    static final Path LIBRARY;
    static final Path SLSKD;

    static {
        try {
            // Real paths: the organiser resolves its root (macOS /var is /private/var), as filed paths are.
            LIBRARY = Files.createTempDirectory("library").toRealPath();
            SLSKD = Files.createTempDirectory("slskd").toRealPath();
            Dispatcher dispatcher = new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    SEEN.add(request);
                    String path = request.getRequestUrl().encodedPath();
                    Function<RecordedRequest, MockResponse> route = ROUTES.get(request.getMethod() + " " + path);
                    return route == null ? new MockResponse().setResponseCode(404) : route.apply(request);
                }
            };
            NAVIDROME.setDispatcher(dispatcher);
            JELLYFIN.setDispatcher(dispatcher);
            NAVIDROME.start();
            JELLYFIN.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("download-task.loop-interval-ms", () -> "3600000");
        registry.add("media-servers.playlist-additions-interval-ms", () -> "3600000");
        registry.add("media-servers.navidrome.url", () -> NAVIDROME.url("/").toString());
        registry.add("media-servers.navidrome.username", () -> "admin");
        registry.add("media-servers.navidrome.password", () -> "pw");
        registry.add("media-servers.jellyfin.url", () -> JELLYFIN.url("/").toString());
        registry.add("media-servers.jellyfin.api-key", () -> "key");
        registry.add("library.root", LIBRARY::toString);
        registry.add("library.slskd-downloads-dir", SLSKD::toString);
    }

    @AfterAll
    static void stopServers() throws IOException {
        NAVIDROME.shutdown();
        JELLYFIN.shutdown();
    }

    @Autowired R2dbcEntityTemplate template;
    @Autowired PlaylistAdditionController controller;
    @Autowired PlaylistAdder adder;
    WebTestClient client;

    @BeforeEach
    void clean() {
        for (String table : List.of("playlist_additions", "album_searches", "download_tasks", "downloads", "media_items")) {
            template.getDatabaseClient().sql("DELETE FROM " + table).fetch().rowsUpdated().block();
        }
        ROUTES.clear();
        SEEN.clear();
        client = WebTestClient.bindToController(controller).build();
    }

    // ---- seeding -----------------------------------------------------------------------------------

    private UUID download(String status) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) VALUES (:id, :yt, 'SONG', :s, now())")
                .bind("id", id).bind("yt", "req-" + id).bind("s", status).fetch().rowsUpdated().block();
        return id;
    }

    /** A song of a download; {@code filed} puts its file under the library root. */
    private void song(UUID downloadId, String youtubeId, String phase, boolean filed, Instant finishedAt) {
        var spec = template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (download_id, youtube_id, song_name, phase, phase_entered_at, next_attempt_at, "
                        + "finished_at, library_path, duration_seconds) VALUES (:id, :yt, 'search', :phase, now(), now(), :at, :path, 259)")
                .bind("id", downloadId).bind("yt", youtubeId).bind("phase", phase);
        spec = finishedAt == null ? spec.bindNull("at", Instant.class) : spec.bind("at", finishedAt);
        spec = filed ? spec.bind("path", LIBRARY.resolve("Oasis/Morning Glory/03 Wonderwall.mp3").toString())
                : spec.bindNull("path", String.class);
        spec.fetch().rowsUpdated().block();
        template.getDatabaseClient()
                .sql("INSERT INTO media_items (youtube_id, title, artists, image_url, duration_seconds) "
                        + "VALUES (:yt, 'Wonderwall', '{Oasis}', 'https://img/w.jpg', 259) ON CONFLICT DO NOTHING")
                .bind("yt", youtubeId).fetch().rowsUpdated().block();
    }

    private static MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse subsonic(String inner) {
        return json("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"" + (inner.isEmpty() ? "" : "," + inner) + "}}");
    }

    private static MockResponse subsonicError(int code, String message) {
        return json("{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}}");
    }

    private void navidromePlaylist(String owner, boolean readonly, String... songIds) {
        StringBuilder entries = new StringBuilder();
        for (String id : songIds) entries.append(entries.isEmpty() ? "" : ",").append("{\"id\":\"").append(id).append("\"}");
        ROUTES.put("GET /rest/getPlaylist", r -> subsonic("\"playlist\":{\"id\":\"pl1\",\"name\":\"Road trip\",\"owner\":\"" + owner
                + "\",\"readonly\":" + readonly + ",\"entry\":[" + entries + "]}"));
    }

    private void navidromeHasTheSong() {
        ROUTES.put("GET /rest/search3", r -> subsonic("""
                "searchResult3":{"song":[{"id":"nd-w","albumId":"nd-al","title":"Wonderwall","artist":"Oasis","duration":258,
                "path":"Oasis/(What's The Story) Morning Glory?/03 - Wonderwall.mp3","created":"2026-10-21T10:00:00Z"}]}"""));
    }

    private WebTestClient.ResponseSpec post(String server, String userId, UUID downloadId, String youtubeId) {
        return client.post().uri("/playlist-additions")
                .bodyValue(Map.of("server", server, "playlistId", "pl1", "userId", userId == null ? "" : userId,
                        "items", List.of(Map.of("downloadId", downloadId.toString(), "youtubeId", youtubeId))))
                .exchange();
    }

    private String state() {
        return template.getDatabaseClient().sql("SELECT added_at, gave_up_reason, tries FROM playlist_additions")
                .map(row -> row.get("added_at", Instant.class) != null ? "ADDED"
                        : row.get("gave_up_reason", String.class) != null ? "GAVE_UP " + row.get("gave_up_reason", String.class)
                        : "TRIES " + row.get("tries", Integer.class))
                .one().block();
    }

    private List<String> calls() {
        return SEEN.stream().map(r -> r.getMethod() + " " + r.getRequestUrl().encodedPath()).toList();
    }

    // ---- Navidrome ---------------------------------------------------------------------------------

    @Test
    void navidrome_aFiledSongGoesIntoThePlaylist_andReadsBackAsAdded() {
        UUID d = download("SUCCEEDED");
        song(d, "yt-w", "SUCCEEDED", true, Instant.now());
        navidromePlaylist("admin", false, "other-song");

        post("NAVIDROME", null, d, "yt-w").expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.additions[0].state").isEqualTo("WAITING_FOR_MEDIA_SERVER")
                .jsonPath("$.additions[0].title").isEqualTo("Wonderwall")
                .jsonPath("$.additions[0].artists[0]").isEqualTo("Oasis")
                .jsonPath("$.additions[0].durationSeconds").isEqualTo(259)
                .jsonPath("$.additions[0].downloadId").isEqualTo(d.toString());

        navidromeHasTheSong();
        ROUTES.put("GET /rest/updatePlaylist", r -> subsonic(""));
        SEEN.clear();
        adder.pass(Instant.now()).block();

        assertEquals("ADDED", state());
        assertEquals(List.of("GET /rest/search3", "GET /rest/getPlaylist", "GET /rest/updatePlaylist"), calls());
        RecordedRequest update = SEEN.get(2);
        assertEquals("pl1", update.getRequestUrl().queryParameter("playlistId"));
        assertEquals("nd-w", update.getRequestUrl().queryParameter("songIdToAdd"));
        client.get().uri("/playlist-additions?server=NAVIDROME&playlistId=pl1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.additions[0].state").isEqualTo("ADDED")
                .jsonPath("$.additions[0].addedAt").isNotEmpty();
    }

    @Test
    void navidrome_alreadyInThePlaylist_countsAsAdded_withoutAddingItTwice() {
        UUID d = download("SUCCEEDED");
        song(d, "yt-w", "SUCCEEDED", true, Instant.now());
        navidromePlaylist("admin", false, "nd-w");
        post("NAVIDROME", null, d, "yt-w").expectStatus().isAccepted();
        navidromeHasTheSong();

        adder.pass(Instant.now()).block();

        assertEquals("ADDED", state());
        assertFalse(calls().contains("GET /rest/updatePlaylist"));
    }

    @Test
    void navidrome_aSongNotListedYet_isTriedAgainLater_thenGivenUpADayAfterFiling() {
        UUID d = download("SUCCEEDED");
        Instant filed = Instant.now();
        song(d, "yt-w", "SUCCEEDED", true, filed);
        navidromePlaylist("admin", false);
        post("NAVIDROME", null, d, "yt-w").expectStatus().isAccepted();
        ROUTES.put("GET /rest/search3", r -> subsonic("\"searchResult3\":{}"));

        adder.pass(filed.plusSeconds(1)).block();
        assertEquals("TRIES 1", state());
        SEEN.clear();
        adder.pass(filed.plusSeconds(5)).block();
        assertTrue(SEEN.isEmpty(), "not due again before its 15 s back-off");

        adder.pass(filed.plus(PlaylistAdder.GIVE_UP_AFTER).plusSeconds(1)).block();
        assertEquals("GAVE_UP NOT_FOUND_IN_MEDIA_SERVER", state());
    }

    @Test
    void anAlbumGoesInInAlbumOrder_aLaterSongWaitsForAnEarlierOneTheServerDoesNotListYet() {
        UUID d = download("SUCCEEDED");
        song(d, "yt-1", "SUCCEEDED", true, Instant.now());
        song(d, "yt-2", "SUCCEEDED", true, Instant.now());
        navidromePlaylist("admin", false);
        client.post().uri("/playlist-additions")
                .bodyValue(Map.of("server", "NAVIDROME", "playlistId", "pl1", "items", List.of(
                        Map.of("downloadId", d.toString(), "youtubeId", "yt-1"),
                        Map.of("downloadId", d.toString(), "youtubeId", "yt-2"))))
                .exchange().expectStatus().isAccepted();
        ROUTES.put("GET /rest/search3", r -> subsonic("\"searchResult3\":{}"));
        SEEN.clear();

        adder.pass(Instant.now()).block();

        assertEquals(List.of("GET /rest/search3"), calls(), "only the first song is tried; the second waits for it");
    }

    @Test
    void onceTheFirstSongIsIn_theRestOfTheAlbumFollowsInTheSamePass_inOrder() {
        UUID d = download("SUCCEEDED");
        song(d, "yt-1", "SUCCEEDED", true, Instant.now());
        song(d, "yt-2", "SUCCEEDED", true, Instant.now());
        navidromePlaylist("admin", false);
        client.post().uri("/playlist-additions")
                .bodyValue(Map.of("server", "NAVIDROME", "playlistId", "pl1", "items", List.of(
                        Map.of("downloadId", d.toString(), "youtubeId", "yt-1"),
                        Map.of("downloadId", d.toString(), "youtubeId", "yt-2"))))
                .exchange().expectStatus().isAccepted();
        navidromeHasTheSong();
        List<String> added = new CopyOnWriteArrayList<>();
        ROUTES.put("GET /rest/updatePlaylist", r -> {
            added.add(r.getRequestUrl().queryParameter("songIdToAdd"));
            return subsonic("");
        });

        adder.pass(Instant.now()).block();

        assertEquals(2, added.size(), "the second song is added in the same pass, right after the first");
        Long left = template.getDatabaseClient().sql("SELECT count(*) AS n FROM playlist_additions WHERE added_at IS NULL")
                .map(row -> row.get("n", Long.class)).one().block();
        assertEquals(0L, left, "both in after one pass");
    }

    @Test
    void navidrome_aPlaylistThatRefusesSongs_isGivenUpAsNotEditable() {
        UUID d = download("SUCCEEDED");
        song(d, "yt-w", "SUCCEEDED", true, Instant.now());
        navidromePlaylist("someone-else", true);
        post("NAVIDROME", null, d, "yt-w").expectStatus().isAccepted();
        navidromeHasTheSong();
        ROUTES.put("GET /rest/updatePlaylist", r -> subsonicError(50, "User is not authorized for the given operation"));

        adder.pass(Instant.now()).block();

        assertEquals("GAVE_UP NOT_EDITABLE", state());
        client.get().uri("/playlist-additions").exchange().expectBody()
                .jsonPath("$.additions[0].state").isEqualTo("GAVE_UP")
                .jsonPath("$.additions[0].reason").isEqualTo("NOT_EDITABLE");
    }

    @Test
    void request_isRefused_forAnUnknownDownload_aMissingPlaylist_orOurOwnReadOnlyPlaylist() {
        post("NAVIDROME", null, UUID.randomUUID(), "yt-w").expectStatus().isNotFound()
                .expectBody().jsonPath("$.reason").isEqualTo("DOWNLOAD_NOT_FOUND");

        UUID d = download("IN_PROGRESS");
        ROUTES.put("GET /rest/getPlaylist", r -> subsonicError(70, "playlist not found"));
        post("NAVIDROME", null, d, "yt-w").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.reason").isEqualTo("PLAYLIST_NOT_FOUND");

        navidromePlaylist("admin", true);
        post("NAVIDROME", null, d, "yt-w").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.reason").isEqualTo("NOT_EDITABLE");

        post("JELLYFIN", null, d, "yt-w").expectStatus().isBadRequest();
        Integer rows = template.getDatabaseClient().sql("SELECT count(*) AS n FROM playlist_additions")
                .map(row -> row.get("n", Long.class).intValue()).one().block();
        assertEquals(0, rows, "nothing stored for a refused request");
    }

    @Test
    void whileDownloading_itWaits_aFailedDownloadReadsAsSuch_andAFinishedDownloadWithoutTheSongIsGivenUp() {
        UUID running = download("IN_PROGRESS");
        song(running, "yt-w", "SEARCH_POLL", false, null);
        navidromePlaylist("admin", false);
        post("NAVIDROME", null, running, "yt-w").expectStatus().isAccepted()
                .expectBody().jsonPath("$.additions[0].state").isEqualTo("WAITING_FOR_DOWNLOAD");
        SEEN.clear();
        adder.pass(Instant.now()).block();
        assertTrue(SEEN.isEmpty(), "nothing to ask the media server while the song downloads");

        template.getDatabaseClient().sql("UPDATE download_tasks SET phase = 'FAILED'").fetch().rowsUpdated().block();
        client.get().uri("/playlist-additions").exchange().expectBody()
                .jsonPath("$.additions[0].state").isEqualTo("DOWNLOAD_FAILED");

        template.getDatabaseClient().sql("DELETE FROM playlist_additions").fetch().rowsUpdated().block();
        UUID other = download("SUCCEEDED");
        post("NAVIDROME", null, other, "yt-not-in-it").expectStatus().isAccepted();
        adder.pass(Instant.now()).block();
        assertEquals("GAVE_UP NOT_IN_DOWNLOAD", state());
    }

    @Test
    void askingAgain_reArmsTheSameRow_andCancelOnlyRemovesOneNotAddedYet() {
        UUID d = download("SUCCEEDED");
        song(d, "yt-w", "SUCCEEDED", true, Instant.now());
        navidromePlaylist("admin", false);
        post("NAVIDROME", null, d, "yt-w").expectStatus().isAccepted();
        ROUTES.put("GET /rest/updatePlaylist", r -> subsonicError(50, "no"));
        navidromeHasTheSong();
        adder.pass(Instant.now()).block();
        assertEquals("GAVE_UP NOT_EDITABLE", state());

        post("NAVIDROME", null, d, "yt-w").expectStatus().isAccepted()
                .expectBody().jsonPath("$.additions[0].state").isEqualTo("WAITING_FOR_MEDIA_SERVER");
        assertEquals("TRIES 0", state());

        UUID id = template.getDatabaseClient().sql("SELECT addition_id FROM playlist_additions")
                .map(row -> row.get("addition_id", UUID.class)).one().block();
        client.delete().uri("/playlist-additions/" + id).exchange().expectStatus().isNoContent();
        client.delete().uri("/playlist-additions/" + id).exchange().expectStatus().isNotFound();

        post("NAVIDROME", null, d, "yt-w").expectStatus().isAccepted();
        ROUTES.put("GET /rest/updatePlaylist", r -> subsonic(""));
        adder.pass(Instant.now()).block();
        UUID added = template.getDatabaseClient().sql("SELECT addition_id FROM playlist_additions")
                .map(row -> row.get("addition_id", UUID.class)).one().block();
        client.delete().uri("/playlist-additions/" + added).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.reason").isEqualTo("ALREADY_ADDED");
    }

    // ---- Jellyfin ----------------------------------------------------------------------------------

    @Test
    void jellyfin_findsTheSongByItsPath_andAddsItAsThePlaylistOwner() {
        UUID d = download("SUCCEEDED");
        song(d, "yt-w", "SUCCEEDED", true, Instant.now());
        ROUTES.put("GET /Playlists/jf-pl/Items", r -> json("{\"Items\":[]}"));
        ROUTES.put("GET /Items", r -> json("""
                {"Items":[{"Id":"twin","AlbumId":"b1","Path":"/media/Oasis/Best Of/03 Wonderwall.mp3"},
                          {"Id":"jf-w","AlbumId":"b2","Path":"/media/Oasis/Morning Glory/03 Wonderwall.mp3"}]}"""));
        ROUTES.put("POST /Playlists/jf-pl/Items", r -> new MockResponse().setResponseCode(204));

        client.post().uri("/playlist-additions")
                .bodyValue(Map.of("server", "JELLYFIN", "playlistId", "jf-pl", "userId", "owner-1",
                        "items", List.of(Map.of("downloadId", d.toString(), "youtubeId", "yt-w"))))
                .exchange().expectStatus().isAccepted();
        SEEN.clear();
        adder.pass(Instant.now()).block();

        assertEquals("ADDED", state());
        RecordedRequest add = SEEN.stream().filter(r -> r.getMethod().equals("POST")).findFirst().orElseThrow();
        assertEquals("jf-w", add.getRequestUrl().queryParameter("ids"));
        assertEquals("owner-1", add.getRequestUrl().queryParameter("userId"));
        assertEquals("MediaBrowser Token=\"key\"", add.getHeader("Authorization"));
    }

    @Test
    void jellyfin_aPlaylistTheUserMayNotChange_isRefusedAtRequestTime() {
        UUID d = download("SUCCEEDED");
        ROUTES.put("GET /Playlists/jf-pl/Items", r -> new MockResponse().setResponseCode(403));

        client.post().uri("/playlist-additions")
                .bodyValue(Map.of("server", "JELLYFIN", "playlistId", "jf-pl", "userId", "owner-1",
                        "items", List.of(Map.of("downloadId", d.toString(), "youtubeId", "yt-w"))))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.reason").isEqualTo("NOT_EDITABLE");
    }
}

package com.catacomb5099.naviseerr.mediaserver;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JellyfinClientTest {

    /** As Jellyfin 12.2.0 answers GET /Library/MediaFolders with one music library (fields trimmed). */
    private static final String MEDIA_FOLDERS = """
            {"Items":[
              {"Name":"Music","Id":"7e64e319657a9516ec78490da03edccb","Path":"/config/root/default/Music","CollectionType":"music"},
              {"Name":"Films","Id":"aaaa","Path":"/config/root/default/Films","CollectionType":"movies"},
              {"Name":"Playlists","Id":"1071671e7bffa0532e930debee501d2e","Path":"/config/data/playlists","CollectionType":"playlists"}
            ],"TotalRecordCount":3,"StartIndex":0}
            """;

    private MockWebServer server;
    private JellyfinClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String url = server.url("/").toString();
        client = new JellyfinClient(MediaServerConfig.http(url, Duration.ofSeconds(2))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "MediaBrowser Token=\"k3y\"").build(),
                url, "k3y", Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void rescan_refreshesEachMusicLibrary_withTheKeyInTheHeader_neverInTheUrl() throws InterruptedException {
        server.enqueue(json(200, MEDIA_FOLDERS));
        server.enqueue(new MockResponse().setResponseCode(204));

        StepVerifier.create(client.rescan()).verifyComplete();

        RecordedRequest list = server.takeRequest();
        assertEquals("GET", list.getMethod());
        assertEquals("/Library/MediaFolders", list.getPath());
        assertEquals("MediaBrowser Token=\"k3y\"", list.getHeader("Authorization"));
        RecordedRequest refresh = server.takeRequest();
        assertEquals("POST", refresh.getMethod());
        assertEquals("/Items/7e64e319657a9516ec78490da03edccb/Refresh?Recursive=true&MetadataRefreshMode=Default&ImageRefreshMode=Default",
                refresh.getPath());
        assertEquals("MediaBrowser Token=\"k3y\"", refresh.getHeader("Authorization"));
        assertEquals(2, server.getRequestCount(), "the film library is left alone");
    }

    @Test
    void noMusicLibrary_fallsBackToRefreshingEverything() throws InterruptedException {
        server.enqueue(json(200, "{\"Items\":[{\"Name\":\"Films\",\"Id\":\"aaaa\",\"CollectionType\":\"movies\"}]}"));
        server.enqueue(new MockResponse().setResponseCode(204));

        StepVerifier.create(client.rescan()).verifyComplete();

        server.takeRequest();
        RecordedRequest refresh = server.takeRequest();
        assertEquals("POST", refresh.getMethod());
        assertEquals("/Library/Refresh", refresh.getPath());
    }

    @Test
    void musicLibraryIds_matchesMusicCaseInsensitively_andAnUntypedMixedLibrary() throws Exception {
        // A "Mixed" library has no CollectionType; Jellyfin imports music and playlists from it too, so it is
        // refreshed per library rather than falling back to the full Library/Refresh of everything.
        tools.jackson.databind.JsonNode body = new tools.jackson.databind.ObjectMapper().readTree(
                "{\"Items\":[{\"Id\":\"a\",\"CollectionType\":\"Music\"},{\"Id\":\"b\",\"CollectionType\":\"movies\"},{\"Id\":\"\",\"CollectionType\":\"music\"},{\"Id\":\"m\",\"Name\":\"Mixed\"}]}");
        assertEquals(List.of("a", "m"), JellyfinClient.musicLibraryIds(body));
    }

    @Test
    void aRejectedKey_isSaidPlainly() {
        server.enqueue(new MockResponse().setResponseCode(401));

        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(MediaServerException.class, error);
                    assertEquals("Jellyfin rejected the API key (JELLYFIN_API_KEY)", error.getMessage());
                })
                .verify();
    }

    @Test
    void anotherHttpErrorOrADeadHost_namesTheServer() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> {
                    assertTrue(error.getMessage().startsWith("Jellyfin at http://"), error.getMessage());
                    assertTrue(error.getMessage().endsWith("did not answer: HTTP 500"), error.getMessage());
                })
                .verify();

        server.shutdown();
        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> assertTrue(error.getMessage().contains("did not answer"), error.getMessage()))
                .verify();
    }

    /** As 12.2.0 answers the playlist search (fields trimmed); the Path is Jellyfin's own mount, not ours. */
    private static final String PLAYLIST_ITEMS = """
            {"Items":[
              {"Name":"Heavy Metal Workout (old)","Id":"0ld","Path":"/music/Playlists/Heavy Metal Workout (old).m3u8","Type":"Playlist"},
              {"Name":"Heavy Metal Workout","Id":"1d3a","Path":"/music/Playlists/Heavy Metal Workout.m3u8","Type":"Playlist"}
            ],"TotalRecordCount":2,"StartIndex":0}
            """;

    @Test
    void findPlaylist_asksByTitle_andPicksTheItemWhosePathIsOurFile() throws InterruptedException {
        server.enqueue(json(200, PLAYLIST_ITEMS));

        StepVerifier.create(client.findPlaylist("Heavy Metal Workout.m3u8")).expectNext("1d3a").verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("/Items", request.getRequestUrl().encodedPath());
        assertEquals("Playlist", request.getRequestUrl().queryParameter("includeItemTypes"));
        assertEquals("true", request.getRequestUrl().queryParameter("recursive"));
        assertEquals("Heavy Metal Workout", request.getRequestUrl().queryParameter("searchTerm"));
        assertEquals("Path", request.getRequestUrl().queryParameter("fields"));
        assertEquals("MediaBrowser Token=\"k3y\"", request.getHeader("Authorization"));
    }

    @Test
    void findPlaylist_isEmptyWhileJellyfinHasNotImportedTheFile_evenWhenAJellyfinMadePlaylistSharesTheTitle() {
        server.enqueue(json(200, "{\"Items\":[],\"TotalRecordCount\":0}"));
        StepVerifier.create(client.findPlaylist("New.m3u8")).verifyComplete();

        // A playlist the user made inside Jellyfin (its own Path, never our file) must keep its own picture.
        server.enqueue(json(200, "{\"Items\":[{\"Name\":\"New\",\"Id\":\"n4tive\",\"Path\":\"/config/data/playlists/New/playlist.xml\",\"Type\":\"Playlist\"}]}"));
        StepVerifier.create(client.findPlaylist("New.m3u8")).verifyComplete();
    }

    @Test
    void setPrimaryImage_postsThePictureAsBase64Text_withTheImageContentType() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 7, 8, 9};

        StepVerifier.create(client.setPrimaryImage("1d3a", jpeg)).verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/Items/1d3a/Images/Primary", request.getPath());
        assertEquals("image/jpeg", request.getHeader("Content-Type"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(jpeg), request.getBody().readUtf8());
    }

    @Test
    void configuredAndCredentials_followTheSettings() {
        assertTrue(client.isConfigured());
        assertTrue(client.hasCredentials());
        JellyfinClient unset = new JellyfinClient(MediaServerConfig.http("", Duration.ofSeconds(1)).build(), "", "", Duration.ofSeconds(1));
        assertFalse(unset.isConfigured());
        JellyfinClient noKey = new JellyfinClient(MediaServerConfig.http("http://nas:8096/", Duration.ofSeconds(1)).build(),
                "http://nas:8096/", " ", Duration.ofSeconds(1));
        assertTrue(noKey.isConfigured());
        assertFalse(noKey.hasCredentials());
        assertEquals("http://nas:8096", noKey.url());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}

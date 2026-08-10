package com.catacomb5099.naviseerr.services.ytmusic;

import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class YtMusicServiceTest {

    private static final String SONGS_BODY = """
            {
              "query": "Oasis Wonderwall",
              "type": "songs",
              "count": 1,
              "items": [
                {
                  "type": "song",
                  "videoId": "hpSrLjc5SMs",
                  "browseId": null,
                  "playlistId": null,
                  "title": "Wonderwall",
                  "artists": [{"name": "Oasis", "channelId": "UCmMUZbaYdNH0bEd1PAlAqsA"}],
                  "album": {"name": "(What's The Story) Morning Glory?", "browseId": "MPREb_PITqkpE6ExP"},
                  "durationSeconds": 259,
                  "thumbnailUrl": "https://example.com/song.jpg",
                  "explicit": false,
                  "year": null
                }
              ]
            }
            """;

    private MockWebServer server;
    private YtMusicService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
        service = new YtMusicService(webClient);
        ReflectionTestUtils.setField(service, "searchResultLimit", 10);
        ReflectionTestUtils.setField(service, "timeoutMs", 2000L);
        ReflectionTestUtils.setField(service, "retryCount", 1);
        ReflectionTestUtils.setField(service, "firstBackOffDurationMs", 1L);
    }

    @AfterEach
    void tearDown() {
        try {
            server.shutdown();
        } catch (IOException ignored) {
            // already shut down by a test that closes the server itself
        }
    }

    @Test
    void getResults_happyPath_deserializesAndMapsSongsResponse() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .assertNext(response -> {
                    assertEquals(1, response.getTracks().size());
                    assertEquals("hpSrLjc5SMs", response.getTracks().get(0).getId());
                    assertTrue(response.getAlbums().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/search/songs?q=Oasis%20Wonderwall&limit=10", request.getPath());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_400WithDetailStringShape_mapsToBadRequest_andDoesNotRetry() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"Unsupported type 'bogus'. Must be one of: songs, videos, albums, artists, playlists\"}"));

        StepVerifier.create(service.getResults("x", YtMusicSearchType.SONGS))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(YtMusicBadRequestException.class, error);
                    assertTrue(error.getMessage().contains("Unsupported type"));
                })
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_422WithDetailArrayShape_mapsToBadRequest_andDoesNotRetry() {
        server.enqueue(new MockResponse().setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"detail\":[{\"type\":\"missing\",\"loc\":[\"query\",\"q\"],\"msg\":\"Field required\",\"input\":null}]}"));

        StepVerifier.create(service.getResults("x", YtMusicSearchType.SONGS))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(YtMusicBadRequestException.class, error);
                    assertTrue(error.getMessage().contains("Field required"));
                })
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_500EnvelopeShape_mapsToBadRequest_andDoesNotRetry() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"internal_auth_misuse\",\"message\":\"Internal error: attempted an auth-required call\"}}"));

        StepVerifier.create(service.getResults("x", YtMusicSearchType.SONGS))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(YtMusicBadRequestException.class, error);
                    assertEquals("Internal error: attempted an auth-required call", error.getMessage());
                })
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_429EnvelopeShape_mapsToUnavailable_andRetriesToSuccess() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "30")
                .setBody("{\"error\":{\"code\":\"rate_limited\",\"message\":\"Upstream rate limit exceeded\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .assertNext(response -> assertEquals(1, response.getTracks().size()))
                .verifyComplete();

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void getResults_502EnvelopeShape_mapsToUnavailable_andRetriesToSuccess() {
        server.enqueue(new MockResponse().setResponseCode(502)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"upstream_error\",\"message\":\"Upstream request failed\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .assertNext(response -> assertEquals(1, response.getTracks().size()))
                .verifyComplete();

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void getResults_clientSideTimeout_mapsToUnavailable() {
        // retryCount=0 here: proving the retry filter itself accepts YtMusicUnavailableException
        // is already covered by the 429/502 tests above, without a timing-sensitive HTTP delay
        // that would otherwise interact with the WebClient connection pool on retry.
        ReflectionTestUtils.setField(service, "timeoutMs", 100L);
        ReflectionTestUtils.setField(service, "retryCount", 0);

        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .expectError(YtMusicUnavailableException.class)
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_connectionRefused_mapsToUnavailable() throws IOException {
        server.shutdown();

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .expectError(YtMusicUnavailableException.class)
                .verify();
    }
}

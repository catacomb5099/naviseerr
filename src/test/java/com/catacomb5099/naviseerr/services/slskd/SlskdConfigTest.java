package com.catacomb5099.naviseerr.services.slskd;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code slskdWebClient()} is built with the static {@code WebClient.builder()}, which never applies
 * Spring Boot's codec configuration -- only the injected, Boot-managed {@code WebClient.Builder}
 * prototype gets the {@code WebClientCodecCustomizer}. That leaves the framework default in-memory
 * buffer limit (256 KiB) in force. {@code SlskdService#getSearchWithResponses} fetches a search
 * including every peer response, which for a popular track's search on a live slskd instance measured
 * over 1 MiB decoded -- 4x the default limit -- and failed with a {@code DataBufferLimitException}
 * wrapped in a {@code WebClientResponseException}, even though the HTTP call itself succeeded with
 * 200 OK.
 */
class SlskdConfigTest {

    /** A search whose padded searchText pushes it past the 256 KiB default -- but under a raised limit. */
    private static final String OVERSIZED_BODY = """
            {
              "endedAt": "2026-03-15T15:55:00.4425569Z",
              "fileCount": 4587,
              "id": "x",
              "isComplete": true,
              "lockedFileCount": 30,
              "responseCount": 250,
              "responses": [],
              "searchText": "%s",
              "startedAt": "2026-03-15T15:54:00.0000000Z",
              "state": "Completed, Succeeded",
              "token": 1
            }
            """.formatted("a".repeat(2 * 1024 * 1024));

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void slskdWebClient_toleratesResponsesLargerThanTheFrameworkDefaultBufferLimit() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(OVERSIZED_BODY));

        SlskdConfig config = new SlskdConfig();
        ReflectionTestUtils.setField(config, "url", server.url("/").toString());
        ReflectionTestUtils.setField(config, "apiKey", "test-key");

        WebClient webClient = config.slskdWebClient();

        assertDoesNotThrow(() -> new SlskdService(webClient)
                .getSearchWithResponses("x")
                .block());
    }

    /**
     * Regression test for the incident this timeout was added for: a connection that never
     * responds (a silently dropped one, in production) used to hang until the OS gave up on the TCP
     * read -- around 90s. {@code responseTimeout} bounds that to a small, configured value instead.
     */
    @Test
    void slskdWebClient_boundsAHungResponse_ratherThanWaitingForTheSocketToTimeOut() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setBody("{}"));

        SlskdConfig config = new SlskdConfig();
        ReflectionTestUtils.setField(config, "url", server.url("/").toString());
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "responseTimeout", Duration.ofMillis(200));

        WebClient webClient = config.slskdWebClient();

        long start = System.nanoTime();
        assertThrows(Exception.class, () -> new SlskdService(webClient)
                .getSearchWithResponses("x")
                .block());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Well under the mock server's 2s delay -- proves responseTimeout cut the call short rather
        // than the call eventually succeeding or some other timeout (e.g. .block()'s own) kicking in.
        assertTrue(elapsedMs < 1_000, "expected the call to fail near responseTimeout (200ms), took "
                + elapsedMs + "ms");
    }
}

package com.catacomb5099.naviseerr.curator;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CuratorClientTest {

    private static final String QUEUED_BODY = """
            {
              "runId": "2026-09-27T21-40-12Z",
              "status": "queued",
              "requestedAt": "2026-09-27T21:40:12Z",
              "startedAt": null,
              "finishedAt": null,
              "somethingNew": {"ignored": true},
              "categories": [
                {"key": "80s-indie-pop", "status": "queued", "editionDate": null, "trackCount": null,
                 "message": null, "extra": 1}
              ]
            }
            """;

    private MockWebServer server;
    private CuratorClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();
        client = new CuratorClient(webClient, Duration.ofSeconds(2), Duration.ofMillis(1), 1);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void triggerRun_202_returnsQueuedRunAndSendsBearerToken() throws InterruptedException {
        server.enqueue(json(202, QUEUED_BODY));

        StepVerifier.create(client.triggerRun())
                .assertNext(run -> {
                    assertEquals("2026-09-27T21-40-12Z", run.runId());
                    assertEquals("queued", run.status());
                    assertFalse(run.isFinal());
                    assertEquals(1, run.categories().size());
                    assertEquals("80s-indie-pop", run.categories().getFirst().key());
                })
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/runs", request.getPath());
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
    }

    @Test
    void triggerRun_200_alreadyRunningIsJustTheRun() {
        server.enqueue(json(200, QUEUED_BODY.replace("\"queued\"", "\"running\"")));

        StepVerifier.create(client.triggerRun())
                .assertNext(run -> assertEquals("running", run.status()))
                .verifyComplete();
    }

    @Test
    void triggerRun_401_failsWithTokenMessageAndIsNotRetried() {
        server.enqueue(json(401, "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"bad token\"}}"));

        StepVerifier.create(client.triggerRun())
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(CuratorException.class, error);
                    assertFalse(((CuratorException) error).isRetryable());
                    assertTrue(error.getMessage().contains("curator rejected the token"), error.getMessage());
                })
                .verify();
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void triggerRun_5xxThenSuccess_retries() {
        server.enqueue(json(503, "{\"error\":{\"code\":\"BUSY\",\"message\":\"restarting\"}}"));
        server.enqueue(json(202, QUEUED_BODY));

        StepVerifier.create(client.triggerRun())
                .assertNext(run -> assertEquals("queued", run.status()))
                .verifyComplete();
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void triggerRun_400_isNotRetried() {
        server.enqueue(json(400, "{\"error\":{\"code\":\"UNKNOWN_CATEGORY\",\"message\":\"no such key\"}}"));

        StepVerifier.create(client.triggerRun())
                .expectErrorSatisfies(error -> {
                    assertFalse(((CuratorException) error).isRetryable());
                    assertTrue(error.getMessage().contains("no such key"));
                })
                .verify();
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void triggerRunOnce_5xx_isNotRetried() {
        server.enqueue(json(503, "{\"error\":{\"code\":\"BUSY\",\"message\":\"restarting\"}}"));

        StepVerifier.create(client.triggerRunOnce())
                .expectErrorSatisfies(error -> assertTrue(((CuratorException) error).isRetryable()))
                .verify();
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getLatestRun_readsTheRun_and404IsNotFound() throws InterruptedException {
        server.enqueue(json(200, QUEUED_BODY.replace("\"queued\"", "\"running\"")));
        server.enqueue(json(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no run yet\"}}"));

        StepVerifier.create(client.getLatestRun())
                .assertNext(run -> assertEquals("running", run.status()))
                .verifyComplete();
        assertEquals("/v1/runs/latest", server.takeRequest().getPath());

        StepVerifier.create(client.getLatestRun())
                .expectErrorSatisfies(error -> assertTrue(((CuratorException) error).isNotFound()))
                .verify();
    }

    @Test
    void getRun_readsFinalState() throws InterruptedException {
        server.enqueue(json(200, """
                {"runId": "r1", "status": "succeeded", "categories": [
                  {"key": "80s-indie-pop", "status": "written", "editionDate": "2026-09-27", "trackCount": 40}
                ]}
                """));

        StepVerifier.create(client.getRun("r1"))
                .assertNext(run -> {
                    assertTrue(run.isFinal());
                    assertEquals(40, run.categories().getFirst().trackCount());
                })
                .verifyComplete();
        assertEquals("/v1/runs/r1", server.takeRequest().getPath());
    }

    @Test
    void getEditions_parsesTheListAndSendsTheToken() throws InterruptedException {
        server.enqueue(json(200, """
                [{"category": "80s-indie-pop", "title": "80s indie pop", "editionDate": "2026-09-27", "trackCount": 40, "new": 1},
                 {"category": "current-pop", "title": "Current pop", "editionDate": "2026-09-28", "trackCount": 38}]
                """));

        StepVerifier.create(client.getEditions())
                .assertNext(editions -> {
                    assertEquals(2, editions.size());
                    assertEquals("80s-indie-pop", editions.getFirst().category());
                    assertEquals("80s indie pop", editions.getFirst().title());
                    assertEquals("2026-09-27", editions.getFirst().editionDate());
                    assertEquals(40, editions.getFirst().trackCount());
                    assertEquals(38, editions.get(1).trackCount());
                })
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/v1/editions", request.getPath());
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
    }

    @Test
    void getEdition_parsesTheTracksAndTheCategoryFilters() throws InterruptedException {
        server.enqueue(json(200, """
                {"title": "80s indie pop", "category": {"title": "80s indie pop", "year": "1980-1989", "style": "Indie Pop"},
                 "editionDate": "2026-09-27", "seed": 1738171583,
                 "tracks": [
                   {"videoId": "kkxixKRfEnk", "title": "Cico Buff", "artists": ["Cocteau Twins"], "album": "Blue Bell Knoll",
                    "albumYear": 1988, "popularity": 6100000, "tier": "top", "reason": "#23 of 1036 by plays", "extra": true},
                   {"videoId": "ewnLtRyqAzo", "title": "Decomposing Trees", "artists": ["Galaxie 500"], "album": "On Fire",
                    "albumId": "MPREb_x", "albumYear": 1988, "popularity": 180000, "tier": "random", "reason": "random pick"}
                 ]}
                """));

        StepVerifier.create(client.getEdition("80s-indie-pop"))
                .assertNext(edition -> {
                    assertEquals("80s indie pop", edition.title());
                    assertEquals("2026-09-27", edition.editionDate());
                    assertEquals(1738171583L, edition.seed());
                    assertEquals("1980-1989", edition.category().get("year"));
                    assertEquals("Indie Pop", edition.category().get("style"));
                    assertEquals(2, edition.tracks().size());
                    CuratorTrack first = edition.tracks().getFirst();
                    assertEquals("kkxixKRfEnk", first.videoId());
                    assertEquals(List.of("Cocteau Twins"), first.artists());
                    assertEquals(6_100_000L, first.popularity());
                    assertEquals("top", first.tier());
                    assertNull(first.albumId(), "older editions carry no albumId");
                    assertEquals("MPREb_x", edition.tracks().get(1).albumId());
                })
                .verifyComplete();
        assertEquals("/v1/editions/80s-indie-pop", server.takeRequest().getPath());
    }

    @Test
    void getEdition_404_isNotFoundAndNotRetryable() {
        server.enqueue(json(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no edition for 90s-grime\"}}"));

        StepVerifier.create(client.getEdition("90s-grime"))
                .expectErrorSatisfies(error -> {
                    CuratorException ce = assertInstanceOf(CuratorException.class, error);
                    assertTrue(ce.isNotFound());
                    assertEquals(404, ce.getStatus());
                    assertFalse(ce.isRetryable());
                    assertTrue(ce.getMessage().contains("no edition for 90s-grime"));
                })
                .verify();
        assertEquals(1, server.getRequestCount(), "a read is not retried");
    }

    @Test
    void connectionRefused_hasNoStatusAndIsRetryable() throws IOException {
        server.shutdown();

        StepVerifier.create(client.getEditions())
                .expectErrorSatisfies(error -> {
                    CuratorException ce = assertInstanceOf(CuratorException.class, error);
                    assertEquals(0, ce.getStatus());
                    assertFalse(ce.isNotFound());
                    assertTrue(ce.isRetryable());
                })
                .verify();
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}

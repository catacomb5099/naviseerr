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

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}

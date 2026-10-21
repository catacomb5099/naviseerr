package com.catacomb5099.naviseerr.learning;

import com.catacomb5099.naviseerr.settings.InstallSettingsRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SearchLearningSenderTest {

    static final Instant NOW = Instant.parse("2026-10-20T22:40:37Z");

    private final SearchLearningRepository reports = mock(SearchLearningRepository.class);
    private final InstallSettingsRepository settings = mock(InstallSettingsRepository.class);
    private final SearchReport report = new SearchReport(UUID.randomUUID(), NOW, "SONG",
            new SearchReport.Song("hpSrLjc5SMs", "Wonderwall", List.of("Oasis"), null, 259, "Wonderwall - Oasis"), List.of(),
            new SearchReport.Won("Wonderwall", "SEARCH"), new SearchReport.File("Oasis/Wonderwall.flac", "flac", null, 258, 1L,
            "EXACT", 1), "LOSSLESS");
    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        when(settings.searchLearningSince()).thenReturn(Mono.just(NOW));
        when(reports.unsent(anyInt())).thenReturn(Flux.just(report));
        when(reports.markSent(any(), any())).thenReturn(Mono.just(1L));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private SearchLearningSender sender(String url, String token) {
        return new SearchLearningSender(reports, settings, Clock.fixed(NOW, ZoneOffset.UTC), url, token, 100, Duration.ofSeconds(2));
    }

    @Test
    void on_postsTheBatchWithTheToken_andA2xxMarksExactlyThoseSent() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"stored\":1}"));

        sender(server.url("/v1/search-reports").toString(), "secret").tick().block();

        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("POST", sent.getMethod());
        assertEquals("/v1/search-reports", sent.getPath());
        assertEquals("Bearer secret", sent.getHeader("Authorization"));
        String body = sent.getBody().readUtf8();
        assertTrue(body.startsWith("{\"schema\":1,\"reports\":[{\"id\":\"" + report.id()), body);
        verify(reports).markSent(List.of(report.id()), NOW);
    }

    @Test
    void aRefusal_marksNothing_andSaysSoForTheSettingsPage() {
        server.enqueue(new MockResponse().setResponseCode(500));
        SearchLearningSender sender = sender(server.url("/v1/search-reports").toString(), "");

        sender.tick().block();

        verify(reports, never()).markSent(any(), any());
        assertEquals("The receiver answered HTTP 500; the reports wait for the next try.", sender.problem());

        server.enqueue(new MockResponse().setResponseCode(204));
        sender.tick().block();
        assertNull(sender.problem(), "the next good send clears it");
    }

    @Test
    void off_orNoAddress_sendsNothing() {
        when(settings.searchLearningSince()).thenReturn(Mono.empty());
        sender(server.url("/").toString(), "").tick().block();
        SearchLearningSender unconfigured = sender(" ", "");
        unconfigured.tick().block();

        assertFalse(unconfigured.isConfigured());
        assertNull(unconfigured.sendsTo());
        assertEquals("croissant:8010", sender("http://user:pw@croissant:8010/v1/search-reports", "").sendsTo(), "host and port only");
        assertEquals(0, server.getRequestCount());
        verify(reports, never()).unsent(anyInt());
    }
}

package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private DownloadService downloadService;
    private ActiveDownloadRepository activeDownloadRepository;
    private DownloadController controller;

    @BeforeEach
    void setUp() {
        downloadService = mock(DownloadService.class);
        activeDownloadRepository = mock(ActiveDownloadRepository.class);
        controller = new DownloadController(downloadService, activeDownloadRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5), RETENTION);
    }

    private static ActiveDownloadView view() {
        return new ActiveDownloadView(UUID.randomUUID(), "song", List.of("Artist"),
                "https://example.com/cover.jpg", DownloadStage.DOWNLOADING,
                new BigDecimal("43.00"), NOW, NOW, null);
    }

    @Test
    void activeDownloads_returnsThePollIntervalAndRetentionWindowAlongsideTheRows() {
        ActiveDownloadView view = view();
        when(activeDownloadRepository.findActive(any())).thenReturn(Flux.just(view));

        ActiveDownloadsResponse response = controller.activeDownloads().block();

        assertEquals(5000L, response.pollIntervalMs());
        // Published so the client's auto-dismiss delay and its memory of what the user dismissed can
        // both be sized to outlive this window instead of guessing at it.
        assertEquals(600_000L, response.terminalRetentionMs());
        assertEquals(List.of(view), response.downloads());
    }

    @Test
    void activeDownloads_asksForRowsFinishedWithinTheRetentionWindow() {
        when(activeDownloadRepository.findActive(any())).thenReturn(Flux.empty());

        controller.activeDownloads().block();

        verify(activeDownloadRepository).findActive(NOW.minus(RETENTION));
    }

    @Test
    void activeDownloads_withNothingInFlight_returnsAnEmptyList() {
        when(activeDownloadRepository.findActive(any())).thenReturn(Flux.empty());

        assertTrue(controller.activeDownloads().block().downloads().isEmpty());
    }

    @Test
    void downloadsByIds_returnsWhateverResolved() {
        ActiveDownloadView view = view();
        when(activeDownloadRepository.findByIds(anyList())).thenReturn(Flux.just(view));

        ResponseEntity<DownloadsByIdResponse> response =
                controller.downloadsByIds(List.of(view.downloadId())).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(view), response.getBody().downloads());
    }

    @Test
    void downloadsByIds_withNoIds_isRejectedWithoutQuerying() {
        ResponseEntity<DownloadsByIdResponse> response = controller.downloadsByIds(List.of()).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(activeDownloadRepository, never()).findByIds(anyList());
    }

    @Test
    void downloadsByIds_aboveTheCap_isRejectedWithoutQuerying() {
        // The only thing these read endpoints add that a caller could size themselves.
        List<UUID> tooMany = Stream.generate(UUID::randomUUID)
                .limit(DownloadController.MAX_RESOLVE_IDS + 1).toList();

        ResponseEntity<DownloadsByIdResponse> response = controller.downloadsByIds(tooMany).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(activeDownloadRepository, never()).findByIds(anyList());
    }

    @Test
    void downloadsByIds_atTheCap_isAccepted() {
        when(activeDownloadRepository.findByIds(anyList())).thenReturn(Flux.empty());
        List<UUID> exactly = Stream.generate(UUID::randomUUID)
                .limit(DownloadController.MAX_RESOLVE_IDS).toList();

        assertEquals(HttpStatus.OK, controller.downloadsByIds(exactly).block().getStatusCode());
    }

    // ---- deprecated POST /download/{songName} ---------------------------------------------------

    @Test
    void download_deprecatedPathRoute_stillReturns202() {
        Download saved = Download.builder()
                .downloadId(UUID.randomUUID()).songName("song")
                .status(DownloadStatus.PENDING).createdAt(NOW).build();
        when(downloadService.requestDownload("song")).thenReturn(Mono.just(saved));

        ResponseEntity<Download> response = controller.download("song").block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(saved, response.getBody());
    }

    @Test
    void download_deprecatedPathRoute_rejectsBlankSongName() {
        ResponseEntity<Download> response = controller.download(" ").block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(downloadService, never()).requestDownload(anyString());
    }

    // ---- POST /download (body route) ------------------------------------------------------------

    @Test
    void requestDownload_returns202WithTheServicesSavedDownload() {
        Download saved = Download.builder()
                .downloadId(UUID.randomUUID()).songName("Riptide")
                .status(DownloadStatus.PENDING).createdAt(NOW).build();
        when(downloadService.requestDownload(eq("Riptide"), eq(List.of("Vance Joy")),
                eq("https://example.com/cover.jpg"))).thenReturn(Mono.just(saved));

        ResponseEntity<Download> response = controller.requestDownload(
                new DownloadRequest("Riptide", List.of("Vance Joy"), "https://example.com/cover.jpg"))
                .block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(saved, response.getBody());
    }

    @Test
    void requestDownload_withNullSongName_isRejectedWithoutCallingTheService() {
        ResponseEntity<Download> response =
                controller.requestDownload(new DownloadRequest(null, List.of(), null)).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(downloadService, never()).requestDownload(any(), anyList(), any());
    }

    @Test
    void requestDownload_withBlankSongName_isRejectedWithoutCallingTheService() {
        ResponseEntity<Download> response =
                controller.requestDownload(new DownloadRequest("   ", List.of(), null)).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(downloadService, never()).requestDownload(any(), anyList(), any());
    }

    @Test
    void requestDownload_withNullArtists_passesAnEmptyListToTheServiceRatherThanRejecting() {
        Download saved = Download.builder()
                .downloadId(UUID.randomUUID()).songName("song")
                .status(DownloadStatus.PENDING).createdAt(NOW).build();
        when(downloadService.requestDownload(eq("song"), eq(List.of()), isNull()))
                .thenReturn(Mono.just(saved));

        // DownloadRequest itself normalises null artists to List.of() in its canonical constructor,
        // which is what this asserts end to end through the controller.
        ResponseEntity<Download> response =
                controller.requestDownload(new DownloadRequest("song", null, null)).block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(downloadService).requestDownload("song", List.of(), null);
    }
}

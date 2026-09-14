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
        return new ActiveDownloadView(UUID.randomUUID(), "song", DownloadStage.DOWNLOADING,
                new BigDecimal("43.00"), NOW, NOW, null);
    }

    // ---- requesting a download -----------------------------------------------------------------

    @Test
    void downloadSong_acceptsTheIdAndAcksImmediately() {
        Download saved = Download.builder().downloadId(UUID.randomUUID()).build();
        when(downloadService.requestDownload("vid-1", DownloadType.SONG))
                .thenReturn(Mono.just(saved));

        ResponseEntity<Download> response = controller.downloadSong("vid-1").block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(saved, response.getBody());
    }

    @Test
    void downloadSong_withABlankId_isRejectedWithoutWriting() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.downloadSong("  ").block().getStatusCode());
        verify(downloadService, never()).requestDownload(any(), any());
    }

    @Test
    void downloadCollection_recordsTheRequestedType() {
        Download saved = Download.builder().downloadId(UUID.randomUUID()).build();
        when(downloadService.requestDownload("MPREb_1", DownloadType.ALBUM))
                .thenReturn(Mono.just(saved));

        ResponseEntity<Download> response =
                controller.downloadCollection("MPREb_1", DownloadType.ALBUM).block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        // The type decides which ytmusic-adapter endpoint admission calls, so losing it here would
        // send an album id to the playlist route.
        verify(downloadService).requestDownload("MPREb_1", DownloadType.ALBUM);
    }

    @Test
    void downloadCollection_withTypeSong_isRejected() {
        // A single track has its own route; accepting SONG here would create a download the
        // collection branch of admission cannot resolve.
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.downloadCollection("vid-1", DownloadType.SONG).block().getStatusCode());
        verify(downloadService, never()).requestDownload(any(), any());
    }

    @Test
    void aFailedInsertIsReportedAsAServerError_notAsAnAck() {
        when(downloadService.requestDownload(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                controller.downloadSong("vid-1").block().getStatusCode());
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
}

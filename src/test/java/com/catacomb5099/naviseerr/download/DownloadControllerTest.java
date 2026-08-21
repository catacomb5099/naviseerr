package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DownloadControllerTest {

    private DownloadService downloadService;
    private ActiveDownloadRepository activeDownloadRepository;
    private DownloadController controller;

    @BeforeEach
    void setUp() {
        downloadService = mock(DownloadService.class);
        activeDownloadRepository = mock(ActiveDownloadRepository.class);
        controller = new DownloadController(downloadService, activeDownloadRepository,
                Duration.ofSeconds(5));
    }

    @Test
    void activeDownloads_returnsThePollIntervalAlongsideTheRows() {
        ActiveDownloadView view = new ActiveDownloadView(UUID.randomUUID(), "song",
                DownloadStatus.IN_PROGRESS, DownloadPhase.DOWNLOAD_POLL,
                new BigDecimal("43.00"), Instant.now());
        when(activeDownloadRepository.findActive()).thenReturn(Flux.just(view));

        ActiveDownloadsResponse response = controller.activeDownloads().block();

        assertEquals(5000L, response.pollIntervalMs());
        assertEquals(List.of(view), response.downloads());
    }

    @Test
    void activeDownloads_withNothingInFlight_returnsAnEmptyList() {
        when(activeDownloadRepository.findActive()).thenReturn(Flux.empty());

        ActiveDownloadsResponse response = controller.activeDownloads().block();

        assertTrue(response.downloads().isEmpty());
    }
}

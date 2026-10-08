package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.DownloadTaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DownloadServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final DownloadTaskRepository repository = mock(DownloadTaskRepository.class);
    private final SlskdService slskd = mock(SlskdService.class);
    private final LibraryOrganiser organiser = mock(LibraryOrganiser.class);
    private final R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
    private final DownloadService service = new DownloadService(template, repository, slskd, organiser);

    @BeforeEach
    void defaults() {
        when(repository.concludeDownloads()).thenReturn(Mono.just(0L));
        when(organiser.deletePartials(any())).thenReturn(Mono.empty());
        when(slskd.cancelDownload(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void cancel_ofAQueuedDownload_failsItUnadmitted_andNeverTouchesTasks() {
        when(repository.failUnadmitted(id, DownloadFailureCode.CANCELLED, NOW)).thenReturn(Mono.just(1L));

        assertEquals(1L, service.cancel(id, null, NOW).block());

        verify(repository, never()).cancelTasks(any(), any(), any());
        verify(repository).concludeDownloads();
    }

    @Test
    void cancel_ofARunningDownload_cancelsItsSongs_stopsTheirTransfers_andConcludes() {
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(0L));
        DownloadTask polling = DownloadTaskFixtures.downloadPolling(DownloadTaskFixtures.candidates("alice"), 0, 0, "t-1");
        DownloadTask searching = DownloadTaskFixtures.searchPolling("s-1");
        when(repository.cancelTasks(id, null, NOW)).thenReturn(Flux.just(polling, searching));

        assertEquals(2L, service.cancel(id, null, NOW).block());

        verify(slskd).cancelDownload("alice", "t-1");
        verify(slskd, never()).cancelDownload(eq(searching.slskdUsername()), any());
        verify(organiser, times(2)).deletePartials(any());
        // The spec names this order as what makes the admission race come out right:
        // the queued-download check first, then the songs, then the download's status.
        InOrder statements = inOrder(repository);
        statements.verify(repository).failUnadmitted(id, DownloadFailureCode.CANCELLED, NOW);
        statements.verify(repository).cancelTasks(id, null, NOW);
        statements.verify(repository).concludeDownloads();
    }

    @Test
    void cancel_ofOneSong_skipsTheUnadmittedCheck() {
        UUID taskId = UUID.randomUUID();
        when(repository.cancelTasks(id, taskId, NOW)).thenReturn(Flux.empty());

        assertEquals(0L, service.cancel(id, taskId, NOW).block());

        verify(repository, never()).failUnadmitted(any(), any(), any());
        verify(repository).concludeDownloads();   // always, so the response body is never the "Waiting" quirk
    }

    @Test
    void cancel_whenSlskdRefuses_stillCancelsTheRow() {
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(0L));
        DownloadTask polling = DownloadTaskFixtures.downloadPolling(DownloadTaskFixtures.candidates("alice"), 0, 0, "t-1");
        when(repository.cancelTasks(id, null, NOW)).thenReturn(Flux.just(polling));
        when(slskd.cancelDownload(any(), any())).thenReturn(Mono.error(new RuntimeException("slskd down")));

        assertEquals(1L, service.cancel(id, null, NOW).block());
    }

    @Test
    void retry_thatResetSongs_doesNotReadmit() {
        when(repository.retry(id, null, NOW)).thenReturn(Mono.just(1L));
        assertEquals(1L, service.retry(id, null, NOW).block());
        verify(repository, never()).readmit(any());
    }

    @Test
    void retry_withNothingToReset_fallsBackToReadmitting() {
        when(repository.retry(id, null, NOW)).thenReturn(Mono.just(0L));
        when(repository.readmit(id)).thenReturn(Mono.just(1L));
        assertEquals(1L, service.retry(id, null, NOW).block());
    }

    @Test
    void retry_withNothingToRetryOrReadmit_isZero() {
        when(repository.retry(id, null, NOW)).thenReturn(Mono.just(0L));
        when(repository.readmit(id)).thenReturn(Mono.just(0L));

        assertEquals(0L, service.retry(id, null, NOW).block());   // the endpoint turns this 0 into its 409
    }

    @Test
    void retry_ofOneSong_neverReadmits() {
        UUID taskId = UUID.randomUUID();
        when(repository.retry(id, taskId, NOW)).thenReturn(Mono.just(0L));

        assertEquals(0L, service.retry(id, taskId, NOW).block());
        verify(repository, never()).readmit(any());   // a bogus taskId must not re-queue an unadmitted failure
    }
}

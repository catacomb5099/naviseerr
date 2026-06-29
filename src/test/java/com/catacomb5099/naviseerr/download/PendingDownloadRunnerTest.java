package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PendingDownloadRunnerTest {

    private final DownloadService downloadService = mock(DownloadService.class);
    private final DownloadQueue downloadQueue = mock(DownloadQueue.class);
    private final PendingDownloadRunner runner =
            new PendingDownloadRunner(downloadService, downloadQueue, 3_600_000L, 10);

    private Download download(String songName) {
        return Download.builder()
                .downloadId(UUID.randomUUID())
                .songName(songName)
                .status(DownloadStatus.IN_PROGRESS)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void claimedDownloads_areEnqueued() {
        Download a = download("a");
        Download b = download("b");
        when(downloadService.claimPendingDownloads(anyInt())).thenReturn(Flux.just(a, b));

        StepVerifier.create(runner.processBatch()).verifyComplete();

        verify(downloadQueue).enqueue(a);
        verify(downloadQueue).enqueue(b);
    }

    @Test
    void claimError_isSwallowed_andNothingEnqueued() {
        when(downloadService.claimPendingDownloads(anyInt()))
                .thenReturn(Flux.error(new RuntimeException("db down")));

        StepVerifier.create(runner.processBatch()).verifyComplete();

        verifyNoInteractions(downloadQueue);
    }
}

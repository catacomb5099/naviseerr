package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadWorkerTest {

    private final DownloadQueue queue = new DownloadQueue();
    private final DownloadFulfillment fulfillment = mock(DownloadFulfillment.class);
    private final DownloadService downloadService = mock(DownloadService.class);
    private final DownloadWorker worker = new DownloadWorker(queue, fulfillment, downloadService, 3);

    private Download download(String songName) {
        return Download.builder()
                .downloadId(UUID.randomUUID())
                .songName(songName)
                .status(DownloadStatus.IN_PROGRESS)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void success_marksSucceeded() {
        Download d = download("ok");
        when(fulfillment.fulfill("ok")).thenReturn(Mono.just(mock(TransferedFile.class)));
        when(downloadService.markStatusIfInProgress(eq(d.getDownloadId()), eq(DownloadStatus.SUCCEEDED))).thenReturn(Mono.just(1L));

        StepVerifier.create(worker.process(d)).verifyComplete();

        verify(downloadService).markStatusIfInProgress(d.getDownloadId(), DownloadStatus.SUCCEEDED);
        verify(downloadService, never()).markStatusIfInProgress(d.getDownloadId(), DownloadStatus.FAILED);
    }

    @Test
    void providerError_marksFailed() {
        Download d = download("err");
        when(fulfillment.fulfill("err")).thenReturn(Mono.error(new RuntimeException("boom")));
        when(downloadService.markStatusIfInProgress(eq(d.getDownloadId()), eq(DownloadStatus.FAILED))).thenReturn(Mono.just(1L));

        StepVerifier.create(worker.process(d)).verifyComplete();

        verify(downloadService).markStatusIfInProgress(d.getDownloadId(), DownloadStatus.FAILED);
    }

    @Test
    void emptyResult_marksFailed() {
        Download d = download("empty");
        when(fulfillment.fulfill("empty")).thenReturn(Mono.empty());
        when(downloadService.markStatusIfInProgress(eq(d.getDownloadId()), eq(DownloadStatus.FAILED))).thenReturn(Mono.just(1L));

        StepVerifier.create(worker.process(d)).verifyComplete();

        verify(downloadService).markStatusIfInProgress(d.getDownloadId(), DownloadStatus.FAILED);
    }

    @Test
    void statusWriteFailure_isIsolated_andProcessStillCompletes() {
        Download d = download("dbdown");
        when(fulfillment.fulfill("dbdown")).thenReturn(Mono.error(new RuntimeException("boom")));
        when(downloadService.markStatusIfInProgress(eq(d.getDownloadId()), eq(DownloadStatus.FAILED)))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(worker.process(d)).verifyComplete();
    }

    @Test
    void succeededStatusWriteFailure_isNotReclassifiedAsFailed() {
        Download d = download("write-fails-after-success");
        when(fulfillment.fulfill("write-fails-after-success")).thenReturn(Mono.just(mock(TransferedFile.class)));
        when(downloadService.markStatusIfInProgress(eq(d.getDownloadId()), eq(DownloadStatus.SUCCEEDED)))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(worker.process(d)).verifyComplete();

        verify(downloadService).markStatusIfInProgress(d.getDownloadId(), DownloadStatus.SUCCEEDED);
        verify(downloadService, never()).markStatusIfInProgress(d.getDownloadId(), DownloadStatus.FAILED);
    }
}

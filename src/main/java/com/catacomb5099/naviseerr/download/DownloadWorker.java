package com.catacomb5099.naviseerr.download;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Consumer of {@link DownloadQueue}: subscribes once and runs {@link DownloadFulfillment} per item
 * with bounded concurrency, then writes the terminal status. Each item is isolated so one failure
 * never tears down the subscription. Queue-based, not event-driven: there is no event, no broker,
 * and no delivery guarantee - just an in-memory queue this process reads from.
 */
@Slf4j
@Component
public class DownloadWorker {

    private final DownloadQueue downloadQueue;
    private final DownloadFulfillment downloadFulfillment;
    private final DownloadService downloadService;
    private final int concurrency;
    private Disposable subscription;

    public DownloadWorker(
            DownloadQueue downloadQueue,
            DownloadFulfillment downloadFulfillment,
            DownloadService downloadService,
            @Value("${download-worker.concurrency:3}") int concurrency) {
        this.downloadQueue = downloadQueue;
        this.downloadFulfillment = downloadFulfillment;
        this.downloadService = downloadService;
        this.concurrency = concurrency;
    }

    @PostConstruct
    void start() {
        subscription = downloadQueue.asFlux()
                .flatMap(this::process, concurrency)
                .subscribe();
    }

    Mono<Void> process(Download download) {
        return downloadFulfillment.fulfill(download.getSongName())
                // Scoped to fulfillment only, so a status-write failure below can't be re-marked FAILED.
                .onErrorResume(error -> {
                    log.error("Download {} for song '{}' failed to fulfill",
                            download.getDownloadId(), download.getSongName(), error);
                    return Mono.empty();
                })
                .flatMap(transferedFile -> {
                    log.info("Download {} for song '{}' succeeded (file='{}')",
                            download.getDownloadId(), download.getSongName(), transferedFile.getFilename());
                    return downloadService.markStatusIfInProgress(download.getDownloadId(), DownloadStatus.SUCCEEDED);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Download {} for song '{}' yielded no result; marking FAILED",
                            download.getDownloadId(), download.getSongName());
                    return downloadService.markStatusIfInProgress(download.getDownloadId(), DownloadStatus.FAILED);
                }))
                .then()
                // Only a failed status write reaches here now; swallowed to keep the subscription alive.
                .onErrorResume(error -> {
                    log.error("Download {} for song '{}' ended without a confirmed terminal status; "
                                    + "row is left non-terminal and needs reclaiming",
                            download.getDownloadId(), download.getSongName());
                    return Mono.empty();
                });
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }
}

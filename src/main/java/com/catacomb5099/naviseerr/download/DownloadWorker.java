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
 * never tears down the subscription. Push-based within this process, not durable messaging.
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
                .onErrorResume(error -> {
                    log.error("Download {} for song '{}' failed; marking FAILED",
                            download.getDownloadId(), download.getSongName(), error);
                    return downloadService.markStatusIfInProgress(download.getDownloadId(), DownloadStatus.FAILED);
                })
                .then()
                // Only status-write failures reach here; swallowed to keep the subscription alive.
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

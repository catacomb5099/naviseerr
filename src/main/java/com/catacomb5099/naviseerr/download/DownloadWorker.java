package com.catacomb5099.naviseerr.download;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Event-driven consumer of {@link DownloadQueue}. Subscribes once and processes claimed downloads
 * with bounded concurrency: for each item it runs {@link DownloadFulfillment} and then writes the
 * terminal status. Every item is fully isolated so a single failure never tears down the worker
 * subscription.
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
                    return downloadService.markStatus(download.getDownloadId(), DownloadStatus.SUCCEEDED);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Download {} for song '{}' yielded no result; marking FAILED",
                            download.getDownloadId(), download.getSongName());
                    return downloadService.markStatus(download.getDownloadId(), DownloadStatus.FAILED);
                }))
                .onErrorResume(error -> {
                    log.error("Download {} for song '{}' failed; marking FAILED",
                            download.getDownloadId(), download.getSongName(), error);
                    return downloadService.markStatus(download.getDownloadId(), DownloadStatus.FAILED);
                })
                .then()
                .onErrorResume(error -> {
                    log.error("Could not finalize status for download {}", download.getDownloadId(), error);
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

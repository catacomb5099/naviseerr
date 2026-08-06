package com.catacomb5099.naviseerr.download;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Consumer of {@link DownloadQueue}. Subscribes once and processes claimed downloads with bounded
 * concurrency: for each item it runs {@link DownloadFulfillment} and then writes the terminal
 * status. Every item is fully isolated so a single failure never tears down the worker subscription.
 *
 * <p>"Event-driven" here means push-based <em>within this process</em>: the worker is woken by
 * queue emissions instead of polling for work. It is not event-driven in the durable-messaging
 * sense - there is no broker, no delivery guarantee, and no redelivery. See {@link DownloadQueue}
 * for what that costs today.
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
                // Only a failed status write can reach here (fulfillment errors are absorbed above,
                // and both handlers above end in a status write). The write error itself is logged in
                // DownloadService, at the statement that failed; this logs the consequence the worker
                // is responsible for - the download is done but its row is left non-terminal. It also
                // covers the one case DownloadService cannot see: a transaction that fails on commit,
                // after the inner chain succeeded.
                //
                // Swallowing is deliberate: letting this propagate into start()'s flatMap would tear
                // down the worker subscription.
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

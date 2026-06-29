package com.catacomb5099.naviseerr.download;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Component
public class PendingDownloadRunner {

    private final DownloadService downloadService;
    private final DownloadQueue downloadQueue;
    private final Duration interval;
    private final int batchSize;
    private Disposable subscription;

    public PendingDownloadRunner(
            DownloadService downloadService,
            DownloadQueue downloadQueue,
            @Value("${download-runner.interval-ms:10000}") long intervalMs,
            @Value("${download-runner.batch-size:10}") int batchSize) {
        this.downloadService = downloadService;
        this.downloadQueue = downloadQueue;
        this.interval = Duration.ofMillis(intervalMs);
        this.batchSize = batchSize;
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(interval)
                .onBackpressureDrop()
                .concatMap(tick -> processBatch())
                .subscribe();
    }

    // Claims the next batch of PENDING downloads (flipping them to IN_PROGRESS) and hands each one to
    // the in-memory queue. That claim transition is what triggers enqueueing; the worker consumes the
    // queue independently and event-driven.
    Mono<Void> processBatch() {
        return downloadService.claimPendingDownloads(batchSize)
                .doOnNext(download -> {
                    log.info("Claimed pending download {} for song '{}'; enqueueing",
                            download.getDownloadId(), download.getSongName());
                    downloadQueue.enqueue(download);
                })
                .then()
                .onErrorResume(error -> {
                    log.error("Pending download claim cycle failed", error);
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

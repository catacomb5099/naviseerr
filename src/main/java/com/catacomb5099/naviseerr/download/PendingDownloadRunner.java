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
    private final Duration interval;
    private final int batchSize;
    private Disposable subscription;

    public PendingDownloadRunner(
            DownloadService downloadService,
            @Value("${download-runner.interval-ms:10000}") long intervalMs,
            @Value("${download-runner.batch-size:10}") int batchSize) {
        this.downloadService = downloadService;
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

    private Mono<Void> processBatch() {
        return downloadService.claimPendingDownloads(batchSize)
                .doOnNext(download -> log.info("Picked up pending download {} for song '{}'",
                        download.getDownloadId(), download.getSongName()))
                .then()
                .onErrorResume(error -> {
                    log.error("Pending download runner cycle failed", error);
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

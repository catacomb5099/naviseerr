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
import java.time.Instant;

/**
 * Periodic reaper that resets stale {@code IN_PROGRESS} downloads back to {@code PENDING},
 * allowing {@link PendingDownloadRunner} to re-claim them on its next cycle.
 *
 * <p>A download is considered stale when its {@code created_at} is older than
 * {@code now - staleLifetime}. This closes the crash-recovery gap where rows flipped to
 * {@code IN_PROGRESS} are stranded if the process dies before the worker finishes them.
 */
@Slf4j
@Component
public class StaleDownloadReaper {

    private final DownloadService downloadService;
    private final Duration interval;
    private final Duration staleLifetime;
    private Disposable subscription;

    public StaleDownloadReaper(
            DownloadService downloadService,
            @Value("${download-reaper.interval-ms:300000}") long intervalMs,
            @Value("${download-reaper.stale-lifetime-ms:600000}") long staleLifetimeMs) {
        this.downloadService = downloadService;
        this.interval = Duration.ofMillis(intervalMs);
        this.staleLifetime = Duration.ofMillis(staleLifetimeMs);
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(interval)
                .onBackpressureDrop()
                .concatMap(tick -> reapBatch())
                .subscribe();
    }

    Mono<Void> reapBatch() {
        Instant cutoff = Instant.now().minus(staleLifetime);
        return downloadService.reclaimStaleDownloads(cutoff)
                .doOnNext(download -> log.warn(
                        "Reclaimed stale IN_PROGRESS download {} for song '{}' (created_at={}); reset to PENDING",
                        download.getDownloadId(), download.getSongName(), download.getCreatedAt()))
                .then()
                .onErrorResume(error -> {
                    log.error("Stale download reap cycle failed", error);
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

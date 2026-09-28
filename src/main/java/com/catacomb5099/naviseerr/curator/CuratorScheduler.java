package com.catacomb5099.naviseerr.curator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Once a week (cron {@code curator.cron}) asks the playlist curator for fresh suggested playlists and
 * follows the run until it finishes. The curator answers the trigger instantly and does the slow
 * work (minutes) in the background, so this polls {@code GET /v1/runs/{id}} every
 * {@code poll-interval-ms} for at most {@code run-budget-ms}. A poll that fails is logged and
 * polling continues: a slow curator is not a failed run. See
 * docs/decisions/curator-weekly-trigger-27-09-2026.md.
 *
 * <p>OFF unless {@code curator.url} and {@code curator.token} are both set -- the same
 * "off unless configured" rule as the library organiser.
 */
@Slf4j
@Component
public class CuratorScheduler {

    private final CuratorClient client;
    private final Duration pollInterval;
    private final Duration runBudget;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public CuratorScheduler(CuratorClient client,
                            @Value("${curator.url:}") String url,
                            @Value("${curator.token:}") String token,
                            @Value("${curator.poll-interval-ms}") Duration pollInterval,
                            @Value("${curator.run-budget-ms}") Duration runBudget) {
        this.client = client;
        this.pollInterval = pollInterval;
        this.runBudget = runBudget;
        this.enabled = !url.isBlank() && !token.isBlank();
        if (!enabled) {
            log.info("Weekly curator refresh OFF: suggested playlists are not refreshed. Set CURATOR_URL "
                    + "and CURATOR_TOKEN (the same token the playlist-curator is started with) to turn it on.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The cron entry point. Never throws: refresh() swallows its own errors into a log line. */
    @Scheduled(cron = "${curator.cron}")
    public void tick() {
        if (!enabled) {
            return;
        }
        refresh().subscribe();
    }

    /**
     * One full refresh: trigger, poll to a final state or the budget, log the outcome. Public so a
     * manual "refresh now" can be wired to it later. Overlapping calls are skipped, not queued.
     */
    public Mono<Void> refresh() {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) {
                log.warn("Curator refresh skipped: the previous one is still running");
                return Mono.empty();
            }
            Instant started = Instant.now();
            return client.triggerRun()
                    .doOnNext(run -> log.info("Curator run {} {} ({} categories)", run.runId(), run.status(),
                            run.categories().size()))
                    .flatMap(run -> run.isFinal() ? Mono.just(run) : poll(run))
                    .doOnNext(run -> logOutcome(run, started))
                    .then()
                    .onErrorResume(error -> {
                        log.error("Curator refresh failed: {}", error.getMessage());
                        return Mono.empty();
                    })
                    .doFinally(signal -> running.set(false));
        });
    }

    private Mono<CuratorRun> poll(CuratorRun triggered) {
        return Flux.interval(pollInterval)
                .concatMap(i -> client.getRun(triggered.runId())
                        .onErrorResume(error -> {
                            log.warn("Curator poll of run {} failed; will ask again: {}", triggered.runId(),
                                    error.getMessage());
                            return Mono.empty();
                        }))
                .takeUntil(CuratorRun::isFinal)
                .take(runBudget)
                .last(triggered)
                .flatMap(run -> {
                    if (!run.isFinal()) {
                        log.warn("Curator run {} still running after {}; check the curator's /v1/runs/latest",
                                run.runId(), runBudget);
                    }
                    return Mono.just(run);
                });
    }

    private void logOutcome(CuratorRun run, Instant started) {
        for (CuratorCategoryResult c : run.categories()) {
            log.info("Curator category {}: {} edition={} tracks={}{}", c.key(), c.status(), c.editionDate(),
                    c.trackCount(), c.message() == null ? "" : " -- " + c.message());
        }
        log.info("Curator run {} ended with status {} after {}", run.runId(), run.status(),
                Duration.between(started, Instant.now()));
    }
}

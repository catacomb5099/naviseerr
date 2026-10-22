package com.catacomb5099.naviseerr.learning;

import com.catacomb5099.naviseerr.settings.InstallSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Help improve search" (search learning, 20-10-2026): every {@code search-learning.interval-ms}, while the switch is on
 * and an address is set, POSTs up to {@code batch-size} new reports as one {@code {schema, reports}} body to
 * {@code search-learning.url} (with {@code Authorization: Bearer <token>} when a token is set). Any 2xx marks exactly
 * those songs sent; anything else marks nothing, so the same batch goes at the next tick. That is the whole retry.
 * ponytail: one batch per tick (100 per 10 min); drain in a loop if a big backlog ever matters.
 */
@Slf4j
@Component
public class SearchLearningSender {

    private final SearchLearningRepository reports;
    private final InstallSettingsRepository settings;
    private final Clock clock;
    private final String url;
    private final int batchSize;
    private final Duration timeout;
    private final WebClient http;
    /** The last send's problem in one sentence, null after a good one; shown in Settings, logged only when it changes. */
    private final AtomicReference<String> problem = new AtomicReference<>();

    public SearchLearningSender(SearchLearningRepository reports, InstallSettingsRepository settings, Clock clock,
                                @Value("${search-learning.url:}") String url,
                                @Value("${search-learning.token:}") String token,
                                @Value("${search-learning.batch-size:100}") int batchSize,
                                @Value("${search-learning.timeout-ms:30000}") Duration timeout) {
        this.reports = reports;
        this.settings = settings;
        this.clock = clock;
        this.url = url.strip();
        this.batchSize = batchSize;
        this.timeout = timeout;
        WebClient.Builder builder = WebClient.builder();
        if (!token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.strip());
        }
        this.http = builder.build();
    }

    /** An address to send to is set in the install file; without one the switch cannot go on. */
    public boolean isConfigured() {
        return !url.isEmpty();
    }

    /** Where reports go, as host[:port] only (never a path, token or login in the address), for the Settings page. */
    public String sendsTo() {
        if (!isConfigured()) {
            return null;
        }
        URI address = URI.create(url);
        return address.getPort() < 0 ? address.getHost() : address.getHost() + ":" + address.getPort();
    }

    public String problem() {
        return problem.get();
    }

    // Spring waits for the returned Mono before counting the delay, so two sends never overlap.
    @Scheduled(fixedDelayString = "${search-learning.interval-ms:600000}",
            initialDelayString = "${search-learning.initial-delay-ms:60000}")
    public Mono<Void> tick() {
        return isConfigured() ? settings.searchLearningSince().flatMap(on -> send()) : Mono.empty();
    }

    /** One batch: sent and marked, or nothing marked. Never an error signal. */
    Mono<Void> send() {
        return reports.unsent(batchSize).collectList()
                .filter(batch -> !batch.isEmpty())
                .flatMap(batch -> http.post().uri(url)
                        .bodyValue(new SearchLearningController.Reports(SearchLearningController.SCHEMA, batch))
                        .retrieve().toBodilessEntity()
                        .timeout(timeout)
                        .then(Mono.defer(() -> reports.markSent(batch.stream().map(SearchReport::id).toList(), clock.instant())))
                        .doOnNext(marked -> {
                            log.info("Sent {} search report(s) to {}", marked, url);
                            report(null);
                        }))
                .onErrorResume(error -> {
                    report(error instanceof WebClientResponseException refused
                            ? "The receiver answered HTTP " + refused.getStatusCode().value() + "; the reports wait for the next try."
                            : "The receiver could not be reached (" + error.getClass().getSimpleName()
                                    + "); the reports wait for the next try.");
                    return Mono.empty();
                })
                .then();
    }

    private void report(String now) {
        String before = problem.getAndSet(now);
        if (now != null && !Objects.equals(before, now)) {
            log.warn("Search reports not sent to {}: {}", url, now);
        }
    }
}

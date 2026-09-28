package com.catacomb5099.naviseerr.curator;

import com.catacomb5099.naviseerr.util.networkcalls.ReactivePoller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;

/**
 * The two curator calls naviseerr makes: start a run, and ask how it is going. Same shape as
 * {@code YtMusicService.execute}: timeout, typed error translation, retry of transient failures only.
 * The trigger retries (a network hiccup must not lose the week's run; the curator's POST is
 * idempotent so a retry can never start a second run). A poll does not retry here -- the scheduler
 * simply asks again on the next interval.
 */
@Slf4j
@Service
public class CuratorClient {
    private static final String RUNS_PATH = "/v1/runs";

    private final WebClient webClient;
    private final Duration timeout;
    private final Duration firstBackOff;
    private final int retryCount;

    public CuratorClient(@Qualifier("curatorWebClient") WebClient webClient,
                         @Value("${curator.timeout-ms}") Duration timeout,
                         @Value("${curator.trigger-first-back-off-ms}") Duration firstBackOff,
                         @Value("${curator.trigger-retry-count}") int retryCount) {
        this.webClient = webClient;
        this.timeout = timeout;
        this.firstBackOff = firstBackOff;
        this.retryCount = retryCount;
    }

    /** POST /v1/runs with no body: refresh every category. 202 for a new run, 200 for one already going. */
    public Mono<CuratorRun> triggerRun() {
        return execute(webClient.post().uri(RUNS_PATH), "trigger")
                .retryWhen(ReactivePoller.defaultBackoff(firstBackOff, retryCount)
                        .filter(error -> error instanceof CuratorException ce && ce.isRetryable())
                        .doBeforeRetry(signal -> log.warn("Retrying curator trigger (attempt {}) after: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    public Mono<CuratorRun> getRun(String runId) {
        return execute(webClient.get().uri(RUNS_PATH + "/{id}", runId), "poll of run " + runId);
    }

    private Mono<CuratorRun> execute(WebClient.RequestHeadersSpec<?> request, String what) {
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .bodyToMono(CuratorRun.class)
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof CuratorException),
                        error -> new CuratorException("curator " + what + " failed: " + error.getMessage(),
                                true, error));
    }

    private Mono<? extends Throwable> translateError(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(JsonNodeFactory.instance.objectNode())
                .onErrorReturn(JsonNodeFactory.instance.objectNode())
                .map(body -> {
                    String detail = body.path("error").path("message").asString("no error body");
                    if (status == 401) {
                        return new CuratorException("curator rejected the token (401): CURATOR_TOKEN must be "
                                + "the same value on both sides. Curator said: " + detail, false);
                    }
                    return new CuratorException("curator returned " + status + ": " + detail,
                            response.statusCode().is5xxServerError());
                });
    }
}

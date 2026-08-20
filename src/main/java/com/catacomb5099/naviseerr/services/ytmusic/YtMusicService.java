package com.catacomb5099.naviseerr.services.ytmusic;

import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;
import com.catacomb5099.naviseerr.util.YtMusicSearchResponseMapper;
import com.catacomb5099.naviseerr.util.networkcalls.ReactivePoller;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class YtMusicService {
    private static final String SEARCH_PATH = "/v1/search";
    private static final String SEARCH_PATH_PREFIX = SEARCH_PATH + "/";
    private static final String QUERY_PARAM = "q";
    private static final String LIMIT_PARAM = "limit";
    private static final String MIXED_SEARCH_LABEL = "mixed";

    private final WebClient ytMusicWebClient;

    @Value("${yt-music-service.search-result-limit}")
    private int searchResultLimit;
    // Mixed (unfiltered) search returns a single page of shelves -- ytmusicapi ignores `limit`
    // when no filter is set. This is set to the adapter's maximum purely to stop the adapter's
    // own items[:limit] truncation from starving the categories YouTube interleaves late
    // (albums, artists). Lowering it silently drops albums from general search.
    @Value("${yt-music-service.mixed-search-limit}")
    private int mixedSearchLimit;
    @Value("${yt-music-service.timeout-ms}")
    private long timeoutMs;
    @Value("${yt-music-service.retry-count}")
    private int retryCount;
    @Value("${yt-music-service.first-back-off-duration-ms}")
    private long firstBackOffDurationMs;

    public YtMusicService(WebClient ytMusicWebClient) {
        this.ytMusicWebClient = ytMusicWebClient;
    }

    public Mono<SearchResponse> getResults(String query, YtMusicSearchType type) {
        return executeSearch(
                uriBuilder -> uriBuilder
                        .path(SEARCH_PATH_PREFIX + type.getPathSegment())
                        .queryParam(QUERY_PARAM, query)
                        .queryParam(LIMIT_PARAM, searchResultLimit)
                        .build(),
                type.getPathSegment(),
                query);
    }

    /**
     * Issues one unfiltered search and partitions the mixed response into tracks/albums/artists
     * ({@link YtMusicSearchResponseMapper}) -- replaces the previous three-leg
     * {@code Mono.zip} of typed searches. This trades result volume (YouTube Music returns one
     * page of shelves for a mixed search, not up to {@code searchResultLimit} per type) and
     * blanks {@code Track.albumId} (song items in a mixed response carry no {@code album} field)
     * for a third of the provider load -- see docs/decisions/ytmusic-mixed-search-20-08-2026.md.
     */
    public Mono<SearchResponse> getResults(String query) {
        return executeSearch(
                uriBuilder -> uriBuilder
                        .path(SEARCH_PATH)
                        .queryParam(QUERY_PARAM, query)
                        .queryParam(LIMIT_PARAM, mixedSearchLimit)
                        .build(),
                MIXED_SEARCH_LABEL,
                query);
    }

    private Mono<SearchResponse> executeSearch(Function<UriBuilder, URI> uriFunction, String label, String query) {
        return ytMusicWebClient.get()
                .uri(uriFunction)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .bodyToMono(YtMusicSearchResponse.class)
                .doOnNext(response -> log.debug(
                        "ytmusic-adapter responded for type={} query='{}': reportedType={}, count={}, items={}",
                        label, query, response.getType(), response.getCount(),
                        summarizeItems(response.getItems())))
                .timeout(Duration.ofMillis(timeoutMs))
                // Any failure that isn't already one of our typed exceptions (client-side
                // timeout, connection refused, decode failure) is a provider-availability
                // problem, same as an adapter-side 502/504/429 -- fold it in so the retry
                // filter below and callers only ever see YtMusicException subtypes.
                .onErrorMap(
                        error -> !(error instanceof YtMusicException),
                        error -> new YtMusicUnavailableException("ytmusic-adapter request failed: " + error.getMessage(), error)
                )
                .retryWhen(ReactivePoller.defaultBackoff(Duration.ofMillis(firstBackOffDurationMs), retryCount)
                        .filter(YtMusicUnavailableException.class::isInstance)
                        .doBeforeRetry(signal -> log.warn(
                                "Retrying ytmusic-adapter request for type={} query='{}' (attempt {}) after: {}",
                                label, query, signal.totalRetries() + 1, signal.failure().getMessage()))
                        // Reactor's default exhaustion behavior wraps the last failure in an
                        // IllegalStateException; unwrap it so callers only ever see
                        // YtMusicException subtypes, retried or not.
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .map(YtMusicSearchResponseMapper::mapToSearchResponse);
    }

    /**
     * The adapter's error body is NOT uniform: {@code {"error":{"code","message"}}} for
     * everything it classifies itself, but {@code {"detail": "..."}} (a plain string) for an
     * unsupported {@code type} 400 and its own 503, and {@code {"detail": [...]}} (an array)
     * for a 422 validation failure. Read defensively as a JsonNode and try both shapes.
     */
    private Mono<? extends Throwable> translateError(ClientResponse response) {
        int statusCode = response.statusCode().value();
        return response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(JsonNodeFactory.instance.objectNode())
                .<YtMusicException>map(body -> buildException(statusCode, extractMessage(body, statusCode)))
                .doOnNext(ex -> log.warn("ytmusic-adapter returned {}: {}", statusCode, ex.getMessage()))
                .onErrorReturn(new YtMusicUnavailableException(
                        "ytmusic-adapter returned " + statusCode + " with an unreadable error body"));
    }

    private YtMusicException buildException(int statusCode, String message) {
        // 400/422: we sent a bad request. 500: the adapter's own internal_auth_misuse case --
        // an adapter bug, not a transient failure. Neither is worth retrying.
        if (statusCode == 400 || statusCode == 422 || statusCode == 500) {
            return new YtMusicBadRequestException(message);
        }
        // 429/502/504/404 and any other unlisted status: provider failed or is unreachable.
        return new YtMusicUnavailableException(message);
    }

    /**
     * DEBUG-only, so this stays on for verifying real-world YouTube Music responses without
     * flooding INFO -- title + resultType per item, never the full raw payload.
     */
    private String summarizeItems(List<YtMusicSearchResponse.Item> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        return items.stream()
                .map(item -> "%s:'%s'".formatted(item.getType(), item.getTitle()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String extractMessage(JsonNode body, int statusCode) {
        String fallback = "ytmusic-adapter returned " + statusCode + " with no decodable error body";
        JsonNode error = body.get("error");
        if (error != null && error.has("message")) {
            // asString(default) never throws, even if the adapter's body doesn't match the
            // shape we expect -- this path builds an error message, so it must not itself fail.
            return error.get("message").asString(fallback);
        }
        JsonNode detail = body.get("detail");
        if (detail != null && !detail.isNull()) {
            return detail.isString() ? detail.asString(fallback) : detail.toString();
        }
        return fallback;
    }
}

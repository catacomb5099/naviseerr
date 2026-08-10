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
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
public class YtMusicService {
    private static final String SEARCH_PATH_PREFIX = "/v1/search/";
    private static final String QUERY_PARAM = "q";
    private static final String LIMIT_PARAM = "limit";

    private final WebClient ytMusicWebClient;

    @Value("${yt-music-service.search-result-limit}")
    private int searchResultLimit;
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
        return ytMusicWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(SEARCH_PATH_PREFIX + type.getPathSegment())
                        .queryParam(QUERY_PARAM, query)
                        .queryParam(LIMIT_PARAM, searchResultLimit)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .bodyToMono(YtMusicSearchResponse.class)
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
                        // Reactor's default exhaustion behavior wraps the last failure in an
                        // IllegalStateException; unwrap it so callers only ever see
                        // YtMusicException subtypes, retried or not.
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .map(response -> YtMusicSearchResponseMapper.mapToSearchResponse(type, response));
    }

    /**
     * Fuses the three typed searches into one combined response. Request-level logging
     * (received/started/completed/failed) lives once, at the controller, in
     * {@code SearchService} -- LastFMService duplicated that triplet here too, which made
     * every combined search log each line twice; this does not repeat that.
     */
    public Mono<SearchResponse> getResults(String query) {
        Mono<SearchResponse> tracksMono = getResults(query, YtMusicSearchType.SONGS);
        Mono<SearchResponse> albumsMono = getResults(query, YtMusicSearchType.ALBUMS);
        Mono<SearchResponse> artistsMono = getResults(query, YtMusicSearchType.ARTISTS);

        return Mono.zip(tracksMono, albumsMono, artistsMono)
                .map(tuple -> new SearchResponse(
                        tuple.getT1().getTracks(),
                        tuple.getT2().getAlbums(),
                        tuple.getT3().getArtists()
                ));
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

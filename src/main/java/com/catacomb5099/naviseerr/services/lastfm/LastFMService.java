package com.catacomb5099.naviseerr.services.lastfm;

import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.services.lastfm.model.LastFmSearchResponse;
import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import com.catacomb5099.naviseerr.util.LastFMAPIMethodHelper;
import com.catacomb5099.naviseerr.util.SearchResponseMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Service
public class LastFMService {
    private static final String API_KEY_HEADER = "api_key";
    private static final String FORMAT_HEADER = "format";
    private static final String LIMIT_HEADER = "limit";
    private static final String METHOD_HEADER = "method";

    private final WebClient lastFmWebClient;
    private final LastFMAPIMethodHelper lastFMAPIMethodHelper;

    @Value("${last-fm-service.api_key}")
    private String apiKey;
    @Value("${last-fm-service.search-result-limit}")
    private int searchResultsLimit;

    public LastFMService(WebClient lastFmWebClient, LastFMAPIMethodHelper lastFMAPIMethodHelper) {
        this.lastFmWebClient = lastFmWebClient;
        this.lastFMAPIMethodHelper = lastFMAPIMethodHelper;
    }

    public Mono<SearchResponse> getResults(String query, LastFMAPIMethod apiMethod) {
        String method = lastFMAPIMethodHelper.getRelevantMethodHeaderValue(apiMethod);
        String paramName = lastFMAPIMethodHelper.getAPIMethodSpecificParam(apiMethod);

        Mono<LastFmSearchResponse> lastFMResponse =  lastFmWebClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .queryParam(API_KEY_HEADER, apiKey)
                        .queryParam(FORMAT_HEADER, "json")
                        .queryParam(LIMIT_HEADER, searchResultsLimit)
                        .queryParam(METHOD_HEADER, method)
                        .queryParam(paramName, query)
                        .build())
                .retrieve()
                .bodyToMono(LastFmSearchResponse.class);

        return SearchResponseMapper.mapFromLastFmResponse(lastFMResponse);
    }

    public Mono<SearchResponse> getResults(String query) {
        Mono<SearchResponse> tracksMono = getResults(query, LastFMAPIMethod.TRACK_SEARCH)
                .doOnSubscribe(subscription -> log.debug("Starting LastFM track search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed LastFM track search for query='{}'", query))
                .doOnError(error -> log.error("LastFM track search failed for query='{}'", query, error));
        Mono<SearchResponse> albumsMono = getResults(query, LastFMAPIMethod.ALBUM_SEARCH)
                .doOnSubscribe(subscription -> log.debug("Starting LastFM album search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed LastFM album search for query='{}'", query))
                .doOnError(error -> log.error("LastFM album search failed for query='{}'", query, error));
        Mono<SearchResponse> artistsMono = getResults(query, LastFMAPIMethod.ARTIST_SEARCH)
                .doOnSubscribe(subscription -> log.debug("Starting LastFM artist search for query='{}' (subscription={})", query, subscription))
                .doOnSuccess(result -> log.info("Completed LastFM artist search for query='{}'", query))
                .doOnError(error -> log.error("LastFM artist search failed for query='{}'", query, error));

        return Mono.zip(tracksMono, albumsMono, artistsMono)
                .map(tuple ->  new SearchResponse(
                            tuple.getT1().getTracks(),
                            tuple.getT2().getAlbums(),
                            tuple.getT3().getArtists()
                    ));
    }
}
